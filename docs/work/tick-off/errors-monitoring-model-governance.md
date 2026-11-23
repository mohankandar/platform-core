# Tick-off: error-codes, monitoring, model-governance

Counts:
- error-codes: DONE 26 · UNTESTED 6 · PARTLY 4 · MISSING 0 · DIFFERENT 1 · APP-SIDE 6 · DOC GAP 1 (44 rules)
- monitoring: DONE 52 · UNTESTED 7 · PARTLY 5 · MISSING 1 · DIFFERENT 2 · APP-SIDE 3 · DOC GAP 3 (73 rules)
- model-governance: DONE 88 · UNTESTED 3 · PARTLY 4 · MISSING 0 · DIFFERENT 1 · APP-SIDE 11 · DOC GAP 5 (112 rules)

Install guides: monitoring 6 findings, model-governance 5 findings.

Short paths used below:
- `E` = `platform-errors/src/main/java/com/documentplatform/errors/`
- `ET` = `platform-errors/src/test/java/com/documentplatform/errors/ErrorsTest.java`
- `CT` = `platform-error-catalogue/src/test/java/com/documentplatform/errors/catalogue/CatalogueTest.java`
- `M` = `platform-monitoring/src/main/java/com/documentplatform/monitoring/`
- `MT` = `platform-monitoring-testkit/src/test/java/com/documentplatform/monitoring/` (`MonitoringCoreTest`, `BuiltInChecksTest`, `DiagnosticBundleTest`)
- `MS` = `platform-monitoring-spring/src/main/java/com/documentplatform/monitoring/spring/`
- `MW` = `platform-monitoring-spring/src/test/java/com/documentplatform/monitoring/spring/MonitoringWiringTest.java`
- `G` = `platform-model-governance/src/main/java/com/documentplatform/modelgov/`
- `GC` = `platform-model-governance-testkit/src/test/java/com/documentplatform/modelgov/ModelGovContract.java` (run on both stores by `InMemoryModelGovTest` and `jdbc/PostgresModelGovTest`)
- `GS` = `platform-model-governance-testkit/src/test/java/com/documentplatform/modelgov/jdbc/ModelGovSchemaTest.java`
- `GD` = `platform-model-governance-testkit/src/test/java/com/documentplatform/modelgov/DigestsTest.java`
- `GSP` = `platform-model-governance-spring/src/main/java/com/documentplatform/modelgov/spring/`
- `GW` = `platform-model-governance-spring/src/test/java/com/documentplatform/modelgov/spring/ModelGovWiringTest.java`

---

## error-codes — intro and section 1 The shape: `PlatformCode`

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| error-codes-1 | Every failure says what failed, why, what to do: every code has a message, cause and fix | DONE | `E/Catalogue.java` `problems` | `CT` `everyCodeHasAMessageACauseAndAFixAndIsUniqueAcrossModules` | runs on every real code enum |
| error-codes-2 | Never in a message, log, metric or bundle: passwords, keys, tokens, secret values, content, field values | DONE | `E/SafeText.java`, `E/PlatformError.java`, `M/DiagnosticBundle.java` | `ET` `anErrorFillsSafeDetailsAndNeverHoldsSecretsOrContent`; `MT/DiagnosticBundleTest` `secretsAndContentNeverReachTheBundle` | metrics carry names only by reading `MS/MonitoringMetrics.java` (tags check, group, integration ID, outcome) |
| error-codes-3 | Names and addresses of things are kept (secret path, key alias, server address) | DONE | `E/SafeText.java` `secretName` (REFERENCE_NAME) | `ET` `safeTextKeepsNamesAndAddressesOfThings` | |
| error-codes-4 | `code()` is the stable name; nothing is renamed | UNTESTED | `E/PlatformCode.java` `code` | none | the catalogue is regenerated, so a rename only shows as a diff in review; no guard |
| error-codes-5 | `kind()` is REFUSED / FAILED / NEEDS_PERSON / INFO | DONE | `E/PlatformCode.java` `Kind`, `E/CodeTexts.java` `kind` | `ET` `textsComeFromTheFamilyFileWithCodePrefixAndFamilyDefaults` | missing kind defaults to FAILED |
| error-codes-6 | `userMessage()` plain and safe, nothing about accounts or internals | UNTESTED | texts in `*.codes.properties` | none | review rule only; no automatic check of wording |
| error-codes-7 | `cause()` with `{name}` placeholders filled from safe details only | DONE | `E/PlatformError.java` `cause`, `fill` | `ET` `anErrorFillsSafeDetailsAndNeverHoldsSecretsOrContent` | |
| error-codes-8 | `fix()`, `supportNote()`, optional `httpStatus()` | DONE | `E/PlatformCode.java` | `ET` `textsComeFromTheFamilyFileWithCodePrefixAndFamilyDefaults` | |
| error-codes-9 | Every platform code enum implements `PlatformCode`; the build check fails otherwise | PARTLY | `CT` `everyCodeEnumImplementsThePlatformCodeShape` | same | finds code enums only by name pattern (`…Code`, `…Refusal`, `…Error`, `…Problem`, `…Reason`, `Outcome$Result`); a code enum named otherwise is not seen; no negative test; a new module must be added to the catalogue POM by hand |
| error-codes-10 | Texts in `<Enum>.codes.properties` next to the enum; nested enums `Outer.Inner.codes.properties` | DONE | `E/CodeTexts.java` `resourceName`, `load` | `CT` `everyCodeHasAMessage…` (nested families such as `ModelGovSchemaException.Problem` would have no texts otherwise); `ET` family assert | |
| error-codes-11 | Keys `.kind`, `.message`, `.cause`, `.fix`, `.support`, `.http` | DONE | `E/CodeTexts.java` | `ET` `textsComeFromTheFamilyFileWithCodePrefixAndFamilyDefaults` | |
| error-codes-12 | Whole-enum defaults under `*.` | DONE | `E/CodeTexts.java` `text` | `ET` same (`*.kind`, `*.support`) | |
| error-codes-13 | Prefix defaults `PREFIX_*.`, the longest prefix wins | PARTLY | `E/CodeTexts.java` `text` | `ET` same | prefix default tested; "longest wins" not (only one prefix in the sample) |
| error-codes-14 | The code's own text wins over prefix and `*.` | DONE | `E/CodeTexts.java` `text` | `ET` same (`SAMPLE_CONFIG_WORSE.fix`) | |
| error-codes-15 | An enum may override a method (`LicenceRefusal.fix()` is its `action()`) | UNTESTED | `platform-licensing/src/main/java/.../LicenceRefusal.java` `fix` | none | the properties file also has `*.fix`, so the catalogue check passes even if the override broke |
| error-codes-16 | Catalogue `docs/errors/platform-error-codes.md` and `.json` (format `platform-error-codes/1`: module, family, code, kind, message, cause, fix, support, http) generated from the enums | DONE | `E/Catalogue.java` `markdown`, `json` | `ET` (markdown/json lines); `CT` `theGeneratedCatalogueIsUpToDate` | |
| error-codes-17 | The build check runs on CI | DONE | `pom.xml` module `platform-error-catalogue`; `.github/workflows/ci.yml` `mvn -B verify` | `CT` | |
| error-codes-18 | Check fails when a code lacks a message, cause or fix | PARTLY | `E/Catalogue.java` `problems` | `ET` `textsComeFrom…` (no cause only) | no-message and no-fix branches have no negative test |
| error-codes-19 | Check fails when a code name is used in two modules | UNTESTED | `E/Catalogue.java` `problems` | none | branch never exercised (no duplicate today) |
| error-codes-20 | The same name in two families of one module is allowed | DONE | `E/Catalogue.java` `problems` | `CT` `everyCodeHasAMessage…` (`NOT_UTF8` is in two identity families) | |
| error-codes-21 | Check fails when the generated files are stale | DONE | `CT` `theGeneratedCatalogueIsUpToDate` | same | |
| error-codes-22 | Regenerate with `mvn -pl platform-error-catalogue -am verify -Dplatform.errors.write=true` | DIFFERENT | `E/Catalogue.java` `markdown` header | — | the generated catalogue's own header says `mvn -pl platform-error-catalogue verify …` without `-am`, which fails without installed jars (and `mvn install` is not allowed here) |

## error-codes — section 2 Failures: `PlatformError`, `ReferenceId`, `FailureLog`

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| error-codes-23 | `ReferenceId.next()`: `R-` + 12 Crockford base32 | DONE | `E/ReferenceId.java` `next`, `isReference` | `ET` `referencesAreShortTimeOrderedAndUnguessable` | |
| error-codes-24 | Time-ordered (tenths of a second) | DONE | same | `ET` same | |
| error-codes-25 | Unguessable (random bits) | DONE | same (25 bits from `SecureRandom`) | `ET` uniqueness tests | randomness by reading |
| error-codes-26 | Never the same twice in one process (random part steps on within a tenth) | DONE | same | `ET` `referencesNeverRepeatWithinOneTenth` | |
| error-codes-27 | Inside a request the request's reference (`ReferenceId.current()`, from `X-Request-Id`) is the failure's reference | UNTESTED | `E/FailureLog.java` `recordError`; `MS/RequestReferenceFilter.java` | `MW` `theRequestFilterEchoes…` sets `current()` only | no test that `FailureLog.record` inside the filter returns the request's reference |
| error-codes-28 | `PlatformError`: code, user message with `(reference R-…)`, cause, reference, time | DONE | `E/PlatformError.java` | `ET` `anErrorFillsSafeDetails…` | |
| error-codes-29 | A detail named like a secret loses its value | DONE | `E/SafeText.java` `forName` | `ET` same (password, token) | |
| error-codes-30 | A value that looks like a JWT, PEM, `password=` pair, Bearer/Basic, long key-like run loses it | DONE | `E/SafeText.java` `scrub` | `ET` `anErrorFills…`, `safeTextKeeps…` | Basic not in the test, Bearer is |
| error-codes-31 | Secret references (`…-secret`, `…-ref`) keep their name | DONE | `E/SafeText.java` REFERENCE_NAME | `ET` `safeTextKeeps…` (`password-secret`), `anErrorFills…` (`secretRef`) | |
| error-codes-32 | `FailureLog.record` writes one line `code=… ref=… cause="…"` and returns the reference | DONE | `E/FailureLog.java` | `ET` `theFailureLogWritesOneLineAndKeepsTheLastFailures` | |
| error-codes-33 | Level ERROR for FAILED, WARNING for REFUSED and NEEDS_PERSON | PARTLY | `E/FailureLog.java` `recordError` | `ET` same (FAILED, REFUSED) | NEEDS_PERSON and INFO levels untested |
| error-codes-34 | The last failures are kept for the Support screen and the bundle | DONE | `E/FailureLog.java` `recent` | `ET` same; `MT/DiagnosticBundleTest` | |
| error-codes-35 | The last 200 are kept | UNTESTED | `E/FailureLog.java` KEEP | none | the cap is never exercised |
| error-codes-36 | Exceptions that carry a code implement `HasPlatformCode` (schema exceptions, `LicenceException`, `CryptoException`); a caller reports the code, not the text | DONE | `E/HasPlatformCode.java`; every `*SchemaException`, `LicenceException`, `CryptoException`, `ModelGovException`; `M/CheckRunner.java` `failed` | `MT/MonitoringCoreTest` `aCheckThatThrowsOrHangsIsDownNeverAnUnhandledError`; `MT/BuiltInChecksTest` `database` | |

## error-codes — section 3 An app's own codes, section 4 The API error format

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| error-codes-37 | Apps keep their names and either implement `PlatformCode` (catalogue with `Catalogue.of`) or map to it | APP-SIDE | `E/Catalogue.java` `of` (usable) | `ET` builds a catalogue of a test enum | app's enum or adapter |
| error-codes-38 | Show `userMessage()` with the reference, log the cause, give IT `fix()`, support reads `supportNote()` | APP-SIDE | — | — | app's screens and handlers |
| error-codes-39 | API error body `{"code","message","reference","details"}` | APP-SIDE | — | — | no shared helper in platform-core; each app builds it |
| error-codes-40 | `details` safe values only, never a value the caller sent | APP-SIDE | — | — | app's API |
| error-codes-41 | HTTP status = `httpStatus()`, else 4xx REFUSED / 5xx FAILED | APP-SIDE | — | — | app's API |
| error-codes-42 | `X-Request-Id` echoed; made when missing or not a short safe token; it is the failure's reference | DONE | `MS/RequestReferenceFilter.java` | `MW` `theRequestFilterEchoesOrMakesTheReferenceAndSetsItForTheRequest` | "is the failure's reference" see error-codes-27 |
| error-codes-43 | API version in the path; only additions within a version; old version ends after one release of notice | APP-SIDE | — | — | app's API |
| error-codes-44 | P032 report decision 6: the catalogue check is its own module, never deployed, not in the BOM | DOC GAP | `platform-error-catalogue/pom.xml` (`maven.deploy.skip`) | — | design section 1 names the module and CI but not "never deployed, not in the BOM" (CLAUDE.md does) |

---

## monitoring — section 1 Checks

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| monitoring-1 | `DependencyCheck`: name, group, `requiredAtStartup`, time limit default 10 s, interval default 60 s | UNTESTED | `M/DependencyCheck.java` | none asserts the defaults | tests always override them |
| monitoring-2 | `check()` returns a `Verdict` WORKING / HAVING_PROBLEMS / DOWN with a `PlatformCode` and safe details | DONE | `M/Verdict.java` | `MT/MonitoringCoreTest` `stateChangesAreEvents…` | |
| monitoring-3 | `CheckRunner` makes the `CheckResult` (state, code, cause, fix, when, how long) | DONE | `M/CheckRunner.java`, `M/CheckResult.java` | `MT/MonitoringCoreTest` `aCheckThatThrows…` | |
| monitoring-4 | A check that throws is DOWN with its `HasPlatformCode` code, else `MON_CHECK_FAILED` with the class name only | DONE | `M/CheckRunner.java` `failed` | same (cause is the class name; `hunter2` never shown) | |
| monitoring-5 | A check past its limit is DOWN with `MON_CHECK_TIMEOUT` | DONE | `M/CheckRunner.java` `run` | same | |
| monitoring-6 | Nothing is ever unhandled | DONE | `M/CheckRunner.java` (all catches, null verdict) | same | |
| monitoring-7 | `DatabaseCheck`: can't reach `MON_DB_UNREACHABLE`; login refused `MON_DB_LOGIN_REFUSED` (SQL state 28…) | DONE | `M/checks/DatabaseCheck.java` `check` | `MT/BuiltInChecksTest` `database` | the wrong password never in the cause |
| monitoring-8 | `DatabaseCheck`: each platform schema in use at its version (module's `…Schema.verify` codes) | DONE | same | same (identity: `IDN_TABLES_MISSING`, `IDN_VERSION_OLDER`) | |
| monitoring-9 | `DatabaseCheck` required at start-up | DONE | same | same | |
| monitoring-10 | `KeyServiceCheck` from `KeyService.health()`: unreachable, login refused, key missing, permission denied, key disabled (`CRYPTO_*`) | DONE | `M/checks/KeyServiceCheck.java` | `MT/BuiltInChecksTest` `keyService` | |
| monitoring-11 | `KeyServiceCheck` required only when the product's `customerKeys` is `required` | DONE | same (constructor flag) | same | Spring wiring see monitoring-30 |
| monitoring-12 | `SecretStoreCheck`: each reference resolves (`MON_SECRET_MISSING`, `MON_SECRET_UNAVAILABLE`), names only, not required | DONE | `M/checks/SecretStoreCheck.java` | `MT/BuiltInChecksTest` `secretStore` | |
| monitoring-13 | `LoginSystemCheck`: ACTIVE connection's discovery and keys through `OutboundIssuerFetch` (`IDN_TEST_*`); none = HAVING_PROBLEMS; not required | DONE | `M/checks/LoginSystemCheck.java` | `MT/BuiltInChecksTest` `loginSystem` | |
| monitoring-14 | `LicenceCheck`: missing, signature invalid, another customer's (`LICENCE_*`), not started, expiring, in grace, expired (`MON_LICENCE_*`) | DONE | `M/checks/LicenceCheck.java` | `MT/BuiltInChecksTest` `licence` | |
| monitoring-15 | `LicenceCheck` read only, never enforces | DONE | same | — | by construction: it only returns a verdict |
| monitoring-16 | `ChannelCheck.mail`: settings complete, server reachable by TCP, nothing sent, no sign-in | DONE | `M/checks/ChannelCheck.java` | `MT/BuiltInChecksTest` `mailAndWebhookChannels` | Spring's settings see monitoring-34 |
| monitoring-17 | `ChannelCheck.webhook`: the host reachable by TCP only | DONE | same | same ("only the host is shown") | |
| monitoring-18 | `FileLocationCheck`: exists, readable, writable (probe file) | DONE | `M/checks/FileLocationCheck.java` | `MT/BuiltInChecksTest` `fileLocations` | |
| monitoring-19 | Disk warning 90 % and down 95 % | DONE | same | same (91 % warns, 96 % down) | defaults 90/95 by reading |
| monitoring-20 | `HttpServiceCheck`: address valid, reachable, expected status; required per service | DONE | `M/checks/HttpServiceCheck.java` | `MT/BuiltInChecksTest` `httpService` | |
| monitoring-21 | `IntegrationChecks`: one per enabled integration; healthy WORKING, busy HAVING_PROBLEMS, else DOWN (`INT_*`) | DONE | `M/checks/IntegrationChecks.java` | `MT/BuiltInChecksTest` `integrationsAreNeverRequiredAndGiveTheirOwnCodes` | |
| monitoring-22 | Integrations never required and never in readiness | DONE | same | same; `MW` `integrationsAreInTheListButNeverInReadiness` | |
| monitoring-23 | `StartupChecks.require`: any DOWN refuses the start, one line per DOWN check `name: CODE: cause Fix: fix` | DONE | `M/StartupChecks.java` | `MT/MonitoringCoreTest` `theAppRefusesToStartWhenARequiredCheckIsDown` | |
| monitoring-24 | Only the required checks run at start-up | DONE | same | same (`optional.runs() == 0`) | |
| monitoring-25 | `DependencyStatus` keeps the latest results | DONE | `M/DependencyStatus.java` `results` | `MT/MonitoringCoreTest` `stateChangesAreEvents…` | |
| monitoring-26 | Runs each check on its own interval | DONE | `M/DependencyStatus.java` `runDue`, `start` | `MT/MonitoringCoreTest` `sourcesGiveChecksPerRound…`, `theSchedulerRunsTheChecks` | |
| monitoring-27 | A `MonitoringEvent` on each change (BECAME_DOWN, BECAME_HAVING_PROBLEMS, RECOVERED) | DONE | `M/MonitoringEvent.java` `between` | `MT/MonitoringCoreTest` `stateChangesAreEvents…` | |

## monitoring — section 2 Spring

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| monitoring-28 | `platform.monitoring.*` per the install guide, including `every` ("how often each check runs") | DIFFERENT | `MS/MonitoringProperties.java` `every` | none | `every` is read by nothing; each check uses its own `every()` (default 60 s). Only the undocumented `tick` (5 s) is used, by `status.start`. Setting `every` has no effect |
| monitoring-29 | Built-in checks from a DataSource, with the schemas whose module's main bean is there | DONE | `MS/BuiltInChecks.java` `all`, `schemasInUse` | `MW` `theBuiltInChecksComeFromTheAppsBeansAndSettings` (identity) | model-governance schema see model-governance-100 |
| monitoring-30 | `KeyService` bean gives `key-service`, required when `customer-keys-required=true` | UNTESTED | `MS/BuiltInChecks.java` `Crypto` | none | decides a start refusal |
| monitoring-31 | `SecretResolver` with `secrets` gives `secret-store` | DONE | `MS/BuiltInChecks.java` `Secrets` | `MW` `theBuiltInChecks…` | |
| monitoring-32 | `LicenceService` gives `licence` | UNTESTED | `MS/BuiltInChecks.java` `Licensing` | none | |
| monitoring-33 | `LoginConnectionStore` + `IssuerFetch` + `product-code` (tenant from `platform.licensing.tenant-id`) give `login-system` | UNTESTED | `MS/BuiltInChecks.java` `Login` | none (`MW` has the store but no product code, so no check) | |
| monitoring-34 | The notifications mail settings give `channel-mail` | DIFFERENT | `MS/BuiltInChecks.java` `all` | none | port defaults to 587 whatever `mail.security` says (notifications defaults to 587 / 465 / 25 by security); `from-address` is required, but with Microsoft 365 notifications lets it default to the mailbox, so a valid set-up shows `MON_CHANNEL_SETTINGS_INCOMPLETE` |
| monitoring-35 | `folders.*`, `services.*` | DONE | `MS/BuiltInChecks.java` | `MW` `theBuiltInChecks…` | |
| monitoring-36 | The integration store gives one check per enabled integration | DONE | `MS/IntegrationSource.java` | `MW` `integrationsAreInTheListButNeverInReadiness` | |
| monitoring-37 | The app's own `DependencyCheck` beans join | DONE | `MS/MonitoringAutoConfiguration.java` `platformDependencyStatus` | `MW` `theAppRefusesToStart…`, `readinessHolds…` | |
| monitoring-38 | Liveness never depends on checks | DONE | no liveness indicator in the module | — | by construction |
| monitoring-39 | Readiness `readinessState` = the app's readiness state + the required checks; OUT_OF_SERVICE while one is DOWN | PARTLY | `MS/DependencyHealth.java` `readiness` | `MW` `readinessHoldsOnlyRequiredChecksAndTheListShowsAll` | the app's REFUSING_TRAFFIC branch untested |
| monitoring-40 | Spring Boot's own `readinessState` backs off | UNTESTED | `MS/MonitoringAutoConfiguration.java` (`beforeName` Availability…, `@ConditionalOnMissingBean(name=…)`) | none | tests run without Actuator's availability auto-configuration |
| monitoring-41 | `dependencies` lists every check, always UP | DONE | `MS/DependencyHealth.java` `all` | `MW` `readinessHolds…` | |
| monitoring-42 | integration-spring's `integrations` contributor backs off with this module; stays without it | DONE | `platform-integration-spring/.../IntegrationAutoConfiguration.java` `Health` (`@ConditionalOnMissingClass`) | `MW` `integrationsAreInTheList…`; `platform-integration-spring/.../IntegrationWiringTest.java` (contributor present) | |
| monitoring-43 | `MonitoringListener` beans get the events | DONE | `MS/MonitoringAutoConfiguration.java` | `MW` `readinessHolds…` | |
| monitoring-44 | Start-up refusal, switchable (`startup-checks`) | DONE | `MS/MonitoringAutoConfiguration.java` `platformMonitoringStart`; `MS/MonitoringFailureAnalyzer.java` | `MW` `theAppRefusesToStartWhileARequiredCheckIsDown` | |

## monitoring — section 3 Metrics, section 4 Logs

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| monitoring-45 | Naming `platform.<area>.<measure>`; tags are names, never values | DONE | `MS/MonitoringMetrics.java` | `MW` `integrationsAreInTheList…` | tags by reading |
| monitoring-46 | `platform.monitoring.check.state` gauge 0/1/2, tags `check`, `group` | DONE | same `record` | same (2.0 for DOWN) | |
| monitoring-47 | `platform.monitoring.check.duration` timer | DONE | same | same | |
| monitoring-48 | `platform.integration.attempts` counter from `CallEngine.onAttempt`, tags `integration`, `outcome` | UNTESTED | `MS/MonitoringMetrics.java` `Attempts` | none | |
| monitoring-49 | One line per failure `code=… ref=… cause="…"` | DONE | `E/FailureLog.java` | `ET` `theFailureLogWritesOneLine…` | |
| monitoring-50 | One line per state change `check=… state=… code=… cause="…"` | UNTESTED | `M/DependencyStatus.java` `record` | none | the RECOVERED line has no code |
| monitoring-51 | During a request the reference is in the MDC as `ref`; `X-Request-Id` echoed or made | DONE | `MS/RequestReferenceFilter.java` | `MW` `theRequestFilterEchoes…` | |
| monitoring-52 | Splunk / Datadog extraction, JSON log layout with MDC | APP-SIDE | — | — | app's log shipping and layout |

## monitoring — section 5 The diagnostic bundle

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| monitoring-53 | `DiagnosticBundle.build` / `DiagnosticBundles.write` writes a zip with the listed entries | DONE | `M/DiagnosticBundle.java`, `MS/DiagnosticBundles.java` | `MT/DiagnosticBundleTest` `secretsAndContentNeverReachTheBundle`; `MW` `theBuiltInChecks…` | |
| monitoring-54 | `versions.txt`: platform modules from the released jars, the app's own through `AppVersions` | PARTLY | `M/DiagnosticBundle.java` `platformVersions` | `MT/DiagnosticBundleTest` (app versions only) | reading the jars' `pom.properties` untested (tests run from classes) |
| monitoring-55 | `configuration.properties`: the app's own property sources; secrets removed, references and addresses kept | PARTLY | `MS/DiagnosticBundles.java` `configuration`; `M/DiagnosticBundle.java` `scrubbed` | `MT/DiagnosticBundleTest` (scrubbing) | which property sources are taken (system properties and environment left out) untested |
| monitoring-56 | `checks.txt` | DONE | `M/DiagnosticBundle.java` `checks` | `MT/DiagnosticBundleTest` | |
| monitoring-57 | `failures.txt`: the last 50 | PARTLY | `MS/DiagnosticBundles.java` FAILURES | `MT/DiagnosticBundleTest` (entries) | the 50 limit untested |
| monitoring-58 | `schemas.txt`: the version tables | PARTLY | `MS/DiagnosticBundles.java` VERSION_TABLES | none in Spring | lists idn, lic, notif, fld, erl, int; **`mg_schema_version` missing** |
| monitoring-59 | `manifest.txt` with the SHA-256 of each entry | DONE | `M/DiagnosticBundle.java` `build` | `MT/DiagnosticBundleTest` | |
| monitoring-60 | Never content, field values, tokens or secrets; reads no log and no exception text | DONE | `M/DiagnosticBundle.java` | `MT/DiagnosticBundleTest` `secretsAndContentNeverReachTheBundle` | |
| monitoring-61 | Limit 5 MiB; entries that do not fit are left out, manifest says `MON_BUNDLE_TOO_LARGE` | DONE | `M/DiagnosticBundle.java` DEFAULT_LIMIT | `MT/DiagnosticBundleTest` `aBundleHasASizeLimit` | |

## monitoring — section 6 Decisions (P032) and the P032-report decisions

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| monitoring-62 | D1 texts as data next to each enum, no constant renamed | DONE | `E/CodeTexts.java` | `CT` | |
| monitoring-63 | D2 `SignedDocument.Problem` not a family; a name in two families of one module allowed, in two modules not | DONE | `CT` INTERNAL; `E/Catalogue.java` `problems` | `CT` `everyCodeEnumImplements…` | two-module branch untested (error-codes-19) |
| monitoring-64 | D3 Monitoring stores nothing in the database (results and the last 200 failures in memory) | DONE | no SQL in `platform-monitoring*` | — | by construction |
| monitoring-65 | D4 readiness through the `readinessState` indicator | DONE | `MS/MonitoringAutoConfiguration.java` `Health` | `MW` `readinessHolds…` | |
| monitoring-66 | D5 the licence check uses `LicenceService.status()` | DONE | `M/checks/LicenceCheck.java` | `MT/BuiltInChecksTest` `licence` | |
| monitoring-67 | D6 integration health moved into `dependencies` only when monitoring-spring is on the classpath | DONE | see monitoring-42 | see monitoring-42 | |
| monitoring-68 | P032 accepted 1: the bundle download button (IT Admin only) is app side | APP-SIDE | `MS/DiagnosticBundles.java` `write` | — | app's Support screen |
| monitoring-69 | P032 accepted 2: the Spring start-up settings messages (`*_SETTINGS_INVALID`) become `PlatformCode` enums per Spring module | MISSING | — | — | known step-2 gap; in this slice also `GSP/ModelGovConfig.java` `Invalid` ("Model governance settings are not valid") and monitoring-spring's bare `IllegalArgumentException` for bad folder thresholds |
| monitoring-70 | P032 accepted 3: Teams / Slack checks stay app-added (`ChannelCheck.webhook`) | APP-SIDE | `M/checks/ChannelCheck.java` `webhook` | `MT/BuiltInChecksTest` | app's `DependencyCheck` beans |
| monitoring-71 | P032 report decision 10: webhook checks open a TCP connection to the host only; the path (holds the webhook's secret) is never shown or called | DOC GAP | `M/checks/ChannelCheck.java` | `MT/BuiltInChecksTest` `mailAndWebhookChannels` | design says "a TCP connection; nothing sent" but not that the path is a secret and never shown |
| monitoring-72 | P032 report decision 8: `FileLocationCheck.Disk` is replaceable (full disk, no write right tested the same everywhere) | DOC GAP | `M/checks/FileLocationCheck.java` `Disk` | `MT/BuiltInChecksTest` `fileLocations` | not in the design |
| monitoring-73 | P034: model governance adds the check `model-governance` | DOC GAP | `GSP/ModelGovCheck.java` | `GW` | monitoring.md's built-in table does not list it (only model-governance.md section 10) |

---

## model-governance — section 2 Split of duties

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-1 | The app registers its models and uses | APP-SIDE | `G/ModelGovernance.java` `registerModel`, `registerUse` | — | app's model screens / start-up |
| model-governance-2 | The app has its test sets, pass marks and measures (section 8: adds them in its own repo) | APP-SIDE | — | — | app's test sets |
| model-governance-3 | The app chooses which signals it reports | APP-SIDE | — | — | app's measure reporting |
| model-governance-4 | The app gives model descriptions, known limits, test results, and its screens | APP-SIDE | `G/PackTexts.java` | — | app's `PackTexts` bean and screens |

## model-governance — section 3 The model register

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-5 | One entry per model use (one model, two purposes = two uses) | DONE | `G/ModelUse.java` (`modelId`) | `GC` `dueDatesPerMaterialityAndNoticesOncePerStage` (four uses of one model) | |
| model-governance-6 | Model: name, provider, version, exact digest, where it runs | DONE | `G/Model.java` | `GC` `historyAndAuditOfEveryChange`, `anyChangeMakesTheUseDueAtOnce` | |
| model-governance-7 | Use: product, feature, entity scope | DONE | `G/ModelUse.java` | `GC` `validationsBelongToTheirUseAndEntity`, `unknownAndInvalidRefused` (product form) | |
| model-governance-8 | Purpose in plain words | DONE | `G/ModelUse.java` | `GC` `thePackIsDeterministic…` | |
| model-governance-9 | Materiality high / medium / low with the reason | DONE | `G/ModelUse.java` | `GC` `historyAndAuditOfEveryChange` | |
| model-governance-10 | Human in the loop | DONE | `G/ModelUse.java` | `GC` `thePackIsDeterministic…` | |
| model-governance-11 | Owner at the vendor and at the customer | DONE | `G/ModelUse.java` `vendorOwner`, `customerOwner` | `GS` `bothStoresGiveBackTheSameRows` | |
| model-governance-12 | Status proposed / validated / live / retired; last validation; next review due | DONE | `G/ModelUse.java` | `GC` `registerValidateApproveGoLiveGateStamp`, `retiredUseRefused` | |
| model-governance-13 | Evidence links: validation records, monitoring history | DONE | `G/ModelGovStore.java` `validations`, `readings`, `drifts` | `GC` `thePackIsDeterministic…` | |
| model-governance-14 | Customer models registered the same; the gate runs the app's test set and records the result | DONE | `G/Model.java` Provider CUSTOMER; `G/ModelGovernance.java` `recordValidation` | `GC` `failedCustomerModelForMediumOnlyWithDocumentedAcceptance` | |
| model-governance-15 | No training on customer documents; a model keeps nothing | APP-SIDE | `G/PackTexts.java` `dataStatement` | — | app's model runtime; stated in the pack's data statement |

## model-governance — section 4 The change gate

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-16 | No model or version goes live without a recorded validation that meets the thresholds | DONE | `G/ModelGovernance.java` `approve`, `goLive`; `G/ModelGate.java` | `GC` `failedVendorValidationNeverApproved`, `gateRefusesUntilLive` | |
| model-governance-17 | A record holds model, version, digest, use, configuration fingerprint, test set (name, version), measures and results, thresholds, pass/fail, who ran it, who approved, date | DONE | `G/ValidationRecord.java`, `G/Decisions.java` `Approval` | `GC` `registerValidateApproveGoLiveGateStamp` | |
| model-governance-18 | Never document content or values in a record | DONE | numbers only; `G/Values.java` `text` | `GC` `recordsAndRefusalsHoldNoContent` | |
| model-governance-19 | The app's default pass marks per use; the customer's stricter per entity, never looser | PARTLY | `G/ModelGovernance.java` `setEntityThreshold`, `setDefaultThreshold` | `GC` `customerThresholdOnlyStricter` | checked only when the customer sets theirs; when the app later tightens its default, an existing entity mark that is now looser stays in force (same in `ModelMeasures.setDefaultThreshold`) |
| model-governance-20 | The thresholds in force are part of the record | DONE | `G/ModelGovernance.java` `recordValidation` | `GC` `customerThresholdOnlyStricter` | |
| model-governance-21 | A pass does not switch a model on; the Model approver approves, audited | DONE | `G/ModelGovernance.java` `approve`, `goLive` | `GC` `gateRefusesUntilLive`, `historyAndAuditOfEveryChange` | |
| model-governance-22 | The Business Admin can never also be the approver | DONE | `G/ModelGovernance.java` `checkApprover` | `GC` `approverRolesAndSeparationOfDuties` | the forbidden roles come from the app's settings; `forbidden-roles` may be empty and nothing warns (install-model-governance-5) |
| model-governance-23 | A compliance officer may hold it | DONE | approver roles from settings | `GC` `approverRolesAndSeparationOfDuties` (`complianceOfficer`) | |
| model-governance-24 | A fail can't be approved (except the medium customer-model rule) | DONE | `G/ModelGovernance.java` `approve` | `GC` `failedVendorValidationNeverApproved`, `acceptanceNotForLowOrPassed` | |
| model-governance-25 | Digest pinning: the app refuses a model whose digest doesn't match (specific code) | DONE | `G/ModelGate.java` `requireLive` (`MOD_DIGEST_MISMATCH`) | `GC` `digestChangeRefusedAtTheGate` | |
| model-governance-26 | IT Admin alerted on a digest mismatch | APP-SIDE | — | — | app's IT notice from the code |
| model-governance-27 | Customer model failing the test set: blocked for high materiality | DONE | `G/ModelGovernance.java` `approve`, `accept` | `GC` `failedCustomerModelBlockedForHigh` | |
| model-governance-28 | Medium: allowed with documented acceptance (who, when, which results), kept with the record, shown in the pack | DONE | `G/ModelGovernance.java` `accept`; `G/ModelPack.java` | `GC` `failedCustomerModelForMediumOnlyWithDocumentedAcceptance`, `thePackIsDeterministic…` | |
| model-governance-29 | Rollback to the previous validated version at once, audited | DONE | `G/ModelGovernance.java` `rollback` | `GC` `rollbackSwitchesBackAtOnce` | |
| model-governance-30 | Every AI result records model, version and digest | DONE | `G/LiveModel.java` `stamp`, `G/ModelStamp.java` | `GC` `registerValidateApproveGoLiveGateStamp` | storing the stamp with the result is the app's |
| model-governance-31 | The gate applies to a new model, version or digest | DONE | `G/ModelGate.java` | `GC` `digestChangeRefusedAtTheGate` | |
| model-governance-32 | The gate applies to a prompt or setting change in the app's release | DONE | `G/ModelGate.java` (`MOD_CONFIG_CHANGED`) | `GC` `configurationChangeRefusedAtTheGate` | |
| model-governance-33 | The gate applies to a change of where the model runs | DIFFERENT | `G/ModelGovernance.java` `changeModel` (`dueAtOnce`) | `GC` `anyChangeMakesTheUseDueAtOnce` | a `runsWhere` change only makes the uses due today (P034 decision 2): the gate warns `MOD_REVIEW_OVERDUE` and lets the model run unless the use is set to refuse; the validation record does not hold `runsWhere` and the gate never compares it. The code follows P034 decision 2, which narrows section 4; the owner should confirm |
| model-governance-34 | The vendor's default models are validated by an independent outside reviewer before the first customer | APP-SIDE | reference in the pack: `G/PackTexts.java` `DocumentRef` | `GC` `thePackIsDeterministic…` ("6: the independent review") | vendor's process |

## model-governance — section 5 Ongoing monitoring hooks

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-35 | The app reports measures per use and entity over time | DONE | `G/ModelMeasures.java` `report` | `GC` `driftPastAThresholdInBothDirectionsWithAMinimumSample` | |
| model-governance-36 | Each measure has a threshold (app default, customer stricter) | DONE | `G/ModelMeasures.java` `setEntityThreshold` | `GC` `monitoringPassMarksOnlyStricterAndRequired` | later default change: see model-governance-19 |
| model-governance-37 | Drift past it raises an event for the app's notifications to the model owner | DONE | `G/ModelMeasures.java` `report` → `ModelEvents` | `GC` `driftPast…`; `GW` `withTheAppsBeans` | routing to the owner is the app's |
| model-governance-38 | The register shows the use as "needs review" | DONE | `G/ModelMeasures.java` `needsReview`; pack `needsReview` | `GC` `needsReviewUntilAnActionIsRecorded` | |
| model-governance-39 | Periodic re-validation on a schedule per materiality | PARTLY | `G/ModelGovernance.java` `recordValidation`; `G/ModelReviews.java` | `GC` `dueDatesPerMaterialityAndNoticesOncePerStage` | the next review date is per use, but validations are per entity (P033 decision 3): a pass for one entity moves the date for every entity of a use scoped to every entity, so another entity whose live validation is old is never due |
| model-governance-40 | Re-validation after any change | DONE | `G/ModelGovernance.java` `changeModel`, `changeUse` | `GC` `anyChangeMakesTheUseDueAtOnce` | |
| model-governance-41 | No document content or values in any measure | DONE | `G/Monitoring.java` (numbers; notes through `Values.text`) | `GC` `needsReviewUntilAnActionIsRecorded` (`password=hunter2` refused) | |

## model-governance — section 6 Documentation pack, section 7 Owner decisions, section 8 Build

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-42 | Per product and entity, on demand, PDF + JSON, fingerprinted | DONE | `G/ModelPack.java` `export` | `GC` `thePackIsDeterministicCompleteAndFreeOfContent` | |
| model-governance-43 | Items 1–7 (register, uses with limits, validation history with acceptances, monitoring, change log, third-party statement with the independent review, data statement) | DONE | `G/ModelPack.java`, `G/PackPdf.java` | same (asserts "1" … "7") | |
| model-governance-44 | Owner 1: Model approver separate from Business Admin; compliance officer may hold it | DONE | see 22, 23 | see 22, 23 | |
| model-governance-45 | Owner 2: materiality app defaults (detection, vision, checking pass high; call-center QA medium; legal QA high) | APP-SIDE | — | — | app's use registration |
| model-governance-46 | Owner 3: independent validation before the first customer | APP-SIDE | see 34 | — | vendor's process |
| model-governance-47 | Owner 4: customer model failing: blocked high, accepted medium | DONE | see 27, 28 | see 27, 28 | |
| model-governance-48 | Owner 5: model screens in each app's admin area; Platform Console status only, for hosted | APP-SIDE | — | — | app's admin area, Platform Console |
| model-governance-49 | Owner 6: EU AI Act per customer, later | APP-SIDE | — | — | not built |
| model-governance-50 | Owner 7: exact digest and refusal; prompt/setting change through the gate; every result stamped | DONE | see 25, 32, 30 | see 25, 32, 30 | |
| model-governance-51 | Own schema run `db/platform/modelgov`, tables `mg_*` | DONE | `G/jdbc/ModelGovSchema.java`; `platform-model-governance/src/main/resources/db/platform/modelgov/V1__modelgov.sql`, `V2__…` | `GS` `verifyPassesForTheAppUserAndCreatesNothing` | |

## model-governance — section 9 As built (P033)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-52 | Plain Java; depends only on platform-errors (+ Apache PDFBox) | DONE | `platform-model-governance/pom.xml` | — | by reading the POM |
| model-governance-53 | `ModelDigest` algorithm + lowercase hex | DONE | `G/ModelDigest.java` | `GD` `digestTextForm` | |
| model-governance-54 | `ModelDigest.sha256Of(Path)` | DONE | same | `GD` `fileDigestIsTheSha256OfTheBytes` | |
| model-governance-55 | `ManifestDigests`: `HEAD /v2/<name>/manifests/<ref>`, `Docker-Content-Digest`, no redirects | DONE | `G/ManifestDigests.java` | `GD` `manifestDigestFromTheModelServer` | |
| model-governance-56 | `ConfigFingerprint.of` = `sha256:` of canonical JSON (keys sorted, no whitespace, shortest numbers) | DONE | `G/ConfigFingerprint.java` | `GD` `fingerprintCanonicalForm`; `GC` `sameConfigurationInAnotherOrderSameFingerprint` | |
| model-governance-57 | Every change a new version with who, when, changed fields (`mg_model_history`, `mg_model_use_history`), to the app's `ModelGovAudit` | DONE | `G/ModelGovernance.java` | `GC` `historyAndAuditOfEveryChange` | |
| model-governance-58 | A stale version is refused (`MOD_VERSION_CONFLICT`) | DONE | same | `GC` `staleVersionRefused` | |
| model-governance-59 | `MOD_THRESHOLD_LOOSER`, `MOD_THRESHOLD_MISSING` | DONE | same | `GC` `customerThresholdOnlyStricter` | |
| model-governance-60 | `recordValidation` copies the pass marks in force (numbers only) and moves the review date on (defaults high 90, medium 180, low 365) | DONE | `G/ModelGovernance.java`; `G/ModelGovSettings.java` DEFAULT_REVIEW | `GC` `registerValidate…`, `dueDatesPerMateriality…` | |
| model-governance-61 | `approve` with approver and forbidden roles from `ModelGovSettings` (`MOD_APPROVER_ROLE`, `_CONFLICT`, `MOD_VALIDATION_FAILED`, `MOD_ALREADY_APPROVED`) | DONE | `G/ModelGovernance.java` `approve` | `GC` `approverRolesAndSeparationOfDuties` | |
| model-governance-62 | P033 D1: forbidden role checked before the approver role | DONE | `G/ModelGovernance.java` `checkApprover` | `GC` `approverRolesAndSeparationOfDuties` (`businessAdmin()`, `bothRoles()`) | doc gap: model-governance-108 |
| model-governance-63 | P033 D2: acceptance needs an eligible approver | DONE | `G/ModelGovernance.java` `accept` | `GC` `failedCustomerModelForMedium…` (`bothRoles()`) | doc gap: model-governance-109 |
| model-governance-64 | P033 D3: validations per entity even for a use of every entity; go-live and rollback per use and entity | DONE | `G/ModelGovernance.java`, `G/ModelGate.java` | `GC` `validationsBelongToTheirUseAndEntity` | doc gap: model-governance-110 |
| model-governance-65 | `goLive` keeps the previous live validation as rollback target; `rollback` at once; `MOD_NO_ROLLBACK_TARGET` | DONE | `G/ModelGovernance.java` | `GC` `rollbackSwitchesBackAtOnce` | |
| model-governance-66 | `ModelGate.requireLive`: `MOD_RETIRED`, `_NOT_VALIDATED`, `_NOT_APPROVED`, `_NOT_LIVE`, `_DIGEST_MISMATCH`, `_CONFIG_CHANGED` | DONE | `G/ModelGate.java` | `GC` `gateRefusesUntilLive`, `digestChange…`, `configurationChange…`, `retiredUseRefused` | |
| model-governance-67 | `MOD_REVIEW_OVERDUE` a warning on the `LiveModel` unless the use refuses it | DONE | same | `GC` `reviewOverdueWarnsOrRefuses` | |
| model-governance-68 | `LiveModel.stamp()` gives the `ModelStamp` | DONE | `G/LiveModel.java` | `GC` `registerValidate…` | |
| model-governance-69 | Run only by the schema step; startup never creates tables | DONE | `G/jdbc/ModelGovSchema.java` `verify` | `GS` `verifyPassesForTheAppUserAndCreatesNothing` | |
| model-governance-70 | Flyway run with history table `mg_schema_history` | UNTESTED | `ModelGovSchema.HISTORY_TABLE` | none | tests run the SQL files directly (`jdbc/Pg.java`), never through Flyway with that history table |
| model-governance-71 | `ModelGovSchema.verify` at startup names what is wrong | DONE | same | `GS` `verifyNamesWhatIsWrong` | |
| model-governance-72 | DML-only runtime user | DONE | same `requireRights` | `GS` `theAppUserCannotChangeTheTables`, `verifyNamesWhatIsWrong` (`MG_PRIVILEGES_MISSING`) | |
| model-governance-73 | `mg_validation`, `_measure`, `mg_approval`, `mg_acceptance`, `mg_live_history` refuse UPDATE, DELETE, TRUNCATE by trigger, even for the owner | DONE | `V1__modelgov.sql` triggers | `GS` `recordsAreInsertOnlyEvenForTheOwner` | TRUNCATE tested on `mg_validation` only; DELETE of `mg_acceptance` not tried |
| model-governance-74 | `InMemoryModelGovStore` with the same rules | DONE | `platform-model-governance-testkit/.../InMemoryModelGovStore.java` | `GC` on both stores; `GS` `bothStoresGiveBackTheSameRows` | |
| model-governance-75 | P033 D5: thresholds replaced in place (`mg_threshold` not insert only); each change audited; marks copied into the insert-only record | DONE | `G/ModelGovernance.java` `setDefaultThreshold` | `GC` `historyAndAuditOfEveryChange` (`audit.count("threshold")`) | doc gap: model-governance-111 |

## model-governance — section 10 As built (P034) and Decisions (P034)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| model-governance-76 | `report(use, entity, measure, value, MeasurePeriod(from, to, samples))`, numbers only, into insert-only `mg_measure` | DONE | `G/ModelMeasures.java` `report`; `V2__…sql` | `GC` `driftPast…`; `GS` `readingsLeaveOnlyThroughThePurge…` | |
| model-governance-77 | Each measure needs a pass mark (`MOD_THRESHOLD_MISSING`) | DONE | same | `GC` `monitoringPassMarksOnlyStricterAndRequired` | |
| model-governance-78 | Stricter = same direction, at least as strict, no more samples (`MOD_THRESHOLD_LOOSER`) | DONE | `G/ModelMeasures.java` `setEntityThreshold` | same | |
| model-governance-79 | Direction and minimum sample; at the mark is not past it | DONE | `G/Monitoring.java` `MonitorThreshold.drifted` | `GC` `driftPast…` | |
| model-governance-80 | Drift in insert-only `mg_drift` with the pass mark copied in | DONE | `G/ModelMeasures.java` | `GC` `driftPast…`; `GS` `readingsLeave…` | |
| model-governance-81 | Raises `ModelEvent` DRIFT (`MOD_DRIFT`) to the app's `ModelEvents` | DONE | same | `GC` `driftPast…` | |
| model-governance-82 | "Needs review" until `recordAction` (`mg_drift_action`, insert only; `MOD_ACTION_ALREADY_RECORDED`) | DONE | `G/ModelMeasures.java` `recordAction` | `GC` `needsReviewUntilAnActionIsRecorded` | |
| model-governance-83 | `purge()` removes readings older than the retention (default 400 days, at least 30) | DONE | `G/ModelMeasures.java` `purge` | `GC` `readingsArePurgedAfterTheRetentionDriftsStay`; `GW` `wrongSettingsAreAllListed` | |
| model-governance-84 | The database allows a delete of `mg_measure` only inside the purge (`mg.purge_before`) | PARTLY | `V2__modelgov_monitoring.sql` `mg_measure_guard` | `GS` `readingsLeaveOnlyThroughThePurge…` | stops a plain DELETE, but any session with DELETE can `set_config('mg.purge_before', <any instant>, true)` and delete any reading; the trigger does not enforce the 30-day minimum. A guard against accidents, not a control |
| model-governance-85 | Drifts and actions are kept | DONE | same | `GC` `readingsArePurged…` | |
| model-governance-86 | Next review = last validation + the use's own `reviewEveryDays` or its materiality's | DONE | `G/ModelGovernance.java` `reviewEvery` | `GC` `dueDatesPerMateriality…` | per-entity issue see 39 |
| model-governance-87 | `due(now)`: within 30, within 7, on or after the date | DONE | `G/ModelReviews.java` `list` | same | |
| model-governance-88 | `REVIEW_DUE` once per use, due date and stage (`mg_review_notice`) | DONE | `G/ModelReviews.java` `due` | same; `GS` `readingsLeave…` (once per stage) | |
| model-governance-89 | `MOD_REVIEW_DUE`, `MOD_REVIEW_OVERDUE` for the last stage | DONE | `G/ModelEvent.java` `reviewDue` | `GC` `dueDatesPerMateriality…` | |
| model-governance-90 | Changing version, digest, where it runs, the use's model or configuration sets the next review to today | DONE | `G/ModelGovernance.java` `dueAtOnce`, `dueToday` | `GC` `anyChangeMakesTheUseDueAtOnce` | |
| model-governance-91 | JSON `platform-model-pack/1` with schema `schema/platform-model-pack-1.schema.json` | DONE | `G/ModelPack.java` FORMAT | `GC` `thePackIsDeterministic…` (schema check) | |
| model-governance-92 | Canonical (sorted, no whitespace, fixed list order, no export time); fingerprint `sha256:` of its UTF-8 bytes | DONE | `G/ModelPack.java` | same | |
| model-governance-93 | PDF (PDFBox) prints the fingerprint, seven sections | DONE | `G/PackPdf.java` | same | |
| model-governance-94 | App texts through `PackTexts`, else "Not supplied by the app" | DONE | `G/PackTexts.java` NOT_SUPPLIED | `GW` `withoutAppBeansOnPostgres` | |
| model-governance-95 | Independent review as a document reference (title, reference, optional SHA-256) | DONE | `G/PackTexts.java` `DocumentRef` | `GC` `thePackIsDeterministic…` | |
| model-governance-96 | Spring: `approver-roles`, `forbidden-roles`, `review-every.*`, `measure-retention`, `worker.*`; every problem listed at startup | DONE | `GSP/ModelGovConfig.java` `from` | `GW` `wrongSettingsAreAllListed` | not a `PlatformCode` (monitoring-69) |
| model-governance-97 | `ModelGovSchema.verify` on the app's DataSource before the Postgres store, or the app's `ModelGovStore`; no DataSource and no store refused | DONE | `GSP/ModelGovAutoConfiguration.java` `modelGovStoreDatabase` | `GW` `startupRefusesWithoutADatabaseOrWithAWrongSchema`, `withTheAppsBeans` | |
| model-governance-98 | Beans `ModelGovernance`, `ModelGate`, `ModelMeasures`, `ModelReviews`, `ModelPack`, `ModelGovWorker`, each backing off for the app's own | DONE | same | `GW` `withTheAppsBeans`, `switchedOffOrWithoutMonitoring` | back-off tested for the store only |
| model-governance-99 | Check `model-governance` when platform-monitoring is present: DOWN wrong schema, HAVING_PROBLEMS unhandled drift / overdue review | DONE | `GSP/ModelGovCheck.java` | `GW` `withTheAppsBeans`, `withoutAppBeansOnPostgres`, `switchedOffOrWithoutMonitoring` | |
| model-governance-100 | monitoring-spring's database check also verifies the `mg_` tables when the store is in use | UNTESTED | `MS/BuiltInChecks.java` SCHEMAS (`ModelGovStore`) | none | only identity is tested (`MW` `theBuiltInChecks…`) |
| model-governance-101 | `ModelGovWorker`: review notices every `every`, purge once a day | UNTESTED | `GSP/ModelGovWorker.java` `runOnce` | `GW` `withoutAppBeansOnPostgres` calls `runOnce` with nothing to assert | |
| model-governance-102 | P034 D1: one drift per measure until handled | DONE | `G/ModelMeasures.java` `report` | `GC` `driftPast…` ("no second drift"), `needsReviewUntil…` | |
| model-governance-103 | P034 D2: overdue on the due date | DONE | `G/ModelGate.java`, `G/ModelReviews.java` (`days <= 0`) | `GC` `reviewOverdueWarnsOrRefuses` ("the due date itself") | code follows D2, not P033 D4 |
| model-governance-104 | P034 D3: a name change alone does not make uses due | DONE | `G/ModelGovernance.java` `changeModel` | `GC` `anyChangeMakesTheUseDueAtOnce` | |
| model-governance-105 | P034 D4: retention enforced in the database too | PARTLY | see 84 | see 84 | see 84 |
| model-governance-106 | P034 D5: Apache PDFBox 3.0, standard Helvetica, no font files | DONE | `G/PackPdf.java` (`Standard14Fonts` HELVETICA) | `GC` `thePackIsDeterministic…` (PDF loads, text found) | |
| model-governance-107 | P034 D6: the pack's period covers validations, readings, drifts, changes; register as at export | DONE | `G/ModelPack.java` | `GC` `thePackCoversItsPeriodOnly` | |
| model-governance-108 | P033 report D1 (forbidden role checked first, so a holder of both gets `MOD_APPROVER_CONFLICT`) | DOC GAP | — | — | not in the design body |
| model-governance-109 | P033 report D2 (acceptance needs an eligible approver) | DOC GAP | — | — | not in the design body |
| model-governance-110 | P033 report D3 (validations, go-live and rollback per use **and entity**, also for a use of every entity) | DOC GAP | — | — | section 9 implies it but does not say it |
| model-governance-111 | P033 report D5 (`mg_threshold` replaced in place, not insert only; the evidence is the copy in each record) | DOC GAP | — | — | not in the design body |
| model-governance-112 | P034 report D8 (no `ModelGovAudit` bean allowed: history tables only, said at startup; no DataSource and no store refused) | DOC GAP | `GSP/ModelGovAutoConfiguration.java` | `GW` `startupRefuses…` | section 10 lists the beans but not this |

---

## Install guide check: docs/install/monitoring.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-monitoring-1: `platform.monitoring.every` default `60s`, "how often each check runs" | property | `MS/MonitoringProperties.java` (unused) | the property exists but nothing reads it; each check runs on its own `every()` |
| install-monitoring-2: (not in guide) `platform.monitoring.tick` default `5s` | property | `MS/MonitoringProperties.java` `tick`, used by `status.start` | the scheduler's tick is a real setting the guide does not list |
| install-monitoring-3: `channel-mail` "the mail settings are complete" | option | `MS/BuiltInChecks.java` | the check reads `platform.notifications.mail.host`, `.port` (default 587 regardless of `mail.security`) and requires `.from-address`; with Microsoft 365 `from-address` may be left out (it defaults to the mailbox), so a valid set-up shows `MON_CHANNEL_SETTINGS_INCOMPLETE`, and an SSL server on its default 465 is checked on 587 |
| install-monitoring-4: the checks table | option | `GSP/ModelGovCheck.java` | the `model-governance` check is not listed |
| install-monitoring-5: bundle holds "the database table versions" | format | `MS/DiagnosticBundles.java` VERSION_TABLES | `mg_schema_version` is not read |
| install-monitoring-6: `.warn-at` (90), `.down-at` (95) | default | `M/checks/FileLocationCheck.java` constructor | no range given; a wrong pair stops the app with a bare `IllegalArgumentException` ("thresholds must be 1 <= warnAt <= downAt <= 100") that names no property |

All other items match: 30 checked (enabled, startup-checks, customer-keys-required, product-code, tenant-id fallback, secrets, folders path/writable/warn-at/down-at/required defaults, services url/expected-status/timeout/required defaults, nine check names, the start-up message text, three health addresses, three metric names, the log line).

## Install guide check: docs/install/model-governance.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-model-governance-1: startup codes `MG_TABLES_MISSING`, `MG_VERSION_OLDER`, `MG_VERSION_NEWER`, `MG_TABLES_INCOMPLETE`, `MG_PRIVILEGES_MISSING` | code | `G/jdbc/ModelGovSchemaException.java` `Problem` | the list leaves out `MG_VERSION_UNKNOWN` and `MG_CHECK_FAILED` |
| install-model-governance-2: runtime grants (narrow: SELECT + INSERT on insert-only tables, + DELETE on `mg_measure`) | format | `G/jdbc/ModelGovSchema.java` `verify` | `verify` accepts exactly the guide's grants, but its `MG_PRIVILEGES_MISSING` message and the `V1__modelgov.sql` header tell IT to grant SELECT, INSERT, UPDATE and DELETE on all `mg_` tables; the guide's narrow grant set is never tested (tests grant all four everywhere) |
| install-model-governance-3: (not in guide) `platform.model-governance.enabled` default true | property | `GSP/ModelGovProperties.java` | missing from the guide |
| install-model-governance-4: `review-every.*`, `worker.every` | default | `GSP/ModelGovConfig.java` | limits not in the guide: whole days 1d–3650d; `worker.every` at least 1 minute |
| install-model-governance-5: `forbidden-roles: [BUSINESS_ADMIN]` shown as an ordinary setting | property | `GSP/ModelGovConfig.java` | may be left empty with no warning, and then nothing separates the approver from the Business Admin; the guide does not say it must be set |

All other items match: 31 checked (approver-roles required, review-every defaults 90/180/365, measure-retention 400d / at least 30d, worker.enabled, worker.every 1h, Flyway location, history table, both file names, `mg_schema_version` SELECT, the insert-only list, seven gate codes, `MOD_REVIEW_DUE`, `MOD_DRIFT`, `MOD_DRIFT_UNHANDLED`, check name `model-governance`, pack format and schema path, the seven pack items, the PDF fingerprint).

---

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| error-codes-4 | Codes never renamed | no guard | C | Commit the list of code names; a test fails when a name disappears | S |
| error-codes-6 | User messages plain, nothing about internals | no automatic check | C | Catalogue test refuses messages naming SQL, exceptions, stack or account terms | S |
| error-codes-9 | Every code enum implements `PlatformCode` | detection by name pattern only; no negative test; new modules added by hand | C | Also flag any enum with a `*.codes.properties` or in a `*Code`-like role; test that every reactor module is on the catalogue classpath | S |
| error-codes-13 | Longest prefix wins | untested | C | Add a second, longer prefix to the sample family | S |
| error-codes-15 | `LicenceRefusal.fix()` is its `action()` | untested | C | Assert `fix()` equals `action()` for every constant | S |
| error-codes-18 | Check refuses no message / no fix | only no-cause tested | C | Sample codes without message and without fix in `ErrorsTest` | S |
| error-codes-19 | Check refuses a name in two modules | branch untested | C | Test `Catalogue.problems` with two entries of one name from two modules | S |
| error-codes-22 | Regenerate command | generated header omits `-am` | C | Use the design's command in `Catalogue.markdown` and regenerate | S |
| error-codes-27 | A failure in a request uses the request's reference | untested | B | Test `FailureLog.record` inside `RequestReferenceFilter` returns the header's value | S |
| error-codes-33 | Log levels per kind | NEEDS_PERSON, INFO untested | C | Add both kinds to the failure-log test | S |
| error-codes-35 | Last 200 kept | cap untested | C | Record 201 and assert 200 remain, oldest dropped | S |
| error-codes-44 | Catalogue module never deployed, not in the BOM | DOC GAP | C | Add one sentence to error-codes.md section 1 | S |
| monitoring-1 | Default limit 10 s, interval 60 s | untested | C | Assert the interface defaults | S |
| monitoring-28 | `platform.monitoring.every` | setting has no effect | B | Use it as the interval of the built-in checks (or drop it from the properties and guide) | S |
| monitoring-30 | Key service check from the bean, required per `customer-keys-required` | untested | B | Wiring test with an in-memory `KeyService`, down, both settings | S |
| monitoring-32 | Licence check from the bean | untested | C | Wiring test with a `LicenceService` bean | S |
| monitoring-33 | Login-system check from beans and `product-code` | untested | C | Wiring test with store, `IssuerFetch` and product code | S |
| monitoring-34 | Mail check from the notifications settings | wrong port default; false DOWN without `from-address` under Microsoft 365 | B | Read the mail settings through notifications-spring's checked config (or mirror its port and sender defaults) and test it | S |
| monitoring-39 | Readiness includes the app's own readiness state | REFUSING_TRAFFIC branch untested | C | Wiring test publishing `ReadinessState.REFUSING_TRAFFIC` | S |
| monitoring-40 | Spring Boot's own `readinessState` backs off | untested | C | Wiring test with Actuator's availability auto-configuration | S |
| monitoring-48 | `platform.integration.attempts` | untested | C | Wiring test: one call through `CallEngine`, counter incremented with tags | S |
| monitoring-50 | State-change log line | untested; RECOVERED line has no code | C | Capture the log in `MonitoringCoreTest`; add `code=` to the RECOVERED line | S |
| monitoring-54 | Platform versions from released jars | untested | C | Test `platformVersions` against a built jar on a test class loader | S |
| monitoring-55 | Bundle configuration from the app's property sources | source selection untested | C | Wiring test: system property and env absent, app property present | S |
| monitoring-57 | Last 50 failures in the bundle | untested | C | Record 60 failures, assert 50 lines | S |
| monitoring-58 | Schema versions in the bundle | `mg_schema_version` missing | C | Add it to `VERSION_TABLES` and test the list against every module's `VERSION_TABLE` | S |
| monitoring-69 | `*_SETTINGS_INVALID` as `PlatformCode` enums | missing (incl. `ModelGovConfig.Invalid`, folder thresholds) | B | One small `PlatformCode` enum per Spring module; startup exceptions implement `HasPlatformCode` | M |
| monitoring-71 | Webhook path is a secret, never shown or called | DOC GAP | C | Add to monitoring.md section 1 | S |
| monitoring-72 | `FileLocationCheck.Disk` replaceable | DOC GAP | C | Add to monitoring.md section 1 | S |
| monitoring-73 | `model-governance` check | DOC GAP | C | Add a row to monitoring.md's table | S |
| model-governance-19 | Customer pass marks never looser | an entity mark becomes looser when the app tightens its default, and stays in force | B | On a default change, refuse it or treat looser entity marks as replaced by the default (both validation and monitoring marks); test it | S |
| model-governance-33 | A change of where the model runs goes through the gate | only makes the review due (warning by default) | B | Owner to confirm P034 D2; if the gate must refuse, record `runsWhere` in the validation and compare it in `requireLive` | M |
| model-governance-39 | Periodic re-validation | review date per use, validations per entity: one entity's pass resets the date for all | A | Keep the next review per use and entity (from that entity's live validation) and check it at the gate and in `ModelReviews` | M |
| model-governance-70 | Flyway run with `mg_schema_history` | untested | C | Schema test running the files through Flyway with that history table | S |
| model-governance-84 | Readings deleted only inside the purge | any user with DELETE can set `mg.purge_before`; no 30-day floor | B | Purge through a SECURITY DEFINER function owned by the schema owner that enforces the floor; revoke DELETE on `mg_measure` from the runtime user | M |
| model-governance-100 | Monitoring's database check verifies `mg_` | untested | C | Wiring test with a `ModelGovStore` bean and missing `mg_` tables | S |
| model-governance-101 | Worker: notices and daily purge | untested | C | Test `runOnce` with a fixed clock: a notice raised, old readings purged once a day | S |
| model-governance-105 | P034 D4 retention in the database | same as 84 | B | See model-governance-84 | — |
| model-governance-108 | P033 D1 | DOC GAP | C | Add to model-governance.md section 9 | S |
| model-governance-109 | P033 D2 | DOC GAP | C | Add to section 9 | S |
| model-governance-110 | P033 D3 | DOC GAP | C | Add to section 9 | S |
| model-governance-111 | P033 D5 | DOC GAP | C | Add to section 9 | S |
| model-governance-112 | P034 report D8 | DOC GAP | C | Add to section 10's Spring line | S |
| install-monitoring-1 | `every` property | does nothing | B | See monitoring-28 | — |
| install-monitoring-2 | `tick` property | not in the guide | C | Document it (or fold it into `every`) | S |
| install-monitoring-3 | `channel-mail` | false DOWN / wrong port | B | See monitoring-34 | — |
| install-monitoring-4 | Checks table | `model-governance` missing | C | Add the row | S |
| install-monitoring-5 | Bundle table versions | `mg_` missing | C | See monitoring-58 | — |
| install-monitoring-6 | Folder thresholds | no range; bare exception | C | Give the range in the guide; list the problem with the property name at startup | S |
| install-model-governance-1 | Startup codes | two codes not listed | C | Add `MG_VERSION_UNKNOWN`, `MG_CHECK_FAILED` | S |
| install-model-governance-2 | Runtime grants | message and SQL header contradict the guide; narrow grants untested | C | Make the message and header give the narrow grants; schema test with exactly the guide's grants | S |
| install-model-governance-3 | `enabled` | not in the guide | C | Document it | S |
| install-model-governance-4 | Setting limits | not in the guide | C | Document 1d–3650d and ≥ 1 minute | S |
| install-model-governance-5 | `forbidden-roles` | may be empty silently; separation then lost | B | Refuse (or warn loudly at startup) when `forbidden-roles` is empty; say in the guide it must be set | S |

Gap totals: A 0 · B 13 · C 41 (54 rows; four of them point to another row: model-governance-105, install-monitoring-1, -3, -5).
