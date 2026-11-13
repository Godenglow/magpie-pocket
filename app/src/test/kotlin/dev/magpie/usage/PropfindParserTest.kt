package dev.magpie.usage

import dev.magpie.usage.core.webdav.PropfindParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PropfindParserTest {

    @Test
    fun `parses a namespaced multistatus with collections`() {
        val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:ns0="urn:schemas-microsoft-com:">
          <D:response>
            <D:href>/dav/magpie/usage/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/magpie/usage/0123abcd-2026-11-12.magpie-usage</D:href>
            <D:propstat>
              <D:prop>
                <D:getetag>"abc123"</D:getetag>
                <D:getlastmodified>Thu, 12 Nov 2026 15:30:00 GMT</D:getlastmodified>
                <D:getcontentlength>2048</D:getcontentlength>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/magpie/usage/fedc9876-2026-11-11.magpie-usage</D:href>
            <D:propstat>
              <D:prop>
                <D:getlastmodified>Wed, 11 Nov 2026 08:00:00 GMT</D:getlastmodified>
                <D:getcontentlength>1024</D:getcontentlength>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
        """.trimIndent().toByteArray()

        val entries = PropfindParser.parse(xml)
        assertEquals(3, entries.size)
        val dir = entries.first { it.isDirectory }
        assertEquals("/", dir.version) // no props beyond resourcetype; dirs are filtered by the caller

        val withEtag = entries.first { it.name == "0123abcd-2026-11-12.magpie-usage" }
        assertEquals("\"abc123\"", withEtag.version)
        assertEquals(2048L, withEtag.sizeBytes)
        assertTrue(withEtag.lastModifiedMs > 0)

        val withoutEtag = entries.first { it.name == "fedc9876-2026-11-11.magpie-usage" }
        assertEquals("Wed, 11 Nov 2026 08:00:00 GMT/1024", withoutEtag.version)
    }

    @Test
    fun `a failed propstat contributes nothing`() {
        val xml = """
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/magpie/usage/0123abcd-2026-11-12.magpie-usage</D:href>
            <D:propstat>
              <D:prop><D:getetag>"x"</D:getetag></D:prop>
              <D:status>HTTP/1.1 404 Not Found</D:status>
            </D:propstat>
            <D:propstat>
              <D:prop><D:getcontentlength>7</D:getcontentlength></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
        """.trimIndent().toByteArray()
        val entries = PropfindParser.parse(xml)
        assertEquals(1, entries.size)
        // ETag was in the failed propstat; only the 200-level length counts.
        assertEquals("/7", entries[0].version)
        assertEquals(7L, entries[0].sizeBytes)
    }

    @Test
    fun `an html login page is refused loudly`() {
        // Unclosed tags: a real-world HTML page, not XML.
        assertThrows(PropfindParser.Bad::class.java) {
            PropfindParser.parse("<html><body><br>please sign in</body>".toByteArray())
        }
    }

    @Test
    fun `well-formed html is refused too, not read as an empty folder`() {
        // A page that happens to be valid XML would otherwise parse to zero
        // responses and look like "the folder has no files".
        assertThrows(PropfindParser.Bad::class.java) {
            PropfindParser.parse(
                """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Sign in</p></body></html>"""
                    .toByteArray(),
            )
        }
    }

    @Test
    fun `a file with no etag and no lastmodified is marked version-unknown`() {
        val xml = """
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/magpie/usage/none-2026-11-12.magpie-usage</D:href>
            <D:propstat>
              <D:prop><D:getcontentlength>10</D:getcontentlength></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/magpie/usage/dated-2026-11-12.magpie-usage</D:href>
            <D:propstat>
              <D:prop><D:getlastmodified>Thu, 12 Nov 2026 15:30:00 GMT</D:getlastmodified></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
        """.trimIndent().toByteArray()
        val entries = PropfindParser.parse(xml)
        assertTrue(entries.first { it.name.startsWith("none-") }.versionUnknown)
        // A date alone still changes when the file does.
        assertTrue(!entries.first { it.name.startsWith("dated-") }.versionUnknown)
    }

    @Test
    fun `href names are percent-decoded once`() {
        assertEquals("我的 文件-2026-01-01.magpie-usage", PropfindParser.fileNameOf("/dav/%E6%88%91%E7%9A%84%20%E6%96%87%E4%BB%B6-2026-01-01.magpie-usage"))
        assertEquals("a.magpie-usage", PropfindParser.fileNameOf("https://dav.example.com/dav/a.magpie-usage"))
    }
}
