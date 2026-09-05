package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Per-project deploy config (design D6), persisted in the project's workspace. Edited via [DeployDialog]. */
@Service(Service.Level.PROJECT)
@State(name = "WdbSettings", storages = [Storage("wdb.xml")])
class WdbSettings : PersistentStateComponent<WdbSettings.State> {
    data class State(
        var gradleTask: String = "",
        var jarPath: String = "",
        /** Compiled-classes dir for hot-reload delta pushes (classpath root, e.g. build/classes/kotlin/main). */
        var classesDir: String = "",
        /** When on, saving a JVM source debounces + reloads all hot machines (add-plugin-auto-reload). */
        var autoReloadOnSave: Boolean = false,
        /** "Don't ask again": hot-run on a running app restarts it without confirming (add-plugin-restart-affordance). */
        var hotRunRestartConfirmed: Boolean = false,
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    val isConfigured: Boolean get() = state.gradleTask.isNotBlank() && state.jarPath.isNotBlank()

    companion object {
        fun get(project: Project): WdbSettings = project.service()
    }
}
