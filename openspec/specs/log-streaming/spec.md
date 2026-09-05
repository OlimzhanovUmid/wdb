# log-streaming Specification

## Purpose

Streams a launched app's stdout/stderr from an agent back to a connected client so a developer can see what a demo-wall machine is doing — now or shortly before — without physical access to it, and without the log path ever slowing the app down.

## Requirements

### Requirement: Stream app output to a connected client

The agent SHALL capture the supervised app's stdout and stderr and stream them to a subscribed client in near-real-time, preserving line order, distinguishing the two streams, and timestamping each line on the agent.

#### Scenario: Live tail of a running app

- **WHEN** a client subscribes to logs for a running machine
- **THEN** the client receives the app's stdout and stderr lines as they are produced, in order, each with an agent-side timestamp

#### Scenario: Stderr is distinguishable

- **WHEN** the app writes to stderr
- **THEN** the streamed output marks those lines as stderr so the client can render them distinctly

### Requirement: Logs survive restarts with a boundary marker

When supervision restarts the app, the log stream SHALL continue for a still-subscribed client and SHALL emit a marker indicating a new app run began.

#### Scenario: Client keeps tailing across a crash-restart

- **WHEN** a supervised app crashes and is relaunched while a client is subscribed
- **THEN** the client's stream continues and includes a marker delimiting the previous run from the new run

### Requirement: Recent history is delivered on subscribe

The agent SHALL retain a bounded in-memory tail of the app's output for the current run and the immediately previous run. When a client subscribes, the agent SHALL deliver that retained history first (including the run-boundary marker between runs), then continue with live output, so a crash that happened with no client connected can still be diagnosed.

#### Scenario: Diagnose an unobserved crash

- **WHEN** the app crashed and was relaunched while no client was subscribed, and a client then subscribes
- **THEN** the client receives the retained tail of the crashed run, the run-boundary marker, the retained tail of the current run, and then live output

#### Scenario: History is bounded

- **WHEN** the app has produced more output than the retention limit
- **THEN** the delivered history contains only the most recent output up to that limit, and the app is not slowed or blocked by retention

### Requirement: A slow subscriber never blocks the app

The agent SHALL keep reading the app's output regardless of subscriber speed. When a subscriber cannot keep up, the agent SHALL drop the oldest pending output for that subscriber and SHALL emit a marker stating how many lines were dropped, rather than stalling the app, other subscribers, or other streams to the same agent.

#### Scenario: Paused terminal

- **WHEN** a subscribed client stops reading while the app keeps producing output
- **THEN** the app continues unaffected, other clients keep receiving output, and when the client resumes it sees a dropped-lines marker followed by current output

### Requirement: Subscription lifecycle is bounded

A client SHALL be able to unsubscribe from logs, and the agent SHALL stop streaming to a disconnected client without affecting the supervised app.

#### Scenario: Client disconnects

- **WHEN** a subscribed client disconnects or unsubscribes
- **THEN** the agent stops streaming to it and the app continues running unaffected
