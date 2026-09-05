## ADDED Requirements

### Requirement: Offer to exclude stale signatures in the build

When a deploy from the IDE strips stale JAR signature files, the plugin SHALL offer an action that
adds a signature-file exclude to the app's Gradle build, so subsequent deploys need no stripping. The
action SHALL target the build task derived from the configured deploy task and insert the exclude into
that task's configuration in the module's Kotlin-DSL build script, undoably. It SHALL be safe: if the
build script or task cannot be located or edited reliably (including a non-Kotlin-DSL build), the
plugin SHALL instead open the build script and place the exclude snippet on the clipboard rather than
modify the file. The action SHALL be idempotent — when the exclude is already present it makes no
change and says so.

#### Scenario: One-click exclude after a strip

- **WHEN** a deploy strips stale signature files and the operator invokes the offered action
- **THEN** the plugin adds the signature-file exclude to the deploy task's configuration in the module's Kotlin-DSL build script and opens the file at the change

#### Scenario: Safe fallback when the build can't be edited

- **WHEN** the action cannot locate or safely edit the build script (e.g. a Groovy build, or an unparsable file)
- **THEN** the plugin opens the build script and copies the exclude snippet to the clipboard instead of modifying the file

#### Scenario: Idempotent

- **WHEN** the exclude is already present for the target task
- **THEN** the action makes no change and reports that the build already excludes signatures
