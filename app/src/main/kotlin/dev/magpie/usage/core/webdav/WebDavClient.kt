package dev.magpie.usage.core.webdav

import java.io.IOException

/** Errors a sync can name for the user, without leaking credentials. */
sealed class DavError(message: String) : Exception(message) {
    class NoNetwork(message: String) : DavError(message)
    class Auth(message: String) : DavError("账号或应用密码不正确（HTTP $message）")
    class NotFound(message: String) : DavError("目录不存在（HTTP $message）：检查用量目录及 Magpie 共享用量开关")
    class RateLimited(val retryAfterSeconds: Long?) : DavError("请求过于频繁（HTTP 429），稍后自动重试")
    class Server(message: String) : DavError("服务器错误（HTTP $message）")
    class RedirectedToOtherHost(val host: String) : DavError("服务器重定向到其他主机（$host），已中止")
    class BadResponse(message: String) : DavError("响应无法解析：$message")
    class TooLarge(val bytes: Long) : DavError("文件超过 ${bytes / (1 shl 20)} MiB 下载上限")
    class Io(message: String) : DavError("网络错误：$message")
}

/**
 * One listed usage file, with the version the manifest compares.
 */
data class DavEntry(
    /** File name only, e.g. 0123abcd-2026-11-12.magpie-usage. */
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    /** getlastmodified as epoch ms, when the server said; 0 when not. */
    val lastModifiedMs: Long = 0,
    /**
     * The server gave neither an ETag nor a last-modified/size pair, so
     * [version] is a placeholder that never changes. Such a file must be
     * re-read from time to time instead of being skipped forever.
     */
    val versionUnknown: Boolean = false,
)

/**
 * A read-only WebDAV client for the usage folder. No PUT, DELETE or MKCOL
 * exists here on purpose: the phone never writes to the cloud.
 */
interface WebDavClient {
    /** PROPFIND Depth:1 of [dirPath]; 404/409/410 read as an empty folder. */
    fun list(dirPath: String): List<DavEntry>

    /** GET of [dirPath]/[fileName], capped at the download limit. */
    fun get(dirPath: String, fileName: String): ByteArray
}
