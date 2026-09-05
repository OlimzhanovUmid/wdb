package uz.disastrouspumpkin.wdb.client

import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolverTest {

    private fun tempCache() = LastSeenCache(Files.createTempFile("wdb-cache", ".json"))

    @Test
    fun `stale cached address falls back to discovery and updates cache`() = runBlocking {
        val cache = tempCache()
        cache.put(Machine("id1", "wall-1", AgentAddress("10.0.0.99", 1))) // unreachable
        val fresh = Machine("id1", "wall-1", AgentAddress("127.0.0.1", 7420))
        val resolver = MachineResolver(cache) { listOf(fresh) }

        val result = resolver.withResolved("wall-1", hostOverride = null) { addr ->
            if (addr.host == "10.0.0.99") throw ConnectException("refused")
            "reached ${addr.host}"
        }

        assertEquals("reached 127.0.0.1", result)
        // cache now points at the fresh address
        assertEquals(AgentAddress("127.0.0.1", 7420), cache.find("wall-1")?.address)
    }

    @Test
    fun `host override short-circuits resolution`() = runBlocking {
        var discoveryCalled = false
        val resolver = MachineResolver(tempCache()) { discoveryCalled = true; emptyList() }
        val override = AgentAddress("192.168.1.50", 7420)

        val result = resolver.withResolved("anything", hostOverride = override) { it.host }

        assertEquals("192.168.1.50", result)
        assertEquals(false, discoveryCalled)
    }

    @Test
    fun `unknown target throws`() = runBlocking {
        val resolver = MachineResolver(tempCache()) { emptyList() }
        assertFailsWith<NoSuchMachineException> {
            resolver.withResolved("ghost", hostOverride = null) { it.host }
        }
        Unit
    }
}
