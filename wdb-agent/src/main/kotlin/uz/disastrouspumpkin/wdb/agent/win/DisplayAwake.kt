package uz.disastrouspumpkin.wdb.agent.win

import com.sun.jna.platform.win32.Kernel32
import java.util.concurrent.Executors

/**
 * Keeps the display on and the machine awake while the app runs (design D20).
 *
 * SetThreadExecutionState scopes the ES_CONTINUOUS requirement to the CALLING
 * thread, and Windows clears it when that thread exits. Supervisor calls
 * [keepAwake]/[release] from short-lived request/watcher threads, so the state
 * MUST be set and cleared on one long-lived thread — here a dedicated
 * single-thread executor that lives for the process. No-op on non-Windows.
 */
object DisplayAwake {
    private const val ES_CONTINUOUS: Int = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED: Int = 0x00000001
    private const val ES_DISPLAY_REQUIRED: Int = 0x00000002

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    private val holder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wdb-display-awake").apply { isDaemon = true }
    }

    fun keepAwake() {
        if (!isWindows) return
        holder.submit { Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED) }
    }

    fun release() {
        if (!isWindows) return
        holder.submit { Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS) }
    }
}
