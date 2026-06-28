package com.imgai.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
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
import androidx.documentfile.provider.DocumentFile
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

class PhotoGridActivity : AppCompatActivity() {

    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnExport: Button
    private lateinit var tvExportStatus: TextView
    private lateinit var exportProgress: ProgressBar
    private lateinit var adapter: PhotoAdapter

    private var photoUris: List<String> = emptyList()
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

        btnExport.setOnClickListener { onExportClick() }
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

    // ── 导出 ──

    private fun onExportClick() {
        if (hasManageStoragePermission()) {
            // 有所有文件权限，用文件选择器选目录后直接 renameTo
            pickDirLauncher.launch(null)
        } else {
            // 没有权限，引导用户授权
            Toast.makeText(this, "需要「所有文件访问权限」才能移动照片，请在设置中开启", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun hasManageStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

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

    private fun startMove(targetDirUri: Uri) {
        val toMove = photoUris.toList()
        if (toMove.isEmpty()) return

        btnExport.isEnabled = false
        exportProgress.visibility = View.VISIBLE
        tvExportStatus.visibility = View.VISIBLE
        exportProgress.max = toMove.size
        exportProgress.progress = 0
        pendingDeleteUris.clear()

        lifecycleScope.launch {
            // 解析目标目录路径
            val targetDirPath = uriToFilePath(targetDirUri)
            val targetDir = targetDirPath?.let { File(it) }

            if (targetDir == null || !targetDir.canWrite()) {
                tvExportStatus.text = "❌ 无法写入目标目录: $targetDirPath"
                btnExport.isEnabled = true
                exportProgress.visibility = View.GONE
                return@launch
            }

            var moved = 0
            var failed = 0

            for ((index, uriStr) in toMove.withIndex()) {
                val sourceUri = Uri.parse(uriStr)
                tvExportStatus.text = "移动中: ${index + 1}/${toMove.size}"

                try {
                    val result = withContext(Dispatchers.IO) {
                        moveDirect(sourceUri, targetDir)
                    }
                    if (result) moved++ else failed++
                } catch (e: Exception) {
                    Log.e(TAG, "Move error", e)
                    failed++
                }
                exportProgress.progress = index + 1
            }

            val path = targetDir.absolutePath
            finishExport(moved, failed, moved > 0 && failed == 0, path)
        }
    }

    /**
     * 直接移动文件（有 MANAGE_EXTERNAL_STORAGE 权限时）
     * 1. 通过 MediaStore.DATA 获取源文件真实路径
     * 2. File.renameTo() 直接移动，保留所有属性
     */
    private fun moveDirect(sourceUri: Uri, targetDir: File): Boolean {
        // 获取源文件路径
        val sourcePath = getRealPath(sourceUri) ?: return false
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return false

        // 获取文件名，防重名
        var targetFile = File(targetDir, sourceFile.name)
        var counter = 1
        while (targetFile.exists()) {
            val name = sourceFile.nameWithoutExtension
            val ext = sourceFile.extension
            targetFile = File(targetDir, "${name}_$counter.$ext")
            counter++
        }

        // 直接移动！属性完整保留
        return sourceFile.renameTo(targetFile)
    }

    private fun getRealPath(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun uriToFilePath(uri: Uri): String? {
        val path = uri.path ?: return null
        return when {
            path.startsWith("/storage/") || path.startsWith("/sdcard/") -> path
            else -> {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                when {
                    docId.startsWith("primary:") -> Environment.getExternalStorageDirectory().absolutePath + "/" + docId.substringAfter(":")
                    docId.startsWith("raw:") -> docId.substringAfter("raw:")
                    else -> {
                        val parts = docId.split(":")
                        if (parts.size >= 2) "/storage/emulated/0/${parts[1]}" else null
                    }
                }
            }
        }
    }

    private fun finishExport(moved: Int, failed: Int, allMoved: Boolean, archivePath: String?) {
        btnExport.isEnabled = true
        exportProgress.visibility = View.GONE

        tvExportStatus.text = buildString {
            appendLine("✅ 移动完成: $moved 张")
            if (failed > 0) appendLine("❌ 失败: $failed 张")
            if (archivePath != null && moved > 0) appendLine("📦 已归档到: $archivePath")
        }

        // 标记 cluster 为已归档
        if (moved > 0 && archivePath != null) {
            val clusterId = intent.getLongExtra("id", -1)
            val type = intent.getStringExtra("type")
            if (type == "cluster" && clusterId >= 0) {
                lifecycleScope.launch {
                    val db = AppDatabase.get(this@PhotoGridActivity)
                    db.faceClusterDao().archive(clusterId, archivePath)
                }
            }
        }

        Toast.makeText(this, "已移动 $moved 张照片", Toast.LENGTH_LONG).show()
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
