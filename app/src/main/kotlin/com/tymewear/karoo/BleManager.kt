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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * Clear Android's GATT service cache via the hidden BluetoothGatt.refresh() method.
     * Forces fresh service discovery after firmware updates that change the GATT table.
     * Returns true if the method was found and invoked successfully.
     */
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            Timber.w("GATT cache refresh not available: ${e.message}")
            false
        }
    }

    data class ScannedDevice(
        val name: String,
        val address: String,
    )

    /**
     * Shared across every scan path in this manager, because Android's scan-rate budget
     * is per-application: discovery scans and targeted reconnect scans draw on the same
     * allowance and would otherwise exhaust it between them.
     */
    private val scanThrottle = ScanThrottle()

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
                Timber.e("BLE scan failed: ${BleStatus.decodeScan(errorCode)}")
                BleDiagnostics.onScanFailed(errorCode)
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        // The Karoo drives startScan/stopScan and has been seen calling them ~10 times
        // in 12s. Exceeding the platform's scan-rate budget makes Android silently
        // refuse to scan at all, so defer instead of burning a rejected registration.
        val handler = Handler(Looper.getMainLooper())
        val started = AtomicBoolean(false)
        lateinit var startWhenAllowed: () -> Unit
        startWhenAllowed = {
            val now = System.currentTimeMillis()
            if (scanThrottle.tryAcquire(now)) {
                Timber.d("Starting BLE scan (sensorId=$sensorId)")
                BleDiagnostics.onScanStart()
                started.set(true)
                scanner.startScan(filters, settings, callback)
            } else {
                val wait = scanThrottle.delayUntilAllowedMs(now)
                Timber.w(
                    "Scan rate budget reached (${scanThrottle.recentStarts(now)} starts in " +
                        "${ScanThrottle.DEFAULT_WINDOW_MS / 1000}s) — deferring scan ${wait}ms",
                )
                BleDiagnostics.onScanDeferred(wait)
                handler.postDelayed({ startWhenAllowed() }, wait + 50L)
            }
        }
        startWhenAllowed()

        awaitClose {
            handler.removeCallbacksAndMessages(null)
            if (started.get()) {
                Timber.d("Stopping BLE scan")
                scanner.stopScan(callback)
            } else {
                Timber.d("Scan cancelled before it started (was deferred)")
            }
        }
    }

    /**
     * Connect to a VitalPro device by MAC address and observe breathing data notifications.
     * Includes:
     * - GATT cache refresh on reconnect (handles firmware updates)
     * - Two-phase reconnection: rapid (10x 2s) then slow (autoConnect=true, power-efficient)
     * - Data watchdog: detects silent notification loss (45s timeout)
     * - GATT error 133 and non-SUCCESS status handling
     */
    fun connect(address: String): Flow<ConnectionEvent> = callbackFlow {
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(address)
            ?: run {
                close(Exception("Bluetooth adapter not available"))
                return@callbackFlow
            }

        val gatt = AtomicReference<BluetoothGatt?>(null)
        val reconnectAttempt = java.util.concurrent.atomic.AtomicInteger(0)
        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val sessionStartMs = System.currentTimeMillis()
        val firstPacketLogged = AtomicBoolean(false)
        // Detects silent notification loss. Keeps retrying while data is absent —
        // see DataWatchdog for why a one-shot watchdog let rides record hours of
        // frozen values.
        val watchdog = DataWatchdog(Constants.BLE_DATA_WATCHDOG_TIMEOUT_MS)

        // Use lateinit lambdas to allow mutual recursion between local functions.
        // (Kotlin local `fun` declarations don't support forward references.)
        lateinit var scheduleReconnect: () -> Unit
        lateinit var attemptConnect: (target: BluetoothDevice, autoConnect: Boolean) -> Unit
        lateinit var scanThenConnect: () -> Unit

        scheduleReconnect = {
            if (!closed.get()) {
                val attempt = reconnectAttempt.incrementAndGet()
                if (attempt <= Constants.BLE_RAPID_PHASE_ATTEMPTS) {
                    // Phase 1: rapid direct reconnects
                    Timber.d("Scheduling reconnect #$attempt (rapid) in ${Constants.BLE_RAPID_PHASE_DELAY_MS}ms")
                    handler.postDelayed({
                        if (!closed.get()) attemptConnect(device, false)
                    }, Constants.BLE_RAPID_PHASE_DELAY_MS)
                } else {
                    // Phase 2: let Android handle it power-efficiently via autoConnect=true.
                    // Also schedule a manual fallback in case autoConnect silently fails.
                    Timber.d("Scheduling reconnect #$attempt (slow/autoConnect) with ${Constants.BLE_SLOW_PHASE_DELAY_MS}ms fallback")
                    attemptConnect(device, true)
                    // Fallback: if autoConnect silently fails, retry after timeout.
                    // Guard: skip if connection succeeded (reconnectAttempt resets to 0).
                    handler.postDelayed({
                        if (!closed.get() && reconnectAttempt.get() == attempt) {
                            scheduleReconnect()
                        }
                    }, Constants.BLE_SLOW_PHASE_DELAY_MS)
                }
            }
        }

        attemptConnect = fn@{ target, autoConnect ->
            if (closed.get()) return@fn
            Timber.d("Connecting GATT to ${target.address} (autoConnect=$autoConnect, attempt=${reconnectAttempt.get()})")
            BleDiagnostics.onReconnectAttempt(reconnectAttempt.get(), autoConnect)
            // Close any existing GATT before reconnecting — single close point
            gatt.getAndSet(null)?.close()
            // Clear stale descriptor queue from previous connection
            activeDescriptorQueue.set(null)

            gatt.set(target.connectGatt(context, autoConnect, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Timber.w("GATT status=${BleStatus.decodeGatt(status)} newState=$newState for $address")
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                // Connected with non-zero status — unreliable connection
                                Timber.w("Connected with error status ${BleStatus.decodeGatt(status)}, scheduling retry")
                                trySend(ConnectionEvent.Disconnected)
                                scheduleReconnect()
                                return
                            }
                            Timber.d("GATT connected to $address")
                            reconnectAttempt.set(0)
                            trySend(ConnectionEvent.Connected)

                            // Refresh GATT cache to force fresh service discovery.
                            // Critical after firmware updates that change the GATT table.
                            val refreshed = refreshGattCache(g)
                            Timber.d("GATT cache refresh: $refreshed")

                            // Delay service discovery slightly to let cache refresh settle.
                            // Guard against disconnect during the delay.
                            handler.postDelayed({
                                if (!closed.get() && gatt.get() === g) {
                                    g.discoverServices()
                                } else {
                                    Timber.d("Skipping discoverServices — GATT changed or closed during delay")
                                }
                            }, 300)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Timber.w("GATT disconnected from $address (status=${BleStatus.decodeGatt(status)}, attempt=${reconnectAttempt.get()})")
                            trySend(ConnectionEvent.Disconnected)
                            scheduleReconnect()
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Timber.e("Service discovery failed: status=${BleStatus.decodeGatt(status)}")
                        g.disconnect()
                        return
                    }
                    val priorityOk = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Timber.d("requestConnectionPriority(HIGH) = $priorityOk")
                    Timber.d("Services discovered, subscribing to notifications")
                    subscribeToAllCharacteristics(g)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    val now = System.currentTimeMillis()
                    watchdog.onData(now)
                    BleDiagnostics.onPacket(now)
                    if (firstPacketLogged.compareAndSet(false, true)) {
                        Timber.d("First data received after ${now - sessionStartMs}ms")
                    }
                    trySend(ConnectionEvent.Data(characteristic.uuid, value))
                }

                @Suppress("DEPRECATION")
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val now = System.currentTimeMillis()
                    watchdog.onData(now)
                    BleDiagnostics.onPacket(now)
                    if (firstPacketLogged.compareAndSet(false, true)) {
                        Timber.d("First data received after ${now - sessionStartMs}ms")
                    }
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
                        Timber.e("Descriptor write failed for $charUuid: status=${BleStatus.decodeGatt(status)}")
                        subscribeNext(g)
                    }
                }

                @Suppress("DEPRECATION")
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS &&
                        characteristic.uuid == Protocol.BATTERY_LEVEL_CHAR_UUID
                    ) {
                        @Suppress("DEPRECATION")
                        val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: return
                        Timber.d("Battery level: $level%")
                        trySend(ConnectionEvent.BatteryLevel(level))
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE))
        }

        scanThenConnect = {
            if (!closed.get()) {
                val scanner = bluetoothAdapter?.bluetoothLeScanner
                if (scanner == null) {
                    Timber.w("Scanner unavailable for targeted scan, falling back to autoConnect=true")
                    attemptConnect(device, true)
                } else {
                    val scanStartMs = System.currentTimeMillis()
                    val filter = ScanFilter.Builder().setDeviceAddress(address).build()
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()

                    lateinit var timeoutRunnable: Runnable

                    val cb = object : ScanCallback() {
                        private val hit = AtomicBoolean(false)
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            if (!hit.compareAndSet(false, true)) return
                            val elapsed = System.currentTimeMillis() - scanStartMs
                            Timber.d("Targeted scan hit for $address in ${elapsed}ms")
                            scanner.stopScan(this)
                            activeScanCallback.compareAndSet(this, null)
                            handler.removeCallbacks(timeoutRunnable)
                            attemptConnect(result.device, false)
                        }
                        override fun onScanFailed(errorCode: Int) {
                            Timber.e("Targeted scan failed: ${BleStatus.decodeScan(errorCode)}; falling back to autoConnect=true")
                            scanner.stopScan(this)
                            activeScanCallback.compareAndSet(this, null)
                            handler.removeCallbacks(timeoutRunnable)
                            attemptConnect(device, true)
                        }
                    }
                    activeScanCallback.set(cb)

                    timeoutRunnable = Runnable {
                        if (closed.get()) return@Runnable
                        val existing = activeScanCallback.getAndSet(null)
                        if (existing === cb) {
                            Timber.w("Targeted scan timeout after ${Constants.BLE_INITIAL_SCAN_TIMEOUT_MS / 1000}s, falling back to autoConnect=true")
                            scanner.stopScan(cb)
                            attemptConnect(device, true)
                        }
                    }

                    // Draws on the same per-app scan budget as discovery scans. If it is
                    // exhausted, skip straight to autoConnect rather than issue a start
                    // the platform will silently reject.
                    val nowMs = System.currentTimeMillis()
                    if (scanThrottle.tryAcquire(nowMs)) {
                        Timber.d("Starting targeted scan for $address (${Constants.BLE_INITIAL_SCAN_TIMEOUT_MS / 1000}s timeout)")
                        BleDiagnostics.onScanStart()
                        scanner.startScan(listOf(filter), settings, cb)
                        handler.postDelayed(timeoutRunnable, Constants.BLE_INITIAL_SCAN_TIMEOUT_MS)
                    } else {
                        val wait = scanThrottle.delayUntilAllowedMs(nowMs)
                        Timber.w(
                            "Scan rate budget reached — skipping targeted scan for $address " +
                                "(next slot in ${wait}ms), using autoConnect=true instead",
                        )
                        BleDiagnostics.onScanDeferred(wait)
                        activeScanCallback.compareAndSet(cb, null)
                        attemptConnect(device, true)
                    }
                }
            }
        }

        // First connection: scan-assisted (scan for MAC, connect to fresh handle)
        scanThenConnect()

        // Data watchdog: detects silent connection loss where GATT stays "connected"
        // but notifications silently stop (radio interference, sensor sleep, firmware bug).
        // Only activates after the first packet arrives, and keeps retrying for as long
        // as data stays absent — a single failed recovery must not silence it.
        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (closed.get()) return
                val now = System.currentTimeMillis()
                // Heartbeat so the diagnostics summary keeps reporting through a dropout,
                // when no packets are arriving to drive it.
                BleDiagnostics.tick(now)
                val currentGatt = gatt.get()
                if (currentGatt != null && watchdog.shouldForceReconnect(now)) {
                    val age = BleDiagnostics.lastPacketAgeMs(now)
                    Timber.w(
                        "Data watchdog: no notifications for ${age?.div(1000) ?: "?"}s " +
                            "(attempt ${watchdog.consecutiveFailures}), forcing reconnect",
                    )
                    BleDiagnostics.onWatchdogFire(watchdog.consecutiveFailures)
                    currentGatt.disconnect()
                }
                handler.postDelayed(this, Constants.BLE_DATA_WATCHDOG_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchdogRunnable, Constants.BLE_DATA_WATCHDOG_INTERVAL_MS)

        awaitClose {
            Timber.d("Closing GATT connection to $address")
            closed.set(true)
            handler.removeCallbacksAndMessages(null)
            activeScanCallback.getAndSet(null)?.let {
                try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {}
            }
            gatt.getAndSet(null)?.close()
        }
    }

    // Per-connection descriptor write queue (AtomicReference for thread-safe swap on reconnect)
    private val activeDescriptorQueue = AtomicReference<LinkedList<BluetoothGattDescriptor>?>(null)

    // Per-connection targeted scan callback (for scan-assisted initial connect)
    private val activeScanCallback = AtomicReference<ScanCallback?>(null)

    /**
     * Subscribe to all VitalPro characteristic notifications.
     * Queues CCCD writes since Android BLE only supports one GATT operation at a time.
     * Queue is scoped to this connection to prevent corruption on rapid reconnect.
     */
    private fun subscribeToAllCharacteristics(gatt: BluetoothGatt) {
        val service = gatt.getService(Protocol.VITALPRO_SERVICE_UUID)
        if (service == null) {
            Timber.e("VitalPro service ${Protocol.VITALPRO_SERVICE_UUID} not found!")
            Timber.e("Available services (${gatt.services.size}):")
            gatt.services.forEach { s ->
                Timber.e("  Service: ${s.uuid}")
                s.characteristics.forEach { c ->
                    Timber.e("    Char: ${c.uuid} props=0x%02x".format(c.properties))
                }
            }
            // Disconnect and let reconnect logic retry — service table may be stale
            gatt.disconnect()
            return
        }

        Timber.d("VitalPro service found with ${service.characteristics.size} characteristics")

        // Subscribe to all three known characteristics
        val charUuids = listOf(
            Protocol.BREATHING_DATA_CHAR_UUID,
            Protocol.COMMAND_CHAR_UUID,
            Protocol.SENSOR_ID_CHAR_UUID,
        )

        // Per-connection queue prevents corruption on rapid reconnect
        val queue = LinkedList<BluetoothGattDescriptor>()

        for (uuid in charUuids) {
            val characteristic = service.getCharacteristic(uuid)
            if (characteristic == null) {
                Timber.e("Characteristic $uuid not found in VitalPro service — firmware may have changed")
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
                queue.add(descriptor)
                Timber.d("Queued notification subscription for $uuid")
            } else {
                Timber.w("CCCD descriptor not found for $uuid")
            }
        }

        // Start processing the queue
        activeDescriptorQueue.set(queue)
        subscribeNext(gatt)
    }

    /**
     * Write the next queued CCCD descriptor, or read battery level when queue is empty.
     */
    private fun subscribeNext(gatt: BluetoothGatt) {
        val queue = activeDescriptorQueue.get()
        val descriptor = queue?.poll()
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        } else {
            // All subscriptions complete — read battery level from standard Battery Service
            val batteryService = gatt.getService(Protocol.BATTERY_SERVICE_UUID)
            val batteryChar = batteryService?.getCharacteristic(Protocol.BATTERY_LEVEL_CHAR_UUID)
            if (batteryChar != null) {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(batteryChar)
            }
        }
    }

    sealed class ConnectionEvent {
        data object Connected : ConnectionEvent()
        data object Disconnected : ConnectionEvent()
        data object Subscribed : ConnectionEvent()
        data class Data(val characteristicUuid: UUID, val bytes: ByteArray) : ConnectionEvent()
        data class BatteryLevel(val percent: Int) : ConnectionEvent()
    }
}
