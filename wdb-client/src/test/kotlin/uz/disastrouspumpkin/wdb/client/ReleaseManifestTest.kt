package uz.disastrouspumpkin.wdb.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseManifestTest {

    private val sample = """
        {
          "agent":  { "version": "0.2.15", "asset": "wdb-agent-installer-0.2.15.zip",
                      "url": "https://example/wdb-agent-installer-0.2.15.zip",
                      "sha256": "abc", "size": 159054515 },
          "plugin": { "version": "0.1.0", "asset": "wdb-plugin-0.1.0.zip",
                      "url": "https://example/wdb-plugin-0.1.0.zip",
                      "sha256": "def", "size": 2818313 }
        }
    """.trimIndent()

    @Test
    fun parses_components() {
        val m = parseReleaseManifest(sample)
        val agent = m.getValue("agent")
        assertEquals("0.2.15", agent.version)
        assertEquals("wdb-agent-installer-0.2.15.zip", agent.asset)
        assertEquals(159054515L, agent.size)
        assertEquals("0.1.0", m.getValue("plugin").version)
    }

    @Test
    fun ignores_unknown_keys() {
        // Forward-compatible: a future component the plugin doesn't know about must not break parsing.
        val json = """{ "agent": { "version":"1","asset":"a","url":"u","sha256":"s","size":1 },
                        "future": { "version":"9","asset":"b","url":"u","sha256":"s","size":2, "extra": true } }"""
        assertEquals(2, parseReleaseManifest(json).size)
    }

    @Test
    fun newer_version_detection() {
        assertTrue(isNewerVersion("0.2.14", "0.2.15"))
        assertTrue(isNewerVersion("0.2.9", "0.2.10"))   // numeric, not lexical
        assertTrue(isNewerVersion("1.0", "1.0.1"))
        assertFalse(isNewerVersion("0.2.15", "0.2.15")) // equal
        assertFalse(isNewerVersion("0.2.15", "0.2.14")) // downgrade
        assertFalse(isNewerVersion("?", "0.2.15"))      // unknown current
        assertFalse(isNewerVersion("0.2.15", "nightly")) // unparseable candidate
    }
}
