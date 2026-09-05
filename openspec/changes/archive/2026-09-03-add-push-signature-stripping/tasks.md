## 1. Strip helper + push path

- [x] 1.1 Add `stripJarSignatures(jar: Path): StripResult?` in `wdb-client` (StripResult = temp jar path + removed entry names). Drop only `META-INF/*.{SF,RSA,DSA,EC}` (case-insensitive, directly under `META-INF/`), copy every other entry preserving its compression; return null when none present.
- [x] 1.2 In `pushJar` (`ClientOps.kt`), before `size`/`sha256`, call the helper; if it stripped, use the temp path for the manifest/blob transfer and delete the temp in `finally`. Add `onNotice: ((String) -> Unit)? = null`; on strip, `onNotice("stripped N stale signature file(s): …")`. Thread `onNotice` through `WdbClient.push`.

## 2. Frontend notices

- [x] 2.1 `wdb-cli` push: pass an `onNotice` that echoes the message.
- [x] 2.2 `wdb-plugin` deploy (`pushJar`): pass an `onNotice` that raises an INFORMATION notification.
- [x] 2.3 `wdb-mcp` `toolDeploy`: capture the notice and append it to the tool's text result.

## 3. Verify

- [x] 3.1 `wdb-client` unit test: `stripJarSignatures` removes `*.SF/*.RSA/*.DSA/*.EC` and keeps other entries/`MANIFEST.MF`; returns null for an unsigned jar. A `pushJar` test against `FakeAgent` confirms a signed jar is transferred cleaned (agent receives the stripped sha) and `onNotice` fires; an unsigned jar is unchanged and silent.
- [x] 3.2 Live: rebuild + redeploy the third-party app uber jar (with its original signature files) through wdb — it strips, reports, and the app launches (`status` RUNNING, no `SecurityException` in logs). Confirm an ordinary unsigned jar (dummy HotApp) still deploys unchanged.
  - Live on wall "1": `wdb push` of the original signed `a signed third-party jar` printed `stripped 4 stale signature file(s): META-INF/META-INF signature files`, `ok restarted=true`; `status` → RUNNING, uptime ~27s, restarts 0, deployed sha = cleaned jar; no `SecurityException`/`javaagent failed` in logs. Unsigned-unchanged-and-silent path is covered by the `wdb-client` unit test (not re-run live, to leave a third-party app running on the wall).
