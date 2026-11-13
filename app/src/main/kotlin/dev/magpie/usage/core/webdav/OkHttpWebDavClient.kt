package dev.magpie.usage.core.webdav

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed WebDAV: basic auth, redirects followed only inside the same
 * host (credentials never leave it), downloads capped, everything read-only.
 */
class OkHttpWebDavClient(
    baseUrl: String,
    username: String,
    password: String,
) : WebDavClient {

    companion object {
        const val DOWNLOAD_LIMIT = dev.magpie.usage.core.pack.Limits.DOWNLOAD_BYTES
        private const val MAX_REDIRECTS = 3
    }

    private val credentials = Credentials.basic(username, password)
    private val base: URI
    private val baseSegments: List<String>

    init {
        val uri = URI(baseUrl.trim())
        require(uri.scheme == "https" || uri.scheme == "http") { "服务器地址必须是 http(s) URL" }
        require(!uri.host.isNullOrBlank()) { "服务器地址缺少主机名" }
        base = uri
        // uri.path is decoded; urlOf encodes each segment exactly once. Using
        // rawPath here encoded an already-encoded path again: "/my%20folder/"
        // became "/my%2520folder/" and a self-hosted server 404ed.
        baseSegments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    override fun list(dirPath: String): List<DavEntry> {
        val request = baseRequest(urlOf(dirPath, null))
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .header("Depth", "1")
            .header("Content-Type", "application/xml")
            .build()
        return execute(request) { res ->
            when {
                res.code == 404 || res.code == 409 || res.code == 410 -> emptyList()
                else -> {
                    val body = res.body?.bytes() ?: throw DavError.BadResponse("PROPFIND 无响应体")
                    PropfindParser.parse(body).filterNot { it.isDirectory }
                }
            }
        }
    }

    override fun get(dirPath: String, fileName: String): ByteArray {
        val request = baseRequest(urlOf(dirPath, fileName)).get().build()
        return execute(request) { res ->
            when {
                res.code == 404 -> throw DavError.NotFound("404")
                else -> {
                    val body = res.body ?: throw DavError.BadResponse("GET 无响应体")
                    val contentLength = body.contentLength()
                    if (contentLength > DOWNLOAD_LIMIT) throw DavError.TooLarge(DOWNLOAD_LIMIT)
                    readCapped(body.byteStream(), DOWNLOAD_LIMIT)
                }
            }
        }
    }

    private fun <T> execute(request: Request, onSuccess: (Response) -> T): T {
        var current = request
        var depth = 0
        while (true) {
            val res = try {
                http.newCall(current).execute()
            } catch (e: IOException) {
                throw classify(e)
            }
            res.use {
                if (it.code in 301..308) {
                    depth++
                    if (depth > MAX_REDIRECTS) throw DavError.BadResponse("重定向次数过多")
                    val location = it.header("Location")
                        ?: throw DavError.BadResponse("重定向缺少 Location")
                    val next = URI(current.url.toString()).resolve(location)
                    if (next.host != base.host) {
                        throw DavError.RedirectedToOtherHost(next.host ?: "")
                    }
                    current = rebuildRequest(current, next.toURL().toString())
                    return@use
                }
                when {
                    it.code == 401 || it.code == 403 -> throw DavError.Auth("${it.code}")
                    it.code == 429 -> throw DavError.RateLimited(it.header("Retry-After")?.toLongOrNull())
                    it.code in 500..599 -> throw DavError.Server("${it.code}")
                    else -> return onSuccess(it)
                }
            }
        }
    }

    private fun rebuildRequest(previous: Request, url: String): Request {
        val builder = Request.Builder().url(url)
        for ((name, value) in previous.headers) {
            if (!name.equals("Authorization", true) && !name.equals("Host", true)) {
                builder.header(name, value)
            }
        }
        builder.header("Authorization", credentials)
        previous.body?.let { builder.method(previous.method, it) }
            ?: builder.method(previous.method, null)
        return builder.build()
    }

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", credentials)

    private fun readCapped(stream: java.io.InputStream, cap: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw DavError.TooLarge(cap)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Builds the request URL: the base URL's path plus each configured
     * segment percent-encoded once — never the whole URL re-encoded, so
     * Chinese names and spaces survive. Directory requests keep a trailing
     * slash (PROPFIND on a collection).
     */
    internal fun urlOf(dirPath: String, fileName: String?): String {
        val segments = ArrayList<String>(baseSegments)
        for (part in dirPath.split('/')) {
            if (part.isNotEmpty()) segments.add(part)
        }
        val isDirectory = fileName.isNullOrEmpty()
        if (!isDirectory) segments.add(fileName)
        val encodedPath = segments.joinToString("/") { encodeSegment(it) }
        val port = base.port
        val authority = if (port in 1..65535) "${base.host}:$port" else base.host
        val tail = if (isDirectory) "/" else ""
        return "${base.scheme}://$authority/$encodedPath$tail"
    }

    /** RFC 3986 path segment encoding: space stays %20, not +. */
    internal fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("%2F", "/")

    private fun classify(e: IOException): DavError = when (e) {
        is UnknownHostException -> DavError.NoNetwork("无法解析服务器地址")
        is SocketTimeoutException -> DavError.Io("连接超时")
        else -> DavError.Io(e.message ?: "网络不可用")
    }
}
