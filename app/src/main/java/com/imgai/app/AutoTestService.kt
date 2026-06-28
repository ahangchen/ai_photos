package com.imgai.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.imgai.app.cluster.DBSCANClustering
import com.imgai.app.data.AppDatabase
import com.imgai.app.data.FaceEmbeddingEntity
import com.imgai.app.data.ProcessedImageEntity
import com.imgai.app.detect.FaceDetectorManager
import com.imgai.app.detect.FaceEmbeddingExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTestService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var faceDetector: FaceDetectorManager
    private lateinit var embeddingExtractor: FaceEmbeddingExtractor
    private val channelId = "imgai_test"
    private val notifId = 9999

    override fun onCreate() {
        super.onCreate()
        fileLog("Service.onCreate start")
        try {
            faceDetector = FaceDetectorManager(this)
            fileLog("FaceDetector init OK")
            embeddingExtractor = FaceEmbeddingExtractor(this)
            fileLog("EmbeddingExtractor init OK, ready=${embeddingExtractor.isReady()}")
            createNotificationChannel()
            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("ImgAI 自动测试")
                .setContentText("初始化中...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
            startForeground(notifId, notif)
            fileLog("startForeground OK")
            Toast.makeText(this, "ImgAI: 测试已启动", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            fileLog("onCreate ERROR: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "test"
        val days = intent?.getIntExtra("days", 0) ?: 0
        fileLog("onStartCommand action=$action days=$days")

        scope.launch {
            try {
                fileLog("Opening database...")
                val db = AppDatabase.get(this@AutoTestService)
                fileLog("Database OK, processed=${db.processedImageDao().count()}")

                val report = when (action) {
                    "scan" -> doScan(db, days)
                    "process" -> doProcess(db, 0, days)
                    "test" -> doProcess(db, 0, if (days > 0) days else 7)
                    "reset" -> {
                        db.processedImageDao().deleteAll()
                        db.faceEmbeddingDao().deleteAll()
                        "数据库已清空"
                    }
                    else -> "Unknown action: $action"
                }
                fileLog("=== RESULT ===\n$report")
                Toast.makeText(this@AutoTestService, "ImgAI: 测试完成", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                fileLog("RUNTIME ERROR: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(this@AutoTestService, "ImgAI: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                try { faceDetector.close() } catch (_: Exception) {}
                try { embeddingExtractor.close() } catch (_: Exception) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun doScan(db: AppDatabase, daysBack: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=== 相册扫描 ===")
        val photos = withContext(Dispatchers.IO) { getAllPhotos(daysBack) }
        val totalSize = photos.sumOf { it.size }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        sb.appendLine("照片总数: ${photos.size}")
        if (photos.isNotEmpty()) {
            sb.appendLine("最早: ${fmt.format(Date(photos.minOf { it.dateTaken }))}")
            sb.appendLine("最新: ${fmt.format(Date(photos.maxOf { it.dateTaken }))}")
            sb.appendLine("总大小: ${formatSize(totalSize)}")
        }
        sb.appendLine("已处理: ${db.processedImageDao().count()}")
        sb.appendLine("人脸记录: ${db.faceEmbeddingDao().count()}")
        sb.appendLine("TFLite模型: ${if (embeddingExtractor.isReady()) "已加载" else "未找到"}")
        return sb.toString()
    }

    private suspend fun doProcess(db: AppDatabase, maxImages: Int, daysBack: Int): String {
        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()

        fileLog("Loading photos (daysBack=$daysBack)...")
        val photos = withContext(Dispatchers.IO) { getAllPhotos(daysBack) }
        fileLog("Got ${photos.size} photos")

        val processedUris = db.processedImageDao().getAllUris().toSet()
        fileLog("Already processed: ${processedUris.size}")

        val newPhotos = photos.filter { it.uri !in processedUris }
        val toProcess = if (maxImages > 0) newPhotos.take(maxImages) else newPhotos

        sb.appendLine("=== 人脸处理 ===")
        sb.appendLine("范围: ${if (daysBack > 0) "最近${daysBack}天" else "全部"}")
        sb.appendLine("照片数: ${photos.size}, 新照片: ${newPhotos.size}, 处理: ${toProcess.size}")
        sb.appendLine("TFLite: ${if (embeddingExtractor.isReady()) "已加载" else "未找到"}")
        sb.appendLine()

        var processed = 0
        var totalFaces = 0
        var errors = 0

        for (photo in toProcess) {
            try {
                fileLog("[$processed/${toProcess.size}] processing...")
                val bitmap = withTimeoutOrNull(10_000) {
                    withContext(Dispatchers.IO) { loadBitmapSampled(photo.uri, 1024) }
                }
                if (bitmap == null) { fileLog("  bitmap null/timeout, skip"); errors++; processed++; continue }

                // 人脸检测和特征提取都放到后台线程，避免阻塞主线程 ANR
                val faces = withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.Default) { faceDetector.detect(bitmap) }
                } ?: emptyList()
                if (faces.isEmpty()) fileLog("  no faces or timeout")
                totalFaces += faces.size
                for (face in faces) {
                    if (embeddingExtractor.isReady()) {
                        // TFLite 推理放到 Default 线程
                        val emb = withTimeoutOrNull(15_000) {
                            withContext(Dispatchers.Default) {
                                val cropped = faceDetector.cropFace(bitmap, face)
                                val aligned = faceDetector.alignFace(cropped, face)
                                embeddingExtractor.extract(aligned)
                            }
                        }
                        fileLog("    embedding: ${if (emb != null) "OK len=${emb.size}" else "NULL/timeout"}")
                        if (emb != null) {
                            val r = face.boundingBox
                            db.faceEmbeddingDao().insert(FaceEmbeddingEntity(
                                imageUri = photo.uri, embeddingRaw = emb.joinToString(","),
                                faceRect = "${r.left},${r.top},${r.right},${r.bottom}"))
                        }
                    }
                }
                db.processedImageDao().upsert(ProcessedImageEntity(
                    uri = photo.uri, processedAt = System.currentTimeMillis(),
                    faceCount = faces.size, dateTaken = photo.dateTaken))
                bitmap.recycle()
                if (faces.isNotEmpty()) sb.appendLine("  [${processed+1}] faces=${faces.size}")
            } catch (e: Exception) { errors++; fileLog("Err@$processed: ${e.message}") }
            processed++
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        sb.appendLine("\n=== 统计 ===")
        sb.appendLine("成功: ${processed-errors}/$processed, 出错: $errors")
        sb.appendLine("人脸: $totalFaces")
        sb.appendLine("耗时: ${"%.1f".format(elapsed)}s (${"%.2f".format(if(processed>0) elapsed/processed else 0.0)}s/张)")

        // DBSCAN 聚类
        fileLog("Checking clustering: isReady=${embeddingExtractor.isReady()} embCount=${db.faceEmbeddingDao().count()}")
        if (embeddingExtractor.isReady() && db.faceEmbeddingDao().count() > 0) {
            sb.appendLine()
            sb.appendLine("=== DBSCAN 聚类 ===")
            val allEmb = db.faceEmbeddingDao().getAll()
            sb.appendLine("特征总数: ${allEmb.size}")
            fileLog("Clustering ${allEmb.size} embeddings...")

            val embeddings = allEmb.map { it.embeddingRaw.split(",").map { v -> v.toFloat() }.toFloatArray() }
            fileLog("Running DBSCAN on ${embeddings.size} vectors...")
            val result = withContext(Dispatchers.Default) { DBSCANClustering.cluster(embeddings, eps = 0.4f, minPts = 2) }
            sb.appendLine("识别人物: ${result.clusterCount}")
            sb.appendLine("噪声点: ${result.labels.count { it == -1 }}")
            fileLog("Clusters: ${result.clusterCount}, noise: ${result.labels.count { it == -1 }}")
            result.clusterSizes.entries.sortedByDescending { it.value }.forEach { (cid, cnt) ->
                sb.appendLine("  Person_${cid + 1}: $cnt 张")
            }
        }
        return sb.toString()
    }

    // ── 工具 ──
    private data class PhotoInfo(val uri: String, val dateTaken: Long, val size: Long)

    private fun getAllPhotos(daysBack: Int = 0): List<PhotoInfo> {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.SIZE)
        val photos = mutableListOf<PhotoInfo>()
        val (sel, args) = if (daysBack > 0) {
            val cutoff = System.currentTimeMillis() - daysBack * 24L*60L*60L*1000L
            Pair("${MediaStore.Images.Media.DATE_TAKEN} >= ?", arrayOf(cutoff.toString()))
        } else Pair<String?, Array<String>?>(null, null)

        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, sel, args,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dC = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sC = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idC)).toString()
                photos.add(PhotoInfo(uri, c.getLong(dC), c.getLong(sC)))
            }
        }
        return photos
    }

    private fun loadBitmapSampled(uri: String, maxDim: Int): android.graphics.Bitmap? {
        return try {
            val u = android.net.Uri.parse(uri)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var s = 1; val l = maxOf(o.outWidth, o.outHeight); while (l/s > maxDim) s*=2
            val d = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
            contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, d) }
        } catch (_: Exception) { null }
    }

    private fun formatSize(b: Long) = "%.1f MB".format(b/(1024.0*1024.0))

    private fun fileLog(msg: String) {
        Log.i(TAG, msg)
        try {
            // 用 App 外部存储目录（不需要额外权限）
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "imgai_test_result.txt")
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            FileOutputStream(file, true).use { it.write("$ts $msg\n".toByteArray()) }
            // 同时尝试写到 Download
            try {
                val dl = File("/sdcard/Download/imgai_test_result.txt")
                FileOutputStream(dl, true).use { it.write("$ts $msg\n".toByteArray()) }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "ImgAI Test", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent): IBinder? = null
    override fun onDestroy() {
        try { faceDetector.close() } catch (_: Exception) {}
        try { embeddingExtractor.close() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }
    companion object { private const val TAG = "AutoTestService" }
}
