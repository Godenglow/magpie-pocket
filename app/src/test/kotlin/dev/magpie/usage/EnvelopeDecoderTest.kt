package dev.magpie.usage

import dev.magpie.usage.core.crypto.MagpieEnvelopeDecoder
import dev.magpie.usage.core.pack.Gzip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The decoder is checked against Python-sealed fixtures (an independent
 * implementation), never against its own encryption.
 */
class EnvelopeDecoderTest {

    private val file1 = Fixtures.byName("0123456789abcdef0123456789abcdef-2026-03-31.magpie-usage")

    @Test
    fun `opens a python-sealed envelope`() {
        val sealed = Base64.getDecoder().decode(file1.sealedBase64)
        val plain = MagpieEnvelopeDecoder.decode(sealed, Fixtures.passphrase.toCharArray())
        // The plain side is gzip of the day pack JSON.
        val unzipped = Gzip.gunzip(plain)
        val text = unzipped.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"computer\":\"0123456789abcdef0123456789abcdef\""))
        assertTrue(text.contains("\"day\":\"2026-03-31\""))
        assertTrue(text.contains("\"calls\""))
    }

    @Test
    fun `wrong passphrase fails authentication`() {
        val sealed = Base64.getDecoder().decode(Fixtures.byName("0123456789abcdef0123456789abcdef-2026-04-02.magpie-usage").sealedBase64)
        try {
            MagpieEnvelopeDecoder.decode(sealed, Fixtures.passphrase.toCharArray())
            throw AssertionError("应当认证失败")
        } catch (e: MagpieEnvelopeDecoder.DecodeError.BadPassphrase) {
            assertEquals("同步口令不匹配或文件损坏", e.message)
        }
    }

    @Test
    fun `corrupt ciphertext fails authentication`() {
        // Flip the first character of the data field's Base64 inside the
        // envelope JSON: the JSON stays valid, the ciphertext does not
        // authenticate.
        val json = String(Base64.getDecoder().decode(file1.sealedBase64), Charsets.UTF_8)
        val marker = "\"data\":\""
        val start = json.indexOf(marker) + marker.length
        val c = json[start]
        val replacement = if (c != 'A') 'A' else 'B'
        val corrupted = json.substring(0, start) + replacement + json.substring(start + 1)
        try {
            MagpieEnvelopeDecoder.decode(corrupted.toByteArray(Charsets.UTF_8), Fixtures.passphrase.toCharArray())
            throw AssertionError("应当认证失败")
        } catch (_: MagpieEnvelopeDecoder.DecodeError.BadPassphrase) {
        }
    }

    @Test
    fun `unknown format or kdf or version is unsupported`() {
        val sealed = Base64.getDecoder().decode(file1.sealedBase64)
        val text = sealed.toString(Charsets.ISO_8859_1)

        val badFormat = text.replace("\"format\":\"magpie-data\"", "\"format\":\"something-else\"").toByteArray(Charsets.ISO_8859_1)
        assertUnsupported(badFormat)

        val badKdf = text.replace("\"kdf\":\"pbkdf2-sha256\"", "\"kdf\":\"argon2\"").toByteArray(Charsets.ISO_8859_1)
        assertUnsupported(badKdf)

        val badVersion = text.replace("\"version\":1", "\"version\":2").toByteArray(Charsets.ISO_8859_1)
        assertUnsupported(badVersion)

        val notJson = "<html>login page</html>".toByteArray()
        assertUnsupported(notJson)
    }

    private fun assertUnsupported(sealed: ByteArray) {
        try {
            MagpieEnvelopeDecoder.decode(sealed, Fixtures.passphrase.toCharArray())
            throw AssertionError("应当不受支持")
        } catch (e: MagpieEnvelopeDecoder.DecodeError.Unsupported) {
            // expected
        }
    }

    @Test
    fun `iterations out of bounds is unsupported`() {
        val sealed = Base64.getDecoder().decode(file1.sealedBase64).toString(Charsets.ISO_8859_1)
        val low = sealed.replace("\"iterations\":600000", "\"iterations\":1000").toByteArray(Charsets.ISO_8859_1)
        assertUnsupported(low)
        val high = sealed.replace("\"iterations\":600000", "\"iterations\":20000000").toByteArray(Charsets.ISO_8859_1)
        assertUnsupported(high)
    }

    @Test
    fun `header matches the Go AAD byte for byte`() {
        val salt = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        val aad = MagpieEnvelopeDecoder.header("magpie-data", 1, "pbkdf2-sha256", 600000, salt)
        val expected = "magpie-data/1/pbkdf2-sha256/600000/000102030405060708090a0b0c0d0e0f"
        assertEquals(expected, aad.toString(Charsets.UTF_8))
    }

    @Test
    fun `cross-checks against a manual AES-GCM decryption`() {
        // Derive the key with the production KDF, decrypt with the plain JCE
        // API: the two paths must agree, guarding against parameter slips.
        val salt = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODw==")
        val nonce = Base64.getDecoder().decode("AAECAwQFBgcICQoL")
        val key = MagpieEnvelopeDecoder.deriveKey(Fixtures.passphrase.toCharArray(), salt, 600000)
        val aad = MagpieEnvelopeDecoder.header("magpie-data", 1, "pbkdf2-sha256", 600000, salt)
        val message = "hello 用量".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(message)
        val envelope = """{"format":"magpie-data","version":1,"kdf":"pbkdf2-sha256","iterations":600000,"salt":"${Base64.getEncoder().encodeToString(salt)}","nonce":"${Base64.getEncoder().encodeToString(nonce)}","data":"${Base64.getEncoder().encodeToString(sealed)}"}"""
        val opened = MagpieEnvelopeDecoder.decode(envelope.toByteArray(), Fixtures.passphrase.toCharArray())
        assertArrayEquals(message, opened)
    }

    @Test
    fun `flat json reader handles spaces and escapes`() {
        val parsed = MagpieEnvelopeDecoder.readFlatJsonObject("""{ "format" : "magpie-data", "note" : "a\"b", "version" : "1" }""")
        assertEquals("magpie-data", parsed!!["format"])
        assertEquals("a\"b", parsed["note"])
        assertEquals(null, MagpieEnvelopeDecoder.readFlatJsonObject("not json"))
    }
}
