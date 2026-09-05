package uz.disastrouspumpkin.wdb.plugin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import org.jetbrains.jewel.bridge.retrieveEditorColorScheme
import org.jetbrains.jewel.bridge.toComposeColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// State colors that read the same in light/dark (GitHub-ish palette). Gray falls back to the
// theme's dim text color at the call site so "stopped/unknown" stays theme-consistent.
private val RunningGreen = Color(0xFF3FB950)
private val CrashedRed = Color(0xFFF85149)
private val HotAmber = Color(0xFFD29922)

private val CardShape = RoundedCornerShape(8.dp)

/** A compact icon toolbar button with a hover tooltip, using bundled IntelliJ icons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionIcon(tip: String, key: IconKey, enabled: Boolean = true, onClick: () -> Unit) {
    Tooltip(tooltip = { Text(tip) }) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(key = key, contentDescription = tip)
        }
    }
}

/** A colored status dot + label so app state reads at a glance (design D8, polish). */
@Composable
private fun StatusDot(appState: String, hot: Boolean) {
    val dim = JewelTheme.globalColors.text.info
    val color = when (appState) {
        "RUNNING" -> RunningGreen
        "CRASHED" -> CrashedRed
        else -> dim // STOPPED / "?"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(appState.lowercase())
        if (hot) {
            Text("⚡hot", color = HotAmber, fontWeight = FontWeight.Medium)
        }
    }
}

/** The per-machine / per-all action icons (design D7/D8). [single] non-null = act on that one machine. */
@Composable
private fun ActionRow(project: Project, service: WdbService, targets: List<MachineUi>, single: MachineUi?) {
    val enabled = targets.isNotEmpty()
    // State gates: per-machine row uses that machine; all-machines row aggregates with `any`.
    val running = single?.let { it.appState == "RUNNING" } ?: (enabled && targets.all { it.appState == "RUNNING" })
    val anyRunning = single?.let { it.appState == "RUNNING" } ?: targets.any { it.appState == "RUNNING" }
    val anyHot = single?.hot ?: targets.any { it.hot }
    val anyPrev = single?.hasPrevious ?: targets.any { it.hasPrevious }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon("Deploy", AllIconsKeys.Actions.Upload, enabled) { deployTargets(project, service, targets) }
        // Run when stopped, Restart when running (the standalone Restart icon is folded in here).
        if (running) {
            ActionIcon("Restart", AllIconsKeys.Actions.Restart, enabled) { service.restart(targets) }
        } else {
            ActionIcon("Run", AllIconsKeys.Actions.Execute, enabled) { service.run(targets) }
        }
        ActionIcon("Hot-run", AllIconsKeys.Actions.Lightning, enabled) { service.hotRun(targets) }
        ActionIcon("Reload", AllIconsKeys.Actions.BuildLoadChanges, enabled && anyHot) { service.reload(targets) }
        ActionIcon("Stop", AllIconsKeys.Actions.Suspend, enabled && anyRunning) { service.stop(targets) }
        ActionIcon("Rollback", AllIconsKeys.Actions.Rollback, enabled && anyPrev) { service.rollback(targets) }
        // Agent update from the published release (change agent-github-pull): enabled only when a
        // strictly newer agent is available; acts on this machine, or all machines that need it.
        val anyUpdate = single?.let { service.agentUpdateAvailable(it) } ?: targets.any { service.agentUpdateAvailable(it) }
        ActionIcon("Update agent", AllIconsKeys.Actions.Download, enabled && anyUpdate) {
            val victims = if (single != null) listOf(single) else targets.filter { service.agentUpdateAvailable(it) }
            service.updateAgent(victims)
        }
        if (single != null) {
            ActionIcon("Bring to front", AllIconsKeys.General.ArrowUp, single.appState == "RUNNING") { service.bringToFront(listOf(single)) }
            ActionIcon("Debug", AllIconsKeys.Actions.StartDebugger, single.appState == "RUNNING") { service.debug(single) }
            ActionIcon("Logs", AllIconsKeys.Debugger.Console) { service.startLogs(single) }
            ActionIcon("Mirror", AllIconsKeys.Actions.Preview, single.hot) { service.mirror(single) }
        }
    }
}

/** A rounded, bordered card using the editor's background so it reads like a code panel. */
@Composable
private fun card(): Modifier {
    val border = JewelTheme.globalColors.borders.normal
    val bg = retrieveEditorColorScheme().defaultBackground.toComposeColor()
    return Modifier.fillMaxWidth()
        .clip(CardShape)
        .background(bg, CardShape)
        .border(1.dp, border, CardShape)
}

/**
 * The wdb tool-window content (design D2/D7/D8): a rounded toolbar card with all-machine actions,
 * per-row machine cards with a colored status dot + their own action row, and a log pane.
 * No select-first step — every action targets its own row, or all rows.
 */
@Composable
fun WallUi(service: WdbService, project: Project) {
    val machines by service.machines.collectAsState()
    val busy by service.busy.collectAsState()
    val deployProgress by service.deployProgress.collectAsState()
    val deployInfo by service.deployInfo.collectAsState()
    val agentRelease by service.agentRelease.collectAsState()
    val dim = JewelTheme.globalColors.text.info

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        // Toolbar card: title + count, refresh/configure on the right, then the all-machines actions.
        Column(card().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wall", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "discovering…" else "${machines.size} machines", color = dim)
                Spacer(Modifier.weight(1f))
                var autoReload by remember { mutableStateOf(WdbSettings.get(project).state.autoReloadOnSave) }
                CheckboxRow(
                    text = "Auto-reload on save",
                    checked = autoReload,
                    onCheckedChange = {
                        autoReload = it
                        WdbSettings.get(project).state.autoReloadOnSave = it
                    },
                )
                Spacer(Modifier.width(8.dp))
                ActionIcon("Refresh", AllIconsKeys.Actions.Refresh, !busy) { service.refresh() }
                ActionIcon("Configure deploy…", AllIconsKeys.General.Settings) { configureDeploy(project) }
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("all", color = dim, modifier = Modifier.width(28.dp))
                ActionRow(project, service, targets = machines, single = null)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (machines.isEmpty()) {
            Text(if (busy) "Discovering…" else "No machines. Click Refresh.", color = dim)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(machines, key = { it.id }) { m ->
                    Column(card().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontWeight = FontWeight.Bold)
                                val addr = "${m.host}:${m.port}"
                                Text(deployInfo[m.id]?.let { "$addr · deployed $it" } ?: addr, color = dim)
                            }
                            StatusDot(m.appState, m.hot)
                            Spacer(Modifier.width(10.dp))
                            // Flag an available agent update inline: "0.2.14 → 0.2.15" in amber.
                            val newAgent = if (service.agentUpdateAvailable(m)) agentRelease?.version else null
                            if (newAgent != null) {
                                Text("${m.agentVersion} → $newAgent", color = HotAmber, fontWeight = FontWeight.Medium)
                            } else {
                                Text(m.agentVersion, color = dim)
                            }
                        }
                        val prog = deployProgress[m.id]
                        if (prog != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                HorizontalProgressBar(prog, Modifier.weight(1f))
                                Text("${(prog * 100).toInt()}%", color = dim)
                            }
                        }
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        ActionRow(project, service, targets = listOf(m), single = m)
                    }
                }
            }
        }

        // Logs stream into the native IDE ConsoleView on the tool window's separate "Logs" tab.
    }
}
