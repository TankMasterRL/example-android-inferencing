package com.edgeimpulse.gattsensors.senml

import com.edgeimpulse.gattsensors.SensorData
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken

/**
 * One raw SenML record as it appears on the wire (RFC 8428 §4.3). Field names
 * match the JSON labels exactly so plain Gson (de)serialization works without
 * adapters; `null` means "field absent" and Gson omits nulls on write, which
 * is exactly what the RFC requires (SenML forbids null members).
 *
 * Only the fields this app produces or consumes are modelled; unknown members
 * (`s`, `ut`, `vd`, `bver`, …) are ignored by Gson on parse, as §4.4 asks of
 * consumers that don't understand them.
 */
data class SenmlRecord(
    val bn: String? = null,
    val bt: Double? = null,
    val bu: String? = null,
    val bv: Double? = null,
    val n: String? = null,
    val u: String? = null,
    val t: Double? = null,
    val v: Double? = null,
    val vs: String? = null,
    val vb: Boolean? = null,
)

/**
 * A measurement with all base fields applied per RFC 8428 §4.3.
 *
 * [shortName] is the record's own `n` (the channel/column key); [name] is the
 * fully resolved `bn + n`. When a record carries no `n` at all (legal — `bn`
 * alone can name it), [shortName] falls back to the full name.
 */
data class ResolvedSenmlRecord(
    val name: String,
    val shortName: String,
    val unit: String?,
    val value: Double?,
    val stringValue: String? = null,
    val boolValue: Boolean? = null,
    val time: Double,
)

/**
 * Minimal SenML (RFC 8428) JSON writer/parser, modelled on the SenmlPack /
 * SenmlRecord API of the KPN senml library that OpenMV boards ship with.
 * JSON representation only (`application/senml+json`); no CBOR.
 */
object Senml {

    private val gson = Gson()
    private val listType = object : TypeToken<List<SenmlRecord>>() {}.type

    /**
     * Render records to an RFC 8428 JSON array. Records carrying a non-finite
     * number are dropped rather than serialized — JSON has no NaN/Infinity and
     * Gson would throw.
     */
    fun toJson(records: List<SenmlRecord>): String {
        val safe = records.filter {
            it.bt?.isFinite() != false && it.bv?.isFinite() != false &&
            it.t?.isFinite() != false && it.v?.isFinite() != false
        }
        return gson.toJson(safe, listType)
    }

    /**
     * Parse a SenML JSON array into raw records. A single bare object is
     * tolerated by wrapping it in an array (devices normally send arrays, per
     * the RFC). Throws [JsonParseException] on malformed input — callers catch.
     */
    fun fromJson(json: String): List<SenmlRecord> {
        val trimmed = json.trim()
        val array = if (trimmed.startsWith("{")) "[$trimmed]" else trimmed
        val parsed: List<SenmlRecord?>? = gson.fromJson(array, listType)
        return parsed?.filterNotNull() ?: emptyList()
    }

    /**
     * Apply bn/bt/bu/bv base propagation in document order (RFC 8428 §4.3): a
     * base field applies to its own record and every subsequent one until
     * overridden. Records that end up with no name, a non-finite time or
     * value, or no value at all (no `v`/`vs`/`vb`) are dropped.
     */
    fun resolve(records: List<SenmlRecord>): List<ResolvedSenmlRecord> {
        var bn = ""
        var bt = 0.0
        var bu: String? = null
        var bv = 0.0
        val out = mutableListOf<ResolvedSenmlRecord>()
        for (r in records) {
            r.bn?.let { bn = it }
            r.bt?.let { bt = it }
            r.bu?.let { bu = it }
            r.bv?.let { bv = it }
            val name = bn + (r.n ?: "")
            if (name.isEmpty()) continue
            val time = bt + (r.t ?: 0.0)
            if (!time.isFinite()) continue
            val value = r.v?.plus(bv)
            if (value != null && !value.isFinite()) continue
            if (value == null && r.vs == null && r.vb == null) continue
            out.add(ResolvedSenmlRecord(
                name        = name,
                shortName   = r.n?.takeIf { it.isNotEmpty() } ?: name,
                unit        = r.u ?: bu,
                value       = value,
                stringValue = r.vs,
                boolValue   = r.vb,
                time        = time,
            ))
        }
        return out
    }

    /**
     * Build one SenML pack from a batch of samples: `bn` = "<deviceName>:",
     * `bt` = first sample's epoch time in seconds, per-record `t` = offset
     * from `bt` in fractional seconds (omitted when zero), `n` = channel key
     * in [SensorData.values] insertion order, `u` from [SenmlUnits] (omitted
     * when unknown). Non-finite values are skipped; an empty batch (or one
     * with no finite values) yields an empty pack.
     */
    fun packFromSensorData(deviceName: String, samples: List<SensorData>): List<SenmlRecord> {
        val first = samples.firstOrNull() ?: return emptyList()
        val records = mutableListOf<SenmlRecord>()
        var basesPending = true
        for (sample in samples) {
            val t = (sample.timestamp - first.timestamp) / 1000.0
            for ((key, value) in sample.values) {
                if (!value.isFinite()) continue
                records.add(SenmlRecord(
                    bn = if (basesPending) "$deviceName:" else null,
                    bt = if (basesPending) first.timestamp / 1000.0 else null,
                    n  = key,
                    u  = SenmlUnits.unitFor(key),
                    t  = if (t == 0.0) null else t,
                    // Round-trip through the float's shortest decimal form so
                    // 0.1f serializes as 0.1, not 0.10000000149011612.
                    v  = value.toString().toDouble(),
                ))
                basesPending = false
            }
        }
        return records
    }
}
