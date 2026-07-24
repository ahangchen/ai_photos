package com.imgai.app.gif

import java.io.OutputStream

/**
 * Simplified and correct LZW encoder for GIF.
 * Based on the well-known algorithm used in giflib.
 */
class LZWEncoder(
    private val width: Int,
    private val height: Int,
    private val pixels: ByteArray,
    private val colorDepth: Int
) {
    private val EOF = -1
    private val BITS = 12
    private val HSIZE = 5003

    private var remaining: Int = width * height
    private var curPixel = 0
    private var initCodeSize = colorDepth + 1

    private var gInitBits: Int = 0
    private var n_bits: Int = 0
    private var maxcode: Int = 0
    private var clearCode: Int = 0
    private var eofCode: Int = 0
    private var freeEnt: Int = 0
    private var clearFlg = false

    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)

    private var curAccum = 0
    private var curBits = 0

    private val masks = intArrayOf(
        0x000, 0x001, 0x003, 0x007, 0x00F, 0x01F, 0x03F, 0x07F,
        0x0FF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    private var aCount = 0
    private val accum = ByteArray(256)

    fun encode(os: OutputStream) {
        gInitBits = initCodeSize
        curAccum = 0
        curBits = 0
        aCount = 0

        clearCode = 1 shl (initCodeSize - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        clearFlg = false
        n_bits = gInitBits
        maxcode = maxCode(gInitBits)

        for (i in 0 until HSIZE) htab[i] = -1

        var ent = nextPixel()

        // Compute hash shift
        var hshift = 0
        var fcode = HSIZE
        while (fcode < 65536) { hshift++; fcode *= 2 }
        hshift = 8 - hshift

        var pixel = nextPixel()
        while (pixel != EOF) {
            fcode = (ent shl BITS) + pixel
            var hashIndex = (ent shl hshift) xor fcode
            if (hashIndex >= HSIZE) hashIndex -= HSIZE

            if (htab[hashIndex] == fcode) {
                ent = codetab[hashIndex]
                pixel = nextPixel()
                continue
            }

            // Probe for empty or matching slot
            var found = false
            if (htab[hashIndex] >= 0) {
                // Quadratic probing
                var disp = HSIZE - hashIndex
                if (hashIndex == 0) disp = 1
                var probe = hashIndex
                loop@ do {
                    probe -= disp
                    if (probe < 0) probe += HSIZE
                    when {
                        htab[probe] == fcode -> {
                            ent = codetab[probe]
                            found = true
                            break@loop
                        }
                        htab[probe] < 0 -> break@loop
                    }
                } while (true)
            }

            if (found) {
                pixel = nextPixel()
                continue
            }

            // Output the current entry
            output(ent, os)
            ent = pixel

            if (freeEnt < (1 shl BITS)) {
                codetab[hashIndex] = freeEnt
                freeEnt++
                htab[hashIndex] = fcode
            } else {
                clBlock(os)
            }

            pixel = nextPixel()
        }

        // Output final entries
        output(ent, os)
        output(eofCode, os)
    }

    private fun nextPixel(): Int {
        return if (remaining == 0) EOF else {
            remaining--
            pixels[curPixel++].toInt() and 0xFF
        }
    }

    private fun maxCode(n: Int): Int = (1 shl n) - 1

    private fun clBlock(os: OutputStream) {
        for (i in 0 until HSIZE) htab[i] = -1
        freeEnt = clearCode + 2
        clearFlg = true
        output(clearCode, os)
    }

    private fun output(code: Int, os: OutputStream) {
        if (curBits > 0) {
            curAccum = curAccum or (code shl curBits)
        } else {
            curAccum = code
        }
        curBits += n_bits

        while (curBits >= 8) {
            charOut(curAccum and 0xFF, os)
            curAccum = curAccum ushr 8
            curBits -= 8
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                maxcode = maxCode(gInitBits)
                clearFlg = false
            } else {
                n_bits++
                maxcode = if (n_bits == BITS) (1 shl BITS) else maxCode(n_bits)
            }
        }

        if (code == eofCode) {
            while (curBits > 0) {
                charOut(curAccum and 0xFF, os)
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            flushChar(os)
        }
    }

    private fun charOut(c: Int, os: OutputStream) {
        accum[aCount++] = c.toByte()
        if (aCount >= 254) flushChar(os)
    }

    private fun flushChar(os: OutputStream) {
        if (aCount > 0) {
            os.write(aCount)
            os.write(accum, 0, aCount)
            aCount = 0
        }
    }
}
