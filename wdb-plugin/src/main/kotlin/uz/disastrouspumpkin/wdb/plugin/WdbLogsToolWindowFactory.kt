package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The "WDB Logs" tool window (design D-logs): a native IDE [com.intellij.execution.ui.ConsoleView]
 * plus a LogCat-style machine selector, in its OWN bottom-anchored tool window so the user can dock
 * or float it independently of the right-side Wall window (a tab inside one tool window can't be
 * moved to a different dock). The panel itself lives on [WdbService] so the log stream can write to it.
 */
class WdbLogsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<WdbService>()
        val content = ContentFactory.getInstance().createContent(service.logPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
