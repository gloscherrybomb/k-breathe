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
 * Today's ventilatory efficiency against the rider's own baseline. Negative means less
 * ventilation for the same power — fresher, fitter, a good day.
 *
 * Shows "cal" while the baseline is still being learned. An honest empty state matters
 * more than a number here: a plausible-looking deviation from a thin baseline is exactly
 * the kind of confident fiction this feature must avoid.
 */
class VentilatoryStateDataType(extension: String) : DataTypeImpl(extension, "vent_state") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
            VentilatoryState.deviation.collect { dev ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to (dev?.percent ?: 0.0)),
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
            VentilatoryState.deviation.collect { dev ->
                val views = RemoteViews(context.packageName, R.layout.view_vent_state)
                val text = when {
                    !VentilatoryState.isEnabled() -> "off"
                    dev == null -> "cal"
                    else -> String.format("%+.0f%%", dev.percent)
                }
                views.setTextViewText(R.id.text_value, text)
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                views.setTextViewText(R.id.text_unit, if (dev == null) "calibrating" else "vs base")
                // Lower ventilation for the same work is the good direction.
                val colour = when {
                    dev == null -> Constants.NO_DATA_COLOR
                    dev.percent <= -5.0 -> Constants.ZONE_COLORS_SOLID[0]
                    dev.percent >= 5.0 -> Constants.ZONE_COLORS_SOLID[3]
                    else -> Constants.ZONE_COLORS_SOLID[1]
                }
                views.setInt(R.id.container, "setBackgroundColor", colour)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
