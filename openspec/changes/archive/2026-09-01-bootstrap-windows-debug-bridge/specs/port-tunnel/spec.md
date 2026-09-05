## Purpose

Provides a TCP-forward channel from a client to a loopback port on an agent's machine, used first to reach the app's always-on JDWP debug port so a developer can attach an IDE's Remote JVM Debug to a demo-wall machine without restarting the app.

## ADDED Requirements

### Requirement: TCP forwarding to the agent's loopback ports

The client SHALL be able to open a forward that binds a local TCP port on the dev machine and relays its byte stream to a port on the agent machine's loopback interface, in both directions, transparently. Each forward SHALL be independent: a stalled forward SHALL NOT affect other forwards, log streams, or commands to the same agent.

#### Scenario: Bytes relayed both ways

- **WHEN** a forward is open from a local port to an agent loopback port and data is written at either end
- **THEN** the same bytes arrive at the other end in order, in both directions, until the forward is closed

#### Scenario: Forward closes cleanly

- **WHEN** either side closes its connection or the client closes the forward
- **THEN** the paired connection is closed and the local port is released

#### Scenario: Independent of other streams

- **WHEN** a log subscriber to the same agent stops reading
- **THEN** an open forward keeps relaying without stalls

### Requirement: Forward targets are restricted to the agent's loopback

The agent SHALL refuse forwards whose target is not the loopback interface of its own machine.

#### Scenario: Forward to another host is refused

- **WHEN** a client requests a forward to a non-loopback address through an agent
- **THEN** the agent rejects the request with an error and opens no connection

### Requirement: App is always debuggable on the agent's loopback

Every launch of the app SHALL enable JDWP listening on a loopback-only port of the agent machine without suspending startup, and the agent SHALL report that port in the machine's status. The debug port SHALL NOT be reachable from the LAN except through a forward.

#### Scenario: JDWP port reported

- **WHEN** the app is running
- **THEN** status reports the JDWP port, and that port accepts connections only from the machine itself

### Requirement: IDE attaches through a forward without restarting the app

Using a forward to a machine's JDWP port, an IDE's standard Remote JVM Debug SHALL be able to attach to the running app over the localhost forward, without any IDE plugin and without restarting or otherwise disturbing the app.

#### Scenario: IDE attaches to a wall machine

- **WHEN** a developer opens a debug forward for a machine and points IntelliJ/Android Studio "Remote JVM Debug" at the local forwarded port
- **THEN** the IDE attaches to the running app, the app's current screen state is preserved, and breakpoints are hit

### Requirement: Debug startup with a suspended launch

On request, the agent SHALL relaunch the app with JDWP configured to suspend until a debugger attaches, so startup code can be debugged.

#### Scenario: Suspended debug launch

- **WHEN** a client requests a suspended debug launch for a machine
- **THEN** the agent relaunches the app, the app waits for a debugger, and it proceeds once the IDE attaches through the forward
