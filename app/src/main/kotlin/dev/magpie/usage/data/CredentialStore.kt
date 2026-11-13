package dev.magpie.usage.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The WebDAV app password and the sync passphrase, sealed with an
 * AndroidKeyStore AES key: the ciphertext sits in a private preferences file,
 * the key material never leaves the keystore. Values stay out of backups
 * (allowBackup=false) and out of logs.
 */
class CredentialStore(context: Context) : Credentials {

    private val prefs = context.getSharedPreferences("magpie.cred.v1", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "magpie_usage_cred"
        private const val PREF_PREFIX = "enc:"
    }

    override fun save(key: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = PREF_PREFIX.toByteArray(Charsets.ISO_8859_1) + iv.size.let { byteArrayOf(it.toByte()) } + iv + sealed
        prefs.edit().putString(key, String(blob, Charsets.ISO_8859_1)).apply()
    }

    override fun load(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (!raw.startsWith(PREF_PREFIX)) return null
        val blob = raw.toByteArray(Charsets.ISO_8859_1)
        val headerLen = PREF_PREFIX.length
        if (blob.size < headerLen + 2) return null
        val ivLen = blob[headerLen].toInt() and 0xff
        if (blob.size < headerLen + 1 + ivLen + 16) return null
        val iv = blob.copyOfRange(headerLen + 1, headerLen + 1 + ivLen)
        val sealed = blob.copyOfRange(headerLen + 1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return try {
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
