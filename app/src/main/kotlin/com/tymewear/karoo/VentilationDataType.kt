package com.tymewear.karoo

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.roundToInt

/**
 * Graphical data type showing minute ventilation (VE) with 30-second rolling average
 * and color-coded background based on ventilation zones.
 */
class VentilationDataType(extension: String) : DataTypeImpl(extension, "ve") {

    // 30-second rolling buffer for smoothing
    private val buffer = LinkedList<Double>()
    private val bufferSize = 30

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)

        val job = scope.launch {
            TymewearData.minuteVolume.collect { ve ->
                // Update rolling buffer
                synchronized(buffer) {
                    buffer.addLast(ve)
                    while (buffer.size > bufferSize) {
                        buffer.removeFirst()
                    }
                }

                val avg = synchronized(buffer) {
                    if (buffer.isEmpty()) 0.0
                    else buffer.sum() / buffer.size
                }

                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf("ve" to avg),
                        ),
                    ),
                )
            }
        }

        emitter.setCancellable {
            job.cancel()
            synchronized(buffer) { buffer.clear() }
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)

        val job = scope.launch {
            while (true) {
                val ve = synchronized(buffer) {
                    if (buffer.isEmpty()) 0.0
                    else buffer.sum() / buffer.size
                }
                val zone = TymewearData.veZone.value

                val remoteViews = RemoteViews(context.packageName, R.layout.view_ventilation)

                // Set VE value text
                val displayValue = if (ve > 0) String.format("%.1f", ve) else "--"
                remoteViews.setTextViewText(R.id.text_value, displayValue)

                // Set zone label and background color
                val (zoneName, bgColor) = zoneStyle(zone)
                remoteViews.setTextViewText(R.id.text_zone, zoneName)
                remoteViews.setInt(R.id.container, "setBackgroundColor", bgColor)

                // Scale text size based on view config
                val scaledSize = (config.textSize * 0.9f)
                remoteViews.setTextViewTextSize(
                    R.id.text_value,
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    scaledSize,
                )

                emitter.updateView(remoteViews)
                delay(1000) // ~1Hz update
            }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }

    companion object {
        // Zone 0 = no data (dark grey)
        // Zone 1 = Endurance (green)
        // Zone 2 = VT1-VT2 Threshold (yellow/orange)
        // Zone 3 = VO2max+ (red)
        fun zoneStyle(zone: Int): Pair<String, Int> = when (zone) {
            1 -> "Z1" to Color.parseColor("#2E7D32")   // Green
            2 -> "Z2" to Color.parseColor("#F57F17")   // Amber
            3 -> "Z3" to Color.parseColor("#C62828")   // Red
            else -> "" to Color.parseColor("#424242")   // Grey
        }
    }
}
