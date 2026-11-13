package dev.magpie.usage.testsupport

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only resealer: mirrors the Python fixture generator for scenarios
 * where a test must rewrite a day pack (e.g. snapshot replacement). Never
 * used for real data and never shipped in main sources.
 */
object TestSealer {

    fun seal(plain: ByteArray, passphrase: String, salt: ByteArray, nonce: ByteArray, iterations: Int = 600_000): ByteArray {
        val key = dev.magpie.usage.core.crypto.MagpieEnvelopeDecoder.deriveKey(
            passphrase.toCharArray(), salt, iterations
        )
        val aad = dev.magpie.usage.core.crypto.MagpieEnvelopeDecoder.header(
            "magpie-data", 1, "pbkdf2-sha256", iterations, salt
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        val envelope = """{"format":"magpie-data","version":1,"kdf":"pbkdf2-sha256","iterations":$iterations,""" +
            """"salt":"${Base64.getEncoder().encodeToString(salt)}",""" +
            """"nonce":"${Base64.getEncoder().encodeToString(nonce)}",""" +
            """"data":"${Base64.getEncoder().encodeToString(sealed)}"}"""
        return envelope.toByteArray()
    }

    fun gzip(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
