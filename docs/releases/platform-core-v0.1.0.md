# platform-core 0.1.0

The first release of platform-core: shared platform libraries for document products. Every module has the same
version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.1.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`. Reading it needs a token with
`read:packages` in your own `~/.m2/settings.xml`.

**No other product's name or behaviour in code.**

## Modules

| Module | What it is |
|---|---|
| `platform-bom` | Bill of materials: every module at 0.1.0 |
| `platform-identity-core` | Identity: claims, OIDC connection bindings, entity admission (`MachineEntityBindings` among them), role mapping; client-binding file reader; store interfaces |
| `platform-identity-spring` | Spring Security adapters: JWT normalizer, audience validator, caching binding resolver |
| `platform-identity-jdbc` | Identity storage (Postgres): customers, entity slots, login connections, group grants, time zones, integration client bindings with the built-in `PostgresMachineClients` behind `PostgresMachineEntityBindings` |
| `platform-identity-testkit` | In-memory identity stores (test scope) |
| `platform-notifications` | Notifications: catalogue, settings, routing, outbox, SMTP / Microsoft 365, Teams, Slack, approval links |
| `platform-notifications-testkit` | In-memory notification parts (test scope) |
| `platform-notifications-spring` | Spring Boot wiring for notifications |
| `platform-licensing` | Signed licences, licence states, usage ledger, licence and licence-issuing commands |
| `platform-licensing-testkit` | In-memory licensing parts and test keys (test scope) |
| `platform-licensing-spring` | Spring Boot wiring for licensing |
| `platform-setup` | The setup command: licence, customer, entity slots, login connection, grants, integration client bindings, key creation; checks integration profiles named in the sign-in file |
| `platform-fields` | Case field definitions, values (sealed sensitive values, fingerprint search), delivery format |
| `platform-fields-testkit` | In-memory case field stores (test scope) |
| `platform-crypto` | Key service plug-in (Vault Transit), envelope encryption, stream encryption, fingerprints, customer key destruction |
| `platform-crypto-testkit` | In-memory key service (test scope) |
| `platform-crypto-spring` | Spring Boot wiring for crypto |
| `platform-erase-log` | The erase log outside the database, restore guard, replay, retention |
| `platform-erase-log-testkit` | In-memory erase log parts (test scope) |
| `platform-erase-log-spring` | Spring Boot wiring for the erase log |
| `platform-integration` | Configurable integrations: versioned configuration, call engine, sign-in bundle, safety, classified results, health, Test action, packagings, field mapping, integrity check; the integration profile format `platform-integration-profile/1` (validator, loader) |
| `platform-integration-testkit` | Mock destination (optionally spec-driven), mock proxy, test certificates, `ProfileMock`, the contract kit `ProfileContract` (test scope) |

## Design docs implemented

`docs/design/`: `licensing.md`, `licensing-file-format.md`, `notifications.md`, `setup.md` (including 6 and 6.1,
integration client bindings), `case-fields.md`, `crypto.md`, `erase-log.md`, `integration-module.md` (core, packagings,
field mapping, integrity, health busy vs down), and `integration-profile-format.md`. `integration-standards.md` holds
requirements only. `model-governance.md` is a draft and is not built.

## Breaking changes

None within platform-core: this is its first release. Compared with the earlier identity library (tags `v0.2.1`,
`v0.3.0`, coordinates `com.documentplatform.identity:enterprise-identity-*`):

- The coordinates are now `com.documentplatform.platform:platform-identity-*`.
- The Java packages `com.documentplatform.identity…` are unchanged. `MachineEntityBindings`, `EntityId` and
  `ConnectionKey` are identical.

## Database schemas

Each schema is run by the app's schema step, as its own Flyway run. App startup only checks the schema.

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | **2** (`V2__machine_binding.sql`: integration client bindings) |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | the app's (location added to its own run) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 1 |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
