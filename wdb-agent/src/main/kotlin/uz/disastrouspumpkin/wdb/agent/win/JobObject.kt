package uz.disastrouspumpkin.wdb.agent.win

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.win32.W32APIOptions
import java.io.Closeable

private interface JobApi : Library {
    fun CreateJobObjectW(lpJobAttributes: Pointer?, lpName: WString?): HANDLE?
    fun SetInformationJobObject(hJob: HANDLE, infoClass: Int, lpInfo: Pointer, cb: Int): Boolean
    fun AssignProcessToJobObject(hJob: HANDLE, hProcess: HANDLE): Boolean
    fun OpenProcess(dwDesiredAccess: Int, bInheritHandle: Boolean, dwProcessId: Int): HANDLE?
    fun TerminateJobObject(hJob: HANDLE, uExitCode: Int): Boolean
    fun CloseHandle(hObject: HANDLE): Boolean

    companion object {
        val INSTANCE: JobApi = Native.load("kernel32", JobApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * A Windows Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` (design D16).
 * Every launched app process is [assign]ed to it. While the agent lives it keeps
 * the job handle open; when the agent process dies, Windows closes the handle and
 * kills all assigned processes — so the app can never outlive its agent.
 */
class JobObject : Closeable {
    private val api = JobApi.INSTANCE
    private val handle: HANDLE = api.CreateJobObjectW(null, null)
        ?: error("CreateJobObject failed: ${Native.getLastError()}")

    init {
        // JOBOBJECT_EXTENDED_LIMIT_INFORMATION is 144 bytes on x64; LimitFlags is a
        // DWORD at offset 16 inside the leading BASIC_LIMIT_INFORMATION.
        val info = Memory(EXTENDED_LIMIT_INFO_SIZE)
        info.clear()
        info.setInt(LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        check(api.SetInformationJobObject(handle, JobObjectExtendedLimitInformation, info, EXTENDED_LIMIT_INFO_SIZE.toInt())) {
            "SetInformationJobObject failed: ${Native.getLastError()}"
        }
    }

    /** Assign a process (by PID) to the job. */
    fun assign(pid: Long) {
        val proc = api.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, pid.toInt())
            ?: error("OpenProcess($pid) failed: ${Native.getLastError()}")
        try {
            check(api.AssignProcessToJobObject(handle, proc)) {
                "AssignProcessToJobObject failed: ${Native.getLastError()}"
            }
        } finally {
            api.CloseHandle(proc)
        }
    }

    /** Kill every process in the job immediately. */
    fun terminateAll(exitCode: Int = 0) {
        api.TerminateJobObject(handle, exitCode)
    }

    /** Closing the last handle triggers KILL_ON_JOB_CLOSE on all assigned processes. */
    override fun close() {
        api.CloseHandle(handle)
    }

    private companion object {
        const val JobObjectExtendedLimitInformation = 9
        const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
        const val PROCESS_TERMINATE = 0x0001
        const val PROCESS_SET_QUOTA = 0x0100
        const val EXTENDED_LIMIT_INFO_SIZE = 144L
        const val LIMIT_FLAGS_OFFSET = 16L
    }
}
