package com.edgeimpulse.gattsensors.senml

import com.edgeimpulse.gattsensors.SensorData
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the SenML writer path: SensorData batch → pack →
 * RFC 8428 JSON. Assertions parse the emitted JSON back into a tree rather
 * than string-comparing — Gson legally prints large doubles in scientific
 * notation (`1.7521E9`), so byte-exact comparisons would be brittle.
 */
class SenmlJsonTest {

    private fun jsonArray(records: List<SenmlRecord>) =
        JsonParser.parseString(Senml.toJson(records)).asJsonArray

    @Test
    fun `pack from batch sets bn with trailing colon and bt from first sample epoch seconds`() {
        val pack = Senml.packFromSensorData("phone123", listOf(
            SensorData(1_752_130_000_123L, linkedMapOf("accel_0" to 0.5f)),
        ))
        assertEquals("phone123:", pack.first().bn)
        assertEquals(1_752_130_000.123, pack.first().bt!!, 1e-6)
        // Bases appear only on the first record of the pack.
        assertTrue(pack.drop(1).all { it.bn == null && it.bt == null })
    }

    @Test
    fun `relative t offsets are fractional seconds and omitted for the first record`() {
        val pack = Senml.packFromSensorData("dev", listOf(
            SensorData(1000L, linkedMapOf("accel_0" to 1f)),
            SensorData(1010L, linkedMapOf("accel_0" to 2f)),
            SensorData(1025L, linkedMapOf("accel_0" to 3f)),
        ))
        assertNull(pack[0].t)
        assertEquals(0.010, pack[1].t!!, 1e-9)
        assertEquals(0.025, pack[2].t!!, 1e-9)
    }

    @Test
    fun `known channel gets unit and unknown channel omits u`() {
        val pack = Senml.packFromSensorData("dev", listOf(
            SensorData(0L, linkedMapOf("accel_0" to 1f, "rotation_0" to 2f)),
        ))
        val arr = jsonArray(pack)
        val accel = arr[0].asJsonObject
        val rotation = arr[1].asJsonObject
        assertEquals("m/s2", accel.get("u").asString)
        assertFalse("unknown channel must omit u entirely", rotation.has("u"))
    }

    @Test
    fun `channel insertion order is preserved`() {
        val pack = Senml.packFromSensorData("dev", listOf(
            SensorData(0L, linkedMapOf("gyro_0" to 1f, "gyro_1" to 2f, "gyro_2" to 3f)),
        ))
        assertEquals(listOf("gyro_0", "gyro_1", "gyro_2"), pack.map { it.n })
    }

    @Test
    fun `emitted json contains no null members`() {
        val pack = Senml.packFromSensorData("dev", listOf(
            SensorData(1_000L, linkedMapOf("accel_0" to 1f, "rotation_0" to 2f)),
            SensorData(1_020L, linkedMapOf("accel_0" to 3f, "rotation_0" to 4f)),
        ))
        val arr = jsonArray(pack)
        for (el in arr) {
            val obj = el as JsonObject
            for ((key, value) in obj.entrySet()) {
                assertFalse("member $key must not be null", value.isJsonNull)
            }
        }
    }

    @Test
    fun `nan and infinity values are skipped not serialized`() {
        val pack = Senml.packFromSensorData("dev", listOf(
            SensorData(0L, linkedMapOf(
                "accel_0" to Float.NaN,
                "accel_1" to Float.POSITIVE_INFINITY,
                "accel_2" to 9.81f,
            )),
        ))
        assertEquals(listOf("accel_2"), pack.map { it.n })
        // The surviving record carries the bases, and rendering doesn't throw.
        assertEquals("dev:", pack[0].bn)
        val arr = jsonArray(pack)
        assertEquals(1, arr.size())
        assertEquals(9.81, arr[0].asJsonObject.get("v").asDouble, 1e-9)
    }

    @Test
    fun `toJson drops records carrying non-finite numbers instead of throwing`() {
        val json = Senml.toJson(listOf(
            SenmlRecord(n = "ok", v = 1.0),
            SenmlRecord(n = "bad", v = Double.NaN),
            SenmlRecord(n = "worse", t = Double.POSITIVE_INFINITY, v = 2.0),
        ))
        val arr = JsonParser.parseString(json).asJsonArray
        assertEquals(1, arr.size())
        assertEquals("ok", arr[0].asJsonObject.get("n").asString)
    }

    @Test
    fun `empty batch renders to empty pack`() {
        assertTrue(Senml.packFromSensorData("dev", emptyList()).isEmpty())
        assertEquals("[]", Senml.toJson(emptyList()))
    }
}
