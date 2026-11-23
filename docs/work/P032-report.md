# P032 report — monitoring hooks and the common error-code standard, release `platform-core-v0.4.0`

Date: 2026-11-23
Branch: `feature/monitoring-error-codes` (then `release/platform-core-0.4.0`, `chore/0.5.0-snapshot`, `docs/p032-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

- **Release:** tag `platform-core-v0.4.0` on commit `9f5c57e1389e4aa33ab720280c64c69ab478f1d9` (main). Main's own
  CI run 45117737092 passed before the tag. Release workflow run 45118207113 passed (version check, `mvn -B verify`,
  deploy, GitHub release). Published and checked: `platform-errors`, `platform-monitoring`,
  `platform-monitoring-spring`, `platform-monitoring-testkit` at 0.4.0; `platform-error-catalogue` is not published
  (by design).
- **PRs**, each merged with the README's stop-on-failed-check line, exit status 0:
  - #19 `feature/monitoring-error-codes`. CI runs 45116476344 and 45116483735 passed.
  - #20 `release/platform-core-0.4.0`. CI runs 45117119998 and 45117126209 passed.
  - #21 `chore/0.5.0-snapshot`. CI runs 45119140411 and 45119145841 passed.
  - This report through its own PR.
- **Tests on CI (#19): 1217, 0 skipped** ("Tests run: 1217, skipped: 0"). P028 had 1188. Per module: crypto 20,
  crypto-spring 5, crypto-testkit 16, erase-log 16, erase-log-spring 5, erase-log-testkit 9,
  **error-catalogue 3**, **errors 5**, fields 91, fields-testkit 18, identity-core 117, identity-jdbc 65,
  identity-spring 28, identity-testkit 14, integration 50, integration-spring 8, integration-testkit 196,
  licensing 144, licensing-spring 34, licensing-testkit 47, **monitoring-spring 5**, **monitoring-testkit 16**,
  notifications 81, notifications-spring 41, notifications-testkit 132, outbound 8, outbound-spring 4, setup 39.
- No failed check was re-run.

## Independence

**No other product's name or behaviour in code.** The code texts are generic; tests use `PRODUCT_A`, made-up
services (`ocr`, `parser`) and made-up secrets. `NoProductNamesGuardTest` passes.

## What was built

### B1. The error-code standard (`platform-errors`, new)

- `PlatformCode`: `code()` (the enum's name; **nothing renamed**), `kind()`, `userMessage()`, `cause()`, `fix()`,
  `supportNote()`, `httpStatus()`, `family()`. The texts are data next to each enum (`<Enum>.codes.properties`,
  defaults per family `*.` and per prefix `PREFIX_*.`), read by the default methods, so no enum's constants changed.
- **Every code enum implements it**: `IntegrationCode`, `OutboundCode`, `PushRefusal`, `TestReport.Code`,
  `LocalTestIssuers.Problem`, `ConnectionOutcome.Result`, `ActivationOutcome.Result`, `TimeZoneOutcome.Result`,
  `IdentityAdmissionException.Reason`, `MappingError.Code`, `ClientBindingsFile.Code`, the six schema `Problem`s
  (identity, integration, licensing, notifications, fields, erase log), `LicenceRefusal`, `ResetRefusal`,
  `IssueError`, `UsageError`, `ChainCheck.Problem`, `ExportCheck.Problem`, `FailureCode`,
  `NotificationException.Reason`, `ApprovalRefusal`, `CryptoError`, `CryptoCommand.Code`, `EraseLogError`,
  `FieldDefinitionException.Code`, `ValueError`, `FieldValuesError`, `DeliveryProblem.Code`,
  `FieldFileException.Code`, `SetupRefusal`, and the new `MonitoringCode`. 616 codes. The texts were seeded from
  each constant's Javadoc and existing meaning/action strings, then completed by hand (every code has a message,
  cause and fix; kinds set per code).
- `PlatformError` (code, user message with the reference, cause with safe details, reference, time; `toString` and
  `logLine` safe); `SafeText` (secret-named details and JWT / PEM / `password=` / Bearer / key-like values removed;
  references and addresses kept); `HasPlatformCode` on the schema exceptions, `LicenceException`, `CryptoException`.
- **Catalogue:** `Catalogue` writes `docs/errors/platform-error-codes.md` and `.json` (module, family, code, kind,
  message, cause, fix, support, http). The build check `platform-error-catalogue` (CI) scans the classpath: every
  enum named like a code implements `PlatformCode`, no code lacks a message, cause or fix, no code name is in two
  modules, and the files are current (`-Dplatform.errors.write=true` regenerates).
- Guide `docs/design/error-codes.md`: apps' own codes (implement or map), message + reference, the API error format
  `{"code","message","reference","details"}`, `X-Request-Id`, API versioning (`/v1`, additions only, new version
  otherwise, a release of notice).

### B2. `platform-monitoring` (+ `-testkit`, `-spring`)

- `DependencyCheck`, `Verdict`, `CheckResult`, `CheckRunner` (throw → the exception's own code or
  `MON_CHECK_FAILED` with the class name only; hang → `MON_CHECK_TIMEOUT`), `DependencyStatus` (latest results,
  scheduler, `MonitoringEvent` BECAME_DOWN / BECAME_HAVING_PROBLEMS / RECOVERED), `StartupChecks`.
- Built-in checks: `DatabaseCheck` (reach, login 28xxx, each schema in use through its own `verify`),
  `KeyServiceCheck` (the key service's `CRYPTO_*` code; required only when `customerKeys` is required),
  `SecretStoreCheck`, `LoginSystemCheck` (test sign-in without a token over `IssuerFetch`/`OutboundIssuerFetch`),
  `LicenceCheck` (read only), `ChannelCheck.mail` / `.webhook` (TCP reach; nothing sent; webhook path never shown),
  `FileLocationCheck` (probe file; 90 % / 95 %), `HttpServiceCheck`, `IntegrationChecks` (never required).
- Spring: `platform.monitoring.*`; built-in checks from the app's beans; the app's `DependencyCheck` beans; start-up
  refusal with `MonitoringFailureAnalyzer`; `MonitoringListener` beans; health: `readinessState` (readiness state +
  required checks), `dependencies` (informational); integration-spring's `integrations` contributor backs off;
  Micrometer `platform.monitoring.check.state` / `.duration`, `platform.integration.attempts` (new
  `CallEngine.onAttempt`); `RequestReferenceFilter` (`X-Request-Id` echoed or made, MDC `ref`,
  `ReferenceId.current()` so a request's failures carry its reference).
- Standard in `docs/design/monitoring.md` (metrics naming, the log line, Splunk / Datadog pickup).

### B3. Reference IDs

`ReferenceId.next()` (`R-` + 12 Crockford base32: 7 time, 5 random), `ReferenceId.current()`,
`FailureLog.record(code, safeDetails)` (one line, returns the reference; last 200 kept); every `PlatformError` has
one.

### B4. Diagnostic bundle

`DiagnosticBundle.build(sink, inputs)` / Spring `DiagnosticBundles.write`: zip with versions (platform from the jars'
Maven properties, app through `AppVersions`), Java and OS, configuration with secrets removed (the app's own property
sources), check results, the last 50 failures, schema versions (the six version tables), and a SHA-256 manifest;
size limit 5 MiB (entries left out are named in the manifest, `MON_BUNDLE_TOO_LARGE`).

### B5. Docs

`docs/install/monitoring.md` (plain words for IT), `docs/design/monitoring.md` (with the decisions),
`docs/design/error-codes.md`; the integration and identity install guides point to the catalogue; CLAUDE.md rows
and a rule for new codes.

## Minimal example: an app's own check and its own code

```java
public enum ScanCode implements PlatformCode {      // texts in ScanCode.codes.properties
    AREA_101_OCR_QUEUE_FULL
}
// ScanCode.codes.properties:
//   AREA_101_OCR_QUEUE_FULL.kind=FAILED
//   AREA_101_OCR_QUEUE_FULL.message=Pages are being read later than usual.
//   AREA_101_OCR_QUEUE_FULL.cause=The OCR queue holds {queued} pages (limit {limit}).
//   AREA_101_OCR_QUEUE_FULL.fix=Add an OCR worker, or check that the OCR service is not stuck.

@Bean
DependencyCheck ocrQueueCheck(OcrQueue queue) {
    return new DependencyCheck() {
        public String name() { return "ocr-queue"; }
        public String group() { return "app"; }
        public Verdict check() {
            int queued = queue.size();
            return queued < 10_000 ? Verdict.working()
                    : Verdict.problems(ScanCode.AREA_101_OCR_QUEUE_FULL, Map.of("queued", queued, "limit", 10_000));
        }
    };
}
```

## Sample start-up refusal

```
The app cannot start: 1 required dependency is down.
  database: IDN_VERSION_OLDER: The identity tables are older than the code: the schema step for this release was not run. Fix: Run the identity schema step of this release (Flyway location db/platform/identity), then start the app.
```

## Sample bundle listing (names only)

```
versions.txt
system.txt
configuration.properties
checks.txt
failures.txt
schemas.txt
manifest.txt
```

## New codes

`MON_WORKING`, `MON_CHECK_FAILED`, `MON_CHECK_TIMEOUT`, `MON_DB_UNREACHABLE`, `MON_DB_LOGIN_REFUSED`,
`MON_KEY_SERVICE_DOWN`, `MON_SECRET_MISSING`, `MON_SECRET_UNAVAILABLE`, `MON_LOGIN_NO_CONNECTION`,
`MON_LICENCE_NOT_STARTED`, `MON_LICENCE_EXPIRING`, `MON_LICENCE_IN_GRACE`, `MON_LICENCE_EXPIRED`,
`MON_CHANNEL_SETTINGS_INCOMPLETE`, `MON_CHANNEL_ADDRESS_INVALID`, `MON_CHANNEL_UNREACHABLE`, `MON_PATH_MISSING`,
`MON_PATH_NOT_READABLE`, `MON_PATH_NOT_WRITABLE`, `MON_DISK_ALMOST_FULL`, `MON_DISK_FULL`, `MON_HTTP_ADDRESS_INVALID`,
`MON_HTTP_UNREACHABLE`, `MON_HTTP_TIMEOUT`, `MON_HTTP_UNEXPECTED_ANSWER`, `MON_BUNDLE_TOO_LARGE`.

## Tests asked for → where

| Asked for | Where |
|---|---|
| Every built-in check in every state | `BuiltInChecksTest` (monitoring-testkit): database (testcontainers PostgreSQL: tables missing, working, version older, login refused, unreachable), key service (crypto testkit: working, unreachable, login refused, key missing, permission denied, key disabled), secret store, login system (`MockIssuer`: working, keys missing, unreachable, no connection, through `OutboundIssuerFetch`), licence (missing, valid, expiring, grace, expired, not started, another customer's, tampered), mail (GreenMail: working, nothing sent, incomplete, unreachable) and webhooks (reachable, unreachable, not https), folders (temp dir and a fake disk: missing, not readable, not writable, 91 %, 96 %), HTTP service (`MockHttpService`: working, 503, slow, down, bad address), integrations (`MockDestination`: working, outage; busy as HAVING_PROBLEMS) |
| Start-up refusal | `MonitoringCoreTest.theAppRefusesToStartWhenARequiredCheckIsDown`, `MonitoringWiringTest.theAppRefusesToStartWhileARequiredCheckIsDown`, `…theBuiltInChecksComeFromTheAppsBeansAndSettings` (identity schema in use and missing) |
| State-change events | `MonitoringCoreTest.stateChangesAreEventsAndTheLatestResultsAreKept`, `MonitoringWiringTest.readinessHoldsOnlyRequiredChecks…` (listener bean) |
| Readiness ignores integrations | `MonitoringWiringTest.integrationsAreInTheListButNeverInReadiness` (plus metrics) |
| The catalogue's own rules | `CatalogueTest` (error-catalogue), `ErrorsTest.textsComeFromTheFamilyFile…` |
| No secrets or content in errors, logs or the bundle | `ErrorsTest.anErrorFillsSafeDetailsAndNeverHoldsSecretsOrContent`, `…theFailureLogWritesOneLine…`, `MonitoringCoreTest` (exception text never shown), `DiagnosticBundleTest` (planted in configuration, results, failures, an exception text and a log line), `MonitoringWiringTest` (unsafe `X-Request-Id` ignored) |
| DML-only user | Monitoring stores nothing in the database (decision 3); the database check only reads (the schema checks are the modules' own read-only verifies) |
| Nothing skipped on CI | "Tests run: 1217, skipped: 0" |

## Files changed, commits

On main (from #19): `d192a19` the error-code standard and catalogue (B1, B3); `22f8558` platform-monitoring with
testkit and Spring (B2–B4); `90c6d33` docs (B5); plus `9f5c57e` release 0.4.0 and the 0.5.0-SNAPSHOT commit (#21).
New modules `platform-errors`, `platform-monitoring`, `platform-monitoring-testkit`, `platform-monitoring-spring`,
`platform-error-catalogue`; 36 new `*.codes.properties`; `docs/errors/*`, `docs/design/error-codes.md`,
`docs/design/monitoring.md`, `docs/install/monitoring.md`, `docs/releases/platform-core-v0.4.0.md`.

## Decisions I had to make

1. **Texts as resource files next to each enum** instead of constructor arguments: no constant or name changes, and
   the texts are easy to review; the catalogue check keeps them complete.
2. **`SignedDocument.Problem` is not a code family** (internal, always mapped to `LicenceRefusal` / `ResetRefusal`).
   **Uniqueness** is per module: the same name in two families of one module is allowed (`NOT_UTF8` in the
   role-mapping and the client-binding file); across modules it is refused (none today).
3. **Monitoring stores nothing** in the database (latest results and the last 200 failures are in memory), so there
   is no table and no DML-only test of one.
4. **Readiness via the `readinessState` bean** (Spring Boot's own backs off) instead of setting the readiness group:
   a library setting the group fails start-up wherever the probes are off.
5. **Integration health:** integration-spring's contributor backs off only when platform-monitoring-spring is on the
   classpath (its entries move into `dependencies`); apps without monitoring keep it.
6. **The catalogue check is its own unpublished module** (`maven.deploy.skip`): it must see every module, so it
   cannot live in one of them.
7. **Monitoring's tests live in its testkit** (like integration's): the testkit depends on monitoring, so monitoring's
   own tests could not use it.
8. **`FileLocationCheck.Disk`** is replaceable so the full-disk and no-write-right states are tested the same on every
   system; the real file system is the default.
9. **The licence check calls `LicenceService.status()`**, the app's usual read (it may promote a waiting renewal on
   its start date); it never enforces.
10. **Webhook checks open a TCP connection to the host only**; the path (which holds the webhook's secret) is never
    shown or called.
11. **`FailureLog` uses the request's reference** when the request filter set one, so the person, the log and the
    support ticket share one reference per request.

## Not done, and why

- **Teams / Slack webhook checks are not created automatically:** their addresses are destination settings kept in
  the app's notification settings (secrets), not in the install config. The app adds `ChannelCheck.webhook` beans for
  its destinations (documented). Mail is automatic from `platform.notifications.mail.*`.
- **Spring settings error codes** (`INTEGRATION_SETTINGS_INVALID`, `OUTBOUND_SETTINGS_INVALID`,
  `IDENTITY_PUSH_SETTINGS_INVALID` and the other modules' startup messages) are exception prefixes, not enum codes,
  so they are not in the catalogue yet (question 2).

## Questions (numbered, each with a recommendation)

1. **The Support screen's bundle download:** the platform gives `DiagnosticBundles.write`; the app adds the button
   and an authorisation (IT Admin only). Recommendation: each app adds it in its next IT Admin task.
2. **Startup-settings codes into the catalogue:** make the `*_SETTINGS_INVALID` startup refusals a small
   `PlatformCode` enum per Spring module. Recommendation: yes, in a later small task; nothing in P033 or P034 depends
   on it.
3. **Webhook checks from the notification settings:** monitoring-spring could read the configured Teams/Slack
   destinations through the notifications store. Recommendation: only if an IT team asks; mail covers the common
   case.
