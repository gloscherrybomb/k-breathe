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
 * Today's breathing against the rider's own normal for the same effort, once the strap's
 * volume scale has been removed (spec §3.1). Negative means less ventilation for the same
 * work — fresher, fitter, a good day.
 *
 * Shows "cal" while the baselines and the strap scale are still being learned. An honest
 * empty state matters more than a number here: a plausible-looking figure from a thin
 * baseline is exactly the kind of confident fiction this feature must avoid.
 */
class VentilatoryStateDataType(extension: String) : DataTypeImpl(extension, "vent_state") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.dayQuality.collect { dq ->
                // A numeric stream has no way to express absence except NotAvailable —
                // emitting 0.0 as a placeholder would be indistinguishable from a real
                // "exactly my normal" reading downstream.
                if (!VentilatoryState.isEnabled() || dq == null) {
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to dq),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)

        scope.launch {
            combine(
                VentilatoryState.dayQuality,
                VentilatoryState.scale,
                VentilatoryState.scaleStatus,
            ) { dq, sc, st -> Triple(dq, sc, st) }.collect { (dq, sc, st) ->
                val views = RemoteViews(context.packageName, R.layout.view_vent_state)
                val enabled = VentilatoryState.isEnabled()
                val value = when {
                    !enabled -> "off"
                    dq == null -> "cal"
                    else -> String.format("%+.0f%%", dq)
                }
                val unit = when {
                    !enabled -> ""
                    st is ScaleStatus.OutOfRange -> "scale n/a"
                    sc != null -> String.format("×%.2f", sc)
                    else -> "learning"
                }
                views.setTextViewText(R.id.text_value, value)
                views.setTextViewText(R.id.text_unit, unit)
                views.setFloat(R.id.text_value, "setTextSize", config.textSize * 0.6f)
                views.setFloat(R.id.text_unit, "setTextSize", config.textSize * 0.25f)
                // Lower ventilation for the same work is the good direction.
                val colour = when {
                    dq == null -> Constants.NO_DATA_COLOR
                    dq <= -5.0 -> Constants.ZONE_COLORS_SOLID[0]
                    dq >= 5.0 -> Constants.ZONE_COLORS_SOLID[2]
                    else -> Constants.ZONE_COLORS_SOLID[1]
                }
                views.setInt(R.id.container, "setBackgroundColor", colour)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
