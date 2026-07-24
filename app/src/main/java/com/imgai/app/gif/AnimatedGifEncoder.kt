package com.imgai.app.gif

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Lightweight animated GIF encoder.
 * Based on the classic LZW GIF encoder algorithm.
 */
class AnimatedGifEncoder {

    private var width = 0
    private var height = 0
    private var transparent = false
    private var transIndex: Int = 0
    private var repeat = -1 // -1: no repeat, 0: infinite
    private var delay = 0 // ms
    private var started = false

    private var out: ByteArrayOutputStream? = null
    private var image: Bitmap? = null
    private var pixels: IntArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorDepth: Int = 0
    private var colorTab: IntArray? = null
    private var lzw: LZWEncoder? = null

    private val usedEntry = BooleanArray(256)
    private var palSize = 7 // color table size (bits-1)
    private var dispose = -1 // 0=no, 1=leave, 2=background, 3=previous
    private var firstFrame = true

    fun setSize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun setDelay(ms: Int) {
        delay = ms
    }

    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    fun setTransparent(color: Int) {
        transparent = true
        transIndex = color
    }

    fun start(): Boolean {
        out = ByteArrayOutputStream()
        started = true
        return true
    }

    fun start(os: OutputStream): Boolean {
        out = if (os is ByteArrayOutputStream) os else ByteArrayOutputStream()
        started = true
        return true
    }

    fun addFrame(bitmap: Bitmap): Boolean {
        if (!started || out == null) return false

        image = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            scaled
        }

        getImagePixels()
        analyzePixels()
        if (firstFrame) {
            writeLSD()
            writePalette()
            if (repeat >= 0) writeNetscapeExt()
        }
        writeGraphicCtrlExt()
        writeImageDesc()
        writePalette()
        writePixels()
        firstFrame = false
        return true
    }

    fun finish(): ByteArray? {
        if (!started) return null
        started = false
        out?.write(0x3B) // trailer
        val result = out?.toByteArray()
        out = null
        image = null
        pixels = null
        indexedPixels = null
        return result
    }

    private fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height
        if (width != w || height != h) {
            width = w
            height = h
        }
        pixels = IntArray(w * h)
        image!!.getPixels(pixels!!, 0, w, 0, 0, w, h)
    }

    private fun analyzePixels() {
        val len = pixels!!.size // number of ARGB pixels
        indexedPixels = ByteArray(len)
        val nq = NeuQuant(pixels!!, len, 10)
        colorTab = nq.process()
        // GIF palette is RGB; our NeuQuant already returns RGB triples
        for (i in 0 until 256) {
            usedEntry[i] = false
        }
        // map image pixels to palette indices
        for (i in 0 until len) {
            val pixel = pixels!![i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val index = nq.map(b, g, r)
            usedEntry[index] = true
            indexedPixels!![i] = index.toByte()
        }
        pixels = null
        colorDepth = 8
        palSize = 7
    }

    private fun writeGraphicCtrlExt() {
        out!!.write(0x21) // extension
        out!!.write(0xF9) // GCE
        out!!.write(4)    // data size
        var transp: Int
        var disp: Int
        if (transparent) {
            transp = 1
            disp = 2
        } else {
            transp = 0
            disp = if (dispose >= 0) dispose else 0
        }
        if (dispose >= 0) disp = dispose and 7
        disp = disp shl 2
        out!!.write(0 or disp or 0 or transp)
        writeShort(delay)
        out!!.write(transIndex)
        out!!.write(0)
    }

    private fun writeImageDesc() {
        out!!.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        if (firstFrame) {
            out!!.write(0)
        } else {
            out!!.write(0x80 or 0 or 0 or palSize)
        }
    }

    private fun writeLSD() {
        writeString("GIF89a")
        writeShort(width)
        writeShort(height)
        out!!.write(0x80 or 0x70 or 0 or palSize)
        out!!.write(0)
        out!!.write(0)
    }

    private fun writeNetscapeExt() {
        out!!.write(0x21)
        out!!.write(0xFF)
        out!!.write(11)
        writeString("NETSCAPE2.0")
        out!!.write(3)
        out!!.write(1)
        writeShort(repeat)
        out!!.write(0)
    }

    private fun writePalette() {
        // colorTab is IntArray, need to convert to ByteArray for OutputStream
        val bytes = ByteArray(colorTab!!.size)
        for (i in colorTab!!.indices) bytes[i] = colorTab!![i].toByte()
        out!!.write(bytes, 0, bytes.size)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) out!!.write(0)
    }

    private fun writePixels() {
        lzw = LZWEncoder(width, height, indexedPixels!!, colorDepth)
        lzw!!.encode(out!!)
    }

    private fun writeShort(value: Int) {
        out!!.write(value and 0xFF)
        out!!.write((value ushr 8) and 0xFF)
    }

    private fun writeString(s: String) {
        for (c in s) out!!.write(c.code)
    }
}
