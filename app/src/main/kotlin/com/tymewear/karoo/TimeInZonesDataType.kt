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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimeInZonesDataType(extension: String) : DataTypeImpl(extension, "ve_zones") {

    companion object {
        private const val PADDING = 4f
        private const val NUM_ZONES = 5
        private const val MIN_BAR_HEIGHT = 4f
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope.launch {
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
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val bitmap = Bitmap.createBitmap(Constants.ZONES_BITMAP_WIDTH, Constants.ZONES_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        scope.launch {
            // Poll at 1Hz — use shared zone times from recording if active,
            // otherwise track locally from live VE zone
            val localZones = longArrayOf(0, 0, 0, 0, 0)
            var localTotal = 0L

            while (true) {
                val shared = TymewearData.zoneTimes.value

                // Use shared zone times if recording is active (total > 0)
                val zt: ZoneTimes
                if (shared.total > 0) {
                    zt = shared
                } else {
                    // Track from live VE zone when not recording
                    val zone = TymewearData.veZone.value
                    if (zone in 1..5) {
                        localZones[zone - 1]++
                        localTotal++
                    }
                    zt = ZoneTimes(
                        z1 = localZones[0], z2 = localZones[1], z3 = localZones[2],
                        z4 = localZones[3], z5 = localZones[4], total = localTotal,
                    )
                }

                renderBars(bitmap, zt)
                val remoteViews = RemoteViews(context.packageName, R.layout.view_time_in_zones)
                remoteViews.setImageViewBitmap(R.id.zones_image, bitmap)
                emitter.updateView(remoteViews)
                delay(1000)
            }
        }
        emitter.setCancellable {
            scope.cancel()
            bitmap.recycle()
        }
    }

    private fun renderBars(bitmap: Bitmap, zoneTimes: ZoneTimes) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val usableWidth = bitmap.width - 2 * PADDING
        val usableHeight = bitmap.height - 2 * PADDING
        val barWidth = usableWidth / NUM_ZONES
        val total = zoneTimes.total

        val paint = Paint().apply { style = Paint.Style.FILL }

        for (zone in 1..NUM_ZONES) {
            val time = zoneTimes[zone]
            val scaled = if (total > 0) (time.toFloat() / total) * usableHeight else 0f
            val barHeight = maxOf(scaled, MIN_BAR_HEIGHT)
            val x = PADDING + (zone - 1) * barWidth
            val top = bitmap.height - PADDING - barHeight
            val bottom = bitmap.height - PADDING

            paint.color = Constants.zoneStyle(zone).second
            canvas.drawRect(x, top, x + barWidth, bottom, paint)
        }
    }
}
