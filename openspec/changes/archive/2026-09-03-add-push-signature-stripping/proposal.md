## Why

A signed dependency (e.g. BouncyCastle) leaves its JAR signature files (`META-INF/*.SF/*.RSA/*.DSA`)
inside an uber/fat jar. After the fat-jar merge the archive's content no longer matches those
signatures, so the JVM jar-verifier throws `SecurityException: Invalid signature file digest for
Manifest main attributes` the first time a class is loaded from it — surfaced fatally under the CHR
`-javaagent` premain, which crash-loops the app. This bit a real deploy (a third-party app, signed by
BouncyCastle + a fiscal lib): the uber jar bundled its JNA/usb4java natives fine but never launched
until the four signature files were stripped. A signed fat jar can never verify after a repack, so
carrying those files is always wrong for a `java -jar` deployment.

## What Changes

- **Auto-strip on push**: when the client pushes a jar that contains signature files
  (`META-INF/*.SF`, `*.RSA`, `*.DSA`, `*.EC`), it rewrites the jar to a temporary copy with those
  entries removed and transfers that copy — so signed-dependency fat jars run. Integrity (size +
  checksum) is computed over the cleaned copy, so the deployed sha is the cleaned jar's.
- **Report it**: the push surfaces a notice of what was stripped (how many / which files), threaded
  to the frontends — the CLI prints it, the plugin notifies, the MCP `deploy` includes it in its
  text result.
- **Unchanged fast path**: a jar with no signature files is pushed byte-for-byte as today (no temp
  copy, no rewrite).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `app-deployment`: a pushed jar has stale signature files removed before transfer, and the client
  reports what was stripped.

## Impact

- **Code:** `wdb-client` `pushJar` (and the `WdbClient.push` wrapper) gain a strip-then-transfer step
  and an `onNotice` hook; `wdb-cli` prints the notice, `wdb-plugin` notifies, `wdb-mcp` includes it
  in the deploy result. A small jar-rewrite helper (drop `META-INF/*.{SF,RSA,DSA,EC}`).
- **Protocol/agent:** unchanged — the agent still receives a jar + manifest; the bytes it verifies
  are the cleaned jar's. No agent update needed.
- **Behavior:** deployed sha changes for a previously-signed jar (it's the cleaned jar) — expected
  and correct.
