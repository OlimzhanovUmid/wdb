package uz.disastrouspumpkin.wdb.plugin

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationType
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

/**
 * Run a single Gradle [taskName] and call [onDone] with whether it succeeded. Used to compile the
 * module before a hot-reload push. The Gradle build tool window shows its own progress.
 */
fun runGradleTask(project: Project, taskName: String, onDone: (Boolean) -> Unit) {
    val exec = ExternalSystemTaskExecutionSettings().apply {
        externalProjectPath = project.basePath
        taskNames = listOf(taskName)
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
    }
    ExternalSystemUtil.runTask(
        exec,
        DefaultRunExecutor.EXECUTOR_ID,
        project,
        GradleConstants.SYSTEM_ID,
        object : TaskCallback {
            override fun onSuccess() = onDone(true)
            override fun onFailure() = onDone(false)
        },
        ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    )
}

/**
 * Run the configured Gradle task, then push its output jar to the selected machines (design D6).
 * A build failure aborts before any push.
 */
fun runGradleThenDeploy(project: Project, service: WdbService, settings: WdbSettings.State, machines: List<MachineUi>) {
    if (settings.gradleTask.isBlank()) {
        service.notify("Deploy: no Gradle task configured", NotificationType.WARNING)
        return
    }
    val exec = ExternalSystemTaskExecutionSettings().apply {
        externalProjectPath = project.basePath
        taskNames = listOf(settings.gradleTask)
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
    }
    ExternalSystemUtil.runTask(
        exec,
        DefaultRunExecutor.EXECUTOR_ID,
        project,
        GradleConstants.SYSTEM_ID,
        object : TaskCallback {
            override fun onSuccess() {
                val jar = resolveJar(settings.jarPath)
                if (jar == null) {
                    service.notify("Deploy: no jar found near '${settings.jarPath}'", NotificationType.ERROR)
                    return
                }
                val main = readMainClass(jar)
                if (main.isNullOrBlank()) {
                    service.notify("Deploy: no Main-Class in $jar", NotificationType.ERROR)
                    return
                }
                service.pushJar(machines, jar, main)
            }

            override fun onFailure() {
                service.notify("Deploy: Gradle task '${settings.gradleTask}' failed — nothing pushed", NotificationType.ERROR)
            }
        },
        ProgressExecutionMode.IN_BACKGROUND_ASYNC,
    )
}

/**
 * Resolve the jar to push: the newest `*.jar` in the jars folder. [jarPath] may be that folder
 * (as pre-filled for a Compose Desktop module) or an example jar inside it — either way we take the
 * folder's newest, so it survives version bumps (`app-1.0.jar` → `app-1.1.jar`). Falls back to the
 * picked file itself if the folder can't be listed.
 */
internal fun resolveJar(jarPath: String): Path? {
    if (jarPath.isBlank()) return null
    val picked = Paths.get(jarPath)
    // A directory path resolves against itself; a file path resolves against its folder.
    val dir = if (Files.isDirectory(picked)) picked else picked.parent ?: return picked.takeIf { Files.isRegularFile(it) }
    if (!Files.isDirectory(dir)) return picked.takeIf { Files.isRegularFile(it) }
    return Files.list(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
            .max(compareBy { Files.getLastModifiedTime(it) })
            .orElse(picked.takeIf { Files.isRegularFile(it) })
    }
}

internal fun readMainClass(jar: Path): String? =
    runCatching { JarFile(jar.toFile()).use { it.manifest?.mainAttributes?.getValue("Main-Class") } }.getOrNull()
