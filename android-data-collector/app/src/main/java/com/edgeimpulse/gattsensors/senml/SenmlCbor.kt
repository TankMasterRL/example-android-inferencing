package com.edgeimpulse.gattsensors.senml

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.CborException
import co.nstant.`in`.cbor.model.AbstractFloat
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.DoublePrecisionFloat
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.SimpleValueType
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.Number as CborNumber

/**
 * SenML CBOR representation (RFC 8428 §6, `application/senml+cbor`): the same
 * records as the JSON form, but each pack is a CBOR array of maps keyed by the
 * RFC's integer labels. A `.senmlc` log file is a CBOR *sequence* — one
 * self-delimiting pack per sample appended back to back — the binary analog of
 * the line-delimited `.senml` layout (append-only; a crash truncates at most
 * the final pack). CBOR is an encoding layer only: [SenmlRecord] and
 * [Senml.resolve] are shared with the JSON path.
 */
object SenmlCbor {

    // RFC 8428 §6 integer map labels. bver(-1), bs(-6), s(5), ut(7) and
    // vd(8) are tolerated on decode but ignored, mirroring how the JSON
    // parser skips unknown members.
    private const val BN = -2L
    private const val BT = -3L
    private const val BU = -4L
    private const val BV = -5L
    private const val N = 0L
    private const val U = 1L
    private const val V = 2L
    private const val VS = 3L
    private const val VB = 4L
    private const val T = 6L

    /**
     * Encode one pack as a CBOR array of maps. Records carrying a non-finite
     * number are dropped, same rule as [Senml.toJson].
     */
    fun encodePack(records: List<SenmlRecord>): ByteArray {
        val pack = CborArray()
        records.filter {
            it.bt?.isFinite() != false && it.bv?.isFinite() != false &&
            it.t?.isFinite() != false && it.v?.isFinite() != false
        }.forEach { r ->
            val m = CborMap()
            r.bn?.let { m.put(key(BN), UnicodeString(it)) }
            r.bt?.let { m.put(key(BT), DoublePrecisionFloat(it)) }
            r.bu?.let { m.put(key(BU), UnicodeString(it)) }
            r.bv?.let { m.put(key(BV), DoublePrecisionFloat(it)) }
            r.n?.let  { m.put(key(N),  UnicodeString(it)) }
            r.u?.let  { m.put(key(U),  UnicodeString(it)) }
            r.v?.let  { m.put(key(V),  DoublePrecisionFloat(it)) }
            r.vs?.let { m.put(key(VS), UnicodeString(it)) }
            r.vb?.let {
                m.put(key(VB), SimpleValue(if (it) SimpleValueType.TRUE else SimpleValueType.FALSE))
            }
            r.t?.let  { m.put(key(T),  DoublePrecisionFloat(it)) }
            pack.add(m)
        }
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(pack)
        return out.toByteArray()
    }

    /**
     * Lazily decode a CBOR sequence of packs. A truncated or corrupt tail
     * (e.g. from a crash mid-write) ends the sequence after the last complete
     * pack. A bare map is tolerated as a single-record pack, mirroring the
     * JSON parser's bare-object handling; other top-level items are skipped.
     */
    fun decodeAllPacks(input: InputStream): Sequence<List<SenmlRecord>> = sequence {
        val decoder = CborDecoder(input)
        while (true) {
            val item: DataItem = try {
                decoder.decodeNext() ?: break
            } catch (e: CborException) {
                break
            }
            when (item) {
                is CborArray -> yield(item.dataItems.filterIsInstance<CborMap>().map { recordFrom(it) })
                is CborMap   -> yield(listOf(recordFrom(item)))
                else         -> {}
            }
        }
    }

    private fun key(label: Long): DataItem =
        if (label < 0) NegativeInteger(label) else UnsignedInteger(label)

    private fun recordFrom(m: CborMap): SenmlRecord {
        var bn: String? = null; var bt: Double? = null
        var bu: String? = null; var bv: Double? = null
        var n: String? = null;  var u: String? = null
        var t: Double? = null;  var v: Double? = null
        var vs: String? = null; var vb: Boolean? = null
        for (keyItem in m.keys) {
            val label = (keyItem as? CborNumber)?.value?.toLong() ?: continue
            val value = m.get(keyItem)
            when (label) {
                BN -> bn = value.asString()
                BT -> bt = value.asDouble()
                BU -> bu = value.asString()
                BV -> bv = value.asDouble()
                N  -> n  = value.asString()
                U  -> u  = value.asString()
                V  -> v  = value.asDouble()
                VS -> vs = value.asString()
                VB -> vb = value.asBoolean()
                T  -> t  = value.asDouble()
            }
        }
        return SenmlRecord(bn, bt, bu, bv, n, u, t, v, vs, vb)
    }

    private fun DataItem?.asString(): String? = (this as? UnicodeString)?.string

    private fun DataItem?.asDouble(): Double? = when (this) {
        is DoublePrecisionFloat -> value
        is AbstractFloat        -> value.toDouble()
        is CborNumber           -> value.toDouble()
        else                    -> null
    }

    private fun DataItem?.asBoolean(): Boolean? =
        when ((this as? SimpleValue)?.simpleValueType) {
            SimpleValueType.TRUE  -> true
            SimpleValueType.FALSE -> false
            else                  -> null
        }
}
