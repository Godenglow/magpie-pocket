package dev.magpie.usage.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.magpie.usage.MagpieApp
import java.util.concurrent.TimeUnit

/**
 * The system-scheduled background check. WorkManager's floor is 15 minutes
 * and the OS may delay it — that is told to the user as "尽力", never as a
 * 3-minute guarantee.
 */
class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MagpieApp ?: return Result.failure()
        return try {
            // Through the coordinator: the same mutex as the foreground poll
            // and the manual refresh, so two passes never download and
            // replace the same file at once (an older result could otherwise
            // overwrite a newer one).
            app.sync().refreshNow(force = true)
            val report = app.lastReport.value
            val relevant = report?.takeIf { it.connectionKey == app.currentConnectionKey() }
            if (relevant != null && relevant.outcome.hasFailures) {
                // Partial failure is not success: retry with backoff instead
                // of reporting a clean run.
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "magpie_usage_sync"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UsageWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
