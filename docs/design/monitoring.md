# Monitoring: dependency self-checks, metrics, logs, the diagnostic bundle (P032)

Modules `platform-monitoring` (plain Java), `platform-monitoring-testkit`, `platform-monitoring-spring`. Install guide
for IT: `docs/install/monitoring.md`. Codes: `docs/errors/platform-error-codes.md` (`MON_*` and each module's own).

## 1. Checks

A `DependencyCheck` has a name, a group, `requiredAtStartup`, a time limit (default 10 s), an interval (default
60 s) and `check()`, which returns a `Verdict`: WORKING, HAVING_PROBLEMS or DOWN with a `PlatformCode` and safe
details. `CheckRunner` makes the `CheckResult` (state, code, cause with the details, fix, when, how long): a check
that throws is DOWN with the exception's own code (`HasPlatformCode`) or `MON_CHECK_FAILED` with the exception's class
name only; a check past its time limit is DOWN with `MON_CHECK_TIMEOUT`. Nothing is ever unhandled.

| Built-in check | Tells apart | Required at start-up |
|---|---|---|
| `DatabaseCheck` | can't reach (`MON_DB_UNREACHABLE`); login refused (`MON_DB_LOGIN_REFUSED`, SQL state 28…); each platform schema in use at its version (the module's own `…Schema.verify`: `IDN_TABLES_MISSING`, `IDN_VERSION_OLDER`, …) | yes |
| `KeyServiceCheck` | from `KeyService.health()` and, with the app's `CustomerId`, that customer's two keys in use (P047): can't reach, login refused, a customer key missing, permission denied, key disabled (`CRYPTO_*`) | when the product's `customerKeys` is `required` |
| `SecretStoreCheck` | each configured reference resolves (`MON_SECRET_MISSING`, `MON_SECRET_UNAVAILABLE`); names only | no |
| `LoginSystemCheck` | the customer's ACTIVE connection's discovery and keys, through `OutboundIssuerFetch` (`IDN_TEST_*`); no connection is HAVING_PROBLEMS | no |
| `LicenceCheck` | missing, signature invalid, another customer's (`LICENCE_*`), not started, expiring (days), in grace, expired (`MON_LICENCE_*`); read only, never enforces | no |
| `ChannelCheck.mail` / `.webhook` | settings complete; the server or the webhook's host reachable (a TCP connection; nothing sent, no sign-in). A webhook is checked on its **host only**: its path holds the webhook's secret, so it is never shown and never called (P032 decision 10) | no |
| `FileLocationCheck` | exists, readable, writable (a probe file); disk warning and down thresholds (90 % / 95 %, each 1 to 100, the warning not above down). How the folder is seen is a replaceable `FileLocationCheck.Disk` (the real file system by default), so a full disk and a missing write right are tested the same on every system (P032 decision 8) | per folder |
| `HttpServiceCheck` | an app's own dependency (model server, OCR): address valid, reachable, the expected status | per service |
| `IntegrationChecks` | one per enabled integration, from its own health (`INT_*`): healthy WORKING, busy HAVING_PROBLEMS, else DOWN | **never**, and never in readiness |

`StartupChecks.require` runs the required checks once; any DOWN refuses the start with one line per DOWN check:
`name: CODE: cause Fix: fix`. `DependencyStatus` keeps the latest results (the Support screen's list), runs each
check on its interval (a check that sets its own `every()` keeps it; the others take the interval the app gives, in
Spring `platform.monitoring.every`; P039) and sends a `MonitoringEvent` on each change (BECAME_DOWN, BECAME_HAVING_PROBLEMS,
RECOVERED) for the app to route to its notifications.

## 2. Spring

- `platform.monitoring.*` (`docs/install/monitoring.md`). The built-in checks come from the app's modules and
  beans: a DataSource (with the schemas whose module's main bean is there), `KeyService`, `SecretResolver` with
  `secrets`, `LicenceService`, `LoginConnectionStore` + `IssuerFetch` + `product-code`, the mail settings as
  notifications-spring checked them (`platform.notifications.mail.*`: the sender's own host, the port from the security when
  none is set, and under Microsoft 365 its default host and the sender mailbox; without notifications-spring there is
  no mail check; P039), `folders.*`, `services.*`, the integration store. Model-governance-spring adds its own
  `model-governance` check (`docs/design/model-governance.md` section 10). The app's own `DependencyCheck` beans join
  them.
- The scheduler wakes every `tick` (default 5 s) and runs the checks that are due; `every` (default 60 s) is the
  interval of the checks that do not set their own (P039). Every settings problem stops the start with
  `MON_SETTINGS_INVALID`, each named by its property, never a value (P037).
- Liveness never depends on checks. **Readiness** is the `readinessState` indicator, which this module provides
  (Spring Boot's own backs off): the app's readiness state plus the required checks; OUT_OF_SERVICE while one is
  DOWN. The informational `dependencies` contributor lists every check (always UP). platform-integration-spring's
  `integrations` contributor backs off when this module is there: its entries are in `dependencies`.
- `MonitoringListener` beans get the events.

## 3. Metrics (Micrometer, when present)

Naming `platform.<area>.<measure>`; tags are names, never values.

| Metric | Type | Tags |
|---|---|---|
| `platform.monitoring.check.state` | gauge: 0 working, 1 having problems, 2 down | `check`, `group` |
| `platform.monitoring.check.duration` | timer | `check`, `group` |
| `platform.integration.attempts` | counter (from `CallEngine.onAttempt`) | `integration`, `outcome` |

## 4. Logs

One key=value line per failure: `code=… ref=… cause="…"` (`FailureLog`), and per check state change
`check=… state=… code=… cause="…"`. During a request the reference is in the MDC as `ref`
(`RequestReferenceFilter`; `X-Request-Id` echoed or made). The app ships its logs as usual; Splunk and Datadog pick
the fields up with their key=value extraction (Splunk: automatic `KV_MODE=auto`; Datadog: a grok parser
`%{data::keyvalue}` on the message), and the MDC's `ref` through the app's JSON log layout (Logback's
`LogstashEncoder` or Spring Boot's structured logging adds MDC fields).

## 5. The diagnostic bundle

`DiagnosticBundle.build(sink, inputs)` (Spring: `DiagnosticBundles.write`) writes a zip: `versions.txt` (platform
modules from the released jars, the app's own through `AppVersions`), `system.txt` (Java, OS), `configuration.properties`
(an allow-list of sources and property names, `platform.monitoring.bundle-properties` adds prefixes; secret-named and
secret-looking values removed, references and addresses kept), `checks.txt`, `failures.txt` (the last 50: time, code, reference, cause), `schemas.txt` (the version tables),
`manifest.txt` (SHA-256 of each entry). Never document content, field values, tokens or secrets: it reads no log and
no exception text, only codes, causes and results. Size limit 5 MiB: entries that do not fit are left out and the
manifest says `MON_BUNDLE_TOO_LARGE`.

## 6. Decisions (P032)

1. **Texts as data next to each enum** (`*.codes.properties`), read by `PlatformCode`'s default methods: no
   constant changed, no code renamed; the generated catalogue and its check keep them complete.
2. **`SignedDocument.Problem` is not a code family**: it is internal and always mapped to `LicenceRefusal` or
   `ResetRefusal` before anyone sees it. The same name in two families of one module is allowed (for example
   `NOT_UTF8` in the role-mapping and client-binding files); a name in two modules is not.
3. **Monitoring stores nothing in the database**: results are in memory (the latest per check); the failure log is
   in memory (the last 200). No table, so no new rights for the app's database user.
4. **Readiness through `readinessState`**: setting the readiness group from a library would fail start-up wherever
   the probes are off; providing the indicator itself works everywhere.
5. **The licence check uses `LicenceService.status()`**, the app's normal read, which may promote a waiting renewal
   on its start date; it never enforces anything.
6. **Integration health moved into `dependencies`** only when platform-monitoring-spring is on the classpath; without
   it, integration-spring's own contributor stays.

## Decisions on the P032 report (shared-capabilities thread, 2026-11-23)

P032 accepted (v0.4.0). 1. The diagnostic-bundle download button (IT Admin only) is app side. 2. The Spring start-up settings messages (`*_SETTINGS_INVALID`) become `PlatformCode` enums per Spring module: a gap for the strength check's step 2. 3. Teams and Slack destination checks stay app-added (`ChannelCheck.webhook`) unless an IT team asks; mail is checked automatically.
