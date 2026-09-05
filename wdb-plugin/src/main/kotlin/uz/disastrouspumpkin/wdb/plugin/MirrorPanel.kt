package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import uz.disastrouspumpkin.wdb.protocol.UiActionKind
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import javax.swing.DefaultComboBoxModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.imageio.ImageIO
import uz.disastrouspumpkin.wdb.client.SemanticNode
import uz.disastrouspumpkin.wdb.client.parseSemanticTree

private const val SCROLL_STEP = 300f

/**
 * The "WDB Mirror" tool-window content (change add-plugin-devtools, flavor A): shows a hot machine's
 * screen as a PNG with Refresh, and turns a click on the image into a tap on the semantic node under
 * that point. A plain Swing panel — the screenshot/tree/tap all come from [WdbService]'s client.
 */
class MirrorPanel(private val project: Project, private val service: WdbService) {

    private val deviceCombo = com.intellij.openapi.ui.ComboBox<String>()
    private val imageLabel = HighlightLabel()
    private val statusLabel = JLabel(" ")
    private val semanticTree = JTree(DefaultMutableTreeNode("(no tree)"))
    private val detailsArea = javax.swing.JTextArea(3, 20).apply { isEditable = false; lineWrap = true }
    private val autoCheck = javax.swing.JCheckBox("Auto")
    private var updatingCombo = false

    /** Last parsed tree + screenshot dims, for mapping between semantic bounds and display pixels. */
    private var root: SemanticNode? = null
    private var scale = 1.0
    private val idToNode = HashMap<Int, DefaultMutableTreeNode>()
    private var autoJob: kotlinx.coroutines.Job? = null

    /** A JLabel that draws a highlight rectangle (display coords) over its icon — the tree↔image link. */
    private inner class HighlightLabel : JLabel("", SwingConstants.CENTER) {
        var highlight: java.awt.Rectangle? = null
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val r = highlight ?: return
            g.color = java.awt.Color(0x33_66CCFF)
            g.fillRect(r.x, r.y, r.width, r.height)
            g.color = java.awt.Color(0xFF_2A88E0.toInt())
            g.drawRect(r.x, r.y, r.width, r.height)
        }
    }

    val component: JComponent = build()

    private fun build(): JComponent {
        val refresh = JButton("Refresh").apply { addActionListener { refresh() } }
        deviceCombo.addActionListener {
            if (!updatingCombo) refresh()
        }
        autoCheck.addActionListener { if (autoCheck.isSelected) startAuto() else stopAuto() }
        val north = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("Machine:")); add(deviceCombo); add(refresh); add(autoCheck); add(statusLabel)
        }
        imageLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onImageClick(e.x, e.y)
        })
        // Selecting a tree node highlights it on the screenshot and shows its details.
        semanticTree.addTreeSelectionListener {
            val node = (semanticTree.lastSelectedPathComponent as? DefaultMutableTreeNode)
                ?.let { (it.userObject as? NodeItem)?.node }
            imageLabel.highlight = node?.let(::highlightFor)
            imageLabel.repaint()
            detailsArea.text = node?.let(::describe) ?: ""
        }
        // Clicks on the semantic tree act on the selected node: double-click taps, right-click opens
        // a Click / Long Click / Set Text… menu.
        semanticTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) nodeAt(e)?.let { runNodeAction(it, UiActionKind.CLICK) }
            }
            private fun maybePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val node = nodeAt(e) ?: return
                semanticTree.selectionPath = semanticTree.getClosestPathForLocation(e.x, e.y)
                nodeMenu(node).show(semanticTree, e.x, e.y)
            }
        })
        // Keep the selector synced to discovered machines.
        service.devtoolsScope.launch {
            service.machines.collect { list ->
                ApplicationManager.getApplication().invokeLater {
                    updatingCombo = true
                    val sel = deviceCombo.selectedItem
                    deviceCombo.model = DefaultComboBoxModel(list.map { it.name }.toTypedArray())
                    deviceCombo.selectedItem = sel
                    updatingCombo = false
                }
            }
        }
        val rightPane = JPanel(BorderLayout()).apply {
            add(JScrollPane(semanticTree), BorderLayout.CENTER)
            add(JScrollPane(detailsArea), BorderLayout.SOUTH)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(imageLabel), rightPane)
            .apply { resizeWeight = 0.7 }
        return JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }
    }

    /** Called by the service when the operator invokes Mirror on a machine. */
    fun showMachine(m: MachineUi) {
        updatingCombo = true
        deviceCombo.selectedItem = m.name
        updatingCombo = false
        refresh()
    }

    private fun selectedMachine(): MachineUi? {
        val name = deviceCombo.selectedItem as? String ?: return null
        return service.machines.value.firstOrNull { it.name == name }
    }

    private fun refresh() {
        val m = selectedMachine() ?: return
        status("loading…")
        service.devtoolsScope.launch {
            val png = service.deviceScreenshot(m)
            val tree = service.deviceTree(m)?.let { parseSemanticTree(it) }
            ApplicationManager.getApplication().invokeLater {
                if (png == null) {
                    imageLabel.icon = null
                    imageLabel.highlight = null
                    imageLabel.text = "devtools unavailable (app not in hot mode?)"
                    status(" ")
                } else {
                    showPng(png)
                }
                root = tree
                showTree(tree)
            }
        }
    }

    private fun showTree(tree: SemanticNode?) {
        idToNode.clear()
        imageLabel.highlight = null
        detailsArea.text = ""
        val rootNode = tree?.let { toTreeNode(it) } ?: DefaultMutableTreeNode("(no tree)")
        semanticTree.model = DefaultTreeModel(rootNode)
        for (r in 0 until semanticTree.rowCount) semanticTree.expandRow(r)
    }

    /** Wraps a [SemanticNode] in a tree node; its label is what the JTree renders. */
    private class NodeItem(val node: SemanticNode) {
        override fun toString(): String = buildString {
            append("#").append(node.id ?: "?")
            node.testTag?.let { append("  «").append(it).append("»") } // app-assigned name
            node.role?.let { append(" ").append(it) }
            node.text?.let { append("  \"").append(it).append("\"") }
            if (node.actions.isNotEmpty()) append("  ").append(node.actions)
        }
    }

    private fun toTreeNode(n: SemanticNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(NodeItem(n)).apply {
            n.id?.let { idToNode[it] = this }
            n.children.forEach { add(toTreeNode(it)) }
        }

    /** Highlight rectangle (display coords) for [node]'s bounds, mapped through the current image. */
    private fun highlightFor(node: SemanticNode): java.awt.Rectangle? {
        val r = root ?: return null
        val icon = imageLabel.icon ?: return null
        if (r.width == 0 || r.height == 0) return null
        val ox = (imageLabel.width - icon.iconWidth) / 2
        val oy = (imageLabel.height - icon.iconHeight) / 2
        val x = ox + (node.x.toDouble() / r.width * icon.iconWidth).toInt()
        val y = oy + (node.y.toDouble() / r.height * icon.iconHeight).toInt()
        val w = (node.width.toDouble() / r.width * icon.iconWidth).toInt()
        val h = (node.height.toDouble() / r.height * icon.iconHeight).toInt()
        return java.awt.Rectangle(x, y, w, h)
    }

    private fun describe(n: SemanticNode): String = buildString {
        append("id: ").append(n.id ?: "?").append('\n')
        n.testTag?.let { append("testTag: ").append(it).append('\n') }
        n.contentDescription?.let { append("desc: ").append(it).append('\n') }
        n.role?.let { append("role: ").append(it).append('\n') }
        n.text?.let { append("text: ").append(it).append('\n') }
        append("bounds: ").append(n.x).append(',').append(n.y).append(' ').append(n.width).append('×').append(n.height).append('\n')
        append("actions: ").append(n.actions)
    }

    /** Deepest node whose bounds contain (x,y) in semantic coords — for selecting from an image click. */
    private fun deepestAt(node: SemanticNode, x: Int, y: Int): SemanticNode? {
        if (!node.contains(x, y)) return null
        for (i in node.children.indices.reversed()) deepestAt(node.children[i], x, y)?.let { return it }
        return node
    }

    private fun selectNode(id: Int) {
        val dmt = idToNode[id] ?: return
        val path = javax.swing.tree.TreePath(dmt.path)
        semanticTree.selectionPath = path
        semanticTree.scrollPathToVisible(path)
    }

    private fun nodeAt(e: MouseEvent): SemanticNode? {
        val path = semanticTree.getClosestPathForLocation(e.x, e.y) ?: return null
        val dmt = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (dmt.userObject as? NodeItem)?.node
    }

    private fun nodeMenu(node: SemanticNode): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Click").apply { addActionListener { runNodeAction(node, UiActionKind.CLICK) } })
        add(JMenuItem("Long Click").apply { addActionListener { runNodeAction(node, UiActionKind.LONG_CLICK) } })
        add(JMenuItem("Set Text…").apply {
            addActionListener {
                val text = Messages.showInputDialog(project, "Text to set on node #${node.id}:", "Set Text", null, node.text, null)
                if (text != null) runNodeAction(node, UiActionKind.SET_TEXT, text = text)
            }
        })
        addSeparator()
        add(JMenuItem("Scroll Up").apply { addActionListener { runNodeAction(node, UiActionKind.SCROLL_BY, dy = -SCROLL_STEP) } })
        add(JMenuItem("Scroll Down").apply { addActionListener { runNodeAction(node, UiActionKind.SCROLL_BY, dy = SCROLL_STEP) } })
        add(JMenuItem("Scroll to index…").apply {
            addActionListener {
                val s = Messages.showInputDialog(project, "Index for node #${node.id}:", "Scroll To Index", null, "0", null)
                s?.toIntOrNull()?.let { runNodeAction(node, UiActionKind.SCROLL_TO_INDEX, index = it) }
            }
        })
    }

    private fun runNodeAction(
        node: SemanticNode,
        kind: UiActionKind,
        text: String = "",
        dx: Float = 0f,
        dy: Float = 0f,
        index: Int = 0,
    ) {
        val m = selectedMachine() ?: return
        val id = node.id ?: run { status("node has no id"); return }
        status("${kind.name.lowercase()} #$id…")
        service.devtoolsScope.launch {
            val ok = service.deviceUiAction(m, id, kind, text, dx, dy, index)
            ApplicationManager.getApplication().invokeLater { status(if (ok) "${kind.name.lowercase()} #$id ok" else "action failed") }
            refresh()
        }
    }

    private fun showPng(png: ByteArray) {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
        if (img == null) { status("bad image"); return }
        val maxW = (component.width - 24).coerceAtLeast(320)
        scale = if (img.width > maxW) maxW.toDouble() / img.width else 1.0
        val shown: Image = if (scale < 1.0) {
            img.getScaledInstance((img.width * scale).toInt(), (img.height * scale).toInt(), Image.SCALE_SMOOTH)
        } else {
            img
        }
        imageLabel.text = ""
        imageLabel.icon = ImageIcon(shown)
        status("${img.width}×${img.height}")
    }

    private fun onImageClick(px: Int, py: Int) {
        val m = selectedMachine() ?: return
        val icon = imageLabel.icon ?: return
        // The label centers the icon; the click as a fraction of the displayed image (0..1). This is
        // resolution/DPI-agnostic: the screenshot pixels and the semantic bounds can differ (Windows
        // display scaling), so we map the fraction onto the semantic tree's own root bounds instead.
        val ox = (imageLabel.width - icon.iconWidth) / 2
        val oy = (imageLabel.height - icon.iconHeight) / 2
        val fracX = (px - ox).toDouble() / icon.iconWidth
        val fracY = (py - oy).toDouble() / icon.iconHeight
        if (fracX < 0 || fracY < 0 || fracX > 1 || fracY > 1) return
        val r = root ?: run { status("no semantic tree"); return }
        val semX = (fracX * r.width).toInt()
        val semY = (fracY * r.height).toInt()
        // Select the node under the point in the tree (any node), and tap it if it's clickable.
        deepestAt(r, semX, semY)?.id?.let { selectNode(it) }
        val nodeId = uz.disastrouspumpkin.wdb.client.clickableNodeAt(r, semX, semY)
        if (nodeId == null) { status("selected (not clickable) at ($semX,$semY)"); return }
        val m2 = m
        status("tap #$nodeId…")
        service.devtoolsScope.launch {
            val ok = service.deviceTap(m2, nodeId)
            ApplicationManager.getApplication().invokeLater { status(if (ok) "tapped node $nodeId" else "tap failed") }
            refresh()
        }
    }

    private fun startAuto() {
        stopAuto()
        autoJob = service.devtoolsScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1500)
                if (autoCheck.isSelected && component.isShowing) refresh()
            }
        }
    }

    private fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
    }

    private fun status(s: String) {
        ApplicationManager.getApplication().invokeLater { statusLabel.text = s }
    }
}
