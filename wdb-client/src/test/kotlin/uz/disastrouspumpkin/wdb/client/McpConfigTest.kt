package uz.disastrouspumpkin.wdb.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpConfigTest {

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun fresh_config_creates_minimal_entry() {
        val out = parse(upsertUserMcpServer(null, "wdb", "C:/x/wdb-mcp.bat"))
        val wdb = out["mcpServers"]!!.jsonObject["wdb"]!!.jsonObject
        assertEquals("stdio", wdb["type"]!!.jsonPrimitive.content)
        assertEquals("C:/x/wdb-mcp.bat", wdb["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun preserves_other_keys_and_servers() {
        val input = """{ "numStartups": 7, "mcpServers": { "other": { "type": "stdio", "command": "o" } } }"""
        val out = parse(upsertUserMcpServer(input, "wdb", "cmd"))
        assertEquals(7, out["numStartups"]!!.jsonPrimitive.content.toInt()) // unrelated key kept
        val servers = out["mcpServers"]!!.jsonObject
        assertTrue("other" in servers)                                     // existing server kept
        assertEquals("cmd", servers["wdb"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun overwrites_existing_wdb_entry() {
        val input = """{ "mcpServers": { "wdb": { "type": "stdio", "command": "old" } } }"""
        val cmd = mcpServerCommand(input, "wdb")
        assertEquals("old", cmd)
        val out = parse(upsertUserMcpServer(input, "wdb", "new"))
        assertEquals("new", out["mcpServers"]!!.jsonObject["wdb"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformed_root_throws() {
        assertFailsWith<Exception> { upsertUserMcpServer("[1, 2, 3]", "wdb", "cmd") } // not an object
    }

    @Test
    fun command_absent_is_null() {
        assertNull(mcpServerCommand("""{ "mcpServers": {} }""", "wdb"))
        assertNull(mcpServerCommand(null, "wdb"))
        assertNull(mcpServerCommand("not json", "wdb"))
    }
}
