package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val TASK_NAME: String = "wdb-agent"
const val FIREWALL_RULE_NAME: String = "wdb-agent"

/**
 * Installs/uninstalls the agent as a Task Scheduler logon task (design D11):
 * runs in the kiosk user's interactive session, restarts on failure, starts a few
 * seconds after logon. Also adds the inbound firewall rule for the agent binary.
 * Requires elevation; verification runs on a real box (see scripts/verify-install.ps1).
 */
class InstallManager(
    private val launcherPath: String = currentLauncherPath(),
    private val user: String = defaultUser(),
) {
    fun install(machineName: String, jdwpPort: Int? = null): List<String> {
        val log = mutableListOf<String>()

        // Establish the versioned layout (design D2): copy this app-image under
        // <base>/agent/versions/<version>/, set current-version, and write a launcher
        // stub the task runs — so self-update only rewrites current-version (no junction).
        val launchCmd = layOutVersioned(machineName, jdwpPort, log)

        val xml = taskXml(launchCmd)
        val xmlFile = Files.createTempFile("wdb-task", ".xml")
        // Task Scheduler XML must be UTF-16 LITTLE-endian with a BOM. Charsets.UTF_16
        // writes big-endian, which schtasks rejects with an invalid-character error.
        val leBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        Files.write(xmlFile, leBom + xml.toByteArray(Charsets.UTF_16LE))
        try {
            log += run("schtasks", "/create", "/tn", TASK_NAME, "/xml", xmlFile.toString(), "/f")
            // Port-based rules (not program-based): survive version changes, since the
            // listening exe path changes per version.
            log += run(
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=$FIREWALL_RULE_NAME", "dir=in", "action=allow",
                "protocol=TCP", "localport=$DEFAULT_AGENT_PORT", "enable=yes",
            )
            log += run(
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=$FIREWALL_RULE_NAME", "dir=in", "action=allow",
                "protocol=UDP", "localport=${uz.disastrouspumpkin.wdb.client.DEFAULT_DISCOVERY_PORT}", "enable=yes",
            )
        } finally {
            Files.deleteIfExists(xmlFile)
        }
        return log
    }

    /**
     * Materialize `<base>/agent/versions/<version>/` (a copy of this app-image, unless
     * already there), set current-version, and write the launcher stub. base = the
     * directory that contains this app-image folder. Returns the launcher path.
     */
    private fun layOutVersioned(machineName: String, jdwpPort: Int?, log: MutableList<String>): String {
        val exe = Path.of(launcherPath)
        val appImageDir = exe.parent ?: return launcherPath
        val layout = AgentInstallLayout(appImageDir.parent ?: appImageDir)
        val versionDir = layout.versionDir(AGENT_VERSION)
        if (appImageDir.normalize() != versionDir.normalize() && !Files.exists(versionDir)) {
            copyDir(appImageDir, versionDir)
            log += "copied app-image -> $versionDir"
        }
        layout.switchTo(AGENT_VERSION)
        layout.writeLauncher(machineName, jdwpPort)
        return layout.launchCmd.toString()
    }

    private fun copyDir(from: Path, to: Path) {
        Files.walk(from).use { walk ->
            walk.forEach { src ->
                val dst = to.resolve(from.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dst)
                else { Files.createDirectories(dst.parent); Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    fun uninstall(): List<String> = buildList {
        add(run("schtasks", "/delete", "/tn", TASK_NAME, "/f"))
        add(run("netsh", "advfirewall", "firewall", "delete", "rule", "name=$FIREWALL_RULE_NAME"))
    }

    private fun taskXml(launchCmd: String): String {
        return """
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo>
            <Description>Windows Debug Bridge agent (demo-wall)</Description>
          </RegistrationInfo>
          <Triggers>
            <LogonTrigger>
              <Enabled>true</Enabled>
              <Delay>PT15S</Delay>
              <UserId>$user</UserId>
            </LogonTrigger>
          </Triggers>
          <Principals>
            <Principal id="Author">
              <UserId>$user</UserId>
              <LogonType>InteractiveToken</LogonType>
              <RunLevel>LeastPrivilege</RunLevel>
            </Principal>
          </Principals>
          <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
            <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
            <StartWhenAvailable>true</StartWhenAvailable>
            <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
            <RestartOnFailure>
              <Interval>PT1M</Interval>
              <Count>3</Count>
            </RestartOnFailure>
          </Settings>
          <Actions Context="Author">
            <Exec>
              <Command>C:\Windows\System32\cmd.exe</Command>
              <Arguments>/c "$launchCmd"</Arguments>
            </Exec>
          </Actions>
        </Task>
        """.trimIndent()
    }

    private fun run(vararg cmd: String): String {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString().trim()
        val code = proc.waitFor()
        return "[$code] ${cmd.joinToString(" ")} -> $out"
    }
}

private fun currentLauncherPath(): String =
    ProcessHandle.current().info().command().orElse("wdb-agent.exe")

private fun defaultUser(): String {
    val domain = System.getenv("USERDOMAIN")
    val name = System.getProperty("user.name")
    return if (domain.isNullOrBlank()) name else "$domain\\$name"
}
