package com.imgai.app.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.imgai.app.R
import com.imgai.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 照片网格页 — 展示某分类/人物下的所有照片，支持导出到文件夹（移动）
 *
 * Intent extras:
 *   title: String   — 标题
 *   type: String    — "cluster" 或 "category"
 *   id: Long        — clusterId 或 categoryId
 */
class PhotoGridActivity : AppCompatActivity() {

    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnExport: Button
    private lateinit var tvExportStatus: TextView
    private lateinit var exportProgress: ProgressBar
    private lateinit var adapter: PhotoAdapter

    private var photoUris: List<String> = emptyList()
    private var pendingExportDir: Uri? = null
    private var pendingDeleteUris: MutableList<Uri> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_grid)

        tvTitle = findViewById(R.id.tvTitle)
        tvCount = findViewById(R.id.tvCount)
        rvPhotos = findViewById(R.id.rvPhotos)
        btnExport = findViewById(R.id.btnExport)
        tvExportStatus = findViewById(R.id.tvExportStatus)
        exportProgress = findViewById(R.id.exportProgress)

        val title = intent.getStringExtra("title") ?: "照片"
        val type = intent.getStringExtra("type") ?: "cluster"
        val id = intent.getLongExtra("id", -1)

        tvTitle.text = title
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        rvPhotos.layoutManager = GridLayoutManager(this, 3)
        adapter = PhotoAdapter()
        rvPhotos.adapter = adapter

        btnExport.setOnClickListener {
            pickExportDirectory()
        }

        loadPhotos(type, id)
    }

    private fun loadPhotos(type: String, id: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@PhotoGridActivity)
            photoUris = withContext(Dispatchers.IO) {
                when (type) {
                    "cluster" -> {
                        db.faceEmbeddingDao().getByCluster(id).map { it.imageUri }.distinct()
                    }
                    "category" -> {
                        db.photoDao().getByCategory(id).map { it.uri }
                    }
                    else -> emptyList()
                }
            }

            tvCount.text = "${photoUris.size} 张"
            btnExport.visibility = if (photoUris.isNotEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(photoUris)
        }
    }

    // ── 导出（移动）流程 ──

    private val pickDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化权限
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            pendingExportDir = uri
            startExport()
        }
    }

    private fun pickExportDirectory() {
        Toast.makeText(this, "请选择目标文件夹", Toast.LENGTH_SHORT).show()
        pickDirLauncher.launch(null)
    }

    private fun startExport() {
        val targetDir = pendingExportDir ?: return
        val toExport = photoUris.toList()
        if (toExport.isEmpty()) return

        btnExport.isEnabled = false
        exportProgress.visibility = View.VISIBLE
        tvExportStatus.visibility = View.VISIBLE
        exportProgress.max = toExport.size
        exportProgress.progress = 0

        lifecycleScope.launch {
            val copiedUris = mutableListOf<Uri>()
            var success = 0
            var failed = 0

            for ((index, uriStr) in toExport.withIndex()) {
                val sourceUri = Uri.parse(uriStr)
                tvExportStatus.text = "移动中: ${index + 1}/${toExport.size}"

                try {
                    // 1. 获取文件名
                    val fileName = getFileName(sourceUri) ?: "photo_${System.currentTimeMillis()}.jpg"

                    // 2. 在目标目录创建文件
                    val targetUri = withContext(Dispatchers.IO) {
                        val docDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@PhotoGridActivity, targetDir)
                        docDir?.createFile("image/*", fileName)?.uri
                    }
                    if (targetUri == null) { failed++; continue }

                    // 3. 复制内容
                    val copied = withContext(Dispatchers.IO) { copyFile(sourceUri, targetUri) }
                    if (copied) {
                        copiedUris.add(sourceUri)
                        success++
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Export error for $uriStr", e)
                    failed++
                }

                exportProgress.progress = index + 1
            }

            // 4. 删除原图（需要用户确认）
            if (copiedUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tvExportStatus.text = "等待确认删除原图..."
                requestDeleteOriginals(copiedUris)
            } else {
                finishExport(success, failed, deleted = false)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            val deleted = resultCode == Activity.RESULT_OK
            val successCount = pendingDeleteUris.size
            finishExport(successCount, 0, deleted = deleted)
        }
    }

    private fun requestDeleteOriginals(uris: List<Uri>) {
        pendingDeleteUris.clear()
        pendingDeleteUris.addAll(uris)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = contentResolver.delete(
                    uris[0],  // MediaStore.createDeleteRequest needs a collection
                    null, null
                )
                // Android 11+ 使用 MediaStore.createDeleteRequest
                val deleteRequest = MediaStore.createDeleteRequest(contentResolver, uris)
                startIntentSenderForResult(deleteRequest.intentSender, 200, null, 0, 0, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Delete request failed", e)
                finishExport(uris.size, 0, deleted = false)
            }
        }
    }

    private fun finishExport(success: Int, failed: Int, deleted: Boolean) {
        btnExport.isEnabled = true
        exportProgress.visibility = View.GONE

        val msg = buildString {
            appendLine("✅ 复制成功: $success 张")
            if (failed > 0) appendLine("❌ 失败: $failed 张")
            appendLine(if (deleted) "🗑 原图已删除（已移动）" else "📋 原图保留（仅复制）")
        }
        tvExportStatus.text = msg

        Toast.makeText(this,
            if (deleted) "已移动 $success 张照片" else "已复制 $success 张照片",
            Toast.LENGTH_LONG
        ).show()
    }

    // ── 工具方法 ──

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun copyFile(source: Uri, target: Uri): Boolean {
        return try {
            contentResolver.openInputStream(source)?.use { input ->
                contentResolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed", e)
            false
        }
    }

    // ── Adapter ──
    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.VH>() {
        private val items = mutableListOf<String>()

        fun submitList(uris: List<String>) {
            items.clear()
            items.addAll(uris)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = items[position]
            Glide.with(holder.itemView)
                .load(Uri.parse(uri))
                .centerCrop()
                .into(holder.imgPhoto)
        }

        override fun getItemCount() = items.size
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imgPhoto: ImageView = view.findViewById(R.id.imgPhoto)
        }
    }

    companion object {
        private const val TAG = "PhotoGrid"
    }
}
