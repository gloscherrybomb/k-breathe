package com.tymewear.karoo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * Graphical data type that plots VE over time as a rolling line graph
 * with ventilation zone colors as horizontal background bands.
 * Tap to cycle smoothing window: 15s / 30s / 60s.
 */
class VeGraphDataType(extension: String) : DataTypeImpl(extension, "ve_graph") {

    companion object {
        private const val MAX_POINTS = 90  // ~3-5 min of data at 1 breath every 2-4s
        private const val GRAPH_WIDTH = 400
        private const val GRAPH_HEIGHT = 200

        // Zone background colors (semi-transparent)
        private val ZONE_COLORS = intArrayOf(
            Color.argb(60, 84, 110, 122),   // Z1 Blue Grey
            Color.argb(60, 2, 119, 189),     // Z2 Blue
            Color.argb(60, 46, 125, 50),     // Z3 Green
            Color.argb(60, 245, 127, 23),    // Z4 Amber
            Color.argb(60, 198, 40, 40),     // Z5 Red
        )

        private val LINE_COLOR = Color.WHITE
        private val GRID_COLOR = Color.argb(40, 255, 255, 255)
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            TymewearData.minuteVolume.collect { ve ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to ve),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)
        val rawHistory = LinkedList<Double>()

        val tapIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, GraphSmoothingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val job = scope.launch {
            combine(
                TymewearData.minuteVolume,
                GraphSmoothingState.mode,
            ) { ve, mode -> ve to mode }.collect { (ve, mode) ->
                synchronized(rawHistory) {
                    rawHistory.addLast(ve)
                    while (rawHistory.size > MAX_POINTS) {
                        rawHistory.removeFirst()
                    }
                }

                val raw = synchronized(rawHistory) { rawHistory.toList() }
                // Apply rolling average smoothing
                val smoothed = smooth(raw, mode.windowSize)
                val bitmap = renderGraph(smoothed)

                val remoteViews = RemoteViews(context.packageName, R.layout.view_ve_graph)
                remoteViews.setImageViewBitmap(R.id.ve_graph_image, bitmap)

                // Current value overlay with smoothing mode
                val displayValue = if (ve > 0) String.format("%.1f %s", ve, mode.label) else "--"
                remoteViews.setTextViewText(R.id.ve_graph_value, displayValue)

                remoteViews.setOnClickPendingIntent(R.id.ve_graph_container, tapIntent)

                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable { job.cancel() }
    }

    /**
     * Apply a rolling average to the data series.
     * Each output point is the average of up to windowSize preceding raw points.
     */
    private fun smooth(data: List<Double>, windowSize: Int): List<Double> {
        if (windowSize <= 1) return data
        return data.indices.map { i ->
            val start = maxOf(0, i - windowSize + 1)
            val window = data.subList(start, i + 1)
            window.sum() / window.size
        }
    }

    private fun renderGraph(points: List<Double>): Bitmap {
        val w = GRAPH_WIDTH
        val h = GRAPH_HEIGHT
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.BLACK)

        val endurance = TymewearData.enduranceThreshold
        val vt1 = TymewearData.vt1Threshold
        val vt2 = TymewearData.vt2Threshold
        val vo2max = TymewearData.vo2maxThreshold

        // Auto-scale Y axis
        val maxData = if (points.isNotEmpty()) points.max() else 0.0
        val yMax = maxOf(vo2max * 1.2, maxData * 1.1, 50.0)

        // Draw zone background bands
        val zonePaint = Paint()
        val thresholds = doubleArrayOf(0.0, endurance, vt1, vt2, vo2max, yMax)
        for (i in 0 until 5) {
            val yBottom = h - (thresholds[i] / yMax * h).toFloat()
            val yTop = h - (thresholds[i + 1] / yMax * h).toFloat()
            zonePaint.color = ZONE_COLORS[i]
            canvas.drawRect(0f, yTop.coerceAtLeast(0f), w.toFloat(), yBottom.coerceAtMost(h.toFloat()), zonePaint)
        }

        // Draw threshold lines
        val linePaint = Paint().apply {
            color = GRID_COLOR
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        for (threshold in doubleArrayOf(endurance, vt1, vt2, vo2max)) {
            val y = h - (threshold / yMax * h).toFloat()
            if (y in 0f..h.toFloat()) {
                canvas.drawLine(0f, y, w.toFloat(), y, linePaint)
            }
        }

        // Draw VE line
        if (points.size >= 2) {
            val pathPaint = Paint().apply {
                color = LINE_COLOR
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

            val path = Path()
            val xStep = w.toFloat() / (MAX_POINTS - 1)
            val startX = w.toFloat() - (points.size - 1) * xStep

            for (i in points.indices) {
                val x = startX + i * xStep
                val y = h - (points[i] / yMax * h).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, pathPaint)

            // Dot on current point
            val dotPaint = Paint().apply {
                color = LINE_COLOR
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val lastX = startX + (points.size - 1) * xStep
            val lastY = h - (points.last() / yMax * h).toFloat()
            canvas.drawCircle(lastX, lastY, 4f, dotPaint)
        }

        return bitmap
    }
}
