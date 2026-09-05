package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme

/**
 * The "wdb" tool window. Renders a Compose UI via the platform's bundled Jewel (design D2):
 * [addComposeTab] enables the new Swing compositing, and [SwingBridgeTheme] mirrors the live IDE
 * theme (light/dark + accent) into Compose. DumbAware so it works during indexing.
 */
class WdbToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<WdbService>()
        service.refresh() // discover on open
        toolWindow.addComposeTab("Wall") {
            SwingBridgeTheme {
                WallUi(service, project)
            }
        }
        // Logs live in their own bottom tool window (wdb-logs) so they dock/float independently.
    }
}
