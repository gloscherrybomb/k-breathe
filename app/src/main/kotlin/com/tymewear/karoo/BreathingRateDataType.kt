package com.tymewear.karoo

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Numeric data type showing breathing rate (breaths/min) from the VitalPro sensor.
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
                            values = mapOf("br" to br),
                        ),
                    ),
                )
            }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }
}
