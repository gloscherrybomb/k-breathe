package com.tymewear.karoo

/** Rolling mean of the last [windowSize] power samples; a null (stream gap) resets it. */
class PowerAverage(private val windowSize: Int = 3) {
    private val window = ArrayDeque<Double>()
    fun add(w: Double?): Double? {
        if (w == null) { window.clear(); return null }
        window.addLast(w); while (window.size > windowSize) window.removeFirst()
        return window.average()
    }
}
