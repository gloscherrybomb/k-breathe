package com.tymewear.karoo

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Numeric data type showing tidal volume (volume per breath) from the VitalPro sensor.
 */
class TidalVolumeDataType(extension: String) : DataTypeImpl(extension, "tv") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)

        val job = scope.launch {
            TymewearData.tidalVolume.collect { tv ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf("tv" to tv),
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
