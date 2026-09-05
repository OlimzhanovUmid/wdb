package uz.disastrouspumpkin.wdb.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path

/**
 * Inserts a signature-file `exclude(...)` into the app's Gradle build so future deploys need no
 * signature stripping (change add-plugin-exclude-signatures-action). Guarded text edit on the module's
 * `build.gradle.kts` (Kotlin DSL) under an undoable [WriteCommandAction]; on any doubt it falls back to
 * opening the file + copying the snippet to the clipboard, never a blind splice.
 */
object GradleSignatureExclude {
    private const val EXCLUDE = "exclude(\"META-INF/*.SF\", \"META-INF/*.RSA\", \"META-INF/*.DSA\", \"META-INF/*.EC\")"
    private const val MARKER = "META-INF/*.SF"

    enum class Outcome { INSERTED, ALREADY, FALLBACK }

    /** Add the exclude for the deploy task's build script. See [Outcome]. */
    fun addExclude(project: Project, gradleTask: String): Outcome {
        val task = gradleTask.substringAfterLast(':').trim()
        val path = resolveBuildFile(project, gradleTask)
        if (task.isBlank() || path == null) return fallback(project, path)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return fallback(project, path)
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return fallback(project, path)

        val text = doc.text
        if (MARKER in text) { // idempotent — already excluded somewhere in this build
            openAt(project, vf, text.indexOf(MARKER))
            return Outcome.ALREADY
        }
        val blockOpen = findBlockOpen(text, task)

        var caret = 0
        WriteCommandAction.runWriteCommandAction(project) {
            if (blockOpen >= 0) {
                val ins = "\n    $EXCLUDE"
                doc.insertString(blockOpen, ins)
                caret = blockOpen + ins.length
            } else {
                // Lazy + typed: `tasks.named("x")` is eager (fails when a plugin registers the task later,
                // e.g. Compose's packageUberJarForCurrentOS) and gives a Task receiver (no `exclude`).
                // withType<Jar>().matching{}.configureEach{} is lazy, targeted, and has a Jar receiver so
                // exclude(vararg String) resolves. jar/shadowJar/Compose uber are all org.gradle.jvm.tasks.Jar.
                ensureJarImport(doc)
                val block = "\n\ntasks.withType<Jar>().matching { it.name == \"$task\" }.configureEach {\n    $EXCLUDE\n}\n"
                caret = doc.textLength + block.indexOf(EXCLUDE)
                doc.insertString(doc.textLength, block)
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc)
            FileDocumentManager.getInstance().saveDocument(doc)
        }
        openAt(project, vf, caret)
        return Outcome.INSERTED
    }

    private const val JAR_IMPORT = "import org.gradle.jvm.tasks.Jar"

    /** Add `import org.gradle.jvm.tasks.Jar` (for the typed `named<Jar>` accessor) if absent. */
    private fun ensureJarImport(doc: com.intellij.openapi.editor.Document) {
        val text = doc.text
        if (JAR_IMPORT in text) return
        val lastImport = Regex("(?m)^import .*$").findAll(text).lastOrNull()
        if (lastImport != null) doc.insertString(lastImport.range.last + 1, "\n$JAR_IMPORT")
        else doc.insertString(0, "$JAR_IMPORT\n")
    }

    private fun resolveBuildFile(project: Project, gradleTask: String): Path? {
        val base = project.basePath?.let(Path::of) ?: return null
        val modulePath = gradleTask.substringBeforeLast(':', "").trim(':') // ":a:b:task" -> "a:b"; "task" -> ""
        val moduleDir = if (modulePath.isBlank()) base else base.resolve(modulePath.replace(':', '/'))
        val kts = moduleDir.resolve("build.gradle.kts")
        return if (Files.isRegularFile(kts)) kts else null // Groovy build.gradle -> null -> fallback
    }

    /**
     * Index just after the task config block's `{`, or -1. Matches a callee that names the task, then
     * its opening brace only across trivia (whitespace / `.configure` / type args / `()`); anything
     * else is treated as "not found" so we never splice into an unrelated block.
     */
    private fun findBlockOpen(text: String, task: String): Int {
        val esc = Regex.escape(task)
        val callees = buildList {
            add(Regex("""tasks\.named(?:<[^>]+>)?\(\s*"$esc"\s*\)"""))
            if (task == "jar") add(Regex("""tasks\.jar\b"""))
            add(Regex("""(?m)^\s*$esc\s*(?=\{)"""))
        }
        val trivia = Regex("""^\s*(?:\.configure\s*)?(?:\([^)]*\)\s*)?\{""")
        for (re in callees) {
            val m = re.find(text) ?: continue
            val after = text.substring(m.range.last + 1)
            val tm = trivia.find(after) ?: continue
            return m.range.last + 1 + tm.range.last + 1 // right after '{'
        }
        return -1
    }

    private fun fallback(project: Project, path: Path?): Outcome {
        CopyPasteManager.getInstance().setContents(StringSelection(EXCLUDE))
        if (path != null) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let {
                FileEditorManager.getInstance(project).openFile(it, true)
            }
        }
        return Outcome.FALLBACK
    }

    private fun openAt(project: Project, vf: com.intellij.openapi.vfs.VirtualFile, offset: Int) {
        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, vf, offset.coerceIn(0, Int.MAX_VALUE)), true,
        )
    }
}
