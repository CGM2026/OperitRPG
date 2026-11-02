package com.ai.assistance.operit.rpg.update

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ExecutorService

class AppLoggerPrivateTest {
    @Test fun privateCallsNeverReachSystemLogAndPublicLogStillWrites() = runBlocking {
        val file = Files.createTempFile("rpg-log-test", ".log").toFile()
        val field = AppLogger::class.java.getDeclaredField("logFile").apply { isAccessible = true }
        val previousFile = field.get(AppLogger)
        val oldSystem = AppLogger.enableSystemLog
        val oldFile = AppLogger.enableFileLogging
        val executorField = AppLogger::class.java.getDeclaredField("fileLogExecutor").apply { isAccessible = true }
        val executor = executorField.get(AppLogger) as ExecutorService
        try {
            executor.submit { }.get()
            field.set(AppLogger, file)
            AppLogger.enableFileLogging = true
            // Android's Log methods throw in JVM tests. The private path must never call them.
            AppLogger.enableSystemLog = true
            AppLogger.withPrivateLogging {
                withContext(Dispatchers.Default) {
                    assertEquals(0, AppLogger.d("RpgPrivateTest", "secret prompt"))
                    assertEquals(0, AppLogger.e("RpgPrivateTest", "secret response", RuntimeException("secret failure")))
                    assertFalse(AppLogger.isLoggable("RpgPrivateTest", AppLogger.DEBUG))
                }
            }
            AppLogger.enableSystemLog = false
            AppLogger.d("RpgPrivateTest", "visible public message")
            executor.submit { }.get()
            val contents = file.readText()
            assertTrue(contents.contains("visible public message"))
            assertFalse(contents.contains("secret"))
        } finally {
            executor.submit { }.get()
            field.set(AppLogger, previousFile)
            AppLogger.enableSystemLog = oldSystem
            AppLogger.enableFileLogging = oldFile
            file.delete()
        }
    }
}
