package com.tymewear.karoo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import kotlin.math.roundToInt

/**
 * MI Percentage display — large percentage text with color-coded background.
 */
class MobilizationIndexDataType(extension: String) : DataTypeImpl(extension, "mi") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            TymewearData.mobilizationIndex.collect { mi ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to mi),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            combine(
                TymewearData.mobilizationIndex,
                TymewearData.heartRate,
                TymewearData.breathRate,
            ) { mi, hr, br -> Triple(mi, hr, br) }.collect { (mi, hr, br) ->
                val hasHr = hr > 0.0
                val hasBr = br > 0.0
                val hrr = TymewearData.percentHrr.value
                val rv = RemoteViews(context.packageName, R.layout.view_mi_percentage)

                val displayValue: String
                val bgColor: Int
                when {
                    !hasBr -> {
                        displayValue = "--"
                        bgColor = Color.parseColor("#424242")
                    }
                    !hasHr -> {
                        displayValue = "no HR"
                        bgColor = Color.parseColor("#424242")
                    }
                    hrr < 10.0 -> {
                        // Low intensity — MI not meaningful
                        displayValue = "idle"
                        bgColor = Color.parseColor("#424242")
                    }
                    else -> {
                        val rounded = mi.roundToInt()
                        displayValue = if (rounded > 100) ">100%" else "$rounded%"
                        bgColor = miColor(mi)
                    }
                }
                rv.setTextViewText(R.id.text_mi_value, displayValue)
                rv.setInt(R.id.mi_container, "setBackgroundColor", bgColor)

                emitter.updateView(rv)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        fun miColor(mi: Double): Int = when {
            mi < 50 -> Color.parseColor("#2E7D32")   // Green
            mi < 75 -> Color.parseColor("#F57F17")    // Amber
            mi < 90 -> Color.parseColor("#EF6C00")    // Orange
            else -> Color.parseColor("#C62828")        // Red
        }
    }
}

/**
 * MI Battery display — battery gauge showing remaining breathing reserve.
 * Uses Canvas→Bitmap→ImageView pattern (same as VeGraphDataType).
 */
class MiBatteryDataType(extension: String) : DataTypeImpl(extension, "mi_bat") {

    companion object {
        private const val BMP_W = 300
        private const val BMP_H = 150

        fun renderBattery(mi: Double, hasData: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(BMP_W, BMP_H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)

            // Battery body outline
            val outlinePaint = Paint().apply {
                color = Color.argb(200, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }
            canvas.drawRoundRect(RectF(10f, 10f, 260f, 140f), 12f, 12f, outlinePaint)

            // Nub terminal on right
            val nubPaint = Paint().apply {
                color = Color.argb(200, 160, 160, 160)
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawRoundRect(RectF(260f, 45f, 280f, 105f), 6f, 6f, nubPaint)

            if (hasData) {
                val remaining = (100.0 - mi).coerceIn(0.0, 100.0)
                val innerWidth = 240f // 260 - 10 - 10, with 5px padding each side
                val fillWidth = (remaining / 100.0 * innerWidth).toFloat()

                if (fillWidth > 0f) {
                    val fillPaint = Paint().apply {
                        color = MobilizationIndexDataType.miColor(mi)
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(
                        RectF(15f, 15f, 15f + fillWidth, 135f),
                        8f, 8f, fillPaint,
                    )
                }

                // MI >= 100: red tint on empty battery interior
                if (mi >= 100.0) {
                    val tintPaint = Paint().apply {
                        color = Color.argb(40, 198, 40, 40)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(RectF(15f, 15f, 255f, 135f), 8f, 8f, tintPaint)
                }
            } else {
                // No data: subtle dark interior
                val emptyPaint = Paint().apply {
                    color = Color.argb(25, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(15f, 15f, 255f, 135f), 8f, 8f, emptyPaint)
            }

            return bitmap
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            TymewearData.mobilizationIndex.collect { mi ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to mi),
                        ),
                    ),
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            combine(
                TymewearData.mobilizationIndex,
                TymewearData.heartRate,
                TymewearData.breathRate,
            ) { mi, hr, br -> Triple(mi, hr, br) }.collect { (mi, hr, br) ->
                val hasHr = hr > 0.0
                val hasBr = br > 0.0
                val hrr = TymewearData.percentHrr.value
                val rv = RemoteViews(context.packageName, R.layout.view_mi_battery)

                val displayValue: String
                val hasData: Boolean
                when {
                    !hasBr -> {
                        displayValue = "--"
                        hasData = false
                    }
                    !hasHr -> {
                        displayValue = "no HR"
                        hasData = false
                    }
                    hrr < 10.0 -> {
                        displayValue = "idle"
                        hasData = false
                    }
                    else -> {
                        val remaining = (100.0 - mi).coerceIn(0.0, 100.0).roundToInt()
                        displayValue = "$remaining%"
                        hasData = true
                    }
                }

                val bitmap = renderBattery(mi, hasData)
                rv.setImageViewBitmap(R.id.mi_battery_image, bitmap)
                rv.setTextViewText(R.id.text_mi_battery_value, displayValue)

                emitter.updateView(rv)
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
