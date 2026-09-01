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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The power at which the rider would cross VT1 today, given how far today's ventilation
 * sits from their baseline. This is what a rider actually paces by — the deviation
 * percentage is informative, a watts number is actionable.
 */
class ThresholdPowerDataType(extension: String) : DataTypeImpl(extension, "threshold_power") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.vt1PowerW.collect { w ->
                // A numeric stream has no way to express absence except NotAvailable —
                // emitting 0.0 as a placeholder would be indistinguishable from a real
                // "0 W" reading downstream.
                if (!VentilatoryState.isEnabled() || w == null) {
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to w),
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

        scope.launch {
            combine(
                VentilatoryState.vt1PowerW,
                VentilatoryState.vt1ThresholdReason,
            ) { w, reason -> w to reason }.collect { (w, reason) ->
                val views = RemoteViews(context.packageName, R.layout.view_threshold_power)
                // "cal" (baseline not confident yet) and "n/a" (baseline is confident
                // but VT1 falls outside the loads it covers) are different situations
                // calling for different rider action — see ThresholdReason.
                val outOfRange = w == null && reason == ThresholdReason.OUT_OF_RANGE
                views.setTextViewText(
                    R.id.text_value,
                    when {
                        !VentilatoryState.isEnabled() -> "off"
                        w != null -> String.format("%.0f", w)
                        outOfRange -> "n/a"
                        else -> "cal"
                    },
                )
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                views.setTextViewText(
                    R.id.text_unit,
                    when {
                        w != null -> "W VT1 today"
                        outOfRange -> "VT1 outside learned range"
                        else -> "calibrating"
                    },
                )
                views.setInt(
                    R.id.container,
                    "setBackgroundColor",
                    if (w == null) Constants.NO_DATA_COLOR else Constants.ZONE_COLORS_SOLID[1],
                )
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
