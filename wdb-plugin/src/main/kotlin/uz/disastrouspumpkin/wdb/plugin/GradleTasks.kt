package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Paths

/**
 * The project's real Gradle task paths (e.g. `:wdb-dummy-app:jar`), read from the imported
 * External System model (stable public API — design D6). Empty if the project isn't a Gradle
 * project or hasn't been imported yet.
 */
fun listGradleTaskPaths(project: Project): List<String> {
    val basePath = project.basePath ?: return emptyList()
    val projectNode = ExternalSystemApiUtil.findProjectNode(project, GradleConstants.SYSTEM_ID, basePath)
        ?: return emptyList()
    val out = LinkedHashSet<String>()
    for (module in ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)) {
        val moduleData: ModuleData = module.data
        for (taskNode in ExternalSystemApiUtil.findAll(module, ProjectKeys.TASK)) {
            val task: TaskData = taskNode.data
            // Prefer the fully-qualified path (`:sub:task`); fall back to the bare name.
            val path = task.name
            out += if (path.startsWith(":")) path else "${moduleGradlePath(moduleData)}$path"
        }
    }
    return out.sorted()
}

/** The `packageUberJarForCurrentOS` task only exists on a module with the Compose Desktop plugin. */
private const val COMPOSE_UBER_JAR_TASK = "packageUberJarForCurrentOS"

/**
 * A suggested deploy config for a Compose Desktop module: its uber-jar task, its jars output dir,
 * and the compiled-classes dir used for hot-reload delta pushes.
 */
data class ComposeTarget(val taskPath: String, val jarsDir: String, val classesDir: String)

/**
 * Detect Compose Desktop modules and suggest a deploy config for each: the `packageUberJarForCurrentOS`
 * task path plus its `build/compose/jars` output dir. Used to pre-fill the deploy dialog. The task's
 * presence is the signal that `org.jetbrains.compose` (desktop) is applied — no plugin introspection.
 */
fun listComposeDesktopTargets(project: Project): List<ComposeTarget> {
    val basePath = project.basePath ?: return emptyList()
    val projectNode = ExternalSystemApiUtil.findProjectNode(project, GradleConstants.SYSTEM_ID, basePath)
        ?: return emptyList()
    val out = ArrayList<ComposeTarget>()
    for (module in ExternalSystemApiUtil.findAll(projectNode, ProjectKeys.MODULE)) {
        val moduleData: ModuleData = module.data
        val hasUberJar = ExternalSystemApiUtil.findAll(module, ProjectKeys.TASK)
            .any { it.data.name.substringAfterLast(':') == COMPOSE_UBER_JAR_TASK }
        if (!hasUberJar) continue
        val taskPath = "${moduleGradlePath(moduleData)}$COMPOSE_UBER_JAR_TASK"
        val moduleDir = moduleData.linkedExternalProjectPath
        val jarsDir = Paths.get(moduleDir, "build", "compose", "jars").toString()
        val classesDir = Paths.get(moduleDir, "build", "classes", "kotlin", "main").toString()
        out += ComposeTarget(taskPath, jarsDir, classesDir)
    }
    return out
}

/** ":" for the root module, ":sub:" for subprojects, so a bare task name becomes a path. */
private fun moduleGradlePath(module: ModuleData): String {
    val id = module.id // e.g. ":wdb-dummy-app" or the root project name
    return when {
        id.startsWith(":") -> if (id.endsWith(":")) id else "$id:"
        else -> ":"
    }
}
