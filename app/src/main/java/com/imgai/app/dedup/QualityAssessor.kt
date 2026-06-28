package com.imgai.app.dedup

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.sqrt

/**
 * 图像质量评估 + pHash 感知哈希
 *
 * 质量评分综合：
 * - 清晰度 (Laplacian variance) — 越高越清晰
 * - 亮度适中程度 — 过暗/过亮扣分
 * - 对比度 — 标准差越大层次越丰富
 *
 * pHash：
 * - 64-bit 感知哈希
 * - Hamming distance <= 5 视为相似
 */
object QualityAssessor {

    data class QualityScore(
        val sharpness: Float,      // 拉普拉斯方差
        val brightness: Float,     // 平均亮度 (0-255)
        val contrast: Float,       // 像素标准差
        val score: Float           // 综合 0-1
    )

    data class PHash(val hash: Long, val hashBits: String)

    /**
     * 计算图像质量评分
     */
    fun assessQuality(bitmap: Bitmap): QualityScore {
        val w = bitmap.width
        val h = bitmap.height

        // 降采样以加速（最大 256px）
        val sampleW: Int
        val sampleH: Int
        val scale: Float
        if (w > 256 || h > 256) {
            scale = 256f / maxOf(w, h)
            sampleW = (w * scale).toInt()
            sampleH = (h * scale).toInt()
        } else {
            scale = 1f
            sampleW = w
            sampleH = h
        }

        val gray = FloatArray(sampleW * sampleH)
        var sum = 0.0
        var sumSq = 0.0

        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                val srcX = (x / scale).toInt().coerceIn(0, w - 1)
                val srcY = (y / scale).toInt().coerceIn(0, h - 1)
                val pixel = bitmap.getPixel(srcX, srcY)
                // 灰度 = 0.299R + 0.587G + 0.114B
                val g = 0.299f * ((pixel shr 16) and 0xFF) +
                        0.587f * ((pixel shr 8) and 0xFF) +
                        0.114f * (pixel and 0xFF)
                gray[y * sampleW + x] = g
                sum += g
                sumSq += g * g
            }
        }

        val n = sampleW * sampleH
        val mean = (sum / n).toFloat()
        val variance = (sumSq / n - sum * sum / (n * n)).toFloat()
        val std = sqrt(variance.coerceAtLeast(0f))

        // 清晰度: Laplacian variance
        val lapVar = laplacianVariance(gray, sampleW, sampleH)

        // 综合评分 (归一化到 0-1)
        // 清晰度权重 0.5, 亮度 0.2, 对比度 0.3
        val sharpScore = (lapVar / 500f).coerceIn(0f, 1f)
        val brightScore = 1f - Math.abs(mean - 128f) / 128f
        val contrastScore = (std / 64f).coerceIn(0f, 1f)
        val total = sharpScore * 0.5f + brightScore * 0.2f + contrastScore * 0.3f

        return QualityScore(
            sharpness = lapVar,
            brightness = mean,
            contrast = std,
            score = total
        )
    }

    /**
     * 拉普拉斯方差 — 衡量清晰度
     */
    private fun laplacianVariance(gray: FloatArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f

        val laplacian = FloatArray(w * h)
        // Laplacian kernel:
        //  0  1  0
        //  1 -4  1
        //  0  1  0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val v = gray[idx - w] + gray[idx + w] +
                        gray[idx - 1] + gray[idx + 1] -
                        4f * gray[idx]
                laplacian[idx] = v
            }
        }

        // 计算方差
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val v = laplacian[y * w + x]
                sum += v
                sumSq += v * v
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sum / count
        return (sumSq / count - mean * mean).toFloat().coerceAtLeast(0f)
    }

    /**
     * 计算 pHash (64-bit)
     */
    fun computePHash(bitmap: Bitmap): PHash {
        // 1. 缩放到 32x32
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)

        // 2. 转灰度
        val gray = FloatArray(32 * 32)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val pixel = small.getPixel(x, y)
                gray[y * 32 + x] = 0.299f * ((pixel shr 16) and 0xFF) +
                        0.587f * ((pixel shr 8) and 0xFF) +
                        0.114f * (pixel and 0xFF)
            }
        }

        // 3. 简化 DCT (仅使用低频 8x8)
        val dct = simpleDCT(gray, 32)
        val lowFreq = FloatArray(64)
        for (i in 0 until 8) {
            for (j in 0 until 8) {
                lowFreq[i * 8 + j] = dct[i * 32 + j]
            }
        }

        // 4. 计算均值（排除 DC 分量 [0,0]）
        var sum = 0.0
        for (i in 1 until 64) sum += lowFreq[i]
        val avg = (sum / 63).toFloat()

        // 5. 生成 hash
        var hash = 0L
        val bits = StringBuilder()
        for (i in 0 until 64) {
            val bit = if (lowFreq[i] > avg) 1 else 0
            bits.append(bit)
            if (bit == 1) hash = hash or (1L shl i)
        }

        return PHash(hash, bits.toString())
    }

    /**
     * 简化 DCT-II
     */
    private fun simpleDCT(gray: FloatArray, n: Int): FloatArray {
        val dct = FloatArray(n * n)
        val alpha = FloatArray(n)
        alpha[0] = sqrt(1f / n)
        for (i in 1 until n) alpha[i] = sqrt(2f / n)

        for (u in 0 until n) {
            for (v in 0 until n) {
                var sum = 0f
                for (x in 0 until n) {
                    for (y in 0 until n) {
                        sum += gray[x * n + y] *
                                Math.cos(Math.PI * (2 * x + 1) * u / (2.0 * n)).toFloat() *
                                Math.cos(Math.PI * (2 * y + 1) * v / (2.0 * n)).toFloat()
                    }
                }
                dct[u * n + v] = alpha[u] * alpha[v] * sum
            }
        }
        return dct
    }

    /**
     * Hamming distance
     */
    fun hammingDistance(a: PHash, b: PHash): Int {
        return java.lang.Long.bitCount(a.hash xor b.hash)
    }

    /**
     * 判断两张图是否相似（pHash distance <= threshold）
     */
    fun isSimilar(a: PHash, b: PHash, threshold: Int = 5): Boolean {
        return hammingDistance(a, b) <= threshold
    }

    private const val TAG = "QualityAssessor"
}
