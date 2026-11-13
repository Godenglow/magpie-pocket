package dev.magpie.usage

import dev.magpie.usage.core.model.CallRow
import dev.magpie.usage.core.pack.Gzip
import dev.magpie.usage.core.pack.PackParser
import dev.magpie.usage.core.pack.UsageFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PackParserTest {

    private val pack by lazy { Fixtures.open(Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")) }

    @Test
    fun `reads the day pack header`() {
        assertEquals("0123456789abcdef0123456789abcdef", pack.computer)
        assertEquals("Demo-PC", pack.name)
        assertEquals("2026-03-31", pack.day)
        assertEquals(9, pack.calls.size)
    }

    @Test
    fun `parses the acceptance row exactly`() {
        val row = pack.calls.first()
        assertEquals(1000L, row.inTok)
        assertEquals(200L, row.outTok)
        assertEquals(3000L, row.cacheRead)
        assertEquals(0L, row.cacheWrite)
        assertEquals(4200L, row.allTokens)
        assertEquals(200, row.status)
        // 2026-03-31T10:00:00+08:00 == 02:00:00Z
        val expected = java.time.OffsetDateTime.parse("2026-03-31T10:00:00+08:00").toInstant().toEpochMilli()
        assertEquals(expected, row.t)
    }

    @Test
    fun `absent cost and explicit zero cost are distinct`() {
        val priced = pack.calls.first()
        assertEquals(0.012, priced.costUsd!!, 1e-12)
        val explicitZero = pack.calls[6]
        assertEquals(0.0, explicitZero.costUsd!!, 1e-12)
        val missing = pack.calls[1]
        assertNull(missing.costUsd)
    }

    @Test
    fun `handed on and rejected flags survive the roundtrip`() {
        assertTrue(pack.calls[2].handedOn)
        assertTrue(pack.calls[2].failed)
        assertTrue(pack.calls[8].rejected)
        assertTrue(pack.calls[8].isRejected)
    }

    @Test
    fun `canceled row is neither failed nor answered`() {
        val canceled = pack.calls[4]
        assertTrue(canceled.canceled)
        assertFalse(canceled.failed)
        assertFalse(canceled.answered)
    }

    @Test
    fun `cache unknown row is excluded from the valid denominator`() {
        val unknown = pack.calls[1]
        assertTrue(unknown.cacheUnknown)
        // Upstream cacheUnknownIn = in + cache_read + cache_write (output aside).
        assertEquals(5000L, unknown.cacheUnknownIn)
    }

    @Test
    fun `kiro without the flag and without cache still counts as unknown`() {
        val row = CallRow(
            fileName = "f", ordInFile = 0, computer = "c", day = "d", t = 0,
            agent = "a", provider = "kiro", model = "m", requested = "", served = "",
            inTok = 100, outTok = 5, cacheRead = 0, cacheWrite = 0, cacheWrite1h = 0,
            cacheUnknown = false, reasoning = 0, effort = "", ms = 0, ttftMs = 0, firstTextMs = 0,
            status = 200, err = "", errType = "", rid = "", session = "", source = "",
            tries = 0, handedOn = false, rejected = false, costUsd = null, credits = null,
            upBytes = 0, downBytes = 0, raw = "",
        )
        assertTrue(row.cacheNotCounted)
        assertEquals(100L, row.cacheUnknownIn)
    }

    @Test
    fun `unix-epoch timestamps are tolerated`() {
        val json = """{"computer":"0123456789abcdef","name":"x","day":"2026-04-01","calls":[{"t":"2026-04-01T00:00:00Z","in":1,"out":2}]}"""
        val p = PackParser.parse(json.toByteArray(), "n.magpie-usage")
        assertEquals(1, p.calls.size)
    }

    @Test
    fun `file name rules match upstream`() {
        val good = "0123456789abcdef-2026-04-01.magpie-usage"
        val parsed = UsageFileName.parse(good)!!
        assertEquals("0123456789abcdef", parsed.computerId)
        assertEquals("2026-04-01", parsed.day)
        assertEquals(good, UsageFileName.build(parsed.computerId, parsed.day))

        // The trailing date locates the id; a non-hex id is rejected even
        // though the shape looks right (upstream validID).
        val hyphens = "abc123de-45-66-2026-04-01.magpie-usage"
        assertNull(UsageFileName.parse(hyphens))

        assertNull(UsageFileName.parse("day-only.magpie-usage"))
        assertNull(UsageFileName.parse("0123456789abcdef-2026-13-99.magpie-usage"))
        assertNull(UsageFileName.parse("01234567-2026-04-01.json"))
    }

    @Test
    fun `oversized gunzip is refused`() {
        val huge = java.util.zip.GZIPOutputStream(java.io.ByteArrayOutputStream()).let { gz ->
            val bytes = ByteArray(3 shl 20)
            val out = java.io.ByteArrayOutputStream()
            val gzos = java.util.zip.GZIPOutputStream(out)
            repeat(100) { gzos.write(bytes) }
            gzos.close()
            out.toByteArray()
        }
        try {
            Gzip.gunzip(huge, limitBytes = 10 shl 20)
            throw AssertionError("应当超过解压上限")
        } catch (_: Gzip.TooLarge) {
        }
    }

    @Test
    fun `non-gzip plain text is refused clearly`() {
        try {
            Gzip.gunzip("plain text".toByteArray())
            throw AssertionError("应当不是 gzip")
        } catch (_: Gzip.NotGzip) {
        }
    }
}
