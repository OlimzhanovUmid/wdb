package uz.disastrouspumpkin.wdb.agent

import uz.disastrouspumpkin.wdb.agent.win.JobObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class JobObjectTest {

    private val dummyJar = Path.of(System.getProperty("wdb.dummyJar")).toString()
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun `closing the job kills its assigned process`() {
        assumeTrue(isWindows, "Job Objects are Windows-only")
        val java = defaultJavaExecutable().toString()
        val process = ProcessBuilder(java, "-jar", dummyJar).start()
        try {
            val job = JobObject()
            job.assign(process.pid())
            assertTrue(process.isAlive)

            job.close() // KILL_ON_JOB_CLOSE fires on the last handle closing

            assertTrue(
                process.waitFor(5, TimeUnit.SECONDS),
                "process should be terminated when the job handle closes",
            )
        } finally {
            process.destroyForcibly()
        }
    }
}
