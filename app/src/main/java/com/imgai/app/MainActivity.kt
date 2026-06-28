package com.imgai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.imgai.app.cluster.DBSCANClustering
import com.imgai.app.data.AppDatabase
import com.imgai.app.detect.FaceDetectorManager
import com.imgai.app.detect.FaceEmbeddingExtractor
import com.imgai.app.dedup.QualityAssessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvDetail: TextView
    private lateinit var btnScan: Button
    private lateinit var btnProcess: Button
    private lateinit var progressBar: ProgressBar

    private val db by lazy { AppDatabase.get(this) }
    private val faceDetector by lazy { FaceDetectorManager(this) }
    private val embeddingExtractor by lazy { FaceEmbeddingExtractor(this) }

    companion object {
        private const val REQ_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)
        tvDetail = findViewById(R.id.tvDetail)
        btnScan = findViewById(R.id.btnScan)
        btnProcess = findViewById(R.id.btnProcess)
        progressBar = findViewById(R.id.progressBar)
        progressBar.visibility = View.GONE

        btnScan.setOnClickListener {
            if (hasPermission()) scanPhotos() else requestPermission()
        }

        btnProcess.setOnClickListener {
            if (hasPermission()) startProcessing() else requestPermission()
        }

        if (hasPermission()) {
            tvStatus.text = "权限已授予"
        } else {
            tvStatus.text = "需要存储权限"
            requestPermission()
        }

        // 检查是否通过 am start 传入 action
        val autoAction = intent?.getStringExtra("action")
        if (autoAction != null && hasPermission()) {
            tvStatus.text = "自动测试: $autoAction"
            // 启动 Service
            val serviceIntent = Intent(this, AutoTestService::class.java).apply {
                putExtra("action", autoAction)
                putExtra("days", intent.getIntExtra("days", 0))
            }
            startForegroundService(serviceIntent)
        }

        // 检查 TFLite 模型状态
        tvDetail.text = if (embeddingExtractor.isReady()) {
            "✅ 人脸特征模型已加载"
        } else {
            "⚠️ 缺少 mobile_face_net.tflite 模型\n" +
            "   人脸聚类功能暂不可用\n" +
            "   其他功能正常"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val autoAction = intent?.getStringExtra("action")
        if (autoAction != null && hasPermission()) {
            tvStatus.text = "自动测试: $autoAction"
            val serviceIntent = Intent(this, AutoTestService::class.java).apply {
                putExtra("action", autoAction)
                putExtra("days", intent.getIntExtra("days", 0))
            }
            startForegroundService(serviceIntent)
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perm, REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            tvStatus.text = "权限已授予"
            scanPhotos()
        } else {
            tvStatus.text = "权限被拒绝"
        }
    }

    // ── 扫描相册 ──
    private data class PhotoInfo(
        val uri: String,
        val dateTaken: Long,
        val size: Long
    )

    private fun scanPhotos() {
        progressBar.visibility = View.VISIBLE
        btnScan.isEnabled = false
        tvStatus.text = "正在扫描相册..."

        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) { getAllPhotos() }
            progressBar.visibility = View.GONE
            btnScan.isEnabled = true

            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val totalSize = photos.sumOf { it.size }

            tvStatus.text = "扫描完成"
            tvStats.text = """
                📷 照片总数: ${photos.size}
                📅 最早: ${if (photos.isNotEmpty()) fmt.format(Date(photos.minOf { it.dateTaken })) else "无"}
                📅 最新: ${if (photos.isNotEmpty()) fmt.format(Date(photos.maxOf { it.dateTaken })) else "无"}
                💾 总大小: ${formatSize(totalSize)}
                🗃️ 已处理: ${db.processedImageDao().count()}
                👤 人脸记录: ${db.faceEmbeddingDao().count()}
            """.trimIndent()
        }
    }

    // ── 人脸处理流程 ──
    private fun startProcessing() {
        progressBar.visibility = View.VISIBLE
        btnProcess.isEnabled = false
        btnScan.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { processPhotos() }
                tvStatus.text = "处理完成！"
                tvStats.text = result
            } catch (e: Exception) {
                tvStatus.text = "处理出错: ${e.message}"
            } finally {
                progressBar.visibility = View.GONE
                btnProcess.isEnabled = true
                btnScan.isEnabled = true
            }
        }
    }

    private suspend fun processPhotos(): String {
        val photos = getAllPhotos()
        val processedUris = db.processedImageDao().getAllUris().toSet()
        val newPhotos = photos.filter { it.uri !in processedUris }

        val total = newPhotos.size
        var processed = 0
        var totalFaces = 0
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        for (photo in newPhotos) {
            try {
                // 加载图片 (降采样大图)
                val bitmap = loadBitmapSampled(photo.uri, maxDim = 1024) ?: continue

                // 检测人脸
                val faces = faceDetector.detect(bitmap)
                totalFaces += faces.size

                // 对每张人脸提取特征
                for (face in faces) {
                    val cropped = faceDetector.cropFace(bitmap, face)
                    val aligned = faceDetector.alignFace(cropped, face)

                    if (embeddingExtractor.isReady()) {
                        val embedding = embeddingExtractor.extract(aligned)
                        if (embedding != null) {
                            val rect = face.boundingBox
                            db.faceEmbeddingDao().insert(
                                com.imgai.app.data.FaceEmbeddingEntity(
                                    imageUri = photo.uri,
                                    embeddingRaw = embedding.joinToString(","),
                                    faceRect = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                                    qualityScore = QualityAssessor.assessQuality(cropped).score
                                )
                            )
                        }
                    }
                }

                // 标记已处理
                db.processedImageDao().upsert(
                    com.imgai.app.data.ProcessedImageEntity(
                        uri = photo.uri,
                        processedAt = System.currentTimeMillis(),
                        faceCount = faces.size,
                        dateTaken = photo.dateTaken
                    )
                )

                bitmap.recycle()
            } catch (e: Exception) {
                // 跳过出错的单张
            }

            processed++
            if (processed % 50 == 0) {
                tvStatus.text = "处理中: $processed / $total (${totalFaces} 人脸)"
            }
        }

        // 聚类
        var clusterInfo = ""
        if (embeddingExtractor.isReady()) {
            val allEmbeddings = db.faceEmbeddingDao().getAll()
            if (allEmbeddings.isNotEmpty()) {
                tvStatus.text = "正在聚类 ${allEmbeddings.size} 个人脸特征..."

                val embeddings = allEmbeddings.map { entity ->
                    entity.embeddingRaw.split(",").map { it.toFloat() }.toFloatArray()
                }

                val clusterResult = DBSCANClustering.cluster(embeddings, eps = 0.4f, minPts = 2)

                // 更新数据库中的 clusterId
                for (clusterId in 0 until clusterResult.clusterCount) {
                    val idsInCluster = allEmbeddings.indices
                        .filter { clusterResult.labels[it] == clusterId }
                        .map { allEmbeddings[it].id }
                    if (idsInCluster.isNotEmpty()) {
                        db.faceEmbeddingDao().updateClusterIds(idsInCluster, clusterId)
                    }
                }

                val personCount = clusterResult.clusterCount
                val noiseCount = clusterResult.labels.count { it == -1 }
                clusterInfo = "\n👥 识别 $personCount 个人物 (噪声点 $noiseCount)\n" +
                    clusterResult.clusterSizes.entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .joinToString("\n") { "   Person_${it.key + 1}: ${it.value} 张" }
            }
        }

        return """
            ✅ 处理完成
            📷 新处理: $processed / $total
            👤 检测到人脸: $totalFaces
            🗃️ 总人脸记录: ${db.faceEmbeddingDao().count()}
            $clusterInfo
        """.trimIndent()
    }

    // ── 工具方法 ──

    private fun getAllPhotos(): List<PhotoInfo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE
        )
        val photos = mutableListOf<PhotoInfo>()

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                ).toString()
                photos.add(PhotoInfo(
                    uri = uri,
                    dateTaken = cursor.getLong(dateCol),
                    size = cursor.getLong(sizeCol)
                ))
            }
        }
        return photos
    }

    private fun loadBitmapSampled(uri: String, maxDim: Int): android.graphics.Bitmap? {
        return try {
            val realUri = android.net.Uri.parse(uri)
            // 先获取尺寸
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(realUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }

            val sampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, maxDim)
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            contentResolver.openInputStream(realUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / sample > maxDim) sample *= 2
        return sample
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024.0
        return if (gb >= 1) String.format("%.1f GB", gb)
        else String.format("%.1f MB", mb)
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetector.close()
        embeddingExtractor.close()
    }
}
