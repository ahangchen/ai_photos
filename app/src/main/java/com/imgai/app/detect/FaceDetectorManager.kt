package com.imgai.app.detect

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ML Kit 人脸检测封装
 *
 * - PERFORMANCE_MODE_ACCURATE 精确模式
 * - 检测所有人脸（不限制数量）
 * - 返回人脸边界框 + 关键点（用于对齐）
 */
class FaceDetectorManager(context: Context) {

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build()
        FaceDetection.getClient(options)
    }

    data class FaceInfo(
        val boundingBox: Rect,
        val leftEye: PointF?,
        val rightEye: PointF?,
        val rotationZ: Float
    )

    /**
     * 检测 bitmap 中的所有人脸（带 10 秒超时）
     */
    suspend fun detect(bitmap: Bitmap): List<FaceInfo> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = withTimeoutOrNull(10_000) {
            detector.process(image).await()
        } ?: run {
            Log.w(TAG, "Face detection timed out for a bitmap")
            return emptyList()
        }
        return faces.map { face ->
            FaceInfo(
                boundingBox = face.boundingBox,
                leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                rotationZ = face.headEulerAngleZ
            )
        }
    }

    /**
     * 从 bitmap 裁剪人脸区域，扩展边界以包含完整人脸
     */
    fun cropFace(bitmap: Bitmap, faceInfo: FaceInfo): Bitmap {
        val rect = Rect(faceInfo.boundingBox)

        // 扩展裁剪区域 30%
        val w = rect.width()
        val h = rect.height()
        val expandX = (w * 0.3f).toInt()
        val expandY = (h * 0.3f).toInt()

        rect.left = (rect.left - expandX).coerceAtLeast(0)
        rect.top = (rect.top - expandY).coerceAtLeast(0)
        rect.right = (rect.right + expandX).coerceAtMost(bitmap.width)
        rect.bottom = (rect.bottom + expandY).coerceAtMost(bitmap.height)

        return Bitmap.createBitmap(
            bitmap,
            rect.left,
            rect.top,
            (rect.right - rect.left).coerceAtLeast(1),
            (rect.bottom - rect.top).coerceAtLeast(1)
        )
    }

    /**
     * 根据眼睛位置旋转对齐人脸（使双眼水平）
     */
    fun alignFace(bitmap: Bitmap, faceInfo: FaceInfo): Bitmap {
        val leftEye = faceInfo.leftEye ?: return bitmap
        val rightEye = faceInfo.rightEye ?: return bitmap

        val angle = Math.toDegrees(
            Math.atan2(
                (rightEye.y - leftEye.y).toDouble(),
                (rightEye.x - leftEye.x).toDouble()
            )
        ).toFloat()

        if (Math.abs(angle) < 2f) return bitmap

        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "FaceDetector"
    }
}
