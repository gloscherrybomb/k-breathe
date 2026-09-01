package com.tymewear.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

class TymewearExtension : KarooExtension("tymewear", BuildConfig.VERSION_NAME) {

    lateinit var karooSystem: KarooSystemService
    private lateinit var bleManager: BleManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private val fgHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingStopRunnable = java.util.concurrent.atomic.AtomicReference<Runnable?>(null)

    override val types by lazy {
        listOf(
            VentilationDataType(extension),
            VeGraphDataType(extension),
            BreathingRateDataType(extension),
            TidalVolumeDataType(extension),
            MobilizationIndexDataType(extension),
            MiBatteryDataType(extension),
            TimeInZonesDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.d("TymewearExtension created")

        karooSystem = KarooSystemService(applicationContext)
        bleManager = BleManager(applicationContext)

        // Load zone thresholds before BLE data arrives
        TymewearData.loadThresholds(applicationContext)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("Connected to Karoo system")
                karooSystem.dispatch(RequestBluetooth(extension))

                // Subscribe to heart rate for Mobilization Index
                // Use raw HR and compute %HRR ourselves (PERCENT_HRR may only work during rides)
                scope.launch {
                    karooSystem.streamDataFlow(DataType.Type.HEART_RATE).collect { state ->
                        when (state) {
                            is StreamState.Streaming -> {
                                val hr = state.dataPoint.singleValue ?: return@collect
                                if (BuildConfig.DEBUG) Timber.d("HR stream: %.0f bpm", hr)
                                TymewearData.updateHr(hr)
                            }
                            else -> {
                                Timber.d("HR stream state: $state")
                            }
                        }
                    }
                }

                // Second RideState consumer (first is in startFit). Intentional: this
                // one is for BT-arbitration defense; the other writes FIT records.
                // Dedupe by class so we only react to actual state transitions, not
                // payload changes within a single state (e.g. Recording's timestamp).
                scope.launch {
                    karooSystem.consumerFlow<RideState>()
                        .distinctUntilChangedBy { it::class }
                        .collect { state ->
                            Timber.d("RideState transition: $state")
                            // The recorded-data freeze consistently begins within ~2
                            // minutes of recording starting, so capture BLE/data state
                            // at each transition to localise the cause.
                            BleDiagnostics.logRideTransition(state::class.simpleName ?: "?")
                            if (state is RideState.Recording) {
                                Timber.d("Recording started — re-dispatching RequestBluetooth")
                                karooSystem.dispatch(RequestBluetooth(extension))
                            }
                        }
                }
            }
        }
    }

    override fun startScan(emitter: Emitter<Device>) {
        Timber.d("Starting VitalPro scan")

        // Read configured sensor ID from preferences
        val prefs = applicationContext.getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
        val sensorId = prefs.getString("sensor_id", null)?.takeIf { it.isNotBlank() }

        val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scanScope.launch {
            bleManager.scan(sensorId).collect { scannedDevice ->
                val device = TymewearDevice(
                    extension = extension,
                    uid = scannedDevice.address,
                    displayName = scannedDevice.name,
                    bleManager = bleManager,
                )
                Timber.d("Emitting discovered device: ${scannedDevice.name}")
                emitter.onNext(device.source)
            }
        }

        emitter.setCancellable {
            Timber.d("Scan cancelled")
            scanScope.cancel()
        }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connecting to device: $uid")

        if (activeConnections.incrementAndGet() == 1) {
            // Cancel any pending stop from a recent disconnect — we're alive again.
            pendingStopRunnable.getAndSet(null)?.let { fgHandler.removeCallbacks(it) }
            Timber.d("First active connection — starting foreground service")
            BleForegroundService.start(applicationContext)
        }

        val wrapped = object : Emitter<DeviceEvent> {
            override fun onNext(event: DeviceEvent) = emitter.onNext(event)
            override fun onError(err: Throwable) = emitter.onError(err)
            override fun onComplete() = emitter.onComplete()
            override fun cancel() = emitter.cancel()
            override fun setCancellable(cancel: () -> Unit) {
                emitter.setCancellable {
                    Timber.d("Wrapped emitter cancelled for $uid")
                    try { cancel() } finally {
                        if (activeConnections.decrementAndGet() == 0) {
                            Timber.d("Last active connection closed — scheduling debounced stop")
                            scheduleForegroundStop()
                        }
                    }
                }
            }
        }

        val device = TymewearDevice(
            extension = extension,
            uid = uid,
            displayName = "VitalPro",
            bleManager = bleManager,
        )
        device.connect(wrapped)
    }

    private fun scheduleForegroundStop() {
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (activeConnections.get() == 0) {
                Timber.d("Debounced stop firing — stopping foreground service")
                BleForegroundService.stop(applicationContext)
            } else {
                Timber.d("Debounced stop fired but counter non-zero — skipping")
            }
            pendingStopRunnable.compareAndSet(runnable, null)
        }
        // Replace any previously-queued stop with the new one.
        pendingStopRunnable.getAndSet(runnable)?.let { fgHandler.removeCallbacks(it) }
        fgHandler.postDelayed(runnable, DEBOUNCE_STOP_MS)
    }

    companion object {
        private const val DEBOUNCE_STOP_MS = 1500L
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        Timber.d("startFit called")
        TymewearData.resetZoneTimes()

        // Use ELAPSED_TIME stream (ticks ~1Hz) combined with RideState
        // so we emit a FIT record every second while recording.
        // RideState alone only fires on state transitions.
        val fitScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        fitScope.launch {
            karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
                .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
                .combine(karooSystem.consumerFlow<RideState>()) { _, rideState -> rideState }
                .collect { rideState ->
                    when (rideState) {
                        is RideState.Recording -> {
                            // Never record a stale reading. Breathing packets can stop
                            // arriving while GATT still reports "connected"; writing the
                            // last-known value every second produced FIT files with
                            // hours of frozen VE that looked like real data downstream.
                            // Omitting the fields leaves an honest gap instead.
                            if (!TymewearData.isDataFresh()) {
                                BleDiagnostics.onStaleRecordSkipped()
                                return@collect
                            }
                            val br = TymewearData.smoothBreathRate.value
                            val tv = TymewearData.smoothTidalVolume.value
                            val ve = TymewearData.smoothMinuteVolume.value
                            val ie = TymewearData.ieRatio.value
                            val mi = TymewearData.mobilizationIndex.value
                            val brr = TymewearData.percentBrr.value
                            val zone = TymewearData.zoneFor(ve)

                            if (BuildConfig.DEBUG) Timber.d("FIT record: BR=%.1f TV=%.3f VE=%.1f zone=%d MI=%.1f", br, tv, ve, zone, mi)

                            // Track zone time (shared state for live display + FIT)
                            TymewearData.incrementZoneTime(zone)

                            // Write per-second record fields
                            emitter.onNext(
                                WriteToRecordMesg(
                                    listOf(
                                        FieldValue(Protocol.FIT_FIELD_BREATH_RATE, br),
                                        FieldValue(Protocol.FIT_FIELD_TIDAL_VOLUME, tv),
                                        FieldValue(Protocol.FIT_FIELD_MINUTE_VOLUME, ve),
                                        FieldValue(Protocol.FIT_FIELD_IE_RATIO, ie),
                                        FieldValue(Protocol.FIT_FIELD_VE_ZONE, zone.toDouble()),
                                        FieldValue(Protocol.FIT_FIELD_MOBILIZATION_INDEX, mi),
                                        FieldValue(Protocol.FIT_FIELD_PERCENT_BRR, brr),
                                    ),
                                ),
                            )
                        }

                        is RideState.Paused -> {
                            writeSessionSummary(emitter)
                        }

                        is RideState.Idle -> {}
                    }
                }
        }

        emitter.setCancellable {
            // Write final session summary before stopping
            writeSessionSummary(emitter)
            fitScope.cancel()
        }
    }

    private fun writeSessionSummary(emitter: Emitter<FitEffect>) {
        val zt = TymewearData.zoneTimes.value
        if (zt.total == 0L) return

        emitter.onNext(
            WriteToSessionMesg(
                listOf(
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE1_TIME, zt.z1 / 60.0),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE1_PCT, zt.z1 * 100.0 / zt.total),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE2_TIME, zt.z2 / 60.0),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE2_PCT, zt.z2 * 100.0 / zt.total),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE3_TIME, zt.z3 / 60.0),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE3_PCT, zt.z3 * 100.0 / zt.total),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE4_TIME, zt.z4 / 60.0),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE4_PCT, zt.z4 * 100.0 / zt.total),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE5_TIME, zt.z5 / 60.0),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE5_PCT, zt.z5 * 100.0 / zt.total),
                ),
            ),
        )
    }

    override fun onDestroy() {
        Timber.d("TymewearExtension destroyed")
        scope.cancel()
        TymewearData.setDisconnected()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        // Flush any pending debounced stop before dying
        pendingStopRunnable.getAndSet(null)?.let { fgHandler.removeCallbacks(it) }
        if (activeConnections.get() == 0) {
            BleForegroundService.stop(applicationContext)
        }
        super.onDestroy()
    }
}
