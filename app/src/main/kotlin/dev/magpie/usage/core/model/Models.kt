package dev.magpie.usage.core.model

/**
 * One usage call, as the desktop ledger finalised it.
 *
 * The desktop packs its already-merged ledger rows (gateway rows plus
 * session-file rows it did not see) into a day pack; the phone counts each
 * row exactly once and never re-matches pairs.
 *
 * Protocol reference: yetone/magpie @ 6acf5ee5, internal/usage/usage.go,
 * internal/usage/shared.go, internal/usage/ledger.go.
 */
data class CallRow(
    val fileName: String,
    val ordInFile: Int,
    val computer: String,
    val day: String,            // the pack's own day string (index only; filtering uses t)
    /** Request start, epoch milliseconds UTC. */
    val t: Long,
    val agent: String,
    val provider: String,
    val model: String,
    val requested: String,
    val served: String,
    val inTok: Long,
    val outTok: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val cacheWrite1h: Long,
    val cacheUnknown: Boolean,
    val reasoning: Long,
    val effort: String,
    val ms: Long,
    val ttftMs: Long,
    val firstTextMs: Long,
    val status: Int,
    val err: String,
    val errType: String,
    val rid: String,
    val session: String,
    /** The provider a session-file row names as its route (session_provider),
     * which prices an otherwise session-unknown row when the catalog lists
     * that exact model under it. */
    val sessionProvider: String = "",
    /** Where the call went ("api.deepseek.com", or "host as account"). */
    val host: String = "",
    /** The subscription account that answered (providerAccount), for the
     * desktop's 账号 dimension (Record.Account). */
    val providerAccount: String = "",
    /** Pack-level source. May be absent: the pack's own field can be dropped
     * by the desktop's JSON encoder when it conflicts with the record's. */
    val source: String,
    val tries: Int,
    val handedOn: Boolean,
    val rejected: Boolean,
    /** Explicit cost the client reported, USD. null = field absent. */
    val costUsd: Double?,
    val credits: Double?,
    val upBytes: Long,
    val downBytes: Long,
    /** The raw JSON of the call, kept so a parser upgrade can re-read it
     * without downloading the file again. */
    val raw: String,
) {
    /** Upstream Record.Failed: error status or message, except a cancel. */
    val failed: Boolean get() = (status >= 400 || err.isNotEmpty()) && !canceled

    /** Upstream Record.Canceled: the agent left before an answer. */
    val canceled: Boolean
        get() = status == STATUS_CANCELED &&
            (errType == ERR_TYPE_CANCELED || (status == STATUS_CANCELED && err == AGENT_CANCELED))

    /** Upstream Record.Answered. */
    val answered: Boolean get() = !failed && !canceled

    /** Upstream Record.IsRejected: refused locally, before a provider. */
    val isRejected: Boolean get() = rejected || (provider.isEmpty() && status >= 400)

    /** Upstream Record.cacheNotCounted: the vendor counted no cache tokens. */
    val cacheNotCounted: Boolean
        get() = cacheUnknown || (cacheRead == 0L && cacheWrite == 0L && provider in NO_CACHE_PROVIDERS)

    /** Prompt tokens of a call whose cache wasn't counted (Totals.CacheUnknown). */
    val cacheUnknownIn: Long
        get() = if (cacheNotCounted) inTok + cacheRead + cacheWrite else 0L

    /** Tokens including cache traffic — the "Token（含缓存）" figure. */
    val allTokens: Long get() = inTok + outTok + cacheRead + cacheWrite

    companion object {
        const val STATUS_CANCELED = 499
        const val ERR_TYPE_CANCELED = "agent_canceled"
        const val AGENT_CANCELED = "the agent canceled the request"

        /** Record.Account (usage.go:1020): the record's account, or the one
         * its Host said the call went out as. */
        fun accountOf(host: String, providerAccount: String): String {
            if (providerAccount.isNotEmpty()) return providerAccount
            val i = host.lastIndexOf(" as ")
            return if (i >= 0) host.substring(i + 4) else ""
        }

        /** Providers whose vendors never count cache tokens (#3464). */
        val NO_CACHE_PROVIDERS = setOf("kiro", "kiro-plugin")

        /** Desktop name for the calls read from agents' session files. */
        const val UNKNOWN_PROVIDER = "session-unknown"
    }
}

/** A remote usage file as the manifest knows it. */
data class RemoteFileInfo(
    val fileName: String,
    val computerId: String,
    val day: String,
    val version: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val callCount: Int,
    val parseVersion: Int,
    val failedReason: String,
)

/** One desktop's calls of one day, the decrypted pack. */
data class SharedDayPack(
    val computer: String,
    val name: String,
    val day: String,
    val calls: List<CallRow>,
)
