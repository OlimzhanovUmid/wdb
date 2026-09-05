package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The "WDB Mirror" tool window (change add-plugin-devtools): hosts [WdbService.mirrorPanel] — a live
 * screenshot of a hot machine's app with click-to-tap. Its own tool window so it can dock/float
 * independently of the Wall.
 */
class WdbMirrorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<WdbService>()
        val content = ContentFactory.getInstance().createContent(service.mirrorPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
