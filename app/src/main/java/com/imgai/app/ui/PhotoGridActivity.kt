package com.imgai.app.ui

import android.app.Activity
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
    private var pendingDeleteCount: Int = 0

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

        btnExport.setOnClickListener { pickExportDirectory() }
        loadPhotos(type, id)
    }

    private fun loadPhotos(type: String, id: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@PhotoGridActivity)
            photoUris = withContext(Dispatchers.IO) {
                when (type) {
                    "cluster" -> db.faceEmbeddingDao().getByCluster(id).map { it.imageUri }.distinct()
                    "category" -> db.photoDao().getByCategory(id).map { it.uri }
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
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            startMove(uri)
        }
    }

    private fun pickExportDirectory() {
        Toast.makeText(this, "请选择目标文件夹", Toast.LENGTH_SHORT).show()
        pickDirLauncher.launch(null)
    }

    private fun startMove(targetDirUri: Uri) {
        val toMove = photoUris.toList()
        if (toMove.isEmpty()) return

        btnExport.isEnabled = false
        exportProgress.visibility = View.VISIBLE
        tvExportStatus.visibility = View.VISIBLE
        exportProgress.max = toMove.size
        exportProgress.progress = 0

        lifecycleScope.launch {
            val targetDir = withContext(Dispatchers.IO) {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(this@PhotoGridActivity, targetDirUri)
            }
            if (targetDir == null || !targetDir.canWrite()) {
                tvExportStatus.text = "❌ 无法写入目标目录"
                btnExport.isEnabled = true
                exportProgress.visibility = View.GONE
                return@launch
            }

            var moved = 0
            var failed = 0
            val copiedUris = mutableListOf<Uri>()

            for ((index, uriStr) in toMove.withIndex()) {
                val sourceUri = Uri.parse(uriStr)
                tvExportStatus.text = "移动中: ${index + 1}/${toMove.size}"

                try {
                    val result = withContext(Dispatchers.IO) {
                        moveViaSAF(sourceUri, targetDir)
                    }
                    if (result) {
                        copiedUris.add(sourceUri)
                        moved++
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Move error", e)
                    failed++
                }

                exportProgress.progress = index + 1
            }

            // 删除原图（通过 MediaStore.createDeleteRequest）
            if (copiedUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tvExportStatus.text = "等待确认删除 ${copiedUris.size} 张原图..."
                requestDeleteOriginals(copiedUris, moved, failed)
            } else {
                finishExport(moved, failed, deleted = false)
            }
        }
    }

    /**
     * 通过 SAF API 移动照片：用 DocumentFile 创建目标文件 + contentResolver 复制内容
     * 复制完成后标记原图待删除
     */
    private fun moveViaSAF(sourceUri: Uri, targetDir: androidx.documentfile.provider.DocumentFile): Boolean {
        val fileName = getFileName(sourceUri) ?: "photo_${System.currentTimeMillis()}.jpg"

        // 防重名
        var targetFile = targetDir.findFile(fileName)
        var finalName = fileName
        var counter = 1
        while (targetFile != null && targetFile.exists()) {
            val dotIdx = fileName.lastIndexOf('.')
            val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
            val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
            finalName = "${base}_$counter$ext"
            targetFile = targetDir.findFile(finalName)
            counter++
        }

        val targetDoc = targetDir.createFile("image/*", finalName) ?: return false

        // 复制内容
        return try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(targetDoc.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            // 尝试保留原始时间戳（通过 ContentResolver 设置）
            // DocumentFile 不支持直接设置时间戳，但 MediaStore 会保留 EXIF
            Log.i(TAG, "Copied via SAF: $sourceUri -> $finalName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SAF copy failed", e)
            targetDoc.delete()  // 清理失败的文件
            false
        }
    }

    // ── 删除原图（仅对无法 renameTo 的文件）──

    private fun requestDeleteOriginals(uris: List<Uri>, moved: Int, failed: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val deleteRequest = MediaStore.createDeleteRequest(contentResolver, uris)
                startIntentSenderForResult(deleteRequest.intentSender, 200, null, 0, 0, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Delete request failed", e)
                finishExport(moved, failed, deleted = false)
            }
        } else {
            for (uri in uris) { try { contentResolver.delete(uri, null, null) } catch (_: Exception) {} }
            finishExport(moved, failed, deleted = true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200) {
            val deleted = resultCode == Activity.RESULT_OK
            finishExport(pendingDeleteCount, 0, deleted = deleted)
        }
    }

    private fun finishExport(moved: Int, failed: Int, deleted: Boolean) {
        btnExport.isEnabled = true
        exportProgress.visibility = View.GONE

        tvExportStatus.text = buildString {
            appendLine("✅ 移动完成: $moved 张")
            if (deleted) appendLine("🗑 原图已删除") else appendLine("📋 原图保留（仅复制）")
            if (failed > 0) appendLine("❌ 失败: $failed 张")
        }
        Toast.makeText(this, if (deleted) "已移动 $moved 张照片" else "已复制 $moved 张", Toast.LENGTH_LONG).show()
    }

    // ── 工具 ──

    private fun uriToFilePath(uri: Uri): String? {
        val path = uri.path ?: return null
        // SAF Uri 转 file path 的常见模式
        // external-primary/Download → /sdcard/Download
        // 或直接是 /storage/... 路径
        return when {
            path.startsWith("/storage/") -> path
            path.startsWith("/sdcard/") -> path
            else -> {
                // 尝试从 tree URI 提取
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                when {
                    docId.startsWith("primary:") -> "/sdcard/${docId.substringAfter(":")}"
                    docId.startsWith("raw:") -> docId.substringAfter("raw:")
                    else -> {
                        // /storage/emulated/0/ + path after :
                        val parts = docId.split(":")
                        if (parts.size >= 2) "/storage/emulated/0/${parts[1]}" else null
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun copyFile(source: Uri, target: File): Boolean {
        return try {
            contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
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
        fun submitList(uris: List<String>) { items.clear(); items.addAll(uris); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.itemView).load(Uri.parse(items[position])).centerCrop().into(holder.imgPhoto)
        }
        override fun getItemCount() = items.size
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imgPhoto: ImageView = view.findViewById(R.id.imgPhoto)
        }
    }

    companion object { private const val TAG = "PhotoGrid" }
}
