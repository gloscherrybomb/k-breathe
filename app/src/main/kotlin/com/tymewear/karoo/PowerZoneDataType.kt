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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * 3-second power with a strip in the colour of the rider's *current VE zone* — for riders
 * whose main screen shows power, not VE. The zone is what the lungs say right now, via the
 * same single classification path as every other field (so it is scale-corrected when the
 * Beta is on). It never maps power to a zone.
 */
@OptIn(FlowPreview::class)
class PowerZoneDataType(extension: String) : DataTypeImpl(extension, "power_vz") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val avg = PowerAverage()
        scope.launch {
            TymewearData.powerW.collect { w ->
                val mean = avg.add(w)
                if (mean == null) emitter.onNext(StreamState.NotAvailable)
                else emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to mean))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val avg = PowerAverage()
        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f
        scope.launch {
            combine(TymewearData.powerW, TymewearData.veZone) { w, z -> w to z }.sample(1000L).collect { (w, zone) ->
                val mean = avg.add(w)
                val views = RemoteViews(context.packageName, R.layout.view_power_zone)
                views.setTextViewText(R.id.text_value, if (mean == null) "--" else String.format("%.0f", mean))
                views.setFloat(R.id.text_value, "setTextSize", valueSize)
                views.setFloat(R.id.text_unit, "setTextSize", unitSize)
                // Grey when breathing is stale: a dropped strap must never leave a stale colour on screen.
                val colour = if (TymewearData.isDataFresh() && zone in 1..5) Constants.ZONE_COLORS_SOLID[zone - 1] else Constants.NO_DATA_COLOR
                views.setInt(R.id.zone_bar, "setBackgroundColor", colour)
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
