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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class TymewearExtension : KarooExtension("tymewear", BuildConfig.VERSION_NAME) {

    lateinit var karooSystem: KarooSystemService
    private lateinit var bleManager: BleManager
    private val scope = CoroutineScope(Dispatchers.IO)
    private var hrrJob: Job? = null

    // Zone time tracking for session summary (5 zones)
    private var zone1Seconds = 0L
    private var zone2Seconds = 0L
    private var zone3Seconds = 0L
    private var zone4Seconds = 0L
    private var zone5Seconds = 0L
    private var totalRecordSeconds = 0L

    override val types by lazy {
        listOf(
            VentilationDataType(extension),
            VeGraphDataType(extension),
            BreathingRateDataType(extension),
            TidalVolumeDataType(extension),
            MobilizationIndexDataType(extension),
            MiBatteryDataType(extension),
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
                hrrJob = scope.launch {
                    karooSystem.streamDataFlow(DataType.Type.HEART_RATE).collect { state ->
                        when (state) {
                            is StreamState.Streaming -> {
                                val hr = state.dataPoint.singleValue ?: return@collect
                                Timber.d("HR stream: %.0f bpm", hr)
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

        val job = CoroutineScope(Dispatchers.IO).launch {
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
            job.cancel()
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
        zone1Seconds = 0L
        zone2Seconds = 0L
        zone3Seconds = 0L
        zone4Seconds = 0L
        zone5Seconds = 0L
        totalRecordSeconds = 0L

        val prefs = applicationContext.getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
        val endurance = prefs.getFloat("endurance_threshold", 69f).toDouble()
        val vt1 = prefs.getFloat("vt1_threshold", 83f).toDouble()
        val vt2 = prefs.getFloat("vt2_threshold", 111f).toDouble()
        val vo2max = prefs.getFloat("vo2max_threshold", 180f).toDouble()

        val job = CoroutineScope(Dispatchers.IO).launch {
            rideStateFlow().collect { rideState ->
                when (rideState) {
                    is RideState.Recording -> {
                        val br = TymewearData.breathRate.value
                        val tv = TymewearData.tidalVolume.value
                        val ve = TymewearData.minuteVolume.value
                        val ie = TymewearData.ieRatio.value
                        val mi = TymewearData.mobilizationIndex.value
                        val brr = TymewearData.percentBrr.value
                        val zone = Protocol.veZone(ve, endurance, vt1, vt2, vo2max)

                        // Track zone time
                        totalRecordSeconds++
                        when (zone) {
                            1 -> zone1Seconds++
                            2 -> zone2Seconds++
                            3 -> zone3Seconds++
                            4 -> zone4Seconds++
                            5 -> zone5Seconds++
                        }

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
            job.cancel()
        }
    }

    /**
     * Wrap KarooSystemService.addConsumer as a Flow for RideState events.
     */
    private fun rideStateFlow(): Flow<RideState> = callbackFlow {
        val consumerId = karooSystem.addConsumer<RideState>(
            onError = { error -> Timber.e("RideState consumer error: $error") },
            onComplete = { close() },
            onEvent = { rideState -> trySend(rideState) },
        )
        awaitClose {
            karooSystem.removeConsumer(consumerId)
        }
    }

    private fun writeSessionSummary(emitter: Emitter<FitEffect>) {
        if (totalRecordSeconds == 0L) return

        emitter.onNext(
            WriteToSessionMesg(
                listOf(
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE1_TIME, zone1Seconds / 60.0),
                    FieldValue(
                        Protocol.FIT_FIELD_VE_ZONE1_PCT,
                        zone1Seconds * 100.0 / totalRecordSeconds,
                    ),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE2_TIME, zone2Seconds / 60.0),
                    FieldValue(
                        Protocol.FIT_FIELD_VE_ZONE2_PCT,
                        zone2Seconds * 100.0 / totalRecordSeconds,
                    ),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE3_TIME, zone3Seconds / 60.0),
                    FieldValue(
                        Protocol.FIT_FIELD_VE_ZONE3_PCT,
                        zone3Seconds * 100.0 / totalRecordSeconds,
                    ),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE4_TIME, zone4Seconds / 60.0),
                    FieldValue(
                        Protocol.FIT_FIELD_VE_ZONE4_PCT,
                        zone4Seconds * 100.0 / totalRecordSeconds,
                    ),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE5_TIME, zone5Seconds / 60.0),
                    FieldValue(
                        Protocol.FIT_FIELD_VE_ZONE5_PCT,
                        zone5Seconds * 100.0 / totalRecordSeconds,
                    ),
                ),
            ),
        )
    }

    override fun onDestroy() {
        Timber.d("TymewearExtension destroyed")
        hrrJob?.cancel()
        TymewearData.setDisconnected()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }
}
