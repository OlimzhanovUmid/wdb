## ADDED Requirements

### Requirement: Trigger Compose hot-reload from the IDE

The plugin SHALL let the operator push code changes into a machine's hot-running Compose app from the IDE, without using the CLI. For each targeted machine the plugin SHALL compute the change set of compiled classes since the last push and send it to that machine, and SHALL report a per-machine outcome (applied, rejected, or failed). A machine whose app is not running in hot-reload mode SHALL be reported as rejected and left untouched, and one machine's failure SHALL NOT abort the others. The operator SHALL be able to point the plugin at the module's compiled-classes location, remembered per project, and the plugin SHALL propose that location automatically for a detected Compose Desktop module.

#### Scenario: Reload pushes changed classes to a hot app

- **WHEN** the operator invokes Reload on a machine whose app is running in hot-reload mode, after changing and recompiling code
- **THEN** the plugin sends only the classes that changed since the last push and reports that the reload was applied, and the running app reflects the change without restarting

#### Scenario: Nothing to reload

- **WHEN** the operator invokes Reload but no compiled class has changed since the last push
- **THEN** the plugin reports there is nothing to reload and sends nothing

#### Scenario: Reload on an app that is not hot

- **WHEN** the operator invokes Reload on a machine whose app is not running in hot-reload mode
- **THEN** the plugin reports the reload was rejected and the app is left untouched

#### Scenario: One failing machine does not abort the others

- **WHEN** Reload is invoked on several machines and one is unreachable or rejects the reload
- **THEN** the plugin surfaces that machine's outcome and still reloads the reachable, hot machines

#### Scenario: Classes location is configured and proposed

- **WHEN** the operator configures deploy for a project that has a Compose Desktop module
- **THEN** the configuration offers a compiled-classes location prefilled from that module, and remembers it for subsequent reloads
