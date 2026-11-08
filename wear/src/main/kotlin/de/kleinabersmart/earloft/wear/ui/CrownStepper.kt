package de.kleinabersmart.earloft.wear.ui

/**
 * Macht aus den Ereignissen der Krone ganze Schritte. Die Krone liefert je Rastung viele kleine Pixelwerte
 * (gemessen auf der Pixel Watch 3: ca. 10 Ereignisse mit zusammen rund 34 px, schnelles Drehen mehr); jedes einzelne
 * als Schritt zu nehmen, wäre viel zu grob. Der Rest unter [pixelsPerStep] bleibt für die nächste Drehung erhalten,
 * ein Richtungswechsel verwirft ihn.
 */
class CrownStepper(private val pixelsPerStep: Float) {
    private var remainder = 0f

    /** @return ganze Schritte, positiv in Drehrichtung „lauter“, negativ umgekehrt; 0, solange der Schwellwert nicht erreicht ist. */
    fun add(pixels: Float): Int {
        if (pixels * remainder < 0f) remainder = 0f
        remainder += pixels
        val steps = (remainder / pixelsPerStep).toInt()
        remainder -= steps * pixelsPerStep
        return steps
    }
}
