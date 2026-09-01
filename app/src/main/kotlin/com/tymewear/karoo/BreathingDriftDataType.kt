package com.tymewear.karoo

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Breathing-rate drift against the effort's own early reference.
 *
 * Experienced Tymewear users pace long efforts by this: once breathing rate sits
 * 11-15% above where it started for the same work, the effort is no longer sustainable.
 * They compute it by hand from lap averages today; no head unit shows it live.
 */
class BreathingDriftDataType(extension: String) : DataTypeImpl(extension, "br_drift") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.driftPercent.collect { d ->
                // A numeric stream has no way to express absence except NotAvailable —
                // emitting 0.0 as a placeholder would be indistinguishable from a real
                // "0% drift" reading downstream.
                if (d == null) {
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to d),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f
        val prefs = context.getSharedPreferences("tymewear_prefs", Context.MODE_PRIVATE)
        val alertPct = prefs.getInt("drift_alert_pct", Constants.STATE_DEFAULT_DRIFT_ALERT_PCT)
        // A switch that is on-screen but changes nothing misrepresents itself to the
        // rider — it must have an observable effect, so the colour ramp itself is what
        // "Alert on breathing drift" turns on. Off, the field still shows the real
        // percentage; it just stops color-coding it as a warning.
        val alertEnabled = prefs.getBoolean("drift_alert_enabled", false)

        scope.launch {
            VentilatoryState.driftPercent.collect { d ->
                val views = RemoteViews(context.packageName, R.layout.view_breathing_drift)
                views.setTextViewText(
                    R.id.text_value,
                    if (d == null) "--" else String.format("%+.0f%%", d),
                )
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                views.setInt(
                    R.id.container,
                    "setBackgroundColor",
                    when {
                        d == null || !alertEnabled -> Constants.NO_DATA_COLOR
                        d >= alertPct -> Constants.ZONE_COLORS_SOLID[4]
                        d >= alertPct * 0.6 -> Constants.ZONE_COLORS_SOLID[3]
                        else -> Constants.ZONE_COLORS_SOLID[0]
                    },
                )
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
