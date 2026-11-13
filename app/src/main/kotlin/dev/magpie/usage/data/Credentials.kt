package dev.magpie.usage.data

/** Credential storage contract, so sync can be tested without a keystore. */
interface Credentials {
    fun save(key: String, value: String)
    fun load(key: String): String?
    fun clear()
}
