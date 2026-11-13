package dev.magpie.usage.sync

/** What one sync run did, for the status line and the diagnostics report. */
data class SyncOutcome(
    val checked: Boolean,
    val filesChanged: Int,
    val filesDownloaded: Int,
    val filesImported: Int,
    val filesFailed: Int,
    val totalRemoteFiles: Int,
    val remoteModifiedAt: Long?,
    val latestRecordAt: Long?,
    val error: String?,
) {
    /**
     * Whether this pass left something unread. A pass that imported what it
     * could but skipped a torn or corrupt file is not a success: the status
     * line must keep saying so, or a file that keeps failing would look like
     * a healthy sync.
     */
    val hasFailures: Boolean get() = filesFailed > 0 || error != null

    /** The message for the status line, or null when the pass was clean. */
    val failureMessage: String?
        get() = when {
            filesFailed > 0 && error != null -> "$filesFailed 个文件导入失败：$error"
            filesFailed > 0 -> "$filesFailed 个文件导入失败"
            else -> error
        }
}
