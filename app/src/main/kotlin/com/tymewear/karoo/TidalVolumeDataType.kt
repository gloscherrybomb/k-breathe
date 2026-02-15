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
import kotlinx.coroutines.launch

/**
 * Graphical data type showing tidal volume (volume per breath) from the VitalPro sensor.
 * Stream emits mL for Karoo numeric display (300-1500 range).
 * Graphical view shows liters with 2 decimal places (e.g. "0.85 L").
 */
class TidalVolumeDataType(extension: String) : DataTypeImpl(extension, "tv") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)

        val job = scope.launch {
            TymewearData.tidalVolume.collect { tv ->
                // Emit in mL for Karoo numeric (integer) display
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to tv * 1000.0),
                        ),
                    ),
                )
            }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        val job = scope.launch {
            TymewearData.tidalVolume.collect { tv ->
                val remoteViews = RemoteViews(context.packageName, R.layout.view_tidal_volume)

                val displayValue = if (tv > 0) String.format("%.2f", tv) else "--"
                remoteViews.setTextViewText(R.id.text_tv_value, displayValue)
                remoteViews.setFloat(R.id.text_tv_value, "setTextSize", valueSize)
                remoteViews.setFloat(R.id.text_tv_unit, "setTextSize", unitSize)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }
}
