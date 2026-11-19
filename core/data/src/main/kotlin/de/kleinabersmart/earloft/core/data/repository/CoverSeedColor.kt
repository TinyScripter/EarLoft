package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

private const val SAMPLE_SIZE = 64
private const val MAX_COLORS = 128

/** Picks from a cover's ARGB pixels the color suitable as seed color of the color scheme (Material Color Utilities scoring). */
fun seedColorOf(pixels: IntArray): Int =
    Score.score(QuantizerCelebi.quantize(pixels, MAX_COLORS), 1).first()

/** Like [seedColorOf], for a bitmap of any size (including hardware bitmaps). */
fun seedColorOf(bitmap: Bitmap): Int {
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val small = Bitmap.createScaledBitmap(software, SAMPLE_SIZE, SAMPLE_SIZE, true)
    val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
    small.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
    return seedColorOf(pixels)
}
