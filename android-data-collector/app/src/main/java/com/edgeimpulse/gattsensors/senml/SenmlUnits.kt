package com.edgeimpulse.gattsensors.senml

/**
 * Channel-key → SenML unit registry (RFC 8428 §12.1, plus the RFC 8798
 * secondary unit `hPa`). Keys are the canonical sensor keys used across the
 * app ([com.edgeimpulse.gattsensors.SensorCollector.allSensors] prefixes and
 * the GPS keys from LocationCollector).
 *
 * `uT` (microtesla, what Android's magnetometer reports) is not a registered
 * SenML unit; it's emitted as-is rather than rescaling data to tesla.
 */
object SenmlUnits {

    private val byKey = mapOf(
        "accel"        to "m/s2",
        "linear_accel" to "m/s2",
        "gravity"      to "m/s2",
        "gyro"         to "rad/s",
        "mag"          to "uT",
        "pressure"     to "hPa",
        "light"        to "lx",
        "hr"           to "beat/min",
        "lat"          to "lat",
        "lon"          to "lon",
        "alt"          to "m",
        "speed"        to "m/s",
    )

    private val axisSuffix = Regex("_\\d+$")

    /**
     * Unit for a channel key, e.g. `"accel_2"` → `"m/s2"`. One trailing
     * `_<digits>` axis suffix is stripped, then the remainder must match a
     * registry key exactly (so `linear_accel_0` can never collide with
     * `accel`). Unknown keys (`rotation_0`, `prox_0`, `col_0`, …) → null,
     * which callers turn into an absent `u` field.
     */
    fun unitFor(channelKey: String): String? =
        byKey[channelKey.replace(axisSuffix, "")]
}
