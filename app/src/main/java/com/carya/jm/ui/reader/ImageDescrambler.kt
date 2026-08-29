package com.carya.jm.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Descrambles a JM comic page image and saves the result to disk.
 *
 * JM scrambles images by dividing them into [num] horizontal strips
 * and reversing their order (bottom strip becomes top). This utility
 * reverses the process using Android's native Bitmap/Canvas API,
 * which handles WebP/JPEG/PNG natively — no PIL needed.
 *
 * The algorithm mirrors jmcomic's `JmImageTool.decode_and_save`:
 * - move  = height / num  (integer division)
 * - over  = height % num  (remainder appended to strip 0)
 * - Strip i source Y: h - move * (i + 1) - over
 * - Strip i dest Y:   move * i  (+ over for i > 0)
 * - Strip 0 height:   move + over
 */
object ImageDescrambler {

    private const val FORMAT_JPEG = 1
    private const val FORMAT_PNG = 2
    private const val FORMAT_WEBP = 3

    /**
     * Descramble [rawPath] and save to [outputPath].
     *
     * @param num strip count; 0 means no scramble (copies the file as-is)
     * @return true on success, false on failure
     */
    fun descramble(rawPath: String, outputPath: String, num: Int): Boolean {
        if (num <= 0) {
            // No scramble — just copy the raw file.
            File(rawPath).copyTo(File(outputPath), overwrite = true)
            return true
        }

        // Decode at full resolution.
        val input = BitmapFactory.decodeFile(rawPath) ?: return false

        return try {
            val w = input.width
            val h = input.height
            val config = input.config ?: Bitmap.Config.ARGB_8888

            val result = Bitmap.createBitmap(w, h, config)
            val canvas = Canvas(result)

            val move = h / num
            val over = h % num

            for (i in 0 until num) {
                val stripHeight: Int
                val ySrc: Int
                val yDst: Int

                if (i == 0) {
                    // First destination strip gets the remainder pixels.
                    stripHeight = move + over
                    ySrc = h - move - over
                    yDst = 0
                } else {
                    stripHeight = move
                    ySrc = h - move * (i + 1) - over
                    yDst = move * i + over
                }

                val srcRect = Rect(0, ySrc, w, ySrc + stripHeight)
                val dstRect = Rect(0, yDst, w, yDst + stripHeight)
                canvas.drawBitmap(input, srcRect, dstRect, null)
            }

            // Save descrambled image to disk.
            File(outputPath).parentFile?.mkdirs()
            FileOutputStream(outputPath).use { out ->
                // Re-encoding with the same format as the source keeps the file
                // extension honest; lossy WebP (q92) / JPEG (q92) encode an order
                // of magnitude faster than the previous lossless WebP(100) path.
                when (detectFormat(rawPath)) {
                    FORMAT_JPEG -> result.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    FORMAT_PNG -> result.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            result.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, out)
                        } else {
                            // Before API 30, WEBP with quality < 100 is lossy.
                            result.compress(Bitmap.CompressFormat.WEBP, 92, out)
                        }
                    }
                }
            }
            result.recycle()
            true
        } catch (e: Exception) {
            false
        } finally {
            input.recycle()
        }
    }

    private fun detectFormat(path: String): Int = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> FORMAT_JPEG
        "png" -> FORMAT_PNG
        else -> FORMAT_WEBP
    }

    /** Check if a descrambled image already exists in cache. */
    fun isCached(decodedPath: String): Boolean = File(decodedPath).exists()
}
