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

    @Test
    fun requestedUnpairBlocksNewDeviceActionsUntilExplicitPairingStartsAgain() {
        val initialGeneration = HubCredentialLock.pairingGeneration()
        HubCredentialLock.commitPairing(initialGeneration) {}
        HubCredentialLock.requestUnpair()

        val rejected = runCatching {
            HubCredentialLock.withDeviceAction { "must-not-run" }
        }
        assertTrue("A requested Unpair must block new device actions.", rejected.isFailure)

        val newPairingGeneration = HubCredentialLock.pairingGeneration()
        HubCredentialLock.commitPairing(newPairingGeneration) {}
        val allowed = HubCredentialLock.withDeviceAction { "paired-again" }
        assertEquals("paired-again", allowed)
    }

    @Test
    fun refreshedCredentialsCannotBeCommittedAfterUnpairWasRequested() {
        val initialGeneration = HubCredentialLock.pairingGeneration()
        HubCredentialLock.commitPairing(initialGeneration) {}
        HubCredentialLock.requestUnpair()

        val commit = runCatching {
            HubCredentialLock.commitActiveSession {
                throw AssertionError("Credential commit must not run after Unpair.")
            }
        }
        assertTrue("Refresh commit must be rejected after Unpair.", commit.isFailure)

        val stalePairingGeneration = initialGeneration
        val staleCommit = runCatching {
            HubCredentialLock.commitPairing(stalePairingGeneration) {}
        }
        assertTrue("An Unpair request during pairing must invalidate that attempt.", staleCommit.isFailure)

        val newPairingGeneration = HubCredentialLock.pairingGeneration()
        var committed = false
        HubCredentialLock.commitPairing(newPairingGeneration) { committed = true }
        assertTrue("A later explicit pairing may commit its credentials.", committed)
        HubCredentialLock.commitActiveSession {}
    }

    @Test
    fun isUnpairRequestedImmediatelyDisablesPairedSession() {
        val generation = HubCredentialLock.pairingGeneration()
        HubCredentialLock.commitPairing(generation) {}
        assertTrue("A newly paired session should be active.", !HubCredentialLock.isUnpairRequested())

        HubCredentialLock.requestUnpair()
        assertTrue("Unpair intent must be visible before disk credentials are cleared.", HubCredentialLock.isUnpairRequested())

        val nextGeneration = HubCredentialLock.pairingGeneration()
        HubCredentialLock.commitPairing(nextGeneration) {}
        assertTrue("A successful explicit pairing clears the pending Unpair state.", !HubCredentialLock.isUnpairRequested())
    }

}
