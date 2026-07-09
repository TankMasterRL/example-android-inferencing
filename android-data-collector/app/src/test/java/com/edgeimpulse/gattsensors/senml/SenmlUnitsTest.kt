package com.edgeimpulse.gattsensors.senml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenmlUnitsTest {

    @Test
    fun `axis suffix is stripped before lookup`() {
        assertEquals("m/s2", SenmlUnits.unitFor("accel_0"))
        assertEquals("m/s2", SenmlUnits.unitFor("accel_2"))
        assertEquals("rad/s", SenmlUnits.unitFor("gyro_1"))
        assertEquals("hPa", SenmlUnits.unitFor("pressure_0"))
    }

    @Test
    fun `bare key without axis suffix also resolves`() {
        assertEquals("m/s2", SenmlUnits.unitFor("accel"))
        assertEquals("beat/min", SenmlUnits.unitFor("hr"))
    }

    @Test
    fun `linear accel does not collide with accel`() {
        assertEquals("m/s2", SenmlUnits.unitFor("linear_accel_0"))
        // Exact match after suffix strip — no prefix accidents.
        assertNull(SenmlUnits.unitFor("accelerometer_0"))
    }

    @Test
    fun `gps keys map to senml position units`() {
        assertEquals("lat", SenmlUnits.unitFor("lat"))
        assertEquals("lon", SenmlUnits.unitFor("lon"))
        assertEquals("m", SenmlUnits.unitFor("alt"))
        assertEquals("m/s", SenmlUnits.unitFor("speed"))
    }

    @Test
    fun `unknown key returns null`() {
        assertNull(SenmlUnits.unitFor("rotation_0"))
        assertNull(SenmlUnits.unitFor("prox_0"))
        assertNull(SenmlUnits.unitFor("col_0"))
        assertNull(SenmlUnits.unitFor("ax"))
    }
}
