package com.tymewear.karoo

/** Loads a recorded ride from test resources. Columns are resolved by header name
 *  because the fixtures were exported at different times with different sets. */
class RideFixture(
    val watts: List<Double?>,
    val ve: List<Double?>,
    val br: List<Double?>,
) {
    /** Fraction of distinct VE values. Corrupt rides (a frozen value written every
     *  second) score near zero; genuine per-second data scores 0.5-0.77. */
    fun qualityRatio(): Double {
        val vals = ve.filterNotNull()
        if (vals.isEmpty()) return 0.0
        return vals.toSet().size.toDouble() / vals.size
    }

    companion object {
        fun load(name: String): RideFixture {
            val stream = RideFixture::class.java.classLoader
                .getResourceAsStream("fixtures/$name")
                ?: error("fixture not found: $name")
            val lines = stream.bufferedReader().readLines()
            require(lines.isNotEmpty()) { "empty fixture: $name" }
            val header = lines.first().split(",").map { it.trim() }
            fun idx(vararg candidates: String): Int? =
                candidates.firstNotNullOfOrNull { c ->
                    header.indexOf(c).takeIf { it >= 0 }
                }
            val wIdx = idx("watts")
            val vIdx = idx("ve", "TymeVentilation", "tidal_volume_min")
            val bIdx = idx("br", "TymeBreathRate", "respiration")
            val watts = ArrayList<Double?>()
            val ve = ArrayList<Double?>()
            val br = ArrayList<Double?>()
            for (line in lines.drop(1)) {
                val f = line.split(",")
                fun get(i: Int?): Double? =
                    i?.let { f.getOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() && s != "None" }?.toDoubleOrNull() }
                watts.add(get(wIdx)); ve.add(get(vIdx)); br.add(get(bIdx))
            }
            return RideFixture(watts, ve, br)
        }
    }
}
