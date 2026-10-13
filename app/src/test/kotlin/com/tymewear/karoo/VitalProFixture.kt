package com.tymewear.karoo

import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import fi.nikosavola.karooext.testing.robolectric.ble.bleUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val STRAP_ADDRESS = "AA:BB:CC:DD:EE:01"

/** Extension id of [TymewearExtension], which prefixes every data type id it emits. */
const val EXT = "tymewear"

/**
 * A VitalPro strap as the code in [Protocol] expects to find it. Notifying characteristics get a
 * client configuration descriptor from the builder, which [BleManager] has to write to subscribe.
 */
fun FakeBle.vitalPro(
    address: String = STRAP_ADDRESS,
    name: String = "TYME-1A2B",
    withService: Boolean = true,
    batteryPercent: Int? = 80,
): FakeBlePeripheral = peripheral(address, name) {
    if (withService) {
        service(Protocol.VITALPRO_SERVICE_UUID) {
            characteristic(Protocol.BREATHING_DATA_CHAR_UUID, notify = true)
            characteristic(Protocol.COMMAND_CHAR_UUID, notify = true)
            characteristic(Protocol.SENSOR_ID_CHAR_UUID, read = true, notify = true)
        }
        service(Protocol.SECONDARY_SERVICE_UUID) {
            characteristic(bleUuid(0xFE01), write = true)
        }
    }
    if (batteryPercent != null) {
        service(Protocol.BATTERY_SERVICE_UUID) {
            characteristic(
                Protocol.BATTERY_LEVEL_CHAR_UUID,
                read = true,
                notify = true,
                value = byteArrayOf(batteryPercent.toByte()),
            )
        }
    }
}

/**
 * Type 0x01 per-breath packet, laid out as documented on [Protocol.parseNotification]. With the
 * defaults the breath lasts 3 s (inhale 1.2 s, exhale 1.8 s), so the first packet of a session
 * gives 20 breaths/min and a 0.5 L tidal volume, 10 L/min.
 */
fun breathPacket(
    timestampTicks: Long,
    inhale: Int = 120,
    exhale: Int = 180,
    tidalRaw: Int = 50,
    fieldE: Int = 7,
): ByteArray = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
    .put(Protocol.PKT_BREATH.toByte())
    .putInt(timestampTicks.toInt())
    .putShort(inhale.toShort())
    .putShort(exhale.toShort())
    .putShort(tidalRaw.toShort())
    .putShort(tidalRaw.toShort())
    .putShort(fieldE.toShort())
    .putShort(fieldE.toShort())
    .array()

fun imuPacket(): ByteArray = ByteArray(13).also { it[0] = Protocol.PKT_IMU.toByte() }

fun adcPeakPacket(): ByteArray = ByteArray(17).also { it[0] = Protocol.PKT_ADC_PEAKS.toByte() }

/** Ticks of 40 ms in [seconds]. */
fun ticks(seconds: Int): Long = seconds * 25L
