package varro.host

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ViewTasksTest {
    @Test fun `searches supersede old work without waiting for a blocked file action`() {
        val tasks = ViewTasks()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstSearch = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val latest = CountDownLatch(1)
        try {
            tasks.submit { blocked.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            tasks.submit(latest = true) { isCancelled ->
                firstSearch.countDown()
                latest.await(2, TimeUnit.SECONDS)
                if (isCancelled()) cancelled.countDown()
            }
            assertTrue(firstSearch.await(2, TimeUnit.SECONDS))
            tasks.submit(latest = true) { latest.countDown() }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); tasks.close() }
    }
}
