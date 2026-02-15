package com.tymewear.karoo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.LinkedList

/**
 * Graphical data type showing minute ventilation (VE) with rolling average
 * and color-coded background based on ventilation zones.
 * Tap to cycle smoothing: Instant -> 15s -> 30s.
 */
class VentilationDataType(extension: String) : DataTypeImpl(extension, "ve") {

    // 30-second rolling buffer for startStream (Karoo numeric system always gets 30s avg)
    private val streamBuffer = LinkedList<Double>()
    private val streamBufferSize = 30

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)

        val job = scope.launch {
            TymewearData.minuteVolume.collect { ve ->
                synchronized(streamBuffer) {
                    streamBuffer.addLast(ve)
                    while (streamBuffer.size > streamBufferSize) {
                        streamBuffer.removeFirst()
                    }
                }

                val avg = synchronized(streamBuffer) {
                    if (streamBuffer.isEmpty()) 0.0
                    else streamBuffer.sum() / streamBuffer.size
                }

                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to avg),
                        ),
                    ),
                )
            }
        }

        emitter.setCancellable {
            job.cancel()
            synchronized(streamBuffer) { streamBuffer.clear() }
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)
        val viewBuffer = LinkedList<Double>()

        val tapIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SmoothingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        val job = scope.launch {
            combine(
                TymewearData.minuteVolume,
                SmoothingState.mode,
            ) { ve, mode -> ve to mode }.collect { (ve, mode) ->
                // Maintain buffer up to current mode's window size
                synchronized(viewBuffer) {
                    viewBuffer.addLast(ve)
                    while (viewBuffer.size > mode.windowSize) {
                        viewBuffer.removeFirst()
                    }
                }

                val avg = synchronized(viewBuffer) {
                    if (viewBuffer.isEmpty()) 0.0
                    else viewBuffer.sum() / viewBuffer.size
                }
                val zone = TymewearData.veZone.value

                val remoteViews = RemoteViews(context.packageName, R.layout.view_ventilation)

                // Set VE value text
                val displayValue = if (avg > 0) String.format("%.1f", avg) else "--"
                remoteViews.setTextViewText(R.id.text_value, displayValue)
                remoteViews.setFloat(R.id.text_value, "setTextSize", valueSize)
                remoteViews.setFloat(R.id.text_unit, "setTextSize", unitSize)

                // Set zone background color
                val (_, bgColor) = zoneStyle(zone)
                remoteViews.setInt(R.id.container, "setBackgroundColor", bgColor)

                // Unit label shows smoothing mode
                remoteViews.setTextViewText(R.id.text_unit, mode.label)

                // Tap to cycle smoothing
                remoteViews.setOnClickPendingIntent(R.id.container, tapIntent)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }

    companion object {
        // Tymewear 5-zone model
        fun zoneStyle(zone: Int): Pair<String, Int> = when (zone) {
            1 -> "Z1" to Color.parseColor("#546E7A")   // Blue Grey
            2 -> "Z2" to Color.parseColor("#0277BD")   // Blue
            3 -> "Z3" to Color.parseColor("#2E7D32")   // Green
            4 -> "Z4" to Color.parseColor("#F57F17")   // Amber
            5 -> "Z5" to Color.parseColor("#C62828")   // Red
            else -> "" to Color.parseColor("#424242")   // Dark Grey
        }
    }
}
