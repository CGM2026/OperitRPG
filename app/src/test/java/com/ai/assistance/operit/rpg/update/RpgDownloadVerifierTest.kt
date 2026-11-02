package com.ai.assistance.operit.rpg.update

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class RpgDownloadVerifierTest {
    private val validHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun copy(text: String, bytes: Long = 3, sha: String = validHash): ByteArray {
        val output = ByteArrayOutputStream()
        RpgDownloadVerifier.copyVerified(text.byteInputStream(), output, bytes, sha) { }
        return output.toByteArray()
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected download rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsExactBytesWithKnownSha256() { assertArrayEquals("abc".toByteArray(), copy("abc")) }
    @Test fun rejectsTruncatedAndOversizedResponses() {
        rejected { copy("ab") }
        rejected { copy("abcd") }
    }
    @Test fun rejectsCorrectLengthWithWrongContentOrDigest() {
        rejected { copy("abd") }
        rejected { copy("abc", sha = "0".repeat(64)) }
    }
    @Test fun refusesOverLimitBeforeReadingInput() {
        rejected { copy("abc", bytes = RpgUpdatePolicy.MAX_APK_BYTES + 1) }
    }
}
