package dev.magpie.usage

import dev.magpie.usage.sync.SyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    @Test
    fun `simultaneous refreshes never execute imports concurrently`() = runTest {
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var passes = 0
        val coordinator = SyncCoordinator(backgroundScope) {
            active++
            maximum = maxOf(maximum, active)
            passes++
            try { release.await() } finally { active-- }
        }
        val first = launch { coordinator.refreshNow() }
        val second = launch { coordinator.refreshNow() }
        runCurrent()
        assertEquals(1, passes)
        assertEquals(1, active)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, maximum)
        assertEquals(2, passes)
    }
}
