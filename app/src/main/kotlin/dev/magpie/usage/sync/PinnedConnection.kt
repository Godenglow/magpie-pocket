package dev.magpie.usage.sync

import dev.magpie.usage.data.Credentials

/**
 * The credentials a single operation uses, frozen at its start. A sync pass
 * must not read the store halfway through: saving another connection while
 * it runs would otherwise pair one account's password (or folder) with
 * another account's archive.
 */
class FixedCredentials(
    private val account: String,
    private val appPassword: String,
    private val passphrase: String,
) : Credentials {

    override fun save(key: String, value: String) = Unit // read-only by design

    override fun load(key: String): String? = when (key) {
        CredentialKeys.ACCOUNT -> account
        CredentialKeys.APP_PASSWORD -> appPassword
        CredentialKeys.SYNC_PASSPHRASE -> passphrase
        else -> null
    }

    override fun clear() = Unit
}

/**
 * One connection, frozen for one pass: the server, the account, its
 * password and passphrase, the remote folder and the local archive all come
 * from one read. A pass that started on connection A keeps writing to A's
 * archive even if the user saves connection B meanwhile.
 */
data class PinnedConnection(
    /** ConnectionArchive.key of server/account/folder. */
    val key: String,
    val serverUrl: String,
    val account: String,
    val appPassword: String,
    val passphrase: String,
    /** The remote folder this pass reads. */
    val dirPath: String,
    /** The local database file this pass writes. */
    val dbName: String,
    val store: dev.magpie.usage.data.UsageStore,
) {
    val credentials: Credentials get() = FixedCredentials(account, appPassword, passphrase)
}
