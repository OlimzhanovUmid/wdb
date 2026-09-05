# app-deployment Specification

## Purpose

Transfers a Compose Desktop app JAR and its launch parameters from the dev machine to one or many demo-wall agents so the whole wall can be updated to a new build with a single command, and rolled back if the build is bad.

## Requirements

### Requirement: Push an app JAR to a target machine

The client SHALL transfer an app JAR to a named agent. On success the agent SHALL have the JAR stored as a deployment and ready to launch against its bundled JRE. The transfer SHALL verify integrity (size and checksum) before the deployment is considered valid.

#### Scenario: Successful single-machine push

- **WHEN** a client pushes a valid JAR to a reachable agent
- **THEN** the agent reports the deployment succeeded and the JAR is available for launch

#### Scenario: Integrity check fails

- **WHEN** a transferred JAR's checksum does not match the source
- **THEN** the agent rejects the deployment, keeps the previously current deployment, and reports the failure

### Requirement: Push carries launch parameters

A push SHALL carry the app's main class and any common JVM and program arguments, and the agent SHALL apply them on every launch of that deployment.

#### Scenario: JVM arguments applied

- **WHEN** a deployment was pushed with JVM arguments
- **THEN** the app process on that machine is launched with those arguments

### Requirement: Fan-out push to many machines

The client SHALL support pushing one JAR to many agents in a single operation (including an "all discovered machines" target) and SHALL report per-machine success or failure so a partial failure is visible.

#### Scenario: Fan-out with a partial failure

- **WHEN** a client pushes to several machines and one is unreachable
- **THEN** the operation completes for the reachable machines and reports the unreachable machine as failed, without aborting the others

#### Scenario: Fan-out with no machines

- **WHEN** a client pushes to all machines and discovery finds none
- **THEN** the operation fails with an explicit error rather than silently doing nothing

### Requirement: Deployment switch is atomic and does not disturb the running app

A new deployment SHALL become current only after it is fully transferred and verified. Until then the running app SHALL continue unaffected from its current deployment, a partially transferred deployment SHALL NOT be launchable, and an interrupted push SHALL NOT corrupt the current deployment.

#### Scenario: App keeps running during transfer

- **WHEN** a push is in progress to a machine whose app is running
- **THEN** the app keeps running from its current deployment until the transfer is verified

#### Scenario: Push interrupted mid-transfer

- **WHEN** a push is interrupted before completion
- **THEN** the current deployment remains intact and launchable, and the incomplete transfer is discarded

### Requirement: Push restarts a running app onto the new deployment

When a push completes on a machine whose app is running, the agent SHALL restart the app onto the new deployment using the graceful stop, unless the client requested stage-only. A stage-only push SHALL leave the running app untouched and make the new deployment current for the next run.

#### Scenario: Wall-wide update in one command

- **WHEN** a client pushes to all machines whose apps are running
- **THEN** every machine restarts its app on the new deployment and reports `running`

#### Scenario: Stage only

- **WHEN** a client pushes with the stage-only option to a running machine
- **THEN** the app keeps running the old deployment and the new deployment is used on the next run

### Requirement: Previous deployment is retained for rollback

The agent SHALL retain the previously current deployment after a successful push, and the client SHALL be able to roll a machine back to it, restarting the app if it is running.

#### Scenario: Roll back a bad build

- **WHEN** a client issues rollback on a machine that has a previous deployment
- **THEN** the previous deployment becomes current again and a running app is restarted onto it

#### Scenario: Nothing to roll back to

- **WHEN** a client issues rollback on a machine with only one deployment
- **THEN** the agent rejects the command and reports that no previous deployment exists

### Requirement: Stale JAR signature files are removed before deployment

When the client pushes a jar that contains JAR signature files (a signed dependency's
`META-INF/*.SF`, `*.RSA`, `*.DSA`, or `*.EC` entries), the client SHALL remove those files before
transferring the jar, so a fat jar built over a signed dependency runs instead of failing the JVM's
jar-signature verification. Integrity verification (size and checksum) SHALL be performed over the
transferred (cleaned) jar, so the deployment's identity reflects what actually runs. When signature
files are removed, the client SHALL report that removal to the operator. A jar that contains no
signature files SHALL be transferred unchanged.

#### Scenario: Signed fat jar is stripped and runs

- **WHEN** a client pushes a jar that carries stale signature files from a signed dependency
- **THEN** the client removes those files before transfer, reports what was removed, and the deployed app launches instead of failing signature verification

#### Scenario: Unsigned jar is unchanged

- **WHEN** a client pushes a jar that contains no signature files
- **THEN** the jar is transferred as-is and no removal is reported

#### Scenario: Integrity reflects the transferred jar

- **WHEN** a jar's signature files are removed before transfer
- **THEN** the size and checksum the agent verifies are those of the cleaned jar, and the reported deployed identity is the cleaned jar's
