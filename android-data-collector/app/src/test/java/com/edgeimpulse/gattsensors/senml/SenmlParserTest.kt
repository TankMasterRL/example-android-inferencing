package com.edgeimpulse.gattsensors.senml

import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for SenML parsing and RFC 8428 §4.3 base resolution.
 * Input strings mirror what the OpenMV/KPN senml library's `to_json()`
 * emits, plus the RFC's own examples.
 */
class SenmlParserTest {

    private fun resolve(json: String) = Senml.resolve(Senml.fromJson(json))

    @Test
    fun `bn and bt on first record are inherited by later records`() {
        val records = resolve(
            """[{"bn":"dev:","bt":100.0,"n":"a","v":1},{"n":"b","v":2,"t":0.5}]"""
        )
        assertEquals(listOf("dev:a", "dev:b"), records.map { it.name })
        assertEquals(100.0, records[0].time, 1e-9)
        assertEquals(100.5, records[1].time, 1e-9)
    }

    @Test
    fun `bn override mid pack rebases subsequent names`() {
        val records = resolve(
            """[{"bn":"one:","n":"a","v":1},{"bn":"two:","n":"b","v":2},{"n":"c","v":3}]"""
        )
        assertEquals(listOf("one:a", "two:b", "two:c"), records.map { it.name })
    }

    @Test
    fun `bu applies when u absent and u wins when present`() {
        val records = resolve(
            """[{"bn":"d:","bu":"Cel","n":"a","v":1},{"n":"b","u":"lx","v":2}]"""
        )
        assertEquals("Cel", records[0].unit)
        assertEquals("lx", records[1].unit)
    }

    @Test
    fun `bv is added to v`() {
        val records = resolve("""[{"bn":"d:","bv":10.0,"n":"a","v":1.5}]""")
        assertEquals(11.5, records[0].value!!, 1e-9)
    }

    @Test
    fun `missing t resolves to bt and missing bt resolves to zero`() {
        val withBt = resolve("""[{"bn":"d:","bt":42.0,"n":"a","v":1}]""")
        assertEquals(42.0, withBt[0].time, 1e-9)
        val withoutBt = resolve("""[{"n":"a","v":1}]""")
        assertEquals(0.0, withoutBt[0].time, 1e-9)
    }

    @Test
    fun `vs and vb records resolve without numeric value`() {
        val records = resolve(
            """[{"bn":"d:","n":"label","vs":"walking"},{"n":"active","vb":true}]"""
        )
        assertEquals(2, records.size)
        assertNull(records[0].value)
        assertEquals("walking", records[0].stringValue)
        assertEquals(true, records[1].boolValue)
    }

    @Test
    fun `record without any name is dropped`() {
        val records = resolve("""[{"v":1.0},{"n":"kept","v":2.0}]""")
        assertEquals(listOf("kept"), records.map { it.name })
    }

    @Test
    fun `record without any value field is dropped`() {
        val records = resolve("""[{"bn":"d:","bt":5.0},{"n":"kept","v":1}]""")
        assertEquals(listOf("d:kept"), records.map { it.name })
        assertEquals(5.0, records[0].time, 1e-9)
    }

    @Test
    fun `non-finite parsed values are dropped by resolve`() {
        // Gson's lenient reader accepts NaN/Infinity literals even though
        // strict JSON forbids them — resolution must filter them out.
        val records = resolve("""[{"n":"bad","v":NaN},{"n":"ok","v":1.0}]""")
        assertEquals(listOf("ok"), records.map { it.name })
    }

    @Test
    fun `bare object line is wrapped and parsed`() {
        val records = resolve("""{"n":"temp","u":"Cel","v":23.5}""")
        assertEquals(1, records.size)
        assertEquals("temp", records[0].name)
        assertEquals(23.5, records[0].value!!, 1e-9)
    }

    @Test
    fun `malformed json throws JsonParseException`() {
        val truncated = """[{"n":"accel_0","v":0.1},{"n":"acc"""
        try {
            Senml.fromJson(truncated)
            throw AssertionError("expected JsonParseException")
        } catch (expected: JsonParseException) {
            // ok
        }
    }

    @Test
    fun `shortName strips bn prefix while name keeps it`() {
        val records = resolve("""[{"bn":"urn:dev:123:","n":"accel_0","v":1}]""")
        assertEquals("urn:dev:123:accel_0", records[0].name)
        assertEquals("accel_0", records[0].shortName)
        // A record named entirely by bn falls back to the full name.
        val bnOnly = resolve("""[{"bn":"urn:dev:temp","u":"Cel","v":23.1}]""")
        assertEquals("urn:dev:temp", bnOnly[0].shortName)
    }

    @Test
    fun `round trip pack to json to resolve preserves values names and times`() {
        val pack = Senml.packFromSensorData("phone", listOf(
            com.edgeimpulse.gattsensors.SensorData(2_000L, linkedMapOf("accel_0" to 0.1f, "accel_1" to -0.34f)),
            com.edgeimpulse.gattsensors.SensorData(2_010L, linkedMapOf("accel_0" to 9.81f, "accel_1" to 0.0f)),
        ))
        val records = resolve(Senml.toJson(pack))
        assertEquals(4, records.size)
        assertTrue(records.all { it.name.startsWith("phone:") })
        assertEquals(listOf("accel_0", "accel_1", "accel_0", "accel_1"), records.map { it.shortName })
        assertEquals(0.1, records[0].value!!, 1e-9)
        assertEquals(9.81, records[2].value!!, 1e-9)
        assertEquals(2.0, records[0].time, 1e-9)
        assertEquals(2.01, records[2].time, 1e-9)
        assertEquals("m/s2", records[0].unit)
    }
}
