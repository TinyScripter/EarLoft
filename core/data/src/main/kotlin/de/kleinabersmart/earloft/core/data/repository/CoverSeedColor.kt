package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

private const val SAMPLE_SIZE = 64
private const val MAX_COLORS = 128

/** Wählt aus den ARGB-Pixeln eines Covers die Farbe, die als Grundfarbe des Farbschemas taugt (Material-Color-Utilities-Bewertung). */
fun seedColorOf(pixels: IntArray): Int =
    Score.score(QuantizerCelebi.quantize(pixels, MAX_COLORS), 1).first()

/** Wie [seedColorOf], für ein Bitmap beliebiger Größe (auch Hardware-Bitmaps). */
fun seedColorOf(bitmap: Bitmap): Int {
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val small = Bitmap.createScaledBitmap(software, SAMPLE_SIZE, SAMPLE_SIZE, true)
    val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
    small.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
    return seedColorOf(pixels)
}
