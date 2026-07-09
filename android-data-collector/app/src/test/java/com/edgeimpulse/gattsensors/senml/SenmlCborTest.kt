package com.edgeimpulse.gattsensors.senml

import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.DoublePrecisionFloat
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.Map as CborMap

/**
 * Pure-JVM tests for the SenML CBOR representation (RFC 8428 §6): integer
 * map labels on the wire, CBOR-sequence file layout, and equivalence with
 * the JSON-lines conversion path.
 */
class SenmlCborTest {

    private fun decode(bytes: ByteArray): List<List<SenmlRecord>> =
        SenmlCbor.decodeAllPacks(ByteArrayInputStream(bytes)).toList()

    @Test
    fun `round trip preserves all record fields`() {
        val pack = listOf(
            SenmlRecord(bn = "dev:", bt = 100.5, bu = "Cel", n = "temp", v = 23.5),
            SenmlRecord(n = "label", vs = "walking", t = 0.5),
            SenmlRecord(n = "active", vb = true, bv = 1.0),
        )
        val packs = decode(SenmlCbor.encodePack(pack))
        assertEquals(1, packs.size)
        assertEquals(pack, packs[0])
    }

    @Test
    fun `rfc integer labels are understood on the wire`() {
        // Hand-assemble a pack with raw cbor-java builders using the RFC 8428
        // §6 labels; the decoder must map them to the right fields.
        val m = CborMap()
        m.put(NegativeInteger(-2), UnicodeString("openmv:"))
        m.put(NegativeInteger(-3), DoublePrecisionFloat(42.0))
        m.put(UnsignedInteger(0), UnicodeString("ax"))
        m.put(UnsignedInteger(1), UnicodeString("m/s2"))
        m.put(UnsignedInteger(2), DoublePrecisionFloat(9.81))
        m.put(UnsignedInteger(6), DoublePrecisionFloat(0.25))
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(CborArray().add(m))

        val record = decode(out.toByteArray()).single().single()
        assertEquals(
            SenmlRecord(bn = "openmv:", bt = 42.0, n = "ax", u = "m/s2", t = 0.25, v = 9.81),
            record,
        )
    }

    @Test
    fun `multi pack sequence decodes in order`() {
        val out = ByteArrayOutputStream()
        out.write(SenmlCbor.encodePack(listOf(SenmlRecord(n = "a", v = 1.0))))
        out.write(SenmlCbor.encodePack(listOf(SenmlRecord(n = "a", v = 2.0))))
        out.write(SenmlCbor.encodePack(listOf(SenmlRecord(n = "a", v = 3.0))))
        val packs = decode(out.toByteArray())
        assertEquals(listOf(1.0, 2.0, 3.0), packs.map { it.single().v })
    }

    @Test
    fun `truncated final item yields only complete packs`() {
        val out = ByteArrayOutputStream()
        out.write(SenmlCbor.encodePack(listOf(SenmlRecord(n = "a", v = 1.0))))
        val full = SenmlCbor.encodePack(listOf(SenmlRecord(n = "a", v = 2.0)))
        out.write(full, 0, full.size - 3)  // crash mid-write of the second pack
        val packs = decode(out.toByteArray())
        assertEquals(1, packs.size)
        assertEquals(1.0, packs[0].single().v)
    }

    @Test
    fun `non finite values are skipped on encode`() {
        val bytes = SenmlCbor.encodePack(listOf(
            SenmlRecord(n = "bad", v = Double.NaN),
            SenmlRecord(n = "worse", t = Double.POSITIVE_INFINITY, v = 2.0),
            SenmlRecord(n = "ok", v = 1.0),
        ))
        assertEquals(listOf("ok"), decode(bytes).single().map { it.n })
    }

    @Test
    fun `unknown labels are ignored on decode`() {
        val m = CborMap()
        m.put(NegativeInteger(-1), UnsignedInteger(10))        // bver
        m.put(NegativeInteger(-6), DoublePrecisionFloat(5.0))  // bs
        m.put(UnsignedInteger(5), DoublePrecisionFloat(2.0))   // s
        m.put(UnsignedInteger(7), DoublePrecisionFloat(1.0))   // ut
        m.put(UnsignedInteger(0), UnicodeString("a"))
        m.put(UnsignedInteger(2), DoublePrecisionFloat(1.5))
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(CborArray().add(m))
        assertEquals(
            SenmlRecord(n = "a", v = 1.5),
            decode(out.toByteArray()).single().single(),
        )
    }

    @Test
    fun `bare map is wrapped as a single record pack`() {
        val m = CborMap()
        m.put(UnsignedInteger(0), UnicodeString("temp"))
        m.put(UnsignedInteger(2), DoublePrecisionFloat(23.0))
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(m)
        assertEquals("temp", decode(out.toByteArray()).single().single().n)
    }

    @Test
    fun `cbor packs convert to the same payload as the json lines path`() {
        val json = listOf(
            """[{"bn":"openmv:","bt":100.00,"n":"ax","v":1.0},{"n":"ay","v":2.0}]""",
            """[{"bt":100.01,"n":"ax","v":3.0},{"n":"ay","v":4.0}]""",
        )
        val fromJson = SenmlIngestion.convert(json.asSequence(), "phone")!!

        val out = ByteArrayOutputStream()
        json.forEach { out.write(SenmlCbor.encodePack(Senml.fromJson(it))) }
        val fromCbor = SenmlIngestion.convertPacks(
            SenmlCbor.decodeAllPacks(ByteArrayInputStream(out.toByteArray())), "phone")!!

        assertEquals(fromJson.payload, fromCbor.payload)
        assertEquals(0, fromCbor.skippedLines)
    }
}
