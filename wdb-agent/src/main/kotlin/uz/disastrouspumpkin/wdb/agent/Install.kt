package uz.disastrouspumpkin.wdb.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val TASK_NAME: String = "wdb-agent"
const val FIREWALL_RULE_NAME: String = "wdb-agent"

/**
 * Installs/uninstalls the agent as a Task Scheduler logon task (design D11): runs in the kiosk
 * user's interactive session, restarts on failure, starts a few seconds after logon. Also adds the
 * inbound firewall rule. Requires elevation; verification runs on a real box (scripts/verify-install.ps1).
 *
 * Two entry points share the "wire it up" step ([finalize]):
 *  - [install]  — legacy zip+ps1 path: detect the base from the running exe, copy the app-image into
 *                 the versioned layout, then finalize.
 *  - [finalize] — installer path (change add-agent-inno-installer): the installer already placed the
 *                 app-image at `<base>/agent/versions/<ver>/`, so just write the pointer + launcher +
 *                 task + firewall (no copy). [base] and the run [user] are passed explicitly.
 *
 * [exec] runs the system commands (schtasks/netsh/taskkill); injectable so tests can record without
 * touching the machine.
 */
class InstallManager(
    private val launcherPath: String = currentLauncherPath(),
    private val user: String = defaultUser(),
    private val exec: (List<String>) -> String = ::realRun,
) {
    /** Legacy path: copy this app-image into the versioned layout under the detected base, then finalize. */
    fun install(machineName: String, jdwpPort: Int? = null): List<String> {
        val log = mutableListOf<String>()
        val exe = Path.of(launcherPath)
        val appImageDir = exe.parent ?: return log
        val base = appImageDir.parent ?: appImageDir
        val layout = AgentInstallLayout(base)
        val versionDir = layout.versionDir(AGENT_VERSION)
        if (appImageDir.normalize() != versionDir.normalize() && !Files.exists(versionDir)) {
            copyDir(appImageDir, versionDir)
            log += "copied app-image -> $versionDir"
        }
        finalizeInto(base, machineName, jdwpPort, log)
        return log
    }

    /** Installer path: app-image already at `<base>/agent/versions/<ver>/`; wire it up without copying. */
    fun finalize(base: Path, machineName: String, jdwpPort: Int? = null): List<String> {
        val log = mutableListOf<String>()
        finalizeInto(base, machineName, jdwpPort, log)
        return log
    }

    private fun finalizeInto(base: Path, machineName: String, jdwpPort: Int?, log: MutableList<String>) {
        val layout = AgentInstallLayout(base)
        layout.switchTo(AGENT_VERSION)
        layout.writeLauncher(machineName, jdwpPort)
        registerTaskAndFirewall(layout.launchCmd.toString(), log)
    }

    private fun registerTaskAndFirewall(launchCmd: String, log: MutableList<String>) {
        val xml = taskXml(launchCmd)
        val xmlFile = Files.createTempFile("wdb-task", ".xml")
        // Task Scheduler XML must be UTF-16 LITTLE-endian with a BOM. Charsets.UTF_16 writes
        // big-endian, which schtasks rejects with an invalid-character error.
        val leBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        Files.write(xmlFile, leBom + xml.toByteArray(Charsets.UTF_16LE))
        try {
            log += exec(listOf("schtasks", "/create", "/tn", TASK_NAME, "/xml", xmlFile.toString(), "/f"))
            // Port-based rules (not program-based): survive version changes, since the listening
            // exe path changes per version.
            log += exec(
                listOf(
                    "netsh", "advfirewall", "firewall", "add", "rule",
                    "name=$FIREWALL_RULE_NAME", "dir=in", "action=allow",
                    "protocol=TCP", "localport=$DEFAULT_AGENT_PORT", "enable=yes",
                ),
            )
            log += exec(
                listOf(
                    "netsh", "advfirewall", "firewall", "add", "rule",
                    "name=$FIREWALL_RULE_NAME", "dir=in", "action=allow",
                    "protocol=UDP", "localport=${uz.disastrouspumpkin.wdb.client.DEFAULT_DISCOVERY_PORT}", "enable=yes",
                ),
            )
        } finally {
            Files.deleteIfExists(xmlFile)
        }
    }

    /**
     * Reverse the install. Stop the running agent first (end the task's instance, then kill any
     * lingering `wdb-agent.exe` EXCEPT this process — the uninstaller itself is a wdb-agent.exe) so
     * the versioned exe isn't locked when the installer removes files; then delete the task + firewall.
     */
    fun uninstall(): List<String> = buildList {
        val self = ProcessHandle.current().pid()
        add(exec(listOf("schtasks", "/end", "/tn", TASK_NAME)))
        add(exec(listOf("taskkill", "/f", "/im", "wdb-agent.exe", "/fi", "PID ne $self")))
        add(exec(listOf("schtasks", "/delete", "/tn", TASK_NAME, "/f")))
        add(exec(listOf("netsh", "advfirewall", "firewall", "delete", "rule", "name=$FIREWALL_RULE_NAME")))
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
}

private fun realRun(cmd: List<String>): String {
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = proc.inputStream.readBytes().decodeToString().trim()
    val code = proc.waitFor()
    return "[$code] ${cmd.joinToString(" ")} -> $out"
}

private fun currentLauncherPath(): String =
    ProcessHandle.current().info().command().orElse("wdb-agent.exe")

private fun defaultUser(): String {
    val domain = System.getenv("USERDOMAIN")
    val name = System.getProperty("user.name")
    return if (domain.isNullOrBlank()) name else "$domain\\$name"
}
