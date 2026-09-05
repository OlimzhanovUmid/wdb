package uz.disastrouspumpkin.wdb.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Run a blocking socket [block] on [Dispatchers.IO] such that coroutine
 * cancellation closes [closeable], unblocking any read/write parked inside
 * [block] (design D10: cancellation is by closing the socket). The blocking
 * call then throws a socket exception which propagates out of [block].
 */
internal suspend fun <T> withCancellationClosing(closeable: Closeable, block: () -> T): T =
    withContext(Dispatchers.IO) {
        coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { closeable.close() }
                }
            }
            try {
                block()
            } finally {
                watcher.cancel()
            }
        }
    }
