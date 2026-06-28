package com.imgai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.imgai.app.AutoTestService
import com.imgai.app.R
import com.imgai.app.data.AppDatabase
import com.imgai.app.detect.FaceEmbeddingExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private lateinit var btnCluster: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvModelStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnCluster = view.findViewById(R.id.btnCluster)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        tvStats = view.findViewById(R.id.tvStats)
        tvModelStatus = view.findViewById(R.id.tvModelStatus)

        val embeddingExtractor = FaceEmbeddingExtractor(requireContext())

        tvModelStatus.text = if (embeddingExtractor.isReady()) {
            "✅ 人脸识别模型已加载"
        } else {
            "⚠️ 人脸识别模型未找到"
        }

        btnCluster.setOnClickListener {
            triggerCluster()
        }

        loadStats()
    }

    private fun triggerCluster() {
        btnCluster.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "正在启动聚类..."
        progressBar.progress = 0
        progressBar.max = 100

        // 启动 AutoTestService 处理最近7天
        val intent = Intent(requireContext(), AutoTestService::class.java).apply {
            putExtra("action", "test")
            putExtra("days", 7)
        }
        requireContext().startForegroundService(intent)

        Toast.makeText(requireContext(), "开始聚类最近一周照片", Toast.LENGTH_SHORT).show()

        // 轮询进度文件
        viewLifecycleOwner.lifecycleScope.launch {
            val resultFile = java.io.File(
                requireContext().getExternalFilesDir(null), "imgai_test_result.txt"
            )
            var lastLine = ""
            while (true) {
                kotlinx.coroutines.delay(2000)
                val content = withContext(Dispatchers.IO) {
                    resultFile.takeIf { it.exists() }?.readText() ?: ""
                }
                val lines = content.lines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    lastLine = lines.last()
                    tvProgress.text = lastLine.substringAfter(" ") // 去时间戳

                    // 解析进度
                    val progressMatch = Regex("\\[(\\d+)/(\\d+)\\]").find(lastLine)
                    if (progressMatch != null) {
                        val cur = progressMatch.groupValues[1].toInt()
                        val total = progressMatch.groupValues[2].toInt()
                        progressBar.progress = (cur * 100 / total)
                    }

                    // 检测完成
                    if (lastLine.contains("RESULT") || lastLine.contains("DBSCAN") ||
                        lastLine.contains("Person_")) {
                        btnCluster.isEnabled = true
                        progressBar.visibility = View.GONE
                        tvProgress.text = "✅ 聚类完成！"
                        loadStats()
                        Toast.makeText(requireContext(), "聚类完成", Toast.LENGTH_LONG).show()
                        break
                    }
                    if (lastLine.contains("ERROR") || lastLine.contains("ERROR")) {
                        btnCluster.isEnabled = true
                        progressBar.visibility = View.GONE
                        tvProgress.text = "❌ 出错了: $lastLine"
                        break
                    }
                }
            }
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val photoCount = withContext(Dispatchers.IO) { db.photoDao().count() }
            val clusterCount = withContext(Dispatchers.IO) { db.faceClusterDao().count() }
            val embCount = withContext(Dispatchers.IO) { db.faceEmbeddingDao().count() }
            val categories = withContext(Dispatchers.IO) { db.categoryDao().getAll() }

            tvStats.text = buildString {
                appendLine("📷 已处理照片: $photoCount")
                appendLine("👤 人脸记录: $embCount")
                appendLine("👥 识别人物: $clusterCount")
                if (categories.isNotEmpty()) {
                    appendLine("📁 分类: ${categories.joinToString(", ") { it.name }}")
                }
            }
        }
    }
}
