# compose-hot-reload Specification

## Purpose

Lets an operator edit a Compose UI on a dev machine and see the change live on remote demo-wall machines without restarting the app, by pushing changed classes to the running JVM and recomposing in place. Falls back to a full redeploy when a change cannot be hot-applied.

## Requirements

### Requirement: Hot-reload run mode is opt-in

The agent SHALL be able to launch the current deployment in a hot-reload run mode that enables live class replacement, and SHALL launch normally otherwise. An app that is not built for hot reload SHALL run unchanged and SHALL NOT be forced into hot-reload mode.

#### Scenario: Launch in hot-reload mode

- **WHEN** an operator starts a deployment in hot-reload mode
- **THEN** the app runs with live class replacement enabled and is able to accept reload pushes

#### Scenario: Normal run is unaffected

- **WHEN** an operator starts a deployment without requesting hot-reload mode
- **THEN** the app runs exactly as a normal run, with no hot-reload machinery attached

### Requirement: Push changed classes to a live app

The client SHALL provide an operation to send a batch of changed application classes — each identified by its path, its bytes, and a change type of added, modified, or removed — to a running hot-reload app. The agent SHALL apply the batch to the live process and SHALL report whether the reload was applied or failed, without the client needing shared filesystem access to the target machine.

#### Scenario: Modified classes are applied live

- **WHEN** the client pushes a batch of modified classes to an app running in hot-reload mode
- **THEN** the agent applies them to the live process and reports the reload as applied

#### Scenario: Reload preserves the running window

- **WHEN** a reload is applied to a running app
- **THEN** the app is not restarted and its existing window remains open, now reflecting the new code

#### Scenario: Reload requires hot-reload mode

- **WHEN** the client pushes a reload batch to an app that is not running in hot-reload mode
- **THEN** the agent rejects the push with an error indicating hot-reload mode is required, and the running app is left untouched

### Requirement: Reload distributes to many machines with per-machine results

The client SHALL be able to target a single machine or all discovered machines with one reload. When targeting many, each machine's reload SHALL be independent: a failure or unreachability on one SHALL NOT abort the others, and the client SHALL report a per-machine result.

#### Scenario: Fan-out reload with a failing machine

- **WHEN** the client reloads all discovered hot-reload apps and one machine is unreachable or its reload fails
- **THEN** the reachable machines apply the reload and the failing one is reported as failed, without aborting the others

### Requirement: A watched reload pushes only what changed

The client SHALL provide a mode that watches a dev-side build-output location and, on each change, pushes only the classes that differ from what was last pushed to each target.

#### Scenario: Watch pushes an incremental change

- **WHEN** the operator is watching and recompiles a single changed class
- **THEN** the client pushes only the changed class to the targets and leaves unchanged classes untransferred

### Requirement: Failed reload falls back to full redeploy

If a change cannot be hot-applied to the live process — for example a structural change beyond the runtime's redefinition limits, or an edit that leaves the app in a broken state — the agent SHALL report the reload as failed and leave a clear signal to redeploy. The client SHALL fall back to a full redeploy and restart of that machine so the app is never left wedged by a partial reload.

#### Scenario: Unsupported change triggers redeploy fallback

- **WHEN** a pushed change cannot be hot-applied
- **THEN** the agent reports the reload as failed and the client falls back to a full redeploy and restart of that machine

### Requirement: Reload integrity is verified before it is applied

The agent SHALL verify the integrity of a received reload batch before applying it to the live process. A batch that fails verification SHALL NOT be applied and SHALL be reported as failed, leaving the running app untouched.

#### Scenario: Corrupt reload batch is rejected

- **WHEN** a received reload batch fails its integrity check
- **THEN** the agent does not apply it, keeps the app running unchanged, and reports the failure
