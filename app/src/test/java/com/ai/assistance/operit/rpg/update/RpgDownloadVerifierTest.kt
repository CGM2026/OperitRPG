package com.ai.assistance.operit.rpg.update

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.io.InputStream

class RpgDownloadVerifierTest {
    private val validHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private suspend fun copy(text: String, bytes: Long = 3, sha: String = validHash): ByteArray {
        val output = ByteArrayOutputStream()
        RpgDownloadVerifier.copyVerified(text.byteInputStream(), output, bytes, sha) { }
        return output.toByteArray()
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected download rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsExactBytesWithKnownSha256() = runBlocking { assertArrayEquals("abc".toByteArray(), copy("abc")) }
    @Test fun rejectsTruncatedAndOversizedResponses() = runBlocking {
        rejected { copy("ab") }
        rejected { copy("abcd") }
    }
    @Test fun rejectsCorrectLengthWithWrongContentOrDigest() = runBlocking {
        rejected { copy("abd") }
        rejected { copy("abc", sha = "0".repeat(64)) }
    }
    @Test fun refusesOverLimitBeforeReadingInput() = runBlocking {
        rejected { copy("abc", bytes = RpgUpdatePolicy.MAX_APK_BYTES + 1) }
    }

    @Test fun cancellationStopsBeforeAnotherRead() = runBlocking {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("bulk reads required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                buffer[offset] = 1
                return 1
            }
        }
        val download = launch(start = CoroutineStart.UNDISPATCHED) {
            val requestJob = currentCoroutineContext().job
            RpgDownloadVerifier.copyVerified(input, ByteArrayOutputStream(), 10, validHash) { requestJob.cancel() }
        }
        download.join()
        assertTrue(download.isCancelled)
        assertEquals(1, reads)
    }
}
