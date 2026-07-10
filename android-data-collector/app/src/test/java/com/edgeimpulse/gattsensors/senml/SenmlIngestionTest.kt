package com.edgeimpulse.gattsensors.senml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the flush-time conversion of line-delimited SenML files
 * into the Edge Impulse data-acquisition payload shape.
 */
class SenmlIngestionTest {

    private fun lines(vararg l: String) = l.asSequence()

    @Test
    fun `three lines convert to 3xN values matrix in first line channel order`() {
        val result = SenmlIngestion.convert(lines(
            """[{"bn":"openmv:","bt":100.00,"n":"ax","u":"m/s2","v":1.0},{"n":"ay","v":2.0}]""",
            """[{"bt":100.01,"n":"ax","v":3.0},{"n":"ay","v":4.0}]""",
            """[{"bt":100.02,"n":"ax","v":5.0},{"n":"ay","v":6.0}]""",
        ), fallbackDeviceName = "phone")!!
        assertEquals(0, result.skippedLines)
        assertEquals(
            listOf(listOf(1.0f, 2.0f), listOf(3.0f, 4.0f), listOf(5.0f, 6.0f)),
            result.payload.values,
        )
        assertEquals(listOf("ax", "ay"), result.payload.sensors.map { it.name })
    }

    @Test
    fun `interval ms derived from bt deltas`() {
        val result = SenmlIngestion.convert(lines(
            """[{"bt":100.00,"n":"a","v":1}]""",
            """[{"bt":100.01,"n":"a","v":2}]""",
            """[{"bt":100.02,"n":"a","v":3}]""",
        ), fallbackDeviceName = "phone")!!
        assertEquals(10.0, result.payload.interval_ms.toDouble(), 1e-6)
    }

    @Test
    fun `single line falls back to default interval`() {
        val result = SenmlIngestion.convert(
            lines("""[{"n":"a","v":1}]"""),
            fallbackDeviceName = "phone",
            defaultIntervalMs = 16.0,
        )!!
        assertEquals(16.0, result.payload.interval_ms.toDouble(), 1e-9)
    }

    @Test
    fun `device name taken from bn without trailing colon else fallback`() {
        val fromBn = SenmlIngestion.convert(
            lines("""[{"bn":"openmv:","n":"a","v":1}]"""), "phone")!!
        assertEquals("openmv", fromBn.payload.device_name)
        val fallback = SenmlIngestion.convert(
            lines("""[{"n":"a","v":1}]"""), "phone")!!
        assertEquals("phone", fallback.payload.device_name)
        assertEquals("SENML_LOG", fallback.payload.device_type)
    }

    @Test
    fun `line with different channel set is skipped and counted`() {
        val result = SenmlIngestion.convert(lines(
            """[{"n":"a","v":1},{"n":"b","v":2}]""",
            """[{"n":"a","v":3},{"n":"c","v":4}]""",
            """[{"n":"a","v":5},{"n":"b","v":6}]""",
        ), "phone")!!
        assertEquals(1, result.skippedLines)
        assertEquals(2, result.payload.values.size)
    }

    @Test
    fun `truncated final line is skipped and counted`() {
        val result = SenmlIngestion.convert(lines(
            """[{"n":"a","v":1}]""",
            """[{"n":"a","v":2}]""",
            """[{"n":"a","v":3},{"n":""",
        ), "phone")!!
        assertEquals(1, result.skippedLines)
        assertEquals(2, result.payload.values.size)
    }

    @Test
    fun `string and bool only line is skipped for the numeric matrix`() {
        val result = SenmlIngestion.convert(lines(
            """[{"n":"a","v":1}]""",
            """[{"n":"label","vs":"walking"},{"n":"active","vb":true}]""",
        ), "phone")!!
        assertEquals(1, result.skippedLines)
        assertEquals(1, result.payload.values.size)
    }

    @Test
    fun `garbage only input returns null`() {
        assertNull(SenmlIngestion.convert(lines("not json at all", "{{{"), "phone"))
        assertNull(SenmlIngestion.convert(emptySequence(), "phone"))
        assertNull(SenmlIngestion.convert(lines("", "   "), "phone"))
    }

    @Test
    fun `sensors list has one SensorInfo per channel with derived frequency`() {
        val result = SenmlIngestion.convert(lines(
            """[{"bt":0.00,"n":"x","v":1},{"n":"y","v":2}]""",
            """[{"bt":0.01,"n":"x","v":3},{"n":"y","v":4}]""",
        ), "phone")!!
        assertEquals(2, result.payload.sensors.size)
        val sensor = result.payload.sensors[0]
        assertNotNull(sensor.frequencies)
        assertEquals(100.0, sensor.frequencies[0].toDouble(), 1e-6)
        assertEquals(600, sensor.maxSampleLengthS)
    }

    @Test
    fun `duplicate names within a pack keep the first value`() {
        val result = SenmlIngestion.convert(lines(
            """[{"n":"a","v":1},{"n":"a","v":99}]""",
        ), "phone")!!
        assertEquals(listOf(listOf(1.0f)), result.payload.values)
    }
}
