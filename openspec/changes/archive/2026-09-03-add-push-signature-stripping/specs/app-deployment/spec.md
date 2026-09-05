## ADDED Requirements

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
