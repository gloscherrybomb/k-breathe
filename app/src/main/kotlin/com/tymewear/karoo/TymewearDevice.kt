package com.tymewear.karoo

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnManufacturerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Represents a connected VitalPro breathing sensor.
 * Handles BLE connection lifecycle and data parsing into Karoo DeviceEvents.
 */
class TymewearDevice(
    private val extension: String,
    private val uid: String,
    private val displayName: String,
    private val bleManager: BleManager,
) {
    val source: Device by lazy {
        Device(
            extension = extension,
            uid = uid,
            dataTypes = listOf(
                DataType.dataTypeId(extension, "ve"),
                DataType.dataTypeId(extension, "ve_graph"),
                DataType.dataTypeId(extension, "br"),
                DataType.dataTypeId(extension, "tv"),
                DataType.dataTypeId(extension, "mi"),
                DataType.dataTypeId(extension, "mi_bat"),
            ),
            displayName = displayName,
        )
    }

    private var scope: CoroutineScope? = null

    private fun emitDataPoints(emitter: Emitter<DeviceEvent>, data: Protocol.BreathingData) {
        emitter.onNext(
            OnDataPoint(
                DataPoint(
                    dataTypeId = DataType.dataTypeId(extension, "ve"),
                    values = mapOf(DataType.Field.SINGLE to data.minuteVolume),
                    sourceId = uid,
                ),
            ),
        )
        emitter.onNext(
            OnDataPoint(
                DataPoint(
                    dataTypeId = DataType.dataTypeId(extension, "br"),
                    values = mapOf(DataType.Field.SINGLE to data.breathRate),
                    sourceId = uid,
                ),
            ),
        )
        emitter.onNext(
            OnDataPoint(
                DataPoint(
                    dataTypeId = DataType.dataTypeId(extension, "tv"),
                    values = mapOf(DataType.Field.SINGLE to data.tidalVolume),
                    sourceId = uid,
                ),
            ),
        )
    }

    /**
     * Connect to the BLE device and start emitting DeviceEvents.
     */
    fun connect(emitter: Emitter<DeviceEvent>) {
        val connectScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope = connectScope

        connectScope.launch {
            emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))

            bleManager.connect(uid).collect { event ->
                when (event) {
                    is BleManager.ConnectionEvent.Connected -> {
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                        emitter.onNext(
                            OnManufacturerInfo(
                                ManufacturerInfo(
                                    manufacturer = "Tymewear",
                                    modelNumber = "VitalPro",
                                ),
                            ),
                        )
                    }

                    is BleManager.ConnectionEvent.Disconnected -> {
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                        TymewearData.setDisconnected()
                    }

                    is BleManager.ConnectionEvent.BatteryLevel -> {
                        val status = when {
                            event.percent > 60 -> BatteryStatus.GOOD
                            event.percent > 20 -> BatteryStatus.OK
                            event.percent > 5  -> BatteryStatus.LOW
                            else -> BatteryStatus.CRITICAL
                        }
                        emitter.onNext(OnBatteryStatus(status))
                        TymewearData.updateBattery(event.percent)
                    }

                    is BleManager.ConnectionEvent.Subscribed -> {
                        Timber.d("Breathing data notifications active")
                    }

                    is BleManager.ConnectionEvent.Data -> {
                        when (event.characteristicUuid) {
                            Protocol.COMMAND_CHAR_UUID -> {
                                // Primary data stream — all breathing data arrives here
                                val pktType = Protocol.packetType(event.bytes)
                                when (pktType) {
                                    Protocol.PKT_BREATH -> {
                                        val data = Protocol.parseNotification(event.bytes)
                                        if (data != null) {
                                            if (BuildConfig.DEBUG) {
                                                Timber.d(
                                                    "Breath: BR=%.1f bpm, TV_raw=%d (%.3f L), VE=%.1f L/min, " +
                                                        "IE=%.2f, inhale=%d cs, exhale=%d cs, E=%d",
                                                    data.breathRate, data.tvRaw, data.tidalVolume,
                                                    data.minuteVolume, data.ieRatio,
                                                    data.inhaleDurationCs, data.exhaleDurationCs, data.fieldE,
                                                )
                                            }
                                            TymewearData.update(data)
                                            emitDataPoints(emitter, data)
                                        }
                                    }
                                    Protocol.PKT_IMU -> {
                                        // IMU data at ~1Hz — ignore for now
                                    }
                                    Protocol.PKT_ADC_PEAKS -> {
                                        // Raw ADC peak data paired with breath packet — ignore for now
                                    }
                                    else -> {
                                        if (BuildConfig.DEBUG) {
                                            Timber.d(
                                                "Unknown pkt type 0x%02x: %d bytes, hex=%s",
                                                pktType, event.bytes.size,
                                                event.bytes.joinToString("") { "%02x".format(it) },
                                            )
                                        }
                                    }
                                }
                            }
                            Protocol.BREATHING_DATA_CHAR_UUID -> {
                                if (BuildConfig.DEBUG) {
                                    Timber.d(
                                        "Char 0001 data: ${event.bytes.size} bytes, " +
                                            "hex=${event.bytes.joinToString("") { "%02x".format(it) }}",
                                    )
                                }
                            }
                            Protocol.SENSOR_ID_CHAR_UUID -> {
                                val sensorId = String(event.bytes, Charsets.UTF_8).trim()
                                Timber.d("Sensor ID: $sensorId")
                            }
                            else -> {
                                if (BuildConfig.DEBUG) {
                                    Timber.d(
                                        "Unknown char ${event.characteristicUuid}: " +
                                            "${event.bytes.joinToString("") { "%02x".format(it) }}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        emitter.setCancellable {
            Timber.d("Cancelling device connection for $uid")
            connectScope.cancel()
        }
    }
}
