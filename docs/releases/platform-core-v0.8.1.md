# platform-core 0.8.1

**Requires Spring Boot 4.2 and Java 21.** A patch of 0.8.0 after checking it against what apps need on Boot 4.2 (P051). No
schema change, no API change. Import `platform-bom` 0.8.1. Published to GitHub Packages,
`https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Fixed

- **The setup jar runs with `java -jar`:** it names its main class and its libraries (which lie in the same folder). In 0.8.0
  it had no main class. A product's setup wrapper still holds the licence keys; without them `plan` and `apply` refuse with
  `SETUP_KEYS_INVALID`.
- **`setup --help`** prints the options and ends with 0 (it was refused as a wrong option).

## Checked, unchanged from 0.8.0

- A plain Boot 4.2 app (JDBC, Actuator, Security starters) starts with each `-spring` module and with all of them.
- One Nimbus version, Spring Security's. Nothing of Boot 3. No Jackson 2 in required dependencies or public signatures.
- Every released migration is unchanged byte for byte and runs under Flyway 12 as its schema's own run.
- The types apps use stay public; a build test keeps the list through 1.x.

## Spring versions and defaults

Spring Boot 4.2.0, Framework 7.1.0, Security 7.2.0 (what 0.8.1 is compiled and tested with; the app's Boot 4.2.x wins).

- **No default of a `platform.*` setting changed** in 0.8.0 or 0.8.1.
- **Boot's own classes moved:** health to `spring-boot-health`, `DataSourceAutoConfiguration` to `spring-boot-jdbc`. An app
  that names them by text must change the name; Boot ignores a missing one silently.
- **Flyway is a module of its own in Boot 4** (`spring-boot-flyway`). The platform's schema runs use Flyway's API and need
  only `flyway-core` and `flyway-database-postgresql`.
- **The liveness and readiness probe addresses are on by default** (`management.endpoint.health.probes.enabled=true`); in
  Boot 3 they were on only under Kubernetes.
- **Jackson 3 is Boot 4's JSON library.** The platform uses its own JSON and needs none.
- Boot's other changed defaults are in Spring Boot's own release notes for 4.0, 4.1 and 4.2.
