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
import kotlinx.coroutines.Job
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
                DataType.dataTypeId(extension, "br"),
                DataType.dataTypeId(extension, "tv"),
            ),
            displayName = displayName,
        )
    }

    private var scope: CoroutineScope? = null

    /**
     * Connect to the BLE device and start emitting DeviceEvents.
     */
    fun connect(emitter: Emitter<DeviceEvent>) {
        val connectScope = CoroutineScope(Dispatchers.IO)
        scope = connectScope

        connectScope.launch {
            emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))

            bleManager.connect(uid).collect { event ->
                when (event) {
                    is BleManager.ConnectionEvent.Connected -> {
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                        emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
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
                        // TODO: Implement reconnection logic
                    }

                    is BleManager.ConnectionEvent.Subscribed -> {
                        Timber.d("Breathing data notifications active")
                    }

                    is BleManager.ConnectionEvent.Data -> {
                        when (event.characteristicUuid) {
                            Protocol.BREATHING_DATA_CHAR_UUID -> {
                                val data = Protocol.parseBreathingData(event.bytes)
                                if (data != null) {
                                    TymewearData.update(data)

                                    emitter.onNext(
                                        OnDataPoint(
                                            DataPoint(
                                                dataTypeId = DataType.dataTypeId(extension, "ve"),
                                                values = mapOf("ve" to data.minuteVolume),
                                                sourceId = uid,
                                            ),
                                        ),
                                    )

                                    emitter.onNext(
                                        OnDataPoint(
                                            DataPoint(
                                                dataTypeId = DataType.dataTypeId(extension, "br"),
                                                values = mapOf("br" to data.breathRate),
                                                sourceId = uid,
                                            ),
                                        ),
                                    )

                                    emitter.onNext(
                                        OnDataPoint(
                                            DataPoint(
                                                dataTypeId = DataType.dataTypeId(extension, "tv"),
                                                values = mapOf("tv" to data.tidalVolume),
                                                sourceId = uid,
                                            ),
                                        ),
                                    )
                                } else {
                                    Timber.w(
                                        "Failed to parse breathing data: ${event.bytes.size} bytes, " +
                                            "hex=${event.bytes.joinToString("") { "%02x".format(it) }}",
                                    )
                                }
                            }
                            Protocol.COMMAND_CHAR_UUID -> {
                                Timber.d(
                                    "Command char notification: ${event.bytes.size} bytes, " +
                                        "hex=${event.bytes.joinToString("") { "%02x".format(it) }}",
                                )
                            }
                            Protocol.SENSOR_ID_CHAR_UUID -> {
                                val sensorId = String(event.bytes, Charsets.UTF_8).trim()
                                Timber.d("Sensor ID: $sensorId")
                            }
                            else -> {
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

        emitter.setCancellable {
            Timber.d("Cancelling device connection for $uid")
            connectScope.cancel()
        }
    }
}
