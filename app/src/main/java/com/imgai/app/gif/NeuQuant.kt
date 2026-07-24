package com.imgai.app.gif

/**
 * Simple color quantizer using median cut algorithm.
 * Reduces RGB pixels to a 256-color palette suitable for GIF.
 */
class NeuQuant(
    private val thePicture: IntArray,
    private val len: Int,
    private val sample: Int
) {
    /**
     * Process pixels and return a 768-element IntArray (256 * RGB).
     */
    fun process(): IntArray {
        // Collect pixel samples — thePicture is ARGB IntArray, one int per pixel
        val sampledPixels = ArrayList<Int>()
        val stride = if (sample > 0) sample.coerceAtLeast(1) else 1
        var i = 0
        while (i < len) {
            sampledPixels.add(thePicture[i])
            i += stride
        }

        if (sampledPixels.isEmpty()) {
            // Return a basic grayscale palette
            val grayPalette = IntArray(768) { idx -> (idx / 3 * 256 / 256) and 0xFF }
            paletteCache = grayPalette
            return grayPalette
        }

        // Median cut quantization
        val palette = medianCut(sampledPixels, 256)

        // Ensure 256 entries in result array
        val result = IntArray(768)
        for (j in palette.indices) {
            result[j * 3] = (palette[j] shr 16) and 0xFF     // R
            result[j * 3 + 1] = (palette[j] shr 8) and 0xFF  // G
            result[j * 3 + 2] = palette[j] and 0xFF           // B
        }
        // Fill remaining with black
        for (j in palette.size until 256) {
            result[j * 3] = 0
            result[j * 3 + 1] = 0
            result[j * 3 + 2] = 0
        }

        paletteCache = result
        return result
    }

    /**
     * Find the nearest palette index for a given RGB color.
     */
    private var paletteCache: IntArray? = null

    fun map(b: Int, g: Int, r: Int): Int {
        val pal = paletteCache ?: return 0
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for (idx in 0 until 256) {
            val pr = pal[idx * 3]
            val pg = pal[idx * 3 + 1]
            val pb = pal[idx * 3 + 2]
            val dist = (pr - r) * (pr - r) + (pg - g) * (pg - g) + (pb - b) * (pb - b)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = idx
            }
        }
        return bestIdx
    }

    private fun medianCut(pixels: List<Int>, maxColors: Int): List<Int> {
        data class Box(val colors: List<Int>)

        val boxes = mutableListOf(Box(pixels))

        while (boxes.size < maxColors) {
            // Find the box with the greatest range to split
            var splitIdx = -1
            var maxRange = 0
            var splitChannel = 0 // 0=r, 1=g, 2=b

            for (idx in boxes.indices) {
                val box = boxes[idx]
                if (box.colors.size < 2) continue

                var minR = 255; var maxR = 0
                var minG = 255; var maxG = 0
                var minB = 255; var maxB = 0

                for (c in box.colors) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < minR) minR = r; if (r > maxR) maxR = r
                    if (g < minG) minG = g; if (g > maxG) maxG = g
                    if (b < minB) minB = b; if (b > maxB) maxB = b
                }

                val rangeR = maxR - minR
                val rangeG = maxG - minG
                val rangeB = maxB - minB
                val maxChanRange = maxOf(rangeR, rangeG, rangeB)

                if (maxChanRange > maxRange) {
                    maxRange = maxChanRange
                    splitIdx = idx
                    splitChannel = when (maxChanRange) {
                        rangeR -> 0
                        rangeG -> 1
                        else -> 2
                    }
                }
            }

            if (splitIdx == -1) break

            // Split the box
            val box = boxes.removeAt(splitIdx)
            val sorted = when (splitChannel) {
                0 -> box.colors.sortedBy { (it shr 16) and 0xFF }
                1 -> box.colors.sortedBy { (it shr 8) and 0xFF }
                else -> box.colors.sortedBy { it and 0xFF }
            }
            val mid = sorted.size / 2
            if (mid == 0 || mid >= sorted.size) {
                boxes.add(box) // can't split further
                break
            }
            boxes.add(Box(sorted.subList(0, mid)))
            boxes.add(Box(sorted.subList(mid, sorted.size)))
        }

        // Compute average color for each box
        val result = mutableListOf<Int>()
        for (box in boxes) {
            if (box.colors.isEmpty()) continue
            var sumR = 0L; var sumG = 0L; var sumB = 0L
            for (c in box.colors) {
                sumR += (c shr 16) and 0xFF
                sumG += (c shr 8) and 0xFF
                sumB += c and 0xFF
            }
            val n = box.colors.size
            val r = (sumR / n).toInt()
            val g = (sumG / n).toInt()
            val b = (sumB / n).toInt()
            result.add((r shl 16) or (g shl 8) or b)
        }

        return result
    }
}
