package com.tymewear.karoo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import timber.log.Timber

/**
 * Plain foreground service that pins the app process in memory while a BLE
 * connection is active. Started by `TymewearExtension` when the first device
 * connects, stopped when the last one disconnects.
 *
 * Pattern modeled on `timklge/karoo-powerbar`: a separate Service (not the
 * `KarooExtension` itself), `IMPORTANCE_MIN` channel, `CATEGORY_SERVICE`.
 */
class BleForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("BleForegroundService starting")
        ensureChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VitalPro connected")
            .setContentText("Tymewear breathing sensor is active")
            .setSmallIcon(R.drawable.ic_breathing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Android 14+ enforces foregroundServiceType declarations.
            // Ecosystem precedent (timklge/karoo-powerbar) omits the type, so we
            // match. If a future Karoo firmware targets Android 14+ and rejects
            // this, log and continue without foreground promotion — BLE may drop
            // on ride-start as before, but the app stays alive.
            Timber.w(e, "startForeground rejected — running without foreground promotion")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("BleForegroundService stopping")
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tymewear_ble"
        private const val NOTIFICATION_ID = 1001

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "Tymewear BLE",
                        NotificationManager.IMPORTANCE_MIN,
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                    mgr.createNotificationChannel(ch)
                }
            }
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BleForegroundService::class.java))
        }
    }
}
