package dev.magpie.usage

import dev.magpie.usage.core.webdav.OkHttpWebDavClient
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavUrlTest {

    private fun client(base: String) = OkHttpWebDavClient(base, "user", "pass")

    @Test
    fun `builds urls segment by segment`() {
        val c = client("https://dav.jianguoyun.com/dav/")
        assertEquals(
            "https://dav.jianguoyun.com/dav/magpie/usage/0123abcd-2026-11-12.magpie-usage",
            c.urlOf("/magpie/usage/", "0123abcd-2026-11-12.magpie-usage"),
        )
        // dir without leading slash still works
        assertEquals(
            "https://dav.jianguoyun.com/dav/magpie/usage/",
            c.urlOf("magpie/usage/", null),
        )
    }

    @Test
    fun `chinese and spaces are encoded once, not the whole url`() {
        val c = client("https://dav.jianguoyun.com/dav/")
        assertEquals(
            "https://dav.jianguoyun.com/dav/%E6%88%91%E7%9A%84%20%E7%9B%AE%E5%BD%95/magpie/usage/f.magpie-usage",
            c.urlOf("/我的 目录/magpie/usage/", "f.magpie-usage"),
        )
    }

    @Test
    fun `custom ports and nested bases survive`() {
        val c = client("http://192.168.1.5:5005/home/dav/")
        assertEquals(
            "http://192.168.1.5:5005/home/dav/magpie/usage/",
            c.urlOf("magpie/usage", null),
        )
    }

    @Test
    fun `a base url whose path is already encoded is not encoded twice`() {
        // 自建 WebDAV 常把目录直接写成 %20；rawPath 再编码一次会变成 %2520。
        val c = client("https://dav.example.com/my%20folder/")
        assertEquals(
            "https://dav.example.com/my%20folder/magpie/usage/",
            c.urlOf("magpie/usage/", null),
        )
        assertEquals(
            "https://dav.example.com/my%20folder/magpie/usage/f.magpie-usage",
            c.urlOf("magpie/usage/", "f.magpie-usage"),
        )
    }

    @Test
    fun `a literal percent in the path survives as percent-25`() {
        val c = client("https://dav.example.com/a%2520b/")
        assertEquals(
            "https://dav.example.com/a%2520b/x/y/",
            c.urlOf("x/y/", null),
        )
    }
}
