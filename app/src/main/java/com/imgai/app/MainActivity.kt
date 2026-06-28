package com.imgai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.imgai.app.data.AppDatabase
import com.imgai.app.detect.FaceDetectorManager
import com.imgai.app.detect.FaceEmbeddingExtractor
import com.imgai.app.ui.BrowseFragment
import com.imgai.app.ui.HomeFragment
import com.imgai.app.ui.ReviewFragment

class MainActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }
    private val faceDetector by lazy { FaceDetectorManager(this) }
    private val embeddingExtractor by lazy { FaceEmbeddingExtractor(this) }

    companion object {
        private const val REQ_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupBottomNav()

        // 检查是否通过 am start --es action xxx 触发
        val autoAction = intent?.getStringExtra("action")
        if (autoAction != null && hasPermission()) {
            startAutoService(autoAction, intent?.getIntExtra("days", 0) ?: 0)
        }

        // 默认显示首页
        if (savedInstanceState == null) {
            switchFragment(HomeFragment())
        }

        if (!hasPermission()) {
            requestPermission()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val autoAction = intent.getStringExtra("action")
        if (autoAction != null && hasPermission()) {
            startAutoService(autoAction, intent.getIntExtra("days", 0))
        }
    }

    private fun startAutoService(action: String, days: Int) {
        val serviceIntent = Intent(this, AutoTestService::class.java).apply {
            putExtra("action", action)
            putExtra("days", days)
        }
        startForegroundService(serviceIntent)
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { switchFragment(HomeFragment()); true }
                R.id.nav_browse -> { switchFragment(BrowseFragment()); true }
                R.id.nav_review -> { switchFragment(ReviewFragment()); true }
                else -> false
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    fun hasPermission(): Boolean {
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
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要存储权限才能使用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceDetector.close()
        embeddingExtractor.close()
    }
}
