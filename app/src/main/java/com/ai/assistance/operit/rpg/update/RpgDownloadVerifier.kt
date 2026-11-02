package com.ai.assistance.operit.rpg.update

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object RpgDownloadVerifier {
    suspend fun copyVerified(input: InputStream, output: OutputStream, expectedBytes: Long, expectedSha256: String, progress: (Float) -> Unit) {
        require(expectedBytes in 1..RpgUpdatePolicy.MAX_APK_BYTES) { "更新文件大小无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= expectedBytes) { "下载超过允许大小" }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            progress(count.toFloat() / expectedBytes)
        }
        require(count == expectedBytes) { "下载未完成，请重试" }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256) { "安装包校验失败，请重试" }
    }
}
