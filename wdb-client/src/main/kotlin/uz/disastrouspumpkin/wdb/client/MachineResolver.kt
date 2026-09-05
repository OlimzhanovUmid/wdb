package uz.disastrouspumpkin.wdb.client

import uz.disastrouspumpkin.wdb.protocol.MessageCodec
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/** Raised when a target name/id resolves to no reachable machine. */
class NoSuchMachineException(target: String) : Exception("no machine matches '$target'")

@Serializable
private data class CacheEntry(val id: String, val name: String, val host: String, val port: Int)

@Serializable
private data class CacheFile(val machines: List<CacheEntry> = emptyList())

/**
 * On-disk last-seen table (machine id/name -> address). Persisted so a command can
 * connect directly without a discovery round-trip (design D9). Not thread-safe;
 * used from a single CLI invocation.
 */
class LastSeenCache(private val file: Path) {
    private val byId = LinkedHashMap<String, Machine>()

    init {
        runCatching {
            if (Files.exists(file)) {
                val parsed = MessageCodec.decode<CacheFile>(Files.readAllBytes(file))
                for (e in parsed.machines) {
                    byId[e.id] = Machine(e.id, e.name, AgentAddress(e.host, e.port))
                }
            }
        }
    }

    fun find(target: String): Machine? =
        byId[target] ?: byId.values.firstOrNull { it.name == target }

    fun put(machine: Machine) {
        byId[machine.id] = machine
        save()
    }

    private fun save() {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val cf = CacheFile(byId.values.map { CacheEntry(it.id, it.name, it.address.host, it.address.port) })
            Files.write(file, MessageCodec.encode(cf))
        }
    }

    companion object {
        fun default(): LastSeenCache {
            val dir = System.getenv("LOCALAPPDATA")?.let { Path.of(it, "wdb") }
                ?: Path.of(System.getProperty("user.home"), ".wdb")
            return LastSeenCache(dir.resolve("last-seen.json"))
        }
    }
}

/**
 * Resolve a target (machine id or name) to an address and run [block] against it,
 * with cache-first + discovery-fallback semantics (design D9): try the cached
 * address; if the connection fails, discover fresh, update the cache, and retry.
 * [discoverFn] is injectable so resolution is unit-tested without UDP.
 */
class MachineResolver(
    private val cache: LastSeenCache,
    private val discoverFn: suspend () -> List<Machine>,
) {
    suspend fun <T> withResolved(
        target: String,
        hostOverride: AgentAddress?,
        block: suspend (AgentAddress) -> T,
    ): T {
        if (hostOverride != null) return block(hostOverride)

        val cached = cache.find(target)
        if (cached != null) {
            try {
                return block(cached.address)
            } catch (_: java.io.IOException) {
                // cached address is stale — fall through to a fresh discovery
            }
        }
        val found = discoverFn().firstOrNull { it.id == target || it.name == target }
            ?: throw NoSuchMachineException(target)
        cache.put(found)
        return block(found.address)
    }
}
