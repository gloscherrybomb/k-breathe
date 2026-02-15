package com.tymewear.karoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.LinkedList
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * Manages BLE scanning and GATT connections for the VitalPro breathing sensor.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    data class ScannedDevice(
        val name: String,
        val address: String,
    )

    /**
     * Scan for VitalPro BLE devices. Emits devices matching the name pattern.
     * Optionally filters by a specific sensor ID.
     */
    fun scan(sensorId: String? = null): Flow<ScannedDevice> = callbackFlow {
        val scanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            ?: run {
                Timber.w("BLE scanner not available")
                close()
                return@callbackFlow
            }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Scan without UUID filter — VitalPro may not advertise service UUID.
        // We filter by device name in the callback instead.
        val filters = emptyList<ScanFilter>()

        val callback = object : ScanCallback() {
            private val seen = mutableSetOf<String>()

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val address = result.device.address

                // Log all named devices for debugging
                if (BuildConfig.DEBUG && !seen.contains(address)) {
                    Timber.d("BLE device seen: $name ($address)")
                }

                if (!Protocol.isVitalProDevice(name)) return
                if (sensorId != null && !Protocol.matchesSensorId(name, sensorId)) return
                if (!seen.add(address)) return

                Timber.d("Found VitalPro device: $name ($address)")
                trySend(ScannedDevice(name, address))
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed: $errorCode")
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        Timber.d("Starting BLE scan (sensorId=$sensorId)")
        scanner.startScan(filters, settings, callback)

        awaitClose {
            Timber.d("Stopping BLE scan")
            scanner.stopScan(callback)
        }
    }

    /**
     * Connect to a VitalPro device by MAC address and observe breathing data notifications.
     * Includes automatic reconnection with exponential backoff on disconnect.
     */
    fun connect(address: String): Flow<ConnectionEvent> = callbackFlow {
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(address)
            ?: run {
                close(Exception("Bluetooth adapter not available"))
                return@callbackFlow
            }

        var gatt: BluetoothGatt? = null
        var reconnectAttempt = 0
        val maxReconnectAttempts = Constants.BLE_MAX_RECONNECT_ATTEMPTS
        val handler = Handler(Looper.getMainLooper())
        var closed = false

        fun attemptConnect(autoConnect: Boolean) {
            if (closed) return
            Timber.d("Connecting GATT to $address (autoConnect=$autoConnect, attempt=$reconnectAttempt)")
            // Close any existing GATT before reconnecting
            gatt?.close()
            gatt = device.connectGatt(context, autoConnect, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Timber.d("GATT connected to $address (status=$status)")
                            reconnectAttempt = 0
                            trySend(ConnectionEvent.Connected)
                            g.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Timber.d("GATT disconnected from $address (status=$status)")
                            trySend(ConnectionEvent.Disconnected)
                            // Attempt reconnection with backoff
                            if (!closed && reconnectAttempt < maxReconnectAttempts) {
                                reconnectAttempt++
                                val delayMs = (Constants.BLE_BASE_RECONNECT_DELAY_MS * reconnectAttempt).coerceAtMost(Constants.BLE_MAX_RECONNECT_DELAY_MS)
                                Timber.d("Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")
                                handler.postDelayed({
                                    attemptConnect(true)
                                }, delayMs)
                            } else if (!closed) {
                                Timber.e("Max reconnect attempts reached for $address")
                                close()
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Timber.e("Service discovery failed: $status")
                        // Treat as disconnect and retry
                        g.disconnect()
                        return
                    }
                    Timber.d("Services discovered, subscribing to notifications")
                    subscribeToAllCharacteristics(g)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    trySend(ConnectionEvent.Data(characteristic.uuid, value))
                }

                @Suppress("DEPRECATION")
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: return
                    trySend(ConnectionEvent.Data(characteristic.uuid, value))
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    val charUuid = descriptor.characteristic.uuid
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.d("Notification subscription active for $charUuid")
                        trySend(ConnectionEvent.Subscribed)
                        subscribeNext(g)
                    } else {
                        Timber.e("Descriptor write failed for $charUuid: $status")
                        subscribeNext(g)
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        }

        // First connection: direct (not autoConnect) for faster initial connect
        attemptConnect(false)

        awaitClose {
            Timber.d("Closing GATT connection to $address")
            closed = true
            handler.removeCallbacksAndMessages(null)
            gatt?.close()
        }
    }

    // Queue for sequential CCCD descriptor writes (Android BLE allows one GATT op at a time)
    private val descriptorWriteQueue = LinkedList<BluetoothGattDescriptor>()

    /**
     * Subscribe to all VitalPro characteristic notifications.
     * Queues CCCD writes since Android BLE only supports one GATT operation at a time.
     */
    private fun subscribeToAllCharacteristics(gatt: BluetoothGatt) {
        val service = gatt.getService(Protocol.VITALPRO_SERVICE_UUID)
        if (service == null) {
            Timber.e("VitalPro service not found")
            if (BuildConfig.DEBUG) {
                gatt.services.forEach { s ->
                    Timber.d("  Service: ${s.uuid}")
                    s.characteristics.forEach { c ->
                        Timber.d("    Char: ${c.uuid} props=${c.properties}")
                    }
                }
            }
            return
        }

        // Subscribe to all three known characteristics
        val charUuids = listOf(
            Protocol.BREATHING_DATA_CHAR_UUID,
            Protocol.COMMAND_CHAR_UUID,
            Protocol.SENSOR_ID_CHAR_UUID,
        )

        descriptorWriteQueue.clear()

        for (uuid in charUuids) {
            val characteristic = service.getCharacteristic(uuid)
            if (characteristic == null) {
                Timber.w("Characteristic $uuid not found in service")
                continue
            }

            // Check if the characteristic supports notifications
            val hasNotify = (characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val hasIndicate = (characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

            if (!hasNotify && !hasIndicate) {
                Timber.d("Characteristic $uuid does not support notify/indicate (props=${characteristic.properties})")
                continue
            }

            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(Protocol.CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = if (hasNotify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                descriptorWriteQueue.add(descriptor)
                Timber.d("Queued notification subscription for $uuid")
            } else {
                Timber.w("CCCD descriptor not found for $uuid")
            }
        }

        // Start processing the queue
        subscribeNext(gatt)
    }

    /**
     * Write the next queued CCCD descriptor, if any.
     */
    private fun subscribeNext(gatt: BluetoothGatt) {
        val descriptor = descriptorWriteQueue.poll() ?: return
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    }

    sealed class ConnectionEvent {
        data object Connected : ConnectionEvent()
        data object Disconnected : ConnectionEvent()
        data object Subscribed : ConnectionEvent()
        data class Data(val characteristicUuid: UUID, val bytes: ByteArray) : ConnectionEvent()
    }
}
