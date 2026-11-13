package dev.magpie.usage

import dev.magpie.usage.domain.Formatters
import dev.magpie.usage.domain.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `token units are 万 and 亿`() {
        Formatters.numberUnit = "cn"
        assertEquals("0", Formatters.tokens(0))
        assertEquals("2081", Formatters.tokens(2081))
        assertEquals("9999", Formatters.tokens(9999))
        assertEquals("1 万", Formatters.tokens(10_000))
        assertEquals("102.6 万", Formatters.tokens(1_026_000))
        assertEquals("1 亿", Formatters.tokens(100_000_000))
        assertEquals("3.1 亿", Formatters.tokens(310_000_000))
        assertEquals("1.2 万", Formatters.tokens(11765))
        // 21 亿+ must render through the Long path.
        assertEquals("26.34 亿", Formatters.tokens(2_634_000_000L))
        assertEquals("-3.1 亿", Formatters.tokens(-310_000_000L))
    }

    @Test
    fun `counts stay plain below 万`() {
        Formatters.numberUnit = "cn"
        assertEquals("2081", Formatters.count(2081))
        assertEquals("2 万", Formatters.count(20_000))
    }

    @Test
    fun `cost formats usd and marks unpriced`() {
        Formatters.currency = "usd"
        assertEquals("$0.012", Formatters.cost(0.012, false))
        assertEquals("$13.50+", Formatters.cost(13.5, true))
        assertEquals("$2137", Formatters.cost(2137.0, false))
        assertEquals("$0", Formatters.cost(0.0, false))
    }

    @Test
    fun `cost can read in yuan at the configured rate`() {
        Formatters.currency = "cny"
        Formatters.exchangeRate = 7.0
        assertEquals("¥94.50", Formatters.cost(13.5, false))
        assertEquals("¥0", Formatters.cost(0.0, false))
        Formatters.currency = "usd"
    }

    @Test
    fun `number unit can switch to K M B`() {
        Formatters.numberUnit = "en"
        assertEquals("1.78B", Formatters.tokens(1_780_000_000))
        assertEquals("180.3M", Formatters.tokens(180_300_000))
        assertEquals("2.1K", Formatters.tokens(2081))
        assertEquals("999", Formatters.tokens(999))
        Formatters.numberUnit = "cn"
        assertEquals("17.8 亿", Formatters.tokens(1_780_000_000))
    }

    @Test
    fun `bytes read like the desktop footer`() {
        // 1911 MB is past 1 GiB, so it reads in GB; the desktop shows both.
        assertEquals("1.9 GB", Formatters.bytes(1_911L * 1024 * 1024))
        assertEquals("900 MB", Formatters.bytes(900L * 1024 * 1024))
        assertEquals("6.0 GB", Formatters.bytes(6L * 1024 * 1024 * 1024))
    }

    @Test
    fun `provider colors are stable per id`() {
        val a = Providers.colorIndex("openai")
        assertEquals(a, Providers.colorIndex("openai"))
        assertNotEquals(
            Providers.palette.toList(),
            Providers.palette.map { Providers.colorIndex("p$it") }.distinct().let { it.size },
        )
    }
}
