package com.imgai.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.imgai.app.R
import com.imgai.app.gif.VideoToGifConverter
import kotlinx.coroutines.launch
import java.io.File

class VideoToGifFragment : Fragment() {

    private lateinit var ivPreview: ImageView
    private lateinit var btnPickVideo: Button
    private lateinit var seekFps: SeekBar
    private lateinit var tvFpsValue: TextView
    private lateinit var seekWidth: SeekBar
    private lateinit var tvWidthValue: TextView
    private lateinit var seekDuration: SeekBar
    private lateinit var tvDurationValue: TextView
    private lateinit var btnConvert: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var ivResult: ImageView
    private lateinit var tvResultInfo: TextView
    private lateinit var btnShare: Button

    private var selectedVideoUri: Uri? = null
    private var resultGifFile: File? = null

    // Width options: index -> px
    private val widthOptions = intArrayOf(120, 240, 360, 480, 540, 640, 720)

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            loadVideoPreview(uri)
            btnConvert.isEnabled = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_video_gif, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivPreview = view.findViewById(R.id.ivVideoPreview)
        btnPickVideo = view.findViewById(R.id.btnPickVideo)
        seekFps = view.findViewById(R.id.seekFps)
        tvFpsValue = view.findViewById(R.id.tvFpsValue)
        seekWidth = view.findViewById(R.id.seekWidth)
        tvWidthValue = view.findViewById(R.id.tvWidthValue)
        seekDuration = view.findViewById(R.id.seekDuration)
        tvDurationValue = view.findViewById(R.id.tvDurationValue)
        btnConvert = view.findViewById(R.id.btnConvert)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        ivResult = view.findViewById(R.id.ivResult)
        tvResultInfo = view.findViewById(R.id.tvResultInfo)
        btnShare = view.findViewById(R.id.btnShare)

        btnPickVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        // FPS: progress 0-14 -> fps 1-15
        seekFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = progress + 1
                tvFpsValue.text = fps.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Width
        seekWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val w = widthOptions[progress.coerceIn(0, widthOptions.size - 1)]
                tvWidthValue.text = w.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Duration: 0 = all, 1-30 = seconds
        seekDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDurationValue.text = if (progress == 0) "全部" else "${progress}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnConvert.setOnClickListener {
            startConversion()
        }

        btnShare.setOnClickListener {
            resultGifFile?.let { shareGif(it) }
        }
    }

    private fun loadVideoPreview(uri: Uri) {
        // Show first frame as preview
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(requireContext(), uri)
                val bitmap = retriever.getFrameAtTime(0)
                retriever.release()
                if (bitmap != null) {
                    ivPreview.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                // ignore, keep default background
            }
        }
    }

    private fun startConversion() {
        val videoUri = selectedVideoUri ?: return

        // Hide previous results
        ivResult.visibility = View.GONE
        tvResultInfo.visibility = View.GONE
        btnShare.visibility = View.GONE

        // Show progress
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgress.text = "准备中..."
        btnConvert.isEnabled = false
        btnPickVideo.isEnabled = false

        val fps = seekFps.progress + 1
        val targetWidth = widthOptions[seekWidth.progress.coerceIn(0, widthOptions.size - 1)]
        val durationSec = seekDuration.progress
        val maxDurationMs = if (durationSec == 0) 0L else durationSec * 1000L

        val outputDir = File(
            requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "imgai_gif"
        )

        val converter = VideoToGifConverter(
            context = requireContext(),
            videoUri = videoUri,
            outputDir = outputDir,
            width = targetWidth,
            fps = fps,
            maxDurationMs = maxDurationMs
        ) { percent, message ->
            if (percent < 0) {
                // Error
                requireActivity().runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvProgress.text = "❌ $message"
                    btnConvert.isEnabled = true
                    btnPickVideo.isEnabled = true
                }
            } else {
                requireActivity().runOnUiThread {
                    progressBar.progress = percent
                    tvProgress.text = message
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val gifFile = converter.convert()
                resultGifFile = gifFile

                // Show result
                progressBar.visibility = View.GONE
                tvProgress.text = "✅ 转换完成"
                btnConvert.isEnabled = true
                btnPickVideo.isEnabled = true

                // Display file info
                try {
                    val sizeKb = gifFile.length() / 1024
                    val sizeStr = if (sizeKb > 1024) "${String.format("%.1f", sizeKb / 1024.0)}MB" else "${sizeKb}KB"
                    tvResultInfo.text = "📁 ${gifFile.name}\n📏 ${sizeStr}\n📂 ${gifFile.parent}"
                    tvResultInfo.visibility = View.VISIBLE
                } catch (e: Exception) {
                    tvResultInfo.text = "📁 ${gifFile.name}"
                    tvResultInfo.visibility = View.VISIBLE
                }

                // Show share button
                btnShare.visibility = View.VISIBLE

                // Also copy to public Pictures for gallery visibility
                copyToGallery(gifFile)

                Toast.makeText(requireContext(), "GIF 已保存到 Pictures/ImgAI_GIF/", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvProgress.text = "❌ 转换失败: ${e.message}"
                btnConvert.isEnabled = true
                btnPickVideo.isEnabled = true
            }
        }
    }

    private fun copyToGallery(gifFile: File) {
        try {
            val resolver = requireContext().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, gifFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ImgAI_GIF")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    gifFile.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            // Non-fatal: the gif is still in app storage
        }
    }

    private fun shareGif(file: File) {
        try {
            // Scan file to make it visible
            MediaScannerConnection.scanFile(
                requireContext(),
                arrayOf(file.absolutePath),
                arrayOf("image/gif")
            ) { _, uri ->
                requireActivity().runOnUiThread {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/gif"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "分享 GIF"))
                }
            }
        } catch (e: Exception) {
            // Fallback: use FileProvider-less share
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/gif"
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            }
            startActivity(Intent.createChooser(shareIntent, "分享 GIF"))
        }
    }
}
