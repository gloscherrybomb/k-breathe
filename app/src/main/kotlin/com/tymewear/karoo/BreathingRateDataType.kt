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
import kotlin.math.roundToInt

/**
 * Graphical data type showing breathing rate (breaths/min) from the VitalPro sensor.
 */
class BreathingRateDataType(extension: String) : DataTypeImpl(extension, "br") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)

        val job = scope.launch {
            TymewearData.breathRate.collect { br ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to br),
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

        val job = scope.launch {
            TymewearData.breathRate.collect { br ->
                val remoteViews = RemoteViews(context.packageName, R.layout.view_breathing_rate)

                val displayValue = if (br > 0) br.roundToInt().toString() else "--"
                remoteViews.setTextViewText(R.id.text_br_value, displayValue)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }
}
