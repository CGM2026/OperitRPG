package com.ai.assistance.operit.rpg.update

import com.ai.assistance.operit.util.PrivateLogScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PrivateLogScopeTest {
    @Test fun propagatesAcrossDispatchersAndRestoresAfterFailure() = runBlocking {
        assertFalse(PrivateLogScope.isPrivate)
        try {
            PrivateLogScope.run {
                assertTrue(PrivateLogScope.isPrivate)
                withContext(Dispatchers.Default) { yield(); assertTrue(PrivateLogScope.isPrivate) }
                error("test failure")
            }
        } catch (_: IllegalStateException) { }
        assertFalse(PrivateLogScope.isPrivate)
    }

    @Test fun concurrentPublicRequestKeepsLoggingEnabled() = runBlocking {
        val privateStarted = CompletableDeferred<Unit>()
        val publicFinished = CompletableDeferred<Unit>()
        val privateRequest = launch(Dispatchers.Default) {
            PrivateLogScope.run {
                privateStarted.complete(Unit)
                publicFinished.await()
                assertTrue(PrivateLogScope.isPrivate)
            }
        }
        val publicRequest = launch(Dispatchers.Default) {
            privateStarted.await()
            assertFalse(PrivateLogScope.isPrivate)
            publicFinished.complete(Unit)
        }
        joinAll(privateRequest, publicRequest)
        assertFalse(PrivateLogScope.isPrivate)
    }
}
