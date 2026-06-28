package com.imgai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "scan"
        val days = intent.getIntExtra("days", 0)
        Log.i(TAG, "AutoTest received action=$action days=$days")
        writeLog(context, "Broadcast received: action=$action days=$days")

        val msg = when (action) {
            "scan" -> "ImgAI: 扫描相册..."
            "test" -> "ImgAI: 开始测试（最近7天）"
            "process" -> "ImgAI: 处理照片..."
            "reset" -> "ImgAI: 清除数据..."
            else -> "ImgAI: $action"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

        try {
            val serviceIntent = Intent(context, AutoTestService::class.java).apply {
                putExtra("action", action)
                putExtra("days", days)
            }
            context.startForegroundService(serviceIntent)
            writeLog(context, "startForegroundService called OK")
        } catch (e: Exception) {
            writeLog(context, "ERROR: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(context, "ImgAI ERROR: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeLog(context: Context, msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val line = "$ts $msg\n"
            // App 外部文件目录（免权限）
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            FileOutputStream(File(dir, "imgai_test_result.txt"), true).use { it.write(line.toByteArray()) }
            // Download 目录
            try { FileOutputStream(File("/sdcard/Download/imgai_test_result.txt"), true).use { it.write(line.toByteArray()) } } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    companion object { private const val TAG = "AutoTestReceiver" }
}
