package dev.magpie.usage.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.magpie.usage.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "magpie.settings")

/** Stable archive identity shared by settings migration and the active store. */
object ConnectionArchive {
    fun key(serverUrl: String, account: String, usageDir: String): String {
        val raw = "$serverUrl|$account|$usageDir"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Non-sensitive preferences. Credentials live only in [CredentialStore]. */
data class SyncSettings(
    val serverUrl: String,
    val account: String,
    val usageDir: String,
    val foregroundMinutes: Int,
    val backgroundEnabled: Boolean,
    val timeZoneMode: String, // "shanghai" | "system"
    val configured: Boolean,
    /** 数字单位："cn"（万/亿）或 "en"（K/M/B）。 */
    val numberUnit: String = "en",
    /** 货币："usd" 或 "cny"（按 exchangeRate 折算）。 */
    val currency: String = "usd",
    /** CNY per USD；用户自定，0 表示退回美元。 */
    val exchangeRate: Double = 7.0,
    val themeMode: String = "system",
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val account = stringPreferencesKey("account")
        val usageDir = stringPreferencesKey("usage_dir")
        val foregroundMinutes = intPreferencesKey("foreground_minutes")
        val backgroundEnabled = booleanPreferencesKey("background_enabled")
        val timeZoneMode = stringPreferencesKey("timezone_mode")
        val numberUnit = stringPreferencesKey("number_unit")
        val currency = stringPreferencesKey("currency")
        val exchangeRate = stringPreferencesKey("exchange_rate")
        val dbOwnerKey = stringPreferencesKey("db_owner_key")
        val themeMode = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<SyncSettings> = context.dataStore.data.map { p ->
        SyncSettings(
            serverUrl = p[Keys.serverUrl] ?: "https://dav.jianguoyun.com/dav/",
            account = p[Keys.account] ?: "",
            usageDir = p[Keys.usageDir] ?: "/magpie/usage/",
            foregroundMinutes = p[Keys.foregroundMinutes] ?: 3,
            backgroundEnabled = p[Keys.backgroundEnabled] ?: true,
            timeZoneMode = p[Keys.timeZoneMode] ?: "shanghai",
            configured = (p[Keys.account] ?: "").isNotEmpty(),
            numberUnit = p[Keys.numberUnit] ?: "en",
            currency = p[Keys.currency] ?: "usd",
            exchangeRate = (p[Keys.exchangeRate] ?: "7.0").toDoubleOrNull() ?: 7.0,
            themeMode = p[Keys.themeMode] ?: "system",
        )
    }

    suspend fun save(
        serverUrl: String,
        account: String,
        usageDir: String,
        foregroundMinutes: Int,
        backgroundEnabled: Boolean,
        timeZoneMode: String,
        numberUnit: String = "en",
        currency: String = "usd",
        exchangeRate: Double = 7.0,
    ) {
        context.dataStore.edit { p ->
            // On an upgrade, the existing database belongs to the connection
            // saved before this edit. Bind it before a changed connection can
            // claim the old archive during its first sync.
            if (p[Keys.dbOwnerKey] == null) {
                val oldAccount = p[Keys.account]
                if (!oldAccount.isNullOrEmpty()) {
                    p[Keys.dbOwnerKey] = ConnectionArchive.key(
                        p[Keys.serverUrl] ?: "https://dav.jianguoyun.com/dav/",
                        oldAccount,
                        p[Keys.usageDir] ?: "/magpie/usage/",
                    )
                }
            }
            p[Keys.serverUrl] = serverUrl.trim()
            p[Keys.account] = account.trim()
            p[Keys.usageDir] = usageDir.trim()
            p[Keys.foregroundMinutes] = foregroundMinutes.coerceIn(1, 60)
            p[Keys.backgroundEnabled] = backgroundEnabled
            p[Keys.timeZoneMode] = timeZoneMode
            p[Keys.numberUnit] = numberUnit
            p[Keys.currency] = currency
            p[Keys.exchangeRate] = exchangeRate.toString()
        }
    }

    /** 只改显示偏好（不改连接配置）。 */
    suspend fun saveDisplay(numberUnit: String, currency: String, exchangeRate: Double) {
        context.dataStore.edit { p ->
            p[Keys.numberUnit] = numberUnit
            p[Keys.currency] = currency
            p[Keys.exchangeRate] = exchangeRate.toString()
        }
    }

    suspend fun saveTheme(mode: String) {
        require(mode in setOf("system", "light", "dark"))
        context.dataStore.edit { it[Keys.themeMode] = mode }
    }

    /**
     * 本机归档与 WebDAV 连接的绑定：第一次配置的连接继续用旧的
     * magpie-usage.db（升级不丢历史），之后每换一个连接（服务器/账号/
     * 目录任一不同）分配一个新的库文件，两个连接的数据互不混入。
     */
    suspend fun archiveDbName(connectionKey: String): String {
        var name = ""
        context.dataStore.edit { p ->
            val owner = p[Keys.dbOwnerKey]
            name = when {
                owner == null || owner == connectionKey -> AppDatabase.DEFAULT_NAME
                else -> "magpie-usage-${connectionKey.take(12)}.db"
            }
            if (owner == null) p[Keys.dbOwnerKey] = connectionKey
        }
        return name
    }
}
