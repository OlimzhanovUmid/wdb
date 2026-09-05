package uz.disastrouspumpkin.wdb.plugin

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.filters.TextConsoleBuilderImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.ActionLink
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import uz.disastrouspumpkin.wdb.client.AgentAddress
import uz.disastrouspumpkin.wdb.client.ClassDiff
import uz.disastrouspumpkin.wdb.client.ComponentRelease
import uz.disastrouspumpkin.wdb.client.isNewerVersion
import uz.disastrouspumpkin.wdb.client.ClassSnapshot
import uz.disastrouspumpkin.wdb.client.ReloadReport
import uz.disastrouspumpkin.wdb.client.WdbClient
import uz.disastrouspumpkin.wdb.client.reloadOrRedeploy
import uz.disastrouspumpkin.wdb.protocol.AppState
import uz.disastrouspumpkin.wdb.protocol.DroppedMarker
import uz.disastrouspumpkin.wdb.protocol.ErrorCode
import uz.disastrouspumpkin.wdb.protocol.ProtocolError
import uz.disastrouspumpkin.wdb.protocol.PushResult
import uz.disastrouspumpkin.wdb.protocol.LogLine
import uz.disastrouspumpkin.wdb.protocol.LogStream
import uz.disastrouspumpkin.wdb.protocol.RunBoundary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** A machine as shown in the tool window: discovery identity enriched with live status. */
data class MachineUi(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val appState: String,
    val hot: Boolean,
    val hasPrevious: Boolean,
    val agentVersion: String,
) {
    val address: AgentAddress get() = AgentAddress(host, port)
}

private const val CARD_CONSOLE = "console"
private const val CARD_EMPTY = "empty"

/**
 * Project-level state holder (design D3). Embeds [WdbClient] on the injected [cs] (cancelled with
 * the project), exposes a [StateFlow] of discovered machines the Compose UI collects, and runs all
 * wdb operations off the EDT on [cs]. Mirrors Android Studio's Device Manager v2 pattern.
 */
@Service(Service.Level.PROJECT)
class WdbService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val client = WdbClient(cs)

    /**
     * The native IDE console the "Logs" tool-window tab hosts; log lines stream into it. Predefined
     * message filters are OFF: they turn the app's remote `Listening for transport dt_socket at
     * address: …:5005` stdout into a bogus "Attach debugger" link that connects to the DEV machine's
     * localhost (refused — the app runs on the wall). Debugging goes through the Wall's Debug button.
     */
    val logConsole: ConsoleView by lazy {
        val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        (builder as? TextConsoleBuilderImpl)?.setUsePredefinedMessageFilter(false)
        builder.console.also { Disposer.register(this, it) }
    }

    /** LogCat-style machine selector inside the Logs tab; switching it streams that machine's logs. */
    private val deviceCombo = ComboBox<String>()
    private var updatingCombo = false

    /**
     * The console plus its native action toolbar (Clear, Scroll to End, Soft-Wrap, …) — a bare
     * [ConsoleView] component ships none — and a top machine selector (LogCat-style). Find (Ctrl+F)
     * is built into the console editor.
     */
    val logPanel: JComponent by lazy {
        val actions = DefaultActionGroup().apply { addAll(*logConsole.createConsoleActions()) }
        val toolbar = ActionManager.getInstance().createActionToolbar("WdbLogs", actions, false)
        toolbar.targetComponent = logConsole.component
        deviceCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val name = deviceCombo.selectedItem as? String ?: return@addActionListener
            if (name != _logTarget.value) machines.value.firstOrNull { it.name == name }?.let { startLogs(it) }
        }
        val north = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("Machine:"))
            add(deviceCombo)
        }
        val consoleCard = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(logConsole.component, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.WEST)
        }
        // Shown until a machine is discovered: point the user at the wdb window to find/configure one.
        val emptyCard = JPanel(FlowLayout(FlowLayout.CENTER, 6, 20)).apply {
            add(JLabel("No machines yet."))
            add(ActionLink("Open wdb to discover…") {
                ToolWindowManager.getInstance(project).getToolWindow("wdb")?.activate(null)
                refresh()
            })
        }
        val root = JPanel(CardLayout()).apply {
            add(consoleCard, CARD_CONSOLE)
            add(emptyCard, CARD_EMPTY)
        }
        // Keep the selector in sync with discovered machines (preserving the streaming selection) and
        // swap to the empty state whenever the list is empty.
        cs.launch {
            machines.collect { list ->
                ApplicationManager.getApplication().invokeLater {
                    updatingCombo = true
                    deviceCombo.model = DefaultComboBoxModel(list.map { it.name }.toTypedArray())
                    deviceCombo.selectedItem = _logTarget.value
                    updatingCombo = false
                    (root.layout as CardLayout).show(root, if (list.isEmpty()) CARD_EMPTY else CARD_CONSOLE)
                }
            }
        }
        root
    }

    override fun dispose() {} // logConsole is a registered child, disposed with this service

    private val _machines = MutableStateFlow<List<MachineUi>>(emptyList())
    val machines: StateFlow<List<MachineUi>> = _machines.asStateFlow()

    /** Latest published agent release (from the release manifest), or null if unreachable/no release. */
    private val _agentRelease = MutableStateFlow<ComponentRelease?>(null)
    val agentRelease: StateFlow<ComponentRelease?> = _agentRelease.asStateFlow()

    /** True when the release manifest advertises a strictly newer agent than [m] is running. */
    fun agentUpdateAvailable(m: MachineUi): Boolean =
        _agentRelease.value?.let { isNewerVersion(m.agentVersion, it.version) } ?: false

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Live deploy progress per machine id (0f..1f); absent = not deploying. Drives the row progress bar. */
    private val _deployProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val deployProgress: StateFlow<Map<String, Float>> = _deployProgress.asStateFlow()

    private fun setDeployProgress(id: String, fraction: Float) {
        _deployProgress.value = _deployProgress.value + (id to fraction)
    }

    private fun clearDeployProgress(id: String) {
        _deployProgress.value = _deployProgress.value - id
    }

    /** Last deployed sha per machine id (this session), shown on the card. */
    private val _deployInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val deployInfo: StateFlow<Map<String, String>> = _deployInfo.asStateFlow()

    init {
        // Auto-refresh: reflect machines going up/down without a manual Refresh click.
        cs.launch {
            while (isActive) {
                delay(5_000)
                if (!_busy.value) refresh()
            }
        }
        // Auto-reload on save (add-plugin-auto-reload): a JVM-source save in the project debounces
        // and reloads the hot machines. The listener is always registered but early-returns when the
        // toggle is off, so flipping it needs no re-wiring. Connection disposed with the service.
        project.messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    if (!WdbSettings.get(project).state.autoReloadOnSave) return
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (vf.extension != "kt" && vf.extension != "java") return
                    if (!ProjectFileIndex.getInstance(project).isInContent(vf)) return
                    scheduleAutoReload()
                }
            },
        )
    }

    // --- Auto-reload on save (add-plugin-auto-reload) ---
    // Flags are only touched on the EDT (all mutators dispatch there) so single-flight needs no lock.
    private var debounceJob: Job? = null
    private var reloadInFlight = false
    private var reloadPending = false

    /** Debounce a burst of saves into one reload (~300ms after the last save). */
    private fun scheduleAutoReload() {
        debounceJob?.cancel()
        debounceJob = cs.launch {
            delay(300)
            ApplicationManager.getApplication().invokeLater { triggerAutoReload() }
        }
    }

    /** EDT. Start an auto-reload of the hot machines, or queue one if a reload is already running. */
    private fun triggerAutoReload() {
        if (reloadInFlight) { reloadPending = true; return }
        val hot = machines.value.filter { it.hot }
        if (hot.isEmpty()) return
        val dir = classesDir() ?: return
        reloadInFlight = true
        val compileTask = compileTaskFor(WdbSettings.get(project).state.gradleTask)
        runGradleTask(project, compileTask) { ok ->
            if (!ok) {
                notify("Auto-reload: compile '$compileTask' failed — nothing pushed", NotificationType.ERROR)
                finishAutoReload()
                return@runGradleTask
            }
            cs.launch {
                try { pushReload(hot, dir) } finally {
                    ApplicationManager.getApplication().invokeLater { finishAutoReload() }
                }
            }
        }
    }

    /** EDT. Clear the in-flight flag; if edits landed mid-reload, run exactly one follow-up. */
    private fun finishAutoReload() {
        reloadInFlight = false
        if (reloadPending) {
            reloadPending = false
            scheduleAutoReload()
        }
    }

    fun client(): WdbClient = client

    /** Re-discover machines and enrich each with its live status; updates [machines]. */
    fun refresh() {
        cs.launch {
            _busy.value = true
            try {
                val found = client.discover()
                val uis = found.map { m ->
                    async {
                        val st = runCatching { client.status(m.name, m.address) }.getOrNull()
                        MachineUi(
                            id = m.id,
                            name = m.name,
                            host = m.address.host,
                            port = m.address.port,
                            appState = st?.appState?.name ?: m.appState?.name ?: "?",
                            hot = st?.hotMode ?: false,
                            hasPrevious = st?.previousSha != null,
                            agentVersion = st?.agentVersion ?: "?",
                        )
                    }
                }.awaitAll().sortedBy { it.name }
                _machines.value = uis
                // Refresh the published agent version so the UI can flag machines with an update
                // available. Best-effort: offline / no release just leaves it null (no indication).
                _agentRelease.value = withContext(Dispatchers.IO) {
                    runCatching { ReleaseSource.latestManifest()?.get("agent") }.getOrNull()
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Update the agent on [sel] from the published release (change agent-github-pull): download the
     * manifest's agent installer once (verified against sha256+size), then push it to each machine
     * over the existing agent-update wire. Per-machine outcome; one failure never aborts the rest.
     */
    fun updateAgent(sel: List<MachineUi>) {
        if (sel.isEmpty()) return
        val release = _agentRelease.value
        if (release == null) {
            notify("No agent release info — refresh, or check network/releases", NotificationType.WARNING)
            return
        }
        cs.launch {
            val zip = try {
                withContext(Dispatchers.IO) { ReleaseSource.downloadVerified(release) }
            } catch (e: Throwable) {
                notify("Agent update download failed — ${e.message}", NotificationType.ERROR)
                return@launch
            }
            for (m in sel) {
                runCatching {
                    client.agentUpdate(m.name, zip, release.version, m.address) { sent, total ->
                        if (total > 0) setDeployProgress(m.id, sent.toFloat() / total)
                    }
                }.onSuccess { r ->
                    if (r.ok) notify("${m.name}: agent updating to ${release.version} (restarting)", NotificationType.INFORMATION)
                    else notify("${m.name}: agent update rejected — ${r.error?.message ?: "unknown"}", NotificationType.ERROR)
                }.onFailure {
                    notify("${m.name}: agent update failed — ${it.message}", NotificationType.ERROR)
                }
                clearDeployProgress(m.id)
            }
            refresh()
        }
    }

    /**
     * Run [op] on each machine independently (fan-out, design D8): one failure never aborts the
     * others, each reports a per-machine notification, and the list refreshes afterward.
     */
    private fun forEach(machines: List<MachineUi>, label: String, op: suspend (MachineUi) -> Unit) {
        if (machines.isEmpty()) return
        cs.launch {
            for (m in machines) {
                runCatching { op(m) }
                    .onSuccess { notify("${m.name}: $label", NotificationType.INFORMATION) }
                    .onFailure { notify("${m.name}: $label failed — ${it.message}", NotificationType.ERROR) }
            }
            refresh()
        }
    }

    fun run(sel: List<MachineUi>) = forEach(sel, "running") { client.run(it.name, it.address) }
    /**
     * Hot-run each target. A RUNNING app is a no-op for the agent's launch, so we stop it first and
     * relaunch hot — after a remembered confirmation, since that closes the current run
     * (add-plugin-restart-affordance). A non-running machine hot-runs directly.
     */
    fun hotRun(sel: List<MachineUi>) {
        if (sel.isEmpty()) return
        cs.launch {
            for (m in sel) {
                val running = m.appState == "RUNNING"
                if (running && !confirmHotRestart(m)) {
                    notify("${m.name}: hot-run cancelled", NotificationType.INFORMATION)
                    continue
                }
                runCatching {
                    if (running) client.stop(m.name, m.address) // fresh launch so hot mode engages
                    client.hotRun(m.name, m.address)
                    seedBaseline(m) // reload deltas are measured from the hot-run build
                }.onSuccess { notify("${m.name}: running (hot)", NotificationType.INFORMATION) }
                    .onFailure { notify("${m.name}: hot-run failed — ${it.message}", NotificationType.ERROR) }
            }
            refresh()
        }
    }

    /** Confirm stopping a running app to relaunch hot; remembers the choice (skips the dialog after). */
    private suspend fun confirmHotRestart(m: MachineUi): Boolean = withContext(Dispatchers.EDT) {
        val state = WdbSettings.get(project).state
        if (state.hotRunRestartConfirmed) return@withContext true
        val option = object : com.intellij.openapi.ui.DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (isSelected && exitCode == Messages.YES) state.hotRunRestartConfirmed = true
            }
        }
        MessageDialogBuilder.yesNo(
            "Restart in Hot-Reload Mode?",
            "App on '${m.name}' is running. Stop it and start in Compose hot-reload mode?",
        ).icon(Messages.getQuestionIcon()).doNotAsk(option).ask(project)
    }
    fun stop(sel: List<MachineUi>) = forEach(sel, "stopped") { client.stop(it.name, it.address) }
    fun restart(sel: List<MachineUi>) = forEach(sel, "restarted") { client.restart(it.name, it.address) }
    fun rollback(sel: List<MachineUi>) = forEach(sel, "rolled back") { client.rollback(it.name, it.address) }
    fun bringToFront(sel: List<MachineUi>) = forEach(sel, "brought to front") { client.bringToFront(it.name, it.address) }

    /** Name of the machine whose logs are currently streaming, for the console header/UI. */
    private val _logTarget = MutableStateFlow<String?>(null)
    val logTarget: StateFlow<String?> = _logTarget.asStateFlow()

    private var logJob: Job? = null

    /** Stream a machine's logs (history then live) into the native [logConsole]; replaces any current stream. */
    fun startLogs(m: MachineUi) {
        logJob?.cancel()
        logConsole.clear()
        logConsole.print("---- logs: ${m.name} ----\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        _logTarget.value = m.name
        updatingCombo = true
        deviceCombo.selectedItem = m.name
        updatingCombo = false
        activateLogsTab()
        logJob = cs.launch {
            runCatching {
                client.logs(m.name, m.address).collect { ev ->
                    when (ev) {
                        is LogLine -> logConsole.print(
                            ev.text + "\n",
                            if (ev.stream == LogStream.STDERR) ConsoleViewContentType.ERROR_OUTPUT
                            else ConsoleViewContentType.NORMAL_OUTPUT,
                        )
                        is RunBoundary -> logConsole.print("---- run ${ev.runId} ----\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        is DroppedMarker -> logConsole.print("---- dropped ${ev.count} lines ----\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                        else -> {}
                    }
                }
            }
        }
    }

    fun stopLogs() {
        logJob?.cancel()
        logJob = null
        _logTarget.value = null
    }

    /** Clear the console without stopping the stream. */
    fun clearLogs() {
        logConsole.clear()
    }

    /** Bring the bottom "WDB Logs" tool window to the front (on the EDT). */
    private fun activateLogsTab() {
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow("wdb-logs")?.activate(null)
        }
    }

    // --- Compose hot-reload from the IDE (change add-plugin-hot-reload) ---

    /** Per-machine snapshot of the classes last pushed; the next reload diffs against it. */
    private val baseline = java.util.concurrent.ConcurrentHashMap<String, ClassSnapshot>()

    private fun classesDir(): Path? {
        val dir = WdbSettings.get(project).state.classesDir
        if (dir.isBlank()) return null
        val path = Path.of(dir)
        return if (Files.isDirectory(path)) path else null
    }

    private fun seedBaseline(m: MachineUi) {
        classesDir()?.let { baseline[m.id] = ClassDiff.snapshot(it) }
    }

    /** Derive the module's compile task from the deploy task: `:mod:packageUber…` → `:mod:classes`. */
    private fun compileTaskFor(gradleTask: String): String {
        val modulePrefix = gradleTask.substringBeforeLast(':', "")
        return if (modulePrefix.isBlank()) "classes" else "$modulePrefix:classes"
    }

    /**
     * Compile the module, then push a class delta into each machine's hot-running app (design D1/D5).
     * Building first is what makes an edit take effect — without it the pushed classes are unchanged.
     * Per machine: diff the configured classes dir against the last-pushed baseline and send only the
     * change set; per-machine outcome; one failure never aborts the others. No redeploy fallback (D4).
     */
    fun reload(machines: List<MachineUi>) {
        if (machines.isEmpty()) return
        val dir = classesDir()
        if (dir == null) {
            notify("Reload: set a Classes dir in Configure deploy first", NotificationType.WARNING)
            return
        }
        val compileTask = compileTaskFor(WdbSettings.get(project).state.gradleTask)
        runGradleTask(project, compileTask) { ok ->
            if (!ok) {
                notify("Reload: compile '$compileTask' failed — nothing pushed", NotificationType.ERROR)
                return@runGradleTask
            }
            cs.launch { pushReload(machines, dir) }
        }
    }

    /**
     * The redeploy fallback for a FAILED hot-apply on [m]: rebuild-free — push the already-configured
     * jar (newest in the deploy dir) with its Main-Class and restart. Mirrors the manual Deploy's jar
     * resolution ([resolveJar]/[readMainClass]). Returns a failed [PushResult] if no deploy config,
     * so `reloadOrRedeploy` reports Failed rather than pretending it redeployed.
     */
    private suspend fun redeployFallback(m: MachineUi): PushResult {
        val jarPath = WdbSettings.get(project).state.jarPath
        val jar = resolveJar(jarPath)
        val main = jar?.let { readMainClass(it) }
        return if (jar != null && !main.isNullOrBlank()) {
            client.push(m.name, jar, main, host = m.address)
        } else {
            PushResult(ok = false, error = ProtocolError(ErrorCode.INTERNAL, "no deploy config for redeploy fallback"))
        }
    }

    private suspend fun pushReload(machines: List<MachineUi>, dir: Path) {
        for (m in machines) {
            runCatching {
                val (payload, snapshot) = ClassDiff.buildPayload(dir, baseline[m.id] ?: emptyMap())
                if (payload.batch.entries.isEmpty()) {
                    notify("${m.name}: nothing to reload", NotificationType.INFORMATION)
                    return@runCatching
                }
                when (val report = client.reloadOrRedeploy(m.name, payload, host = m.address, redeploy = { redeployFallback(m) })) {
                    is ReloadReport.Applied -> {
                        baseline[m.id] = snapshot
                        notify("${m.name}: reloaded ${report.classCount} classes", NotificationType.INFORMATION)
                    }
                    is ReloadReport.Rejected ->
                        notify("${m.name}: reload rejected — ${report.reason ?: "app not in hot mode"}", NotificationType.WARNING)
                    is ReloadReport.Redeployed ->
                        notify("${m.name}: redeployed (fallback)", NotificationType.INFORMATION)
                    is ReloadReport.Failed ->
                        notify("${m.name}: hot-apply failed (${report.reason ?: "?"}) — run Deploy", NotificationType.ERROR)
                }
            }.onFailure { notify("${m.name}: reload failed — ${it.message}", NotificationType.ERROR) }
        }
        refresh()
    }

    // --- Devtools mirror (change add-plugin-devtools) ---

    /** Coroutine scope for the mirror panel's fetches (cancelled with the project). */
    internal val devtoolsScope: CoroutineScope get() = cs

    suspend fun deviceScreenshot(m: MachineUi): ByteArray? = runCatching { client.screenshot(m.name, m.address) }.getOrNull()
    suspend fun deviceTree(m: MachineUi): String? = runCatching { client.semanticTree(m.name, m.address) }.getOrNull()
    suspend fun deviceUiAction(
        m: MachineUi,
        nodeId: Int,
        kind: uz.disastrouspumpkin.wdb.protocol.UiActionKind,
        text: String = "",
        dx: Float = 0f,
        dy: Float = 0f,
        index: Int = 0,
    ): Boolean =
        runCatching { client.uiAction(m.name, nodeId, kind, text, dx, dy, index, host = m.address) }.getOrDefault(false)

    suspend fun deviceTap(m: MachineUi, nodeId: Int): Boolean = deviceUiAction(m, nodeId, uz.disastrouspumpkin.wdb.protocol.UiActionKind.CLICK)

    private val mirrorUi: MirrorPanel by lazy { MirrorPanel(project, this) }

    /** The "WDB Mirror" tool-window content (built lazily, holds a device selector + screen). */
    val mirrorPanel: JComponent get() = mirrorUi.component

    /** Open the mirror on [m]: select it, activate the tool window, and load its screen. */
    fun mirror(m: MachineUi) {
        mirrorUi.showMachine(m)
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow("wdb-mirror")?.activate(null)
        }
    }

    /** One-click debug: open a tunnel to the machine's JDWP port and attach the IDE debugger (D5). */
    fun debug(m: MachineUi) {
        cs.launch {
            val st = runCatching { client.status(m.name, m.address) }.getOrNull()
            val jdwp = st?.jdwpPort
            if (st?.appState != AppState.RUNNING || jdwp == null) {
                notify("${m.name}: debug unavailable (app not running or no debug port)", NotificationType.WARNING)
                return@launch
            }
            val tunnel = runCatching { client.openTunnel(m.name, jdwp, host = m.address) }.getOrElse {
                notify("${m.name}: could not open debug tunnel — ${it.message}", NotificationType.ERROR)
                return@launch
            }
            withContext(Dispatchers.EDT) {
                attachRemoteDebugger(project, m.name, tunnel.localPort) { tunnel.close() }
            }
        }
    }

    /**
     * Push an already-built jar to machines (fan-out, per-machine result) under an IDE background
     * progress bar that shows the upload fraction (bytes sent / total) per machine. Called after a build.
     */
    fun pushJar(machines: List<MachineUi>, jar: java.nio.file.Path, mainClass: String) {
        if (machines.isEmpty()) return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "wdb: deploying", true) {
            override fun run(indicator: ProgressIndicator) {
                val gradleTask = WdbSettings.get(project).state.gradleTask
                var offeredExclude = false // offer the build-fix action once per push, not per machine
                runBlocking {
                    for ((i, m) in machines.withIndex()) {
                        indicator.text = "Deploying to ${m.name} (${i + 1}/${machines.size})"
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        setDeployProgress(m.id, 0f)
                        val onProgress: (Long, Long) -> Unit = { sent, total ->
                            val f = if (total > 0) sent.toFloat() / total else 0f
                            indicator.fraction = f.toDouble()
                            indicator.text2 = "${sent / 1_000_000} / ${total / 1_000_000} MB"
                            setDeployProgress(m.id, f)
                        }
                        try {
                            val onNotice: (String) -> Unit = {
                                if (!offeredExclude && it.startsWith("stripped")) {
                                    offeredExclude = true
                                    notifyExcludeAction(it, gradleTask)
                                } else {
                                    notify("${m.name}: $it", NotificationType.INFORMATION)
                                }
                            }
                            runCatching { client.push(m.name, jar, mainClass, host = m.address, onProgress = onProgress, onNotice = onNotice) }
                                .onSuccess { r ->
                                    if (r.ok) {
                                        r.deployedSha?.let { _deployInfo.value = _deployInfo.value + (m.id to it.take(7)) }
                                        notify("${m.name}: deployed ${r.deployedSha?.take(12)}", NotificationType.INFORMATION)
                                    } else {
                                        notify("${m.name}: deploy failed — ${r.error?.message}", NotificationType.ERROR)
                                    }
                                }
                                .onFailure { notify("${m.name}: deploy failed — ${it.message}", NotificationType.ERROR) }
                        } finally {
                            clearDeployProgress(m.id)
                        }
                    }
                }
                refresh()
            }
        })
    }

    fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("wdb")
            .createNotification(message, type)
            .notify(project)
    }

    /**
     * A strip notice plus a one-click action that inserts the signature `exclude` into the app's
     * build (add-plugin-exclude-signatures-action), so future pushes skip the temp rewrite.
     */
    private fun notifyExcludeAction(message: String, gradleTask: String) {
        val n = NotificationGroupManager.getInstance()
            .getNotificationGroup("wdb")
            .createNotification(message, NotificationType.INFORMATION)
        n.addAction(NotificationAction.createSimple("Exclude signatures in build.gradle") {
            ApplicationManager.getApplication().invokeLater {
                when (GradleSignatureExclude.addExclude(project, gradleTask)) {
                    GradleSignatureExclude.Outcome.INSERTED ->
                        notify("Added signature exclude to build.gradle", NotificationType.INFORMATION)
                    GradleSignatureExclude.Outcome.ALREADY ->
                        notify("build.gradle already excludes signatures", NotificationType.INFORMATION)
                    GradleSignatureExclude.Outcome.FALLBACK ->
                        notify("Couldn't edit build.gradle automatically — exclude snippet copied to clipboard; paste it into your jar/uber task", NotificationType.WARNING)
                }
                n.expire()
            }
        })
        n.notify(project)
    }
}
