package dev.magpie.usage.core.pack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** Decompression limits, mirroring upstream's 64 MiB download / 256 MiB plain. */
object Limits {
    const val DOWNLOAD_BYTES = 64L shl 20
    const val PLAIN_BYTES = 256L shl 20
}

/**
 * Gunzip with a hard output cap: the plain side of a downloaded file is never
 * trusted, so the stream is cut at [Limits.PLAIN_BYTES] instead of being read
 * into memory whole.
 */
object Gzip {

    class TooLarge(val limitBytes: Long) : IOException("解压后超过 ${limitBytes / (1 shl 20)} MiB 上限")

    class NotGzip(message: String) : IOException(message)

    fun gunzip(data: ByteArray, limitBytes: Long = Limits.PLAIN_BYTES): ByteArray {
        val input = ByteArrayInputStream(data)
        val out = ByteArrayOutputStream(maxOf(64, minOf(data.size * 4, 1 shl 22)))
        val gz = try {
            GZIPInputStream(input, 8192)
        } catch (e: ZipException) {
            throw NotGzip("不是 gzip 数据: ${e.message}")
        }
        gz.use { stream ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = try {
                    stream.read(buf)
                } catch (e: IOException) {
                    throw NotGzip("gzip 数据无法读取: ${e.message}")
                }
                if (n < 0) break
                total += n
                if (total > limitBytes) throw TooLarge(limitBytes)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
