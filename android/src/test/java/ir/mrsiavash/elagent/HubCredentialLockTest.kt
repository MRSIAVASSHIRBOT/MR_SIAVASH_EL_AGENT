package ir.mrsiavash.elagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HubCredentialLockTest {
    @Test
    fun credentialMutationsAreSerializedAcrossHubClientInstances() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maximumConcurrent = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = (1..2).map {
            Thread {
                try {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS)) { "Workers did not receive the start signal." }
                    HubCredentialLock.withLock {
                        val current = active.incrementAndGet()
                        maximumConcurrent.updateAndGet { previous -> maxOf(previous, current) }
                        try {
                            Thread.sleep(100)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }.apply { start() }
        }

        assertTrue("Workers did not become ready.", ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(2_000) }

        assertTrue("A worker did not finish.", workers.none { it.isAlive })
        assertTrue("A worker failed: $failures", failures.isEmpty())
        assertEquals("Credential critical sections must never overlap.", 1, maximumConcurrent.get())
    }
    @Test
    fun nestedCredentialOperationsCanReuseTheSameLock() {
        val result = HubCredentialLock.withLock {
            HubCredentialLock.withLock { "nested-credential-operation" }
        }

        assertEquals("nested-credential-operation", result)
    }

}
