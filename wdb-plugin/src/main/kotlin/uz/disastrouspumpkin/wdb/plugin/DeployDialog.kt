package uz.disastrouspumpkin.wdb.plugin

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Deploy config dialog (design D6): a free-text Gradle-task field whose browse button opens a
 * type-to-filter task picker (the project's real tasks, filtered like Gradle's "Run Anything"), plus
 * a jar file-picker. Free text is kept so a task can be typed if the project isn't imported yet.
 * Uses a [JBPopupFactory] chooser (with speed-search filtering) rather than an editable combo, whose
 * dropdown can't filter its items without fighting the editor.
 */
class DeployDialog(project: Project) : DialogWrapper(project) {
    private val allTasks: List<String> = listGradleTaskPaths(project)
    private val composeTargets: List<ComposeTarget> = listComposeDesktopTargets(project)
    private val taskField = ExtendableTextField()
    private val jarField = TextFieldWithBrowseButton()
    private val classesField = TextFieldWithBrowseButton()
    private val useComposeDefaultButton = JButton("Use Compose Default")

    init {
        title = "wdb — Configure Deploy"
        val settings = WdbSettings.get(project).state
        // Give both fields a real body width so they don't collapse to a few characters.
        taskField.columns = 40
        jarField.textField.columns = 40
        classesField.textField.columns = 40
        // Unconfigured project: pre-fill from a detected Compose Desktop module (uber-jar task + its
        // jars dir), so the common case needs no typing. Existing values always win.
        val unconfigured = settings.gradleTask.isBlank() && settings.jarPath.isBlank()
        val suggested = if (unconfigured) composeTargets.firstOrNull() else null
        taskField.text = settings.gradleTask.ifBlank { suggested?.taskPath.orEmpty() }
        // Inline dropdown-arrow extension opens the filtered task chooser; the field stays free-text.
        taskField.addExtension(
            ExtendableTextComponent.Extension.create(AllIcons.General.ArrowDown, "Choose Gradle task") {
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(allTasks)
                    .setTitle("Gradle Tasks")
                    .setNamerForFiltering { it }
                    .setFilterAlwaysVisible(true)
                    .setItemChosenCallback { taskField.text = it }
                    .createPopup()
                    .showUnderneathOf(taskField)
            },
        )
        jarField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleLocalFileDescriptor()
                .withExtensionFilter("jar")
                .withTitle("Select Jar To Deploy"),
        )
        jarField.text = settings.jarPath.ifBlank { suggested?.jarsDir.orEmpty() }
        classesField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Compiled-Classes Dir (Hot Reload)"),
        )
        classesField.text = settings.classesDir.ifBlank { suggested?.classesDir.orEmpty() }
        // One click fills both fields from a Compose Desktop module (chooser if several); disabled
        // when the project has none.
        useComposeDefaultButton.isEnabled = composeTargets.isNotEmpty()
        useComposeDefaultButton.addActionListener {
            when (composeTargets.size) {
                0 -> {}
                1 -> applyTarget(composeTargets.first())
                else -> JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(composeTargets.map { it.taskPath })
                    .setTitle("Compose Desktop Modules")
                    .setItemChosenCallback { path -> composeTargets.first { it.taskPath == path }.let(::applyTarget) }
                    .createPopup()
                    .showUnderneathOf(useComposeDefaultButton)
            }
        }
        init()
    }

    private fun applyTarget(target: ComposeTarget) {
        taskField.text = target.taskPath
        jarField.text = target.jarsDir
        classesField.text = target.classesDir
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Gradle task:", taskField)
            .addLabeledComponent("Jar file:", jarField)
            .addLabeledComponent("Classes dir (hot reload):", classesField)
            .addComponentToRightColumn(useComposeDefaultButton)
            .panel

    fun gradleTask(): String = taskField.text.trim()
    fun jarPath(): String = jarField.text.trim()
    fun classesDir(): String = classesField.text.trim()
}

/** Open the deploy dialog and persist on OK. Returns true if saved. */
fun configureDeploy(project: Project): Boolean {
    val dialog = DeployDialog(project)
    if (!dialog.showAndGet()) return false
    WdbSettings.get(project).state.apply {
        gradleTask = dialog.gradleTask()
        jarPath = dialog.jarPath()
        classesDir = dialog.classesDir()
    }
    return true
}

/** Deploy to [targets]: configure first if unset (opens the dialog), then run the task + push. */
fun deployTargets(project: Project, service: WdbService, targets: List<MachineUi>) {
    if (targets.isEmpty()) {
        service.notify("Deploy: no machines", NotificationType.WARNING)
        return
    }
    if (!WdbSettings.get(project).isConfigured && !configureDeploy(project)) return
    runGradleThenDeploy(project, service, WdbSettings.get(project).state, targets)
}
