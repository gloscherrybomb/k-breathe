package com.tymewear.karoo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

class TimeInZonesDataType(extension: String) : DataTypeImpl(extension, "ve_zones") {

    companion object {
        private const val BITMAP_WIDTH = 400
        private const val BITMAP_HEIGHT = 200
        private const val PADDING = 4f
        private const val NUM_ZONES = 5
        private const val MIN_BAR_HEIGHT = 4f
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            TymewearData.zoneTimes.collect { zt ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to zt.total.toDouble()),
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
            TymewearData.zoneTimes.collect { zt ->
                val bitmap = renderBars(zt)
                val remoteViews = RemoteViews(context.packageName, R.layout.view_time_in_zones)
                remoteViews.setImageViewBitmap(R.id.zones_image, bitmap)
                emitter.updateView(remoteViews)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun renderBars(zoneTimes: ZoneTimes): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val usableWidth = BITMAP_WIDTH - 2 * PADDING
        val usableHeight = BITMAP_HEIGHT - 2 * PADDING
        val barWidth = usableWidth / NUM_ZONES
        val total = zoneTimes.total

        val paint = Paint().apply { style = Paint.Style.FILL }

        for (zone in 1..NUM_ZONES) {
            val time = zoneTimes[zone]
            val scaled = if (total > 0) (time.toFloat() / total) * usableHeight else 0f
            val barHeight = maxOf(scaled, MIN_BAR_HEIGHT)
            val x = PADDING + (zone - 1) * barWidth
            val top = BITMAP_HEIGHT - PADDING - barHeight
            val bottom = BITMAP_HEIGHT - PADDING

            paint.color = VentilationDataType.zoneStyle(zone).second
            canvas.drawRect(x, top, x + barWidth, bottom, paint)
        }

        return bitmap
    }
}
