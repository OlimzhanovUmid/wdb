package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.protocol.AgentUpdateManifest
import uz.disastrouspumpkin.wdb.protocol.ErrorCode
import uz.disastrouspumpkin.wdb.protocol.ProtocolError
import uz.disastrouspumpkin.wdb.protocol.PushResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Applies an agent self-update (design D3/D4): verify the app-image zip, extract it
 * as a new version, mark the update in flight, and switch `current` to it. Restarting
 * onto the new version is a separate, injected step ([restart]) so the filesystem work
 * is testable without terminating the process.
 */
class SelfUpdater(
    private val layout: AgentInstallLayout,
    private val restart: () -> Unit,
) {
    /** Verify + extract + switch. Consumes [tempZip]. Does NOT restart. */
    fun apply(manifest: AgentUpdateManifest, tempZip: Path): PushResult {
        layout.log("apply start ver=${manifest.version} zipSize=${manifest.size}")
        try {
            val size = Files.size(tempZip)
            if (size != manifest.size) {
                layout.log("apply reject: size mismatch (got $size, want ${manifest.size})")
                return PushResult(ok = false, error = ProtocolError(ErrorCode.INTEGRITY_FAILED, "size mismatch"))
            }
            val sha = sha256(tempZip)
            if (sha != manifest.sha256) {
                layout.log("apply reject: checksum mismatch")
                return PushResult(ok = false, error = ProtocolError(ErrorCode.INTEGRITY_FAILED, "checksum mismatch"))
            }
            val previous = layout.currentVersion()
            layout.extract(tempZip, manifest.version)
            layout.log("extracted versions/${manifest.version}")
            // Switching is just a text-file write now (launcher stub reads current-version),
            // so the applier can do it directly — no junction to be blocked on.
            layout.switchTo(manifest.version)
            layout.writeMarker(
                PendingUpdate(
                    previousVersion = previous,
                    newVersion = manifest.version,
                    createdMillis = System.currentTimeMillis(),
                ),
            )
            layout.log("switchTo ${manifest.version} (prev=$previous), marker written")
            return PushResult(ok = true, deployedSha = manifest.sha256)
        } catch (e: Throwable) {
            layout.log("apply error: ${e.message ?: e.toString()}")
            return PushResult(ok = false, error = ProtocolError(ErrorCode.INTERNAL, e.message ?: e.toString()))
        } finally {
            runCatching { Files.deleteIfExists(tempZip) }
        }
    }

    /** Production: spawn the watchdog, ask Task Scheduler to relaunch, and exit. */
    fun triggerRestart() = restart()
}
