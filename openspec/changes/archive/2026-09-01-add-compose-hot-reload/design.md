## Context

See proposal.md — Why. Today `Supervisor.buildCommand` launches the current deployment as `java <jvmArgs> -agentlib:jdwp=... -jar <dep.jar> <programArgs>` on the bundled JBR. Deploy is whole-fat-jar over the `PUSH` stream. JetBrains Compose Hot Reload (CHR) already solves the redefine + recompose half, but assumes compiler and app share one filesystem: its `ReloadClassesRequest` carries **file paths, not bytes**, and its coordinator (Gradle) hosts a loopback `OrchestrationServer` that the app's `-javaagent` connects to. wdb splits compiler (dev machine) and app (remote wall machine) across the LAN, so the class **bytes** must cross the wire and something on the remote box must play Gradle's coordinator role.

## Goals / Non-Goals

**Goals:**
- Reuse CHR's `hot-reload-agent` + `hot-reload-runtime-jvm` unchanged for the redefine+recompose leg.
- Cross-machine class delivery over wdb's existing framed-JSON transport (bytes on the wire).
- Sub-second incremental reload from a `wdb reload --watch` loop; single + fan-out targets.
- Clean fallback to full redeploy+restart when a change can't be hot-applied.

**Non-Goals:**
- IntelliJ-plugin-driven reload (backlog #4) — the `RELOAD` stream is shaped so the plugin can drive it later.
- Reusing CHR's Gradle FS-watcher or its auto-reload — wdb pushes explicitly, which also sidesteps CHR's "no FS watching on Windows ReFS/Dev Drive" limitation.
- Rolling our own recomposition/invalidation — CHR's runtime owns that.

## Decisions

**D1 — wdb-agent hosts the OrchestrationServer; wdb replaces only the class-delivery leg.**
On a hot launch the agent starts a CHR `OrchestrationServer` on a loopback port and passes it to the app via `-Dcompose.reload.orchestration.port`. The app's `hot-reload-agent` connects to it locally, exactly as it would to Gradle. wdb-agent then acts as the coordinator: on a reload push it writes the received bytes into the hot-classpath dir and emits a `ReloadClassesRequest(paths)` into the server. *Alternative rejected:* tunnel the orchestration port back to the dev machine and run the coordinator there — still doesn't move bytes (the message is path-only) and adds a fragile long-lived tunnel per app.

**D2 — Bytes cross the wire on a new `RELOAD` stream; the agent reproduces paths locally.**
New `StreamKind.RELOAD`. The client sends a reload batch = list of `{ relPath, changeType, bytes(for added/modified) }` plus a batch hash. The agent writes each added/modified entry to `<hotClasspathDir>/<relPath>`, deletes removed ones, then emits the CHR request referencing those now-local paths. This keeps CHR's path-based message intact — we satisfy its shared-FS assumption *on the remote box*. *Alternative rejected:* fork CHR's message to carry bytes — more upstream drift, no benefit since we control both ends of our own stream.

**D3 — Hot launch is a distinct mode in the Supervisor, not a tweak to normal run.**
Hot mode adds: `-javaagent:<hot-reload-agent.jar>`, `-XX:+AllowEnhancedClassRedefinition`, `-Dcompose.reload.orchestration.port=<loopback>`, `-Dcompose.reload.hotApplicationClasspath=<hotDir>`, `-Dcompose.reload.isHotReloadActive=true`, and prepends `<hotDir>` to the app classpath so added classes resolve. Non-hot runs keep the current command verbatim. The agent tracks whether the current run is hot; a `RELOAD` push to a non-hot run is rejected. *Alternative rejected:* always attach the hot machinery — forces the JBR/DCEVM + CHR-runtime dependency and startup cost on apps that never reload.

**D4 — Classpath: cold fat jar + hot dir, no exploded initial layout required.**
The deployment's fat jar stays the cold classpath (deps + initial app classes); the hot dir starts empty and receives only pushed deltas. Modified classes are already loaded (from the jar) so DCEVM redefines them from the pushed bytes; added classes are loaded from the hot-dir path. This means hot mode needs **no change to how deployments are built or pushed** — only how they're launched. The app must still have been *compiled* with CHR (depends on `hot-reload-runtime-jvm`, built Kotlin ≥2.1.20 / Compose ≥1.8.2) for the runtime invalidation to fire.

**D5 — `wdb reload --watch` diffs a build-output dir against a per-target snapshot.**
The CLI watches a dev-side classes dir, hashes each `.class`, and on change computes the delta (added/modified/removed) versus the last batch acked by each target, pushing only the difference. First push after connect sends the full current set to establish the baseline. Fan-out reuses the existing discovery + per-machine result pattern from `push --all`.

**D6 — Fallback to redeploy is client-driven off the agent's reload result.**
The agent's reload result carries an outcome: `applied` or `failed(reason)`. On `failed`, the CLI (for that machine only) triggers the existing full deploy+restart path, so a reload that DCEVM can't handle degrades to the pre-hot-reload behavior rather than leaving a half-swapped process. The agent never auto-restarts on a failed reload — it reports and waits, so the operator/CLI decides.

## Risks / Trade-offs

- **Bundled JBR might be a stripped JBRSDK without DCEVM** → verify `-XX:+AllowEnhancedClassRedefinition` is accepted on the wall's JBR 21.0.11 as a first task; if rejected, bundle a full JBR before anything else works.
- **CHR version/API churn (1.2.0 stable, 1.3.0-alpha)** → pin an exact version; wrap CHR orchestration types behind a thin agent-side adapter so a version bump is localized.
- **Global/heap state edits throw after redefine** → this is a CHR-level limit; surfaced to the operator via the `failed` result → redeploy fallback (D6). Not something wdb can fix.
- **Added-class visibility depends on hot-dir being on the classpath** (D4) → if the app pins its own classloader oddly, added classes may not resolve; modified-only reloads still work. Document hot mode as "best with standard app classloading".
- **One CHR connection per app** → the agent hosts exactly one orchestration server per running app; fine since a wall machine runs one app.
- **Reload ordering / concurrent pushes** → serialize reload application per app under a lock (same pattern as the self-update mutation lock) so two pushes can't interleave a redefine.

## Migration Plan

Additive. New stream + new CLI command + new launch mode; normal deploy/run/debug paths untouched. Rollout: (1) verify DCEVM on the wall JBR; (2) land protocol + agent hot launch + orchestration host; (3) land `wdb reload` + watch; (4) adopt CHR in `wdb-dummy-app`; (5) live-verify on a wall machine. Rollback: hot mode is opt-in per run — not starting a hot run leaves the system exactly as before; the CHR dependencies are inert unless a hot run is launched.

## Open Questions

- Exact CHR orchestration entry points and whether we drive `OrchestrationServer` + `ReloadClassesRequest` directly or via a small helper CHR exposes — resolve during implementation against the pinned version; does not affect the specs or the task breakdown.
