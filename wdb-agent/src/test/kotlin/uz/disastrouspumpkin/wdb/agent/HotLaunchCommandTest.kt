package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotLaunchCommandTest {

    private fun deployment(): Deployment {
        val dir = Path.of("dep")
        val meta = DeploymentMeta(
            sha = "abc",
            jarName = "app.jar",
            mainClass = "com.example.MainKt",
            jvmArgs = listOf("-Xmx512m"),
            programArgs = listOf("--kiosk"),
        )
        return Deployment("abc", dir, dir.resolve("app.jar"), meta)
    }

    @Test
    fun `normal launch uses the -jar form and no hot flags`() {
        val cmd = buildLaunchCommand("java", deployment(), jdwpPort = 5005, suspend = false, hot = null)
        assertEquals("java", cmd.first())
        assertTrue(cmd.contains("-jar"))
        assertTrue(cmd.any { it.endsWith("app.jar") })
        assertFalse(cmd.any { it.contains("AllowEnhancedClassRedefinition") })
        assertFalse(cmd.any { it.contains("compose.reload") })
        assertFalse(cmd.any { it.startsWith("-javaagent") })
        // program args are preserved at the tail
        assertEquals("--kiosk", cmd.last())
    }

    @Test
    fun `normal launch is byte-for-byte the v1 command`() {
        val dep = deployment()
        val cmd = buildLaunchCommand("java", dep, jdwpPort = 5005, suspend = true, hot = null)
        val expected = listOf(
            "java",
            "-Xmx512m",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005",
            "-jar",
            dep.jar.toString(),
            "--kiosk",
        )
        assertEquals(expected, cmd)
    }

    @Test
    fun `hot launch adds the CHR agent, enhanced-redefinition flag, orchestration props, and -cp form`() {
        val hot = HotLaunch(
            orchestrationPort = 51234,
            hotDir = Path.of("hot"),
            agentJar = Path.of("agent", "hot-reload-agent.jar"),
        )
        val cmd = buildLaunchCommand("java", deployment(), jdwpPort = 5005, suspend = false, hot = hot)

        assertTrue(cmd.any { it == "-javaagent:${hot.agentJar}" }, "missing -javaagent")
        assertTrue(cmd.contains("-XX:+AllowEnhancedClassRedefinition"))
        assertTrue(cmd.contains("-Dcompose.reload.orchestration.port=51234"))
        assertTrue(cmd.contains("-Dcompose.reload.hotApplicationClasspath=${hot.hotDir}"))
        assertTrue(cmd.contains("-Dcompose.reload.isHotReloadActive=true"))
        // -cp form: hot dir prepended to the jar, main class named, no -jar
        assertFalse(cmd.contains("-jar"))
        val cpIdx = cmd.indexOf("-cp")
        assertTrue(cpIdx >= 0)
        assertEquals("${hot.hotDir}${java.io.File.pathSeparator}${deployment().jar}", cmd[cpIdx + 1])
        assertEquals("com.example.MainKt", cmd[cpIdx + 2])
        assertEquals("--kiosk", cmd.last())
    }

    @Test
    fun `hot launch prepends the devtools runtime jars to the -cp`() {
        val hot = HotLaunch(
            orchestrationPort = 51234,
            hotDir = Path.of("hot"),
            agentJar = Path.of("agent", "hot-reload-agent.jar"),
        )
        val devtools = listOf(Path.of("devtools", "hot-reload-runtime-jvm.jar"), Path.of("devtools", "hot-reload-core.jar"))
        val cmd = buildLaunchCommand("java", deployment(), jdwpPort = 5005, suspend = false, hot = hot, devtoolsJars = devtools)
        val sep = java.io.File.pathSeparator
        val cp = cmd[cmd.indexOf("-cp") + 1]
        val expected = (devtools.map { it.toString() } + hot.hotDir.toString() + deployment().jar.toString()).joinToString(sep)
        assertEquals(expected, cp)
        // devtools jars come first, before the hot dir.
        assertTrue(cp.startsWith(devtools.first().toString()))
    }
}
