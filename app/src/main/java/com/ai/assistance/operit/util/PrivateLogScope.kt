package com.ai.assistance.operit.util

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Request-local suppression, inherited by child coroutines and restored after suspension. */
internal object PrivateLogScope {
    private val privateRequest = ThreadLocal.withInitial { false }
    val isPrivate: Boolean get() = privateRequest.get()

    suspend fun <T> run(block: suspend () -> T): T =
        withContext(privateRequest.asContextElement(true)) { block() }
}
