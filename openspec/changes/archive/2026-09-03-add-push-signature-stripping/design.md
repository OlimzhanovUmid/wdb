# Design — strip stale JAR signatures on push

## Context

`pushJar` (`wdb-client/.../ClientOps.kt:53`) reads a `Path`, computes `size` + `sha256`, sends a
`PushManifest`, then streams the jar's bytes and reads a `PushResult`. `WdbClient.push` wraps it and
is the single funnel for CLI, plugin, and MCP deploys. The agent stores + verifies the bytes it
receives and launches `java -jar`. Nothing else needs to change — the fix is a client-side rewrite
before the size/sha/stream step.

## Decisions

### D1 — Strip in the push path, over a temp copy

Before hashing, `pushJar` checks the jar for signature entries (`META-INF/*.SF`, `*.RSA`, `*.DSA`,
`*.EC`, case-insensitive, directly under `META-INF/`). If any exist, it writes a temp jar copying
every other entry (preserving each entry's compression), and proceeds with that temp path for
`size`/`sha256`/streaming; the temp file is deleted in a `finally`. If none exist, it uses the
original path unchanged — no copy, no allocation on the common path. Integrity is therefore computed
over exactly the bytes the agent receives, so the deployed sha is the cleaned jar's (design intent).

A small helper: `stripJarSignatures(jar: Path): StripResult?` returning the temp path + the list of
removed entry names, or null when nothing was stripped. Only `META-INF/*.SF/*.RSA/*.DSA/*.EC` are
dropped; `MANIFEST.MF` stays (its per-entry digests are inert once the `.SF` is gone).

### D2 — Report via an `onNotice` hook

`pushJar` (and `WdbClient.push`) gain an optional `onNotice: ((String) -> Unit)? = null`. When it
strips, it calls `onNotice("stripped N stale signature file(s): a.SF, b.RSA")`. Frontends wire it:
the CLI echoes it, the plugin raises an INFORMATION notification, and the MCP `deploy` tool appends
it to its text result. Silent on the unchanged path.

### D3 — Why client-side, auto, always

A signed jar cannot pass verification after a fat-jar repack, so the signature files are never useful
in a deployed `java -jar` artifact — stripping is safe and correct by default (no opt-in flag).
Doing it in `pushJar` means CLI, plugin, and MCP all benefit from one place, and the agent needs no
change (older agents work). Agent-side stripping was rejected: it would mutate the artifact after the
integrity check and split the fix across a protocol boundary.

## Non-Goals

- Re-signing or preserving signatures (a deployed fat jar is not a distributable signed artifact).
- Stripping anything beyond signature files (no service-file merging, no manifest rewriting).
- A fix in the app's own build — recommended separately (exclude signatures from the uber jar), but
  wdb making any prebuilt fat jar deployable is the point here.

## Risks

- **A jar that legitimately relies on its own signature at runtime** — none in scope; wall apps are
  run with `java -jar`, which only *verifies* signed jars, never *requires* them. Stripping only
  removes a failure mode.
- **Temp-file space / cleanup** — one temp jar per signed push, deleted in `finally`; negligible.
