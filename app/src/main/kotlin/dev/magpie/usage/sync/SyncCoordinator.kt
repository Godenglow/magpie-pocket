package dev.magpie.usage.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-flight sync orchestration shared by the foreground poll, the manual
 * refresh and the WorkManager job — one mutex, one run at a time, manual
 * refresh debounced.
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val run: suspend () -> Unit,
) {

    private val mutex = Mutex()
    private val lastManual = AtomicLong(0)
    private var foregroundJob: Job? = null

    companion object {
        /** A manual refresh at most every 10 s. */
        const val MANUAL_DEBOUNCE_MS = 10_000L
    }

    /** Runs one pass now unless one is already running or just ran. */
    suspend fun refreshNow(force: Boolean = true) {
        if (!force) {
            val last = lastManual.get()
            if (System.currentTimeMillis() - last < MANUAL_DEBOUNCE_MS) return
        }
        lastManual.set(System.currentTimeMillis())
        mutex.withLock {
            run()
        }
    }

    /** Fire-and-forget pass, e.g. from WorkManager. */
    fun launchBackground() {
        scope.launch { refreshNow(force = true) }
    }

    /**
     * The foreground poll: check the cloud every [intervalMinutes] minutes
     * while the page is visible; [stop] ends the loop.
     */
    fun startForegroundPolling(intervalMinutes: Int) {
        stopForegroundPolling()
        foregroundJob = scope.launch {
            while (true) {
                try {
                    refreshNow(force = false)
                } catch (_: Exception) {
                    // the status line already shows the reason
                }
                delay(intervalMinutes * 60_000L)
            }
        }
    }

    fun stopForegroundPolling() {
        foregroundJob?.cancel()
        foregroundJob = null
    }
}
