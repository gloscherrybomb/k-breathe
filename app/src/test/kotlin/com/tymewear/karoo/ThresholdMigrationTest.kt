package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdMigrationTest {

    /** The athlete's actual 0.6.x settings (2026-09-23). */
    private val athlete06 = mapOf<String, Any?>(
        "vt1_threshold" to 73f,
        "vt2_threshold" to 96f,
        "topz4_threshold" to 112f,
        "vo2max_threshold" to 130f,
        "sensor_id" to "1234",
    )

    @Test
    fun `old thresholds move one name down and VO2max gets the default`() {
        val edit = ThresholdMigration.migrate(athlete06)!!
        assertEquals(
            mapOf(
                ThresholdKeys.ENDURANCE to 73f,
                ThresholdKeys.VT1 to 96f,
                ThresholdKeys.VT2 to 112f,
                ThresholdKeys.TOP_Z4 to 130f,
                ThresholdKeys.VO2MAX to 180f,
            ),
            edit.floats,
        )
        assertEquals(setOf("vt1_threshold", "vt2_threshold", "topz4_threshold", "vo2max_threshold"), edit.removals)
    }

    @Test
    fun `applying the edit leaves no old key and reads back as Tymewear's profile`() {
        val after = applyTo(athlete06, ThresholdMigration.migrate(athlete06)!!)
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), ThresholdMigration.read(after))
        assertTrue(ThresholdMigration.OLD_THRESHOLD_KEYS.none { it in after })
        assertEquals("1234", after["sensor_id"])
    }

    @Test
    fun `runs once - nothing to do once any new key exists`() {
        val after = applyTo(athlete06, ThresholdMigration.migrate(athlete06)!!)
        assertNull(ThresholdMigration.migrate(after))
    }

    @Test
    fun `a stale old key beside the new ones is deleted, never moved`() {
        val after = applyTo(athlete06, ThresholdMigration.migrate(athlete06)!!)
        val stale = after + ("vt1_threshold" to 50f) + ("vt2_threshold" to 51f) +
            ("topz4_threshold" to 52f) + ("vo2max_threshold" to 53f)
        val edit = ThresholdMigration.migrate(stale)!!
        // The new keys win: nothing is written, the old keys just go.
        assertTrue(edit.floats.isEmpty())
        assertEquals(ThresholdMigration.OLD_THRESHOLD_KEYS.toSet(), edit.removals)
        assertEquals(after, applyTo(stale, edit))
    }

    @Test
    fun `a fresh install has nothing to migrate`() {
        assertNull(ThresholdMigration.migrate(emptyMap<String, Any?>()))
        assertNull(ThresholdMigration.migrate(mapOf("sensor_id" to "1234")))
    }

    @Test
    fun `a partial old set moves only what is there`() {
        // 0.6.x could write vt1/vt2 alone: applying a suggestion before ever saving settings.
        val edit = ThresholdMigration.migrate(mapOf("vt1_threshold" to 66f))!!
        assertEquals(mapOf(ThresholdKeys.ENDURANCE to 66f, ThresholdKeys.VO2MAX to 180f), edit.floats)
        val after = applyTo(mapOf("vt1_threshold" to 66f), edit)
        assertEquals(ZoneThresholds(66.0, 96.0, 112.0, 130.0, 180.0), ThresholdMigration.read(after))
    }

    @Test
    fun `VO2max stays above Top Z4 when the old top edge was already high`() {
        val edit = ThresholdMigration.migrate(athlete06 + ("vo2max_threshold" to 195f))!!
        val topZ4 = edit.floats.getValue(ThresholdKeys.TOP_Z4)
        val vo2max = edit.floats.getValue(ThresholdKeys.VO2MAX)
        assertEquals(195f, topZ4)
        assertEquals(195f * 180f / 130f, vo2max, 0.01f)
        assertTrue(vo2max > topZ4)
    }

    /** Every key the removed suggestion feature ever wrote: 0.6.x names, and the names
     *  this branch introduced before the feature was removed. */
    private val suggestionKeys06 = mapOf<String, Any?>(
        "threshold_auto_apply" to true,
        "threshold_change_history" to "VT1:73.0:66.0:100",
        "threshold_dismissed_vt1" to 66f,
        "threshold_dismissed_vt2" to 88f,
        "threshold_evidence_history" to "200.0:66.0::",
    )
    private val suggestionKeysBranch = mapOf<String, Any?>(
        "threshold_changes" to "ENDURANCE:73.0:66.0:100",
        "suggestion_dismissed_endurance" to 66f,
        "suggestion_dismissed_vt1" to 86f,
        "suggestion_dismissed_vt2" to 124f,
        "suggestion_dismissed_top_z4" to 1f,
        "suggestion_dismissed_vo2max" to 1f,
    )

    @Test
    fun `upgrading from 0_6 deletes every suggestion key and keeps the thresholds`() {
        val old = athlete06 + suggestionKeys06
        val after = applyTo(old, ThresholdMigration.migrate(old)!!)
        assertTrue(suggestionKeys06.keys.none { it in after })
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), ThresholdMigration.read(after))
        assertNull(ThresholdMigration.migrate(after))
    }

    @Test
    fun `an install already on the new names still loses its suggestion keys`() {
        val migrated = applyTo(athlete06, ThresholdMigration.migrate(athlete06)!!)
        val stale = migrated + suggestionKeysBranch + ("threshold_auto_apply" to false)
        val edit = ThresholdMigration.migrate(stale)!!
        // Thresholds are not touched a second time: only removals.
        assertTrue(edit.floats.isEmpty())
        val after = applyTo(stale, edit)
        assertEquals(migrated, after)
    }

    @Test
    fun `stale keys alone, with no thresholds ever saved, are removed too`() {
        val edit = ThresholdMigration.migrate(mapOf("threshold_evidence_history" to "x", "sensor_id" to "1"))!!
        assertEquals(setOf("threshold_evidence_history"), edit.removals)
        assertTrue(edit.floats.isEmpty())
    }

    @Test
    fun `other settings are left alone`() {
        val old = athlete06 + ("dynamic_state_enabled" to true) + ("baseline_bins" to "b") + ("max_hr" to 190f)
        val edit = ThresholdMigration.migrate(old)!!
        assertTrue(listOf("sensor_id", "dynamic_state_enabled", "baseline_bins", "max_hr").none { it in edit.removals })
    }

    @Test
    fun `read falls back to Tymewear-named defaults`() {
        assertEquals(ZoneThresholds(73.0, 96.0, 112.0, 130.0, 180.0), ThresholdMigration.read(emptyMap<String, Any?>()))
    }

    private fun applyTo(stored: Map<String, Any?>, edit: PrefsEdit): Map<String, Any?> =
        (stored - edit.removals) + edit.floats
}
