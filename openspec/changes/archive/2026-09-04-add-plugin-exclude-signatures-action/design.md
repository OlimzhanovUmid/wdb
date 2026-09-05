# Design — "exclude signatures in build.gradle" action

## Context

`WdbService.pushJar` passes an `onNotice` to `client.push`; on a strip it currently raises an
INFORMATION notification via the `"wdb"` `NotificationGroup`. The strip message is per-machine (the
push fans out over a machine list). The build to fix is identified by `WdbSettings.gradleTask` — the
deploy task path, e.g. `:desktopApp:packageUberJarForCurrentOS`. `substringBeforeLast(':')` already
yields the module path (`:desktopApp`) and `substringAfterLast(':')` the task name — the same trick
`compileTaskFor` uses.

## Decisions

### D1 — Raise one action-notification per push

The strip is a property of the build, not a machine, so the action is offered **once per deploy**, not
once per machine. In `pushJar`, guard with a local `var offeredExclude = false`; the first strip notice
raises a notification carrying a `NotificationAction` ("Exclude signatures in build.gradle") and sets
the flag; later per-machine notices stay as plain info. The action's closure captures
`settings.gradleTask` at push time.

### D2 — Resolve module build script + task from the deploy task

`gradleTask` → `taskName = substringAfterLast(':')`, `modulePath = substringBeforeLast(':')`. Map the
Gradle path to a directory: strip the leading `:`, replace `:` with `/` (`:core:app` → `core/app`);
empty module path → the root project. `buildFile = <project.basePath>/<moduleDir>/build.gradle.kts`.
If only `build.gradle` (Groovy) exists, or neither exists → fallback (D5).

### D3 — Insert into the task block (Kotlin DSL)

**Mechanism note (revised during apply):** full Kotlin PSI (`KtPsiFactory`/`KtFile`) needs a
`bundledPlugin("org.jetbrains.kotlin")` dependency — heavy and K1/K2-version-sensitive for one small
edit. Instead the insert is a **conservative text edit** on the file's `Document` under a
`WriteCommandAction` (still undoable). It matches only well-formed, unambiguous task blocks and, on any
doubt, takes the clipboard fallback (D5) — so it never blindly splices. Behavior and safety match the
spec; only the "how" changed from PSI to guarded text.

Under `WriteCommandAction` on the module `build.gradle.kts` document:

1. Locate the task's config block by matching (regex) a callee that identifies the task, then the
   block's opening `{` **only** when separated from the callee by trivia (whitespace / `.configure` /
   type args / `()`); anything else → treat as not found (fallback), never guess:
   - `tasks.named("<task>")` / `tasks.named<T>("<task>")`
   - `tasks.jar` when the task is `jar`
   - a bare `<task> {` at line start (e.g. `shadowJar {`)
2. If found: if the whole file already contains `exclude(` with `META-INF/*.SF`, no-op (idempotent,
   D4). Else insert the statement
   `exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")` right after the
   block's `{`.
3. If not found: append a lazy, targeted block
   `tasks.withType<Jar>().matching { it.name == "<task>" }.configureEach { exclude(...) }` (covers
   Compose's `packageUberJarForCurrentOS`, which the user never declares AND which the plugin registers
   *after* the script body runs — so `tasks.named("x")` would fail eagerly with "task not found").
   `configureEach` is lazy (no eager lookup) and the `withType<Jar>` receiver is a `Jar`, so `exclude`
   resolves; also insert `import org.gradle.jvm.tasks.Jar` (see D6).
4. Commit the document, reformat the touched range (`CodeStyleManager`), save, open the file and move
   the caret to the inserted `exclude`.

### D4 — Idempotent

Before inserting, scan the target block (or the whole file when appending) for an existing
`exclude(...)` containing `META-INF/*.SF`. If present, make no edit and notify "build already excludes
signatures".

### D5 — Safe fallback (never corrupt the build)

If the module dir/build script can't be resolved, the file is Groovy (`build.gradle`), the `KtFile`
won't parse, or PSI matching is ambiguous/throws, do NOT edit: open the build script (if it exists) in
the editor and put the multi-line snippet on the system clipboard, with a notification telling the user
to paste it into their jar/uber task. A wrong auto-edit is worse than a manual paste.

### D6 — `exclude` needs a `Jar` receiver → typed accessor + import

`exclude(vararg String)` is on `AbstractCopyTask`/`Jar`. **`tasks.named("x") { }` gives a `Task`
receiver — `exclude` does NOT resolve there** (Kotlin instead binds the wrong `Configuration.exclude`
overload → "receiver type mismatch"). So the appended block uses the **typed** accessor
`tasks.named<Jar>("<task>") { exclude(...) }` and the helper inserts `import org.gradle.jvm.tasks.Jar`
if missing. Verified: `tasks.jar`, `shadowJar`, and Compose's `packageUberJarForCurrentOS` are all
`org.gradle.jvm.tasks.Jar` (the compose plugin registers the uber task as a `Jar`), so `named<Jar>`
resolves for all deploy targets. (Inserting into an existing already-typed block — `tasks.jar { }` or a
user's `tasks.named<Jar>(...)` — needs no import.)

## Non-Goals

- Auto-editing Groovy `build.gradle` (clipboard fallback only).
- Resolving the module via the full Gradle tooling model — the `:path` → directory mapping covers the
  standard layout; anything else falls back.
- Touching the client-side strip (it stays as the safety net for jars built without the exclude).

## Risks

- **Brittle DSL matching** — many ways to declare a task block. Mitigated by: match the common forms,
  and on any miss/parse issue use the clipboard fallback (never a blind text splice).
- **Wrong task type** — a non-jar deploy task would make the inserted `exclude` not compile; undoable,
  and such a task wouldn't have produced a signed uber jar to strip in the first place.
