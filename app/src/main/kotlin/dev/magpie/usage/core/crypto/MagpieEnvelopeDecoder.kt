package dev.magpie.usage.core.crypto

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens what Magpie's backup.SealData sealed (the .magpie-usage day packs).
 *
 * Envelope JSON: format/version/kdf/iterations/salt/nonce/data, the last
 * three Base64. Key: PBKDF2-HMAC-SHA256(pass, salt, iterations, 32 bytes).
 * Cipher: AES-256-GCM, 12-byte nonce, 128-bit tag, and the AAD is the
 * envelope's own fields joined with "/" — the salt as lowercase hex:
 *
 *     format + "/" + version + "/" + kdf + "/" + iterations + "/" + saltHex
 *
 * Verified against yetone/magpie @ 6acf5ee5, internal/backup/backup.go
 * (header, deriveKey, gcmOf) and internal/backup/data.go (OpenData bounds).
 */
object MagpieEnvelopeDecoder {

    const val FORMAT = "magpie-data"
    const val VERSION = 1
    const val KDF = "pbkdf2-sha256"

    /** OpenData refuses envelopes outside these bounds. */
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 10_000_000
    private const val MIN_SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    sealed class DecodeError(message: String) : Exception(message) {
        /** Not a magpie envelope at all, or fields we do not implement. */
        class Unsupported(message: String) : DecodeError(message)

        /** Authentication failed: wrong passphrase or a changed file. */
        class BadPassphrase : DecodeError("同步口令不匹配或文件损坏")

        /** Structurally valid but unreadable (bad Base64, lengths…). */
        class Corrupt(message: String) : DecodeError(message)
    }

    fun decode(sealed: ByteArray, passphrase: CharArray): ByteArray {
        val envelope = parseEnvelope(sealed)
        val key = deriveKey(passphrase, envelope.salt, envelope.iterations)
        val aad = header(envelope.format, envelope.version, envelope.kdf, envelope.iterations, envelope.salt)
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding")
        } catch (e: GeneralSecurityException) {
            throw DecodeError.Corrupt("本机不支持 AES-GCM: ${e.message}")
        }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, envelope.nonce))
        // Android's GCM doFinal input is ciphertext || tag; the envelope's
        // data field already is exactly that.
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(envelope.data)
        } catch (e: AEADBadTagException) {
            throw DecodeError.BadPassphrase()
        } catch (e: GeneralSecurityException) {
            throw DecodeError.BadPassphrase()
        }
    }

    /** The AAD string: none of the envelope's fields may change unnoticed. */
    fun header(format: String, version: Int, kdf: String, iterations: Int, salt: ByteArray): ByteArray =
        "$format/$version/$kdf/$iterations/${salt.toLowerHex()}".toByteArray(Charsets.UTF_8)

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (e: GeneralSecurityException) {
            throw DecodeError.Corrupt("密钥派生失败: ${e.message}")
        } finally {
            spec.clearPassword()
        }
    }

    private data class Envelope(
        val format: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val data: ByteArray,
    )

    private fun parseEnvelope(sealed: ByteArray): Envelope {
        // The envelope is read field by field from the JSON text; org.json and
        // kotlinx both would do — this hand reader avoids any dependency and
        // keeps the failure modes of an arbitrary file predictable.
        val text = sealed.toString(Charsets.UTF_8)
        val fields = readFlatJsonObject(text)
        if (fields == null) {
            throw DecodeError.Unsupported("不是 magpie 封装的文件（外层不是 JSON 对象）")
        }
        val format = fields["format"] ?: throw DecodeError.Unsupported("缺少 format 字段")
        if (format != FORMAT) {
            throw DecodeError.Unsupported("不支持的 format: $format")
        }
        val version = fields["version"]?.toIntOrNull()
            ?: throw DecodeError.Unsupported("version 字段无效")
        if (version != VERSION) {
            throw DecodeError.Unsupported("由更新版本 magpie 封装（version=$version）")
        }
        val kdf = fields["kdf"] ?: throw DecodeError.Unsupported("缺少 kdf 字段")
        if (kdf != KDF) {
            throw DecodeError.Unsupported("不支持的 KDF: $kdf")
        }
        val iterations = fields["iterations"]?.toIntOrNull()
            ?: throw DecodeError.Corrupt("iterations 字段无效")
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw DecodeError.Unsupported("iterations 超出范围: $iterations")
        }
        val salt = fields["salt"]?.let { decodeBase64(it, "salt") }
            ?: throw DecodeError.Corrupt("缺少 salt 字段")
        val nonce = fields["nonce"]?.let { decodeBase64(it, "nonce") }
            ?: throw DecodeError.Corrupt("缺少 nonce 字段")
        val data = fields["data"]?.let { decodeBase64(it, "data") }
            ?: throw DecodeError.Corrupt("缺少 data 字段")
        if (nonce.size != NONCE_LEN) {
            throw DecodeError.Unsupported("nonce 长度不为 12 字节")
        }
        if (salt.size < MIN_SALT_LEN) {
            throw DecodeError.Unsupported("salt 短于 16 字节")
        }
        return Envelope(format, version, kdf, iterations, salt, nonce, data)
    }

    /**
     * Reads the envelope's top-level string fields. The envelope has no
     * nested objects, so a flat scan of keys and bare values is enough and
     * keeps a hostile file from surprising the parser. version and iterations
     * are JSON numbers in what Go writes; salt/nonce/data are strings.
     */
    internal fun readFlatJsonObject(text: String): Map<String, String>? {
        val s = text.trimStart()
        if (!s.startsWith("{")) return null
        val out = HashMap<String, String>(8)
        var i = 1
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            if (i < n && s[i] == '}') break
            if (i >= n || s[i] != '"') return null
            val keyStart = ++i
            i = skipJsonString(s, i) ?: return null
            val key = unescape(s.substring(keyStart, i - 1))
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != ':') return null
            i++
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) return null
            if (s[i] == '"') {
                val valueStart = ++i
                i = skipJsonString(s, i) ?: return null
                out[key] = unescape(s.substring(valueStart, i - 1))
            } else {
                // A bare literal (number, true, false, null), up to whitespace
                // or a delimiter. Nested structures are not envelopes.
                val valueStart = i
                while (i < n && s[i] != ',' && s[i] != '}' && !s[i].isWhitespace()) i++
                if (i == valueStart) return null
                out[key] = s.substring(valueStart, i)
            }
            while (i < n && s[i].isWhitespace()) i++
            if (i < n && s[i] == ',') {
                i++
                continue
            }
            if (i < n && s[i] == '}') break
            return null
        }
        return out
    }

    /** Returns the index one past the closing quote, or null when malformed. */
    private fun skipJsonString(s: String, start: Int): Int? {
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '"') return i + 1
            i++
        }
        return null
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        java.util.Base64.getDecoder().decode(value.trim())
    } catch (e: IllegalArgumentException) {
        throw DecodeError.Corrupt("$field 不是有效的 Base64")
    }

    private fun ByteArray.toLowerHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xf])
            sb.append("0123456789abcdef"[b.toInt() and 0xf])
        }
        return sb.toString()
    }
}
