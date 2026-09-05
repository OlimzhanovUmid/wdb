package uz.disastrouspumpkin.wdb.dummy

import kotlin.system.exitProcess

/**
 * Minimal stand-in app for agent tests. Prints its machine identity and a
 * heartbeat to stdout/stderr, and can be told to crash after N ticks so
 * auto-restart and log-boundary behaviour can be exercised.
 *
 * Flags: --crash-after=N  --exit-code=C  --tick-ms=M
 */
fun main(args: Array<String>) {
    fun flag(name: String): String? =
        args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")

    val machine = System.getenv("WDB_MACHINE_NAME") ?: "unknown"
    val id = System.getenv("WDB_MACHINE_ID") ?: "?"
    val crashAfter = flag("crash-after")?.toIntOrNull()
    val exitCode = flag("exit-code")?.toIntOrNull() ?: 1
    val tickMs = flag("tick-ms")?.toLongOrNull() ?: 150L

    println("dummy-app start machine=$machine id=$id pid=${ProcessHandle.current().pid()}")
    System.out.flush()

    // --crash-once=<marker>: crash on the first run (marker absent), run normally after.
    flag("crash-once")?.let { markerPath ->
        val marker = java.nio.file.Path.of(markerPath)
        if (!java.nio.file.Files.exists(marker)) {
            java.nio.file.Files.writeString(marker, "crashed")
            println("crashing once")
            System.out.flush()
            exitProcess(exitCode)
        }
    }

    var i = 0
    while (true) {
        println("tick $i")
        System.err.println("err $i")
        System.out.flush()
        System.err.flush()
        if (crashAfter != null && i + 1 >= crashAfter) {
            exitProcess(exitCode)
        }
        Thread.sleep(tickMs)
        i++
    }
}
