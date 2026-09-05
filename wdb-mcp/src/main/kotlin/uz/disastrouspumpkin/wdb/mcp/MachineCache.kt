package uz.disastrouspumpkin.wdb.mcp

import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.Machine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches discovered machines (name → [Machine]) with a short TTL so machine-addressed tool calls
 * don't each pay a full UDP discovery (add-wdb-mcp-v2 D1). [list] always refreshes; [resolve] returns
 * a cached address when the cache is fresh and only discovers on a miss or after the TTL. A [Mutex]
 * coalesces concurrent refreshes so a burst of calls fans out into one discovery. [now] is injectable
 * for tests.
 */
internal class MachineCache(
    private val ttlMs: Long = 5_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val discover: suspend () -> List<Machine>,
) {
    private val mutex = Mutex()

    @Volatile
    private var entries: Map<String, Machine> = emptyMap()

    @Volatile
    private var lastRefresh: Long = 0

    /** Force a fresh discovery, repopulate, and return the machines. */
    suspend fun list(): List<Machine> = refresh()

    /** Resolve a machine's address: cache hit when fresh, otherwise one discovery. */
    suspend fun resolve(name: String): AgentAddress? {
        entries[name]?.let { if (now() - lastRefresh < ttlMs) return it.address }
        refresh()
        return entries[name]?.address
    }

    private suspend fun refresh(): List<Machine> {
        val before = lastRefresh
        return mutex.withLock {
            // Another caller refreshed while we waited for the lock — reuse their result.
            if (lastRefresh != before && entries.isNotEmpty()) return@withLock entries.values.toList()
            val found = discover()
            entries = found.associateBy { it.name }
            lastRefresh = now()
            found
        }
    }
}
