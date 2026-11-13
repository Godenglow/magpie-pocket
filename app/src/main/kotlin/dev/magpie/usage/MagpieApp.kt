package dev.magpie.usage

import android.app.Application
import dev.magpie.usage.core.webdav.OkHttpWebDavClient
import dev.magpie.usage.core.webdav.WebDavClient
import dev.magpie.usage.data.CredentialStore
import dev.magpie.usage.data.ConnectionArchive
import dev.magpie.usage.data.PriceSource
import dev.magpie.usage.data.PriceTableStore
import dev.magpie.usage.data.RoomUsageStore
import dev.magpie.usage.data.SettingsStore
import dev.magpie.usage.data.settingsSnapshot
import dev.magpie.usage.data.toSource
import dev.magpie.usage.sync.SyncCoordinator
import dev.magpie.usage.sync.UsageRepository
import dev.magpie.usage.sync.UsageWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class MagpieApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var organizeStore: dev.magpie.usage.data.OrganizeStore
        private set

    /**
     * The archive of the connection configured now. The database file is
     * chosen by the WebDAV server/account/folder, so switching to another
     * account never shows the previous one's calls, costs or quotas; the
     * first configured connection keeps the pre-isolation file.
     */
    @Volatile
    private var activeStore: RoomUsageStore? = null

    @Volatile
    private var activeRepository: UsageRepository? = null

    @Volatile
    private var activeConnection: String? = null

    /** Rebuilds the store/repository when the connection changed. */
    private fun ensureActiveConnection() {
        val key = connectionKey()
        if (key == activeConnection) return
        synchronized(this) {
            if (key == activeConnection) return
            val dbName = kotlinx.coroutines.runBlocking { settingsStore.archiveDbName(key) }
            activeStore = RoomUsageStore(this, dbName)
            activeRepository = UsageRepository(::newClient, activeStore!!, credentialStore)
            activeConnection = key
            // A different archive has different files behind it: an old
            // failure or success says nothing about this one.
            lastSyncError = null
            lastSyncOutcome = null
        }
    }

    /** Server + account + folder: what one local archive belongs to. */
    private fun connectionKey(): String {
        val s = kotlinx.coroutines.runBlocking { settingsStore.settingsSnapshot() }
        return ConnectionArchive.key(s.serverUrl, s.account, s.usageDir)
    }

    private val repository: UsageRepository
        get() {
            ensureActiveConnection()
            return activeRepository!!
        }

    /** One finished pass: its counts and the connection they belong to. */
    data class SyncReport(val outcome: dev.magpie.usage.sync.SyncOutcome, val connectionKey: String)

    private val _syncGeneration = MutableStateFlow(0L)

    /** Bumped after every pass (poll, manual, background), so pages reload. */
    val syncGeneration: StateFlow<Long> = _syncGeneration.asStateFlow()

    private val _lastReport = MutableStateFlow<SyncReport?>(null)
    val lastReport: StateFlow<SyncReport?> = _lastReport.asStateFlow()

    /**
     * Serialises connection writes against snapshot reads. Saving a
     * connection updates the settings (folder) and the credential store
     * separately; without one lock a sync that starts between those writes
     * could pair A's folder and archive with B's account and password.
     */
    private val connectionGate = kotlinx.coroutines.sync.Mutex()

    /** Runs [block] with connection saves and snapshot reads serialised. */
    suspend fun <T> withConnectionGate(block: suspend () -> T): T = connectionGate.withLock { block() }

    /**
     * Freezes one connection for one pass: settings, credentials, folder and
     * archive file are read once. Otherwise a save that lands mid-pass could
     * pair the new account's folder or password with the old account's
     * archive, and import one connection's files into the other's store.
     */
    suspend fun pinConnection(): dev.magpie.usage.sync.PinnedConnection = connectionGate.withLock {
        val s = settingsStore.settingsSnapshot()
        val account = credentialStore.load(dev.magpie.usage.sync.CredentialKeys.ACCOUNT)
            ?.takeIf { it.isNotEmpty() } ?: s.account
        val appPassword = credentialStore.load(dev.magpie.usage.sync.CredentialKeys.APP_PASSWORD) ?: ""
        val passphrase = credentialStore.load(dev.magpie.usage.sync.CredentialKeys.SYNC_PASSPHRASE) ?: ""
        val key = ConnectionArchive.key(s.serverUrl, s.account, s.usageDir)
        val dbName = settingsStore.archiveDbName(key)
        dev.magpie.usage.sync.PinnedConnection(
            key = key,
            serverUrl = s.serverUrl,
            account = account,
            appPassword = appPassword,
            passphrase = passphrase,
            dirPath = s.usageDir,
            dbName = dbName,
            store = RoomUsageStore(this, dbName),
        )
    }

    /** The connection the page shows now; its archive is the one to open. */
    fun currentConnectionKey(): String = connectionKey()

    /** The archive and the connection key, read as one pair. */
    data class ActiveArchive(val key: String, val store: dev.magpie.usage.data.UsageStore)

    /**
     * One read's store: the key and the store come from the same connection,
     * so a page load can tell afterwards whether its result is still current.
     */
    fun activeArchive(): ActiveArchive {
        ensureActiveConnection()
        return ActiveArchive(activeConnection!!, activeStore!!)
    }

    private val syncCoordinator by lazy {
        SyncCoordinator(appScope) {
            val pin = pinConnection()
            val outcome = try {
                UsageRepository(
                    { _, _ -> OkHttpWebDavClient(pin.serverUrl, pin.account, pin.appPassword) },
                    pin.store,
                    pin.credentials,
                ).synchronize(pin.dirPath)
            } catch (e: Exception) {
                val message = e.message ?: "同步失败"
                lastSyncError = message
                _lastReport.value = SyncReport(
                    dev.magpie.usage.sync.SyncOutcome(
                        checked = false, filesChanged = 0, filesDownloaded = 0, filesImported = 0,
                        filesFailed = 0, totalRemoteFiles = 0, remoteModifiedAt = null,
                        latestRecordAt = null, error = message,
                    ),
                    pin.key,
                )
                _syncGeneration.value += 1
                throw e
            }
            // A pass that skipped files is not a clean sync: keep the reason,
            // or a file that keeps failing would show a green status line.
            lastSyncError = outcome.failureMessage
            lastSyncOutcome = outcome
            _lastReport.value = SyncReport(outcome, pin.key)
            _syncGeneration.value += 1
        }
    }

    /** The most recent pass's counts, for the status line's detail. */
    @Volatile
    var lastSyncOutcome: dev.magpie.usage.sync.SyncOutcome? = null
        private set
    private val priceTableStore by lazy { PriceTableStore(this) }

    /** The price source, loaded once at start and after an update. */
    private val priceSource = MutableStateFlow(PriceSource.EMPTY)

    /** The most recent sync error message, for the status line. */
    @Volatile
    var lastSyncError: String? = null
        private set

    fun repository(): UsageRepository = repository
    fun store(): dev.magpie.usage.data.UsageStore {
        ensureActiveConnection()
        return activeStore!!
    }
    fun appScope(): CoroutineScope = appScope
    fun sync(): SyncCoordinator = syncCoordinator
    fun priceSource(): StateFlow<PriceSource> = priceSource.asStateFlow()

    /** Re-loads the price table (bundled asset or the user-fetched one). */
    fun reloadPriceTable() {
        appScope.launch {
            priceSource.value = priceTableStore.load().toSource()
        }
    }

    /** Fetches a fresh models.dev table; the caller surfaces the outcome. */
    suspend fun updatePriceTable(): PriceSource {
        val loaded = priceTableStore.refresh()
        val source = loaded.toSource()
        priceSource.value = source
        return source
    }

    fun newClient(account: String, appPassword: String): WebDavClient {
        val settings = kotlinx.coroutines.runBlocking { settingsStore.settingsSnapshot() }
        return OkHttpWebDavClient(settings.serverUrl, account, appPassword)
    }

    /** A client for a server the caller names — the test connection's form
     *  values, so testing never has to save them first. */
    fun clientFor(serverUrl: String, account: String, appPassword: String): WebDavClient =
        OkHttpWebDavClient(serverUrl, account, appPassword)

    fun noteSyncError(message: String?) {
        lastSyncError = message
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        credentialStore = CredentialStore(this)
        organizeStore = dev.magpie.usage.data.OrganizeStore(this)
        appScope.launch {
            val settings = settingsStore.settingsSnapshot()
            UsageWorker.schedule(this@MagpieApp, settings.backgroundEnabled && settings.configured)
        }
        reloadPriceTable()
    }
}
