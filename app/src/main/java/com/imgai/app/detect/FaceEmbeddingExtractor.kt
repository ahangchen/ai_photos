package com.imgai.app.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * MobileFaceNet TFLite 人脸特征提取
 *
 * 输入: 112x112x3 Float (归一化到 [-1, 1])
 * 输出: 192-dim L2-normalized embedding
 *
 * 需要在 assets 中放置 mobile_face_net.tflite
 * 下载: https://github.com/deepinsight/insightface 的 MobileFaceNet 模型转 TFLite
 */
class FaceEmbeddingExtractor(context: Context) {

    private var interpreter: Interpreter? = null
    private val inputImageSize = 112
    private val embeddingDim = 192

    // 输入 buffer: 112*112*3 floats * 4 bytes
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * inputImageSize * inputImageSize * 3)
        .order(ByteOrder.nativeOrder())

    // 输出 buffer: 192 floats * 4 bytes
    private val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * embeddingDim)
        .order(ByteOrder.nativeOrder())

    init {
        try {
            val modelBuffer = loadModelFile(context, "mobile_face_net.tflite")
            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.w(TAG, "mobile_face_net.tflite not found in assets. " +
                    "Face embedding will be unavailable until model is added.", e)
        }
    }

    /**
     * 从 bitmap 提取 192 维人脸特征向量
     * bitmap 应该是已裁剪+对齐的人脸区域
     */
    fun extract(bitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: run {
            Log.w(TAG, "Interpreter not initialized")
            return null
        }

        // Resize 到 112x112
        val resized = Bitmap.createScaledBitmap(bitmap, inputImageSize, inputImageSize, true)

        // 填充 input buffer (归一化到 [-1, 1])
        inputBuffer.rewind()
        val pixels = IntArray(inputImageSize * inputImageSize)
        resized.getPixels(pixels, 0, inputImageSize, 0, 0, inputImageSize, inputImageSize)

        for (pixel in pixels) {
            // RGB 归一化: (value / 127.5) - 1.0
            val r = ((pixel shr 16 and 0xFF) / 127.5f - 1.0f)
            val g = ((pixel shr 8 and 0xFF) / 127.5f - 1.0f)
            val b = ((pixel and 0xFF) / 127.5f - 1.0f)
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        // 推理
        outputBuffer.rewind()
        interp.run(inputBuffer, outputBuffer)

        // 读取结果
        val embedding = FloatArray(embeddingDim)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(embedding)

        // L2 归一化
        l2Normalize(embedding)

        return embedding
    }

    fun isReady(): Boolean = interpreter != null

    private fun l2Normalize(vec: FloatArray) {
        var norm = 0.0
        for (v in vec) norm += (v * v).toDouble()
        norm = Math.sqrt(norm)
        if (norm > 0) {
            val inv = (1.0 / norm).toFloat()
            for (i in vec.indices) vec[i] *= inv
        }
    }

    private fun loadModelFile(context: Context, filename: String): ByteBuffer {
        val fd = context.assets.openFd(filename)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        val startOffset = fd.startOffset
        val declaredLength = fd.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "FaceEmbedding"
    }
}
