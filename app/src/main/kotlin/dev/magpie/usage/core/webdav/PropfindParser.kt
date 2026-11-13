package dev.magpie.usage.core.webdav

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a 207 Multi-Status PROPFIND body with namespaces intact, refusing
 * anything that reads like an HTML login page instead of XML data.
 *
 * Reference: yetone/magpie @ 6acf5ee5, internal/davsync/usage.go dav.list —
 * the version is the ETag when present, else lastmodified + length.
 */
object PropfindParser {

    class Bad(message: String) : Exception(message)

    fun parse(body: ByteArray): List<DavEntry> {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            } catch (_: Exception) {
            }
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
            }
        }
        val doc = try {
            dbf.newDocumentBuilder().parse(ByteArrayInputStream(body))
        } catch (e: Exception) {
            throw Bad("PROPFIND 响应不是有效的 XML（可能是登录页或错误页）")
        }
        // Only a DAV multistatus body answers a listing. A well-formed HTML
        // or error document that happens to parse would otherwise read as
        // "the folder is empty" and leave the page looking synced.
        val root = doc.documentElement
        if (root == null || root.localName?.lowercase() != "multistatus") {
            throw Bad("PROPFIND 响应不是 WebDAV 的 multistatus（可能是登录页或错误页）")
        }
        val out = ArrayList<DavEntry>()
        val responses = doc.getElementsByTagNameNS("*", "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = textOf(response, "href") ?: continue
            val name = fileNameOf(href) ?: continue
            // Each propstat carries its own status; only a 200-level one's
            // properties count.
            val propstats = childrenOfNS(response, "propstat")
            var etag: String? = null
            var modified: String? = null
            var length: String? = null
            var isDir = false
            for (propstat in propstats) {
                val status = textOf(propstat, "status") ?: ""
                if (!status.contains(" 2")) continue // e.g. "HTTP/1.1 200 OK"
                val prop = childOfNS(propstat, "prop") ?: continue
                if (childOfNS(prop, "resourcetype")?.getElementsByTagNameNS("*", "collection")?.length?.let { it > 0 } == true) {
                    isDir = true
                }
                etag = etag ?: textOf(prop, "getetag")
                modified = modified ?: textOf(prop, "getlastmodified")
                length = length ?: textOf(prop, "getcontentlength")
            }
            val cleanEtag = etag?.trim().takeUnless { it.isNullOrEmpty() }
            val cleanModified = modified?.trim().takeUnless { it.isNullOrEmpty() }
            val cleanLength = length?.trim().takeUnless { it.isNullOrEmpty() }
            // A version the server does not really give is not a version: a
            // constant placeholder ("/") would compare equal on every pass
            // and the file would never be read again (a folder without ETags
            // and without getlastmodified is rare, but it happens).
            val unknown = cleanEtag == null && cleanModified == null
            out.add(
                DavEntry(
                    name = name,
                    version = cleanEtag ?: "${cleanModified ?: ""}/${cleanLength ?: ""}",
                    sizeBytes = cleanLength?.toLongOrNull() ?: 0L,
                    isDirectory = isDir,
                    lastModifiedMs = parseHttpDate(cleanModified),
                    versionUnknown = unknown,
                )
            )
        }
        return out
    }

    /** The last path segment of an href, percent-decoded once. */
    fun fileNameOf(href: String): String? {
        if (href.isBlank()) return null
        val path = href.substringBefore('?').trimEnd('/')
        val segment = path.substringAfterLast('/')
        if (segment.isEmpty()) return null
        return try {
            java.net.URLDecoder.decode(segment, Charsets.UTF_8)
        } catch (_: Exception) {
            segment
        }
    }

    /** RFC 1123 date (getlastmodified) to epoch ms; 0 when unreadable. */
    private fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        return try {
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .parse(value.trim(), java.time.OffsetDateTime::from)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                // Some servers answer with "EEE MMM d HH:mm:ss yyyy" without a zone.
                val fmt = java.time.format.DateTimeFormatter
                    .ofPattern("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US)
                    .withZone(java.time.ZoneOffset.UTC)
                java.time.ZonedDateTime.parse(value.trim(), fmt).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0
            }
        }
    }

    private fun textOf(parent: Element, localName: String): String? {
        val e = childOfNS(parent, localName) ?: return null
        return e.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun childOfNS(parent: Element, localName: String): Element? {
        val list = parent.getElementsByTagNameNS("*", localName)
        return if (list.length > 0) list.item(0) as? Element else null
    }

    private fun childrenOfNS(parent: Element, localName: String): List<Element> {
        val list = parent.getElementsByTagNameNS("*", localName)
        val out = ArrayList<Element>(list.length)
        for (i in 0 until list.length) {
            (list.item(i) as? Element)?.let { out.add(it) }
        }
        return out
    }
}
