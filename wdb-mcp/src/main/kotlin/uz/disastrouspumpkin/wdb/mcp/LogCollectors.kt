package uz.disastrouspumpkin.wdb.mcp

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.protocol.LogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backs the streaming logs resource `wdb://logs/{machine}` (add-wdb-mcp-v2 D5). SDK 0.13 has no
 * subscribe hook, so a per-(session, machine) collector is started on first [read] of the resource:
 * it tails [WdbClient.logs] into a bounded ring buffer and, via a coalescing loop (≤ [coalesceMs]),
 * calls [notify] with the resource URI whenever new lines have arrived so the client re-reads the
 * fresh tail. [read] returns the current buffer. Collectors are cancelled with [cancelAll] on session
 * close. Decoupled from the MCP `ClientConnection` (push goes through the injected [notify]) so it is
 * unit-testable without a real client.
 */
internal class LogCollectors(
    private val scope: CoroutineScope,
    private val client: WdbClient,
    private val maxLines: Int = 500,
    private val coalesceMs: Long = 500,
) {
    private class Entry(val job: Job, val buffer: ArrayDeque<String>, val lock: Any)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Ensure a collector for ([sessionId], [machine]) is running (starting it on first call), and
     * return the current buffered tail. [notify] is invoked with the resource URI when new lines
     * arrive; only the first call's [notify] is used for a given key.
     */
    fun read(
        sessionId: String,
        machine: String,
        host: AgentAddress?,
        notify: suspend (uri: String) -> Unit,
    ): String {
        val entry = entries.computeIfAbsent("$sessionId|$machine") { start(machine, host, notify) }
        synchronized(entry.lock) {
            return entry.buffer.joinToString("\n").ifEmpty { "(no logs yet)" }
        }
    }

    private fun start(machine: String, host: AgentAddress?, notify: suspend (String) -> Unit): Entry {
        val buffer = ArrayDeque<String>()
        val lock = Any()
        val uri = "wdb://logs/$machine"
        val job = scope.launch {
            val dirty = AtomicBoolean(false)
            val collectJob = launch {
                client.logs(machine, host).collect { ev ->
                    if (ev is LogLine) {
                        synchronized(lock) {
                            buffer.addLast(ev.text)
                            while (buffer.size > maxLines) buffer.removeFirst()
                        }
                        dirty.set(true)
                    }
                }
            }
            try {
                while (isActive) {
                    delay(coalesceMs)
                    if (dirty.getAndSet(false)) runCatching { notify(uri) }
                }
            } finally {
                collectJob.cancel()
            }
        }
        return Entry(job, buffer, lock)
    }

    fun cancelAll() {
        entries.values.forEach { it.job.cancel() }
        entries.clear()
    }
}
