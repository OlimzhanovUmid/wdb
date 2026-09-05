## ADDED Requirements

### Requirement: Downloadable release distribution

The wdb MCP server SHALL be published as a versioned, self-contained distribution archive that can be downloaded and run without a repository checkout or a local build. The archive SHALL contain a launcher and all runtime dependencies, and running the launcher SHALL start the same stdio MCP server the CLI and plugin use. The distribution SHALL be produced and published automatically for a release.

#### Scenario: Release publishes the distribution

- **WHEN** a release is cut (a version tag is pushed)
- **THEN** the CI publishes a versioned `wdb-mcp` distribution archive as a downloadable release asset

#### Scenario: Downloaded launcher starts the server

- **WHEN** the distribution archive is unzipped on a machine with a compatible Java runtime and its launcher is run
- **THEN** the wdb MCP server starts and speaks the Model Context Protocol over stdio, with no repository checkout required

#### Scenario: Missing Java runtime is surfaced

- **WHEN** the launcher is run on a machine without a discoverable Java runtime
- **THEN** the launcher fails with a clear message indicating a Java runtime is required, rather than a silent no-op
