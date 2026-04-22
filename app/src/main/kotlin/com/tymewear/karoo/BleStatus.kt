package com.tymewear.karoo

/**
 * Decodes Android BLE status and scan error codes into human-readable
 * strings for logcat. Pure helper, no state.
 */
object BleStatus {

    /** Decode a `BluetoothGatt` status code (from `onConnectionStateChange`,
     *  `onServicesDiscovered`, `onDescriptorWrite`, etc.). */
    fun decodeGatt(status: Int): String = when (status) {
        0 -> "SUCCESS"
        8 -> "CONN_TIMEOUT"
        19 -> "REMOTE_DISCONNECT"
        22 -> "LOCAL_DISCONNECT"
        34 -> "LMP_TIMEOUT"
        62 -> "CONN_FAIL_ESTABLISH"
        133 -> "GATT_ERROR"
        137 -> "AUTH_FAIL"
        143 -> "INSUF_ENCRYPT"
        257 -> "TOO_MANY_CONNECTIONS"
        else -> "UNKNOWN"
    }.let { "$it($status)" }

    /** Decode a `ScanCallback.SCAN_FAILED_*` error code. */
    fun decodeScan(errorCode: Int): String = when (errorCode) {
        1 -> "ALREADY_STARTED"
        2 -> "APP_REG_FAILED"
        3 -> "INTERNAL_ERROR"
        4 -> "FEATURE_UNSUPPORTED"
        5 -> "OUT_OF_RESOURCES"
        6 -> "SCANNING_TOO_FREQUENTLY"
        else -> "UNKNOWN"
    }.let { "$it($errorCode)" }
}
