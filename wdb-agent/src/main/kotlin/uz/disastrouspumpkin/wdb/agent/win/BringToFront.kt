package uz.disastrouspumpkin.wdb.agent.win

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions

private interface User32Ext : Library {
    fun EnumWindows(cb: WNDENUMPROC, data: Pointer?): Boolean
    fun GetWindowThreadProcessId(hWnd: HWND, pid: IntByReference): Int
    fun IsWindowVisible(hWnd: HWND): Boolean
    fun GetWindowTextW(hWnd: HWND, buf: CharArray, max: Int): Int
    fun IsIconic(hWnd: HWND): Boolean
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean
    fun SetWindowPos(hWnd: HWND, insertAfter: HWND, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun BringWindowToTop(hWnd: HWND): Boolean
    fun SetForegroundWindow(hWnd: HWND): Boolean

    companion object {
        val INSTANCE: User32Ext = Native.load("user32", User32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * Raise a running app's window to the foreground on the wall (change add-bring-to-front). The Compose
 * window often sits behind others after a redeploy / RDP / operator action; this pulls it to the top.
 * `SetForegroundWindow` alone is blocked by Windows' foreground-lock when the agent isn't the foreground
 * process, so the reliable part is the top-most toggle (HWND_TOPMOST → HWND_NOTOPMOST); focus is a
 * best-effort add-on.
 */
object BringToFront {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private const val SW_RESTORE = 9
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_SHOWWINDOW = 0x0040
    private val HWND_TOPMOST = HWND(Pointer.createConstant(-1))
    private val HWND_NOTOPMOST = HWND(Pointer.createConstant(-2))

    /** Raise [pid]'s main window; returns false when not Windows, no window is found, or the call fails. */
    fun bringToFront(pid: Long): Boolean {
        if (!isWindows) return false
        return runCatching {
            val u = User32Ext.INSTANCE
            var found: HWND? = null
            u.EnumWindows(
                WNDENUMPROC { hWnd, _ ->
                    val owner = IntByReference()
                    u.GetWindowThreadProcessId(hWnd, owner)
                    if (owner.value.toLong() == pid && u.IsWindowVisible(hWnd)) {
                        val buf = CharArray(512)
                        if (u.GetWindowTextW(hWnd, buf, buf.size) > 0) { // a titled top-level window
                            found = hWnd
                            return@WNDENUMPROC false // stop enumerating
                        }
                    }
                    true
                },
                null,
            )
            val hWnd = found ?: return false
            if (u.IsIconic(hWnd)) u.ShowWindow(hWnd, SW_RESTORE)
            val flags = SWP_NOMOVE or SWP_NOSIZE or SWP_SHOWWINDOW
            u.SetWindowPos(hWnd, HWND_TOPMOST, 0, 0, 0, 0, flags) // raise above everything…
            u.SetWindowPos(hWnd, HWND_NOTOPMOST, 0, 0, 0, 0, flags) // …then release top-most
            u.BringWindowToTop(hWnd)
            u.SetForegroundWindow(hWnd) // best-effort focus
            true
        }.getOrDefault(false)
    }
}
