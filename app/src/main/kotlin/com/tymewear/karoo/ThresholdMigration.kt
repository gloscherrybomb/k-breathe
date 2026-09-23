package com.tymewear.karoo

import android.content.Context

/** Preference keys for the thresholds, under Tymewear's names. All new in 0.7.0: none of
 *  them reuses a 0.6.x key, because the 0.6.x key `vt1_threshold` held what Tymewear
 *  calls Endurance, and reusing a name with a new meaning is exactly the confusion this
 *  release removes. */
object ThresholdKeys {
    const val ENDURANCE = "zone_endurance"
    const val VT1 = "zone_vt1"
    const val VT2 = "zone_vt2"
    const val TOP_Z4 = "zone_top_z4"
    const val VO2MAX = "zone_vo2max"

    val THRESHOLDS = listOf(ENDURANCE, VT1, VT2, TOP_Z4, VO2MAX)
}

/** Writes and removals to apply to the preferences in one edit. */
data class PrefsEdit(
    val floats: Map<String, Float>,
    val removals: Set<String>,
)

/**
 * Brings stored settings up to 0.7.0.
 *
 * 1. Thresholds move onto Tymewear's names. 0.6.x called the four zone edges `vt1`,
 *    `vt2`, `topZ4`, `vo2max`; Tymewear's Fitness Profile calls the same four numbers
 *    Endurance, VT1, VT2 and Top Z4, and adds VO2max (the top of Z5). So each stored
 *    value moves one name down, and VO2max starts at the default. Runs once: never when
 *    any new threshold key exists.
 * 2. The threshold-suggestion feature is gone: K-Breathe keeps the thresholds the rider
 *    enters in Settings, taken from their Tymewear Fitness Profile. Every key the feature
 *    ever wrote — the 0.6.x names and the ones this release briefly used — is deleted,
 *    whenever present, so nothing stale lingers.
 *
 * Pure — takes and returns plain maps — so every rule is testable on the JVM.
 */
object ThresholdMigration {
    private const val OLD_VT1 = "vt1_threshold"
    private const val OLD_VT2 = "vt2_threshold"
    private const val OLD_TOP_Z4 = "topz4_threshold"
    private const val OLD_VO2MAX = "vo2max_threshold"

    val OLD_THRESHOLD_KEYS = listOf(OLD_VT1, OLD_VT2, OLD_TOP_Z4, OLD_VO2MAX)

    /** Old key → new key: one name down. */
    private val MOVES = mapOf(
        OLD_VT1 to ThresholdKeys.ENDURANCE,
        OLD_VT2 to ThresholdKeys.VT1,
        OLD_TOP_Z4 to ThresholdKeys.VT2,
        OLD_VO2MAX to ThresholdKeys.TOP_Z4,
    )

    /** Everything the removed threshold-suggestion feature stored. */
    val SUGGESTION_KEYS = setOf(
        // 0.6.x
        "threshold_auto_apply",
        "threshold_change_history",
        "threshold_dismissed_vt1",
        "threshold_dismissed_vt2",
        "threshold_evidence_history",
        // introduced and removed again on the way to 0.7.0
        "threshold_changes",
        "suggestion_dismissed_endurance",
        "suggestion_dismissed_vt1",
        "suggestion_dismissed_vt2",
        "suggestion_dismissed_top_z4",
        "suggestion_dismissed_vo2max",
    )

    /** The edit that brings [stored] up to date, or null when there is nothing to do. */
    fun migrate(stored: Map<String, *>): PrefsEdit? {
        val removals = LinkedHashSet<String>(SUGGESTION_KEYS.filter { it in stored })
        val floats = LinkedHashMap<String, Float>()

        val alreadyOnNewNames = ThresholdKeys.THRESHOLDS.any { it in stored }
        if (!alreadyOnNewNames && OLD_THRESHOLD_KEYS.any { it in stored }) {
            for ((old, new) in MOVES) {
                (stored[old] as? Number)?.let { floats[new] = it.toFloat() }
            }
            // VO2max is new. The default sits above every edge unless the old top edge was
            // already that high; then keep the default's ratio to Top Z4 so the order holds
            // and the settings screen will save.
            val topZ4 = floats[ThresholdKeys.TOP_Z4] ?: Constants.DEFAULT_TOP_Z4
            floats[ThresholdKeys.VO2MAX] =
                if (Constants.DEFAULT_VO2MAX > topZ4) Constants.DEFAULT_VO2MAX
                else topZ4 * Constants.DEFAULT_VO2MAX / Constants.DEFAULT_TOP_Z4
        }
        // Old threshold keys always go: moved above on a real upgrade, and on an install
        // already on the new names a stale one must not linger beside them.
        removals += OLD_THRESHOLD_KEYS.filter { it in stored }

        if (floats.isEmpty() && removals.isEmpty()) return null
        return PrefsEdit(floats, removals)
    }

    /** The configured thresholds in [stored], defaults for any key that is missing. */
    fun read(stored: Map<String, *>): ZoneThresholds {
        fun get(key: String, default: Float) = ((stored[key] as? Number)?.toFloat() ?: default).toDouble()
        return ZoneThresholds(
            endurance = get(ThresholdKeys.ENDURANCE, Constants.DEFAULT_ENDURANCE),
            vt1 = get(ThresholdKeys.VT1, Constants.DEFAULT_VT1),
            vt2 = get(ThresholdKeys.VT2, Constants.DEFAULT_VT2),
            topZ4 = get(ThresholdKeys.TOP_Z4, Constants.DEFAULT_TOP_Z4),
            vo2max = get(ThresholdKeys.VO2MAX, Constants.DEFAULT_VO2MAX),
        )
    }
}

/** Android side of [ThresholdMigration]. */
object ThresholdPrefs {
    const val PREFS = "tymewear_prefs"

    /**
     * Applies [ThresholdMigration] to the shared prefs if it has anything to do. Cheap and
     * idempotent, so every entry point that reads thresholds calls it first — the settings
     * screen can cold-start in a process where the extension never ran.
     *
     * Written with `commit()`: a one-off, small edit, and it is simplest to reason about
     * when it has fully landed before the caller goes on to read.
     */
    fun ensureMigrated(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val edit = ThresholdMigration.migrate(prefs.all) ?: return
            val e = prefs.edit()
            for (k in edit.removals) e.remove(k)
            for ((k, v) in edit.floats) e.putFloat(k, v)
            e.commit()
        }
    }
}
