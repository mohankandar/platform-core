# platform-core 0.4.0

The common error-code standard and monitoring hooks (task P032): every platform code now says what failed, why and
what to do; the app checks its dependencies itself, refuses to start when a required one is down, and can hand
support a diagnostic bundle. Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.4.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Modules

All modules of 0.3.0, plus:

| Module | What it is |
|---|---|
| `platform-errors` (**new**) | `PlatformCode` (code, kind, user message, cause, fix, support note, HTTP status), `PlatformError`, `ReferenceId`, `FailureLog`, `SafeText`, `HasPlatformCode`, `Catalogue`. No dependencies. |
| `platform-monitoring` (**new**) | `DependencyCheck`, `CheckResult`, `CheckRunner`, the built-in checks (database and platform schemas, key service, secret store, login system, licence, mail and webhook channels, folders, HTTP services, integrations), `StartupChecks`, `DependencyStatus` with `MonitoringEvent`s, `DiagnosticBundle`, `MonitoringCode` (`MON_*`). |
| `platform-monitoring-testkit` (**new**) | `SwitchableCheck`, `RecordingEvents`, `MockHttpService`. Test scope only. |
| `platform-monitoring-spring` (**new**) | `platform.monitoring.*`, built-in checks from the app's beans, start-up refusal, scheduler, health (`readinessState`, `dependencies`), Micrometer metrics, `RequestReferenceFilter` (`X-Request-Id`, MDC `ref`), `DiagnosticBundles`. |
| every module with codes | Each code enum implements `PlatformCode`, with its texts in `<Enum>.codes.properties`. **No code was renamed.** The schema exceptions, `LicenceException` and `CryptoException` implement `HasPlatformCode`. |
| `platform-integration` | `CallEngine.onAttempt(listener)` (attempts by outcome, for metrics). |

A build check (`platform-error-catalogue`, not published) keeps every code complete and the catalogue current.

## Design docs implemented

`docs/design/error-codes.md` (the standard, apps' own codes, the API error format and versioning),
`docs/design/monitoring.md`; integration standards requirements 15 and 16; `docs/design/integration-module.md`
"Decisions on the P025 report" item 3 (integration health never makes the app unready).

## The catalogue

`docs/errors/platform-error-codes.md` and `docs/errors/platform-error-codes.json` (format `platform-error-codes/1`):
616 codes, each with module, family, kind, message, cause, fix and support note.

## New codes

`MON_WORKING`, `MON_CHECK_FAILED`, `MON_CHECK_TIMEOUT`, `MON_DB_UNREACHABLE`, `MON_DB_LOGIN_REFUSED`,
`MON_KEY_SERVICE_DOWN`, `MON_SECRET_MISSING`, `MON_SECRET_UNAVAILABLE`, `MON_LOGIN_NO_CONNECTION`,
`MON_LICENCE_NOT_STARTED`, `MON_LICENCE_EXPIRING`, `MON_LICENCE_IN_GRACE`, `MON_LICENCE_EXPIRED`,
`MON_CHANNEL_SETTINGS_INCOMPLETE`, `MON_CHANNEL_ADDRESS_INVALID`, `MON_CHANNEL_UNREACHABLE`, `MON_PATH_MISSING`,
`MON_PATH_NOT_READABLE`, `MON_PATH_NOT_WRITABLE`, `MON_DISK_ALMOST_FULL`, `MON_DISK_FULL`, `MON_HTTP_ADDRESS_INVALID`,
`MON_HTTP_UNREACHABLE`, `MON_HTTP_TIMEOUT`, `MON_HTTP_UNEXPECTED_ANSWER`, `MON_BUNDLE_TOO_LARGE`.

## Breaking changes

- **Health JSON readers only:** with platform-monitoring-spring on the classpath, platform-integration-spring's
  `integrations` health contributor is replaced by entries (group `integrations`) in the `dependencies` contributor,
  and `readinessState` also holds the checks required at start-up. Without platform-monitoring-spring nothing
  changes.
- An app on platform-monitoring-spring refuses to start while a required check is down
  (`platform.monitoring.startup-checks=false` switches that off).
- No code is renamed; no method is removed. platform-licensing and platform-crypto now depend on platform-errors (which
  has no dependencies).

## Database schemas

No change since 0.3.0. Monitoring stores nothing in the database.

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 4 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 2 |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | the app's (location added to its own run) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
