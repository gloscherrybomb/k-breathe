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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

class TymewearExtension : KarooExtension("tymewear", BuildConfig.VERSION_NAME) {

    lateinit var karooSystem: KarooSystemService
    private lateinit var bleManager: BleManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)

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
            }
        }
    }

    override fun startScan(emitter: Emitter<Device>) {
        Timber.d("Starting VitalPro scan")

        // Read configured sensor ID from preferences
        val prefs = applicationContext.getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
        val sensorId = prefs.getString("sensor_id", null)

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

        val device = TymewearDevice(
            extension = extension,
            uid = uid,
            displayName = "VitalPro",
            bleManager = bleManager,
        )
        device.connect(emitter)
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        Timber.d("startFit called")
        TymewearData.resetZoneTimes()

        val prefs = applicationContext.getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
        val vt1 = prefs.getFloat("vt1_threshold", Constants.DEFAULT_VT1).toDouble()
        val vt2 = prefs.getFloat("vt2_threshold", Constants.DEFAULT_VT2).toDouble()
        val topZ4 = prefs.getFloat("topz4_threshold", Constants.DEFAULT_TOP_Z4).toDouble()
        val vo2max = prefs.getFloat("vo2max_threshold", Constants.DEFAULT_VO2MAX).toDouble()

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
                            val br = TymewearData.breathRate.value
                            val tv = TymewearData.tidalVolume.value
                            val ve = TymewearData.minuteVolume.value
                            val ie = TymewearData.ieRatio.value
                            val mi = TymewearData.mobilizationIndex.value
                            val brr = TymewearData.percentBrr.value
                            val zone = Protocol.veZone(ve, vt1, vt2, topZ4, vo2max)

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
        super.onDestroy()
    }
}
