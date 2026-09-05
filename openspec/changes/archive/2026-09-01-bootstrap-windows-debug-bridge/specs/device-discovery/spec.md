## Purpose

Lets a dev-machine client learn which demo-wall machines are reachable on the LAN and their current state, without manual IP configuration, so commands can target machines by name.

## ADDED Requirements

### Requirement: Agents answer discovery queries

Each agent SHALL listen for discovery queries on the local network and SHALL answer each query directly to its sender. An answer SHALL include a stable machine identifier, a human-readable name, the agent's contact address (IP and port), the protocol version, the current app state (stopped / running / crashed), and the desired state.

#### Scenario: Client discovers a running agent

- **WHEN** a client sends a discovery query on a LAN where an agent is running
- **THEN** the client receives that agent's answer with its identifier, name, address, protocol version and states within the query window

#### Scenario: Answer carries app state

- **WHEN** an agent is supervising a running app
- **THEN** its answer reports state `running`; when no app is deployed it reports `stopped`

### Requirement: Discovery needs no inbound firewall configuration on the dev machine

Discovery SHALL work from a dev machine with default Windows Firewall settings, without adding an inbound rule or answering a firewall prompt, because the client only sends a query and receives direct answers to it.

#### Scenario: Fresh dev machine

- **WHEN** a developer runs discovery from a machine where no firewall rule for the client exists
- **THEN** agents on the LAN are listed without any firewall prompt or configuration

### Requirement: Client enumerates discovered machines

The client SHALL provide a discovery operation that returns the set of machines that answered within a bounded query window, de-duplicated by machine identifier, so a machine that answers more than once appears once.

#### Scenario: Duplicate answers collapse to one entry

- **WHEN** an agent answers more than once during one discovery (e.g. to a repeated query)
- **THEN** the client returns exactly one entry for that machine identifier

#### Scenario: Empty network

- **WHEN** discovery runs and no agents answer
- **THEN** the client returns an empty machine set without error

### Requirement: Machine identifier is stable

An agent's machine identifier SHALL remain the same across agent restarts, machine reboots, and IP address changes.

#### Scenario: IP changes after reboot

- **WHEN** a machine reboots and obtains a different IP address
- **THEN** discovery reports it under the same machine identifier with the new address

### Requirement: Machines can be addressed without discovery

The client SHALL accept an explicit agent address for any machine-targeting command, so a machine remains reachable when broadcast discovery is unavailable on the network.

#### Scenario: Broadcast blocked

- **WHEN** discovery yields nothing but the developer supplies the agent's address explicitly
- **THEN** the command connects to that agent directly and succeeds
