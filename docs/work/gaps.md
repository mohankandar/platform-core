# Gap list: strength check step 1 (P035)

This lists every rule from the P035 tick-off that is not DONE or APP-SIDE, plus every cross-check finding. The full
tables, with code and test for each rule, are in [tick-off/](tick-off/). Rows keep their tick-off IDs.

**Severity:**
- **A:** wrong or unsafe; must be fixed before the integration release.
- **B:** missing or untested; should be fixed before it.
- **C:** polish or docs; fine to fix after.

**Size:** S, M or L.

**Totals:** 342 rows: **A 3 · B 85 · C 254**.

A group of DOC GAP rows that share one fix counts as one row. Rows that only say "see <other ID>" are kept, so a
fix ticks off both.

## Proposed step-2 tasks (the closing tasks)

| Task | Scope | Rows (A / B / C) | Sizes | When |
|---|---|---|---|---|
| **S2-1 Fix the three wrong things** | A rows: secrets in URLs reach the support bundle; the development-key marks are dropped in Spring apps; model review dates are kept per use, not per use and entity | 3 / 0 / 0 | 2 S, 1 M | first, before the release |
| **S2-2 Every failure is a `PlatformCode`** | `*_SETTINGS_INVALID` and the other startup messages as enum codes in each Spring module; exceptions carrying a code implement `HasPlatformCode`; uncoded store failures; string codes (`ERASE_HANDLER_ERROR`, `FLD_PARTIAL_SEARCH_UNAVAILABLE`); broken code texts (setup's fix texts lost their variable names; `--no-keys`; `%s` placeholders); a catalogue check that refuses empty slots | 0 / 21 / 24 | 34 S, 5 M | before the release |
| **S2-3 Never-leak and safety tests** | Tests for every place that could leak but nothing proves it does not (licence command, call engine send-time sensitive-field refusal, `HttpServiceCheck.shown`, record `toString`s incl. `AuthenticatedIdentity`); integration safety checks with code but no test (same-origin status and next-page links, ICAP verdict, intake self-check, `token.requiredClaims`); the two unsafe public entry points (`ValueStore.delete` around the erase log, `WebhookOptions.allowingPlainHttpForTests`) | 0 / 12 / 14 | 25 S, 1 M | before the release |
| **S2-4 Schema rights and verify** | A DDL-refused test for the runtime user in licensing, notifications and fields; untested verify outcomes (erase log, integration); the modelgov purge guard (`mg.purge_before` settable by any DML user, no 30-day floor) | 0 / 5 / 4 | 8 S, 1 M | before the release |
| **S2-5 Untested rules: fields, erase log, crypto, licensing** | B rows: known-answer tests for the stored formats (value, record envelope, fingerprint, licence signed bytes), locks between instances and processes, the erase-log admin data, crypto login retry and redirects, licence refusal codes without a test | 0 / 28 / 0 | 25 S, 3 M | before the release |
| **S2-6 Untested rules: setup, identity, notifications, monitoring, model governance** | B rows: `platform.monitoring.every` read by nothing, the mail check's fixed port and `from-address`, looser customer marks kept after a default tightens, `forbidden-roles` empty, certificate push caller needs an ACTIVE connection, P014 grant rules, notification e-mail addresses never stored, multi-instance expiry claim on Postgres | 0 / 15 / 0 | 12 S, 2 M | before the release |
| **S2-7 Design docs and install guides catch up** | DOC GAPs (decisions only in reports), DIFFERENT rows where the code follows a later decision and the doc is stale, install-guide differences, the two root notes that name apps (`INTEGRATION-NOTES.md`, `SCHEMA_CONTRACT.md`) and a wider name guard | 0 / 4 / 78 | 78 S, 1 M | the B rows before the release, the rest after |
| **S2-8 Public API: hide internals** | About 60 internal-looking public types (no visibility policy, no `module-info`), public test hooks on production classes, the licence-issuing side in the customer jar | 0 / 0 / 15 | 10 S, 4 M, 1 L | before the release if possible: hiding a type after apps pin the release is a breaking change (question 3 of the report) |
| **S2-9 Polish after the release** | The remaining C rows: small untested behaviours, edge-case codes, log lines no test captures | 0 / 0 / 119 | 114 S, 4 M, 1 L | after the release |

S2-1 to S2-6 hold every A and B row. S2-7's four B rows are docs and guides only. S2-2, S2-3 and S2-4 may be done
as one task if the owner prefers fewer, larger tasks: all of their rows are S or M.

## S2-1 Fix the three wrong things

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| model-governance-39 | Periodic re-validation | review date per use, validations per entity: one entity's pass resets the date for all | A · **FIXED in P036 (912f3cd)** | Keep the next review per use and entity (from that entity's live validation) and check it at the gate and in `ModelReviews` | M |
| xc-devkey-1 (licensing-file-format-44) | A key marked `"development": true` counts as a development key in the app | `LicensingConfig.from` drops the development set, so in Spring apps `DevelopmentLicence` is always false and a local http issuer is refused even under a development-key licence; untested | A · **FIXED in P036 (dd2d3e8)** | Keep the file's development IDs when merging keys (`TrustedKeys.of(keys, development)`) and add a wiring test through identity-spring | S |
| xc-never-leak-2, -11 | never log / bundle secrets | `SafeText.scrub` keeps URL user-info, so `redis://user:pw@host`-style app properties reach the diagnostic bundle (and any `PlatformError` detail) | A · **FIXED in P036 (dc2860a)** | In `SafeText.scrub` replace `://[^/\s@]+@` with `://[removed]@`; plant such a property in `DiagnosticBundleTest` and a detail in `ErrorsTest` | S |

## S2-2 Every failure is a PlatformCode

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| xc-catalogue-1 | `FLD_PARTIAL_SEARCH_UNAVAILABLE` | Not a `PlatformCode` | B · **FIXED in P037 (00560bc)** | Add it to a fields code enum with message, cause, fix; regenerate the catalogue | S |
| xc-catalogue-2 | `ERASE_HANDLER_ERROR` | Not a `PlatformCode` | B · **FIXED in P037 (59178ca)** | Add it to `EraseLogError` (or a handler code enum) with texts; regenerate the catalogue | S |
| xc-catalogue-3 | `ERASE_LOG_SETTINGS_INVALID` as message prefix | Exception does not carry the code | B · **FIXED in P037 (2a21e26)** | Make `EraseLogConfigException` implement `HasPlatformCode` (with the P032 question 2 group) | S |
| error-codes-27 | A failure in a request uses the request's reference | untested | B · **FIXED in P037 (77e6652)** | Test `FailureLog.record` inside `RequestReferenceFilter` returns the header's value | S |
| monitoring-69 | `*_SETTINGS_INVALID` as `PlatformCode` enums | missing (incl. `ModelGovConfig.Invalid`, folder thresholds) | B · **FIXED in P037 (2a21e26)** | One small `PlatformCode` enum per Spring module; startup exceptions implement `HasPlatformCode` | M |
| install-integration-2 | `*_SETTINGS_INVALID` not codes | Catalogue | B · **FIXED in P037 (2a21e26)** | Cross-check 2 (P032 question 2): make them `PlatformCode`s or say in the guide they are message prefixes | M |
| xc-catalogue-2 | Every refusal is a code | `NotificationsConfigException` ("Notifications configuration is not valid: …") and the missing-bean messages are not `PlatformCode`s (P032 question 2) | B · **FIXED in P037 (2a21e26)** | give the settings problems a `NOTIF_SETTINGS_INVALID` code with the per-key lines as details | S |
| setup-111 | Flag is `--no-customer-keys` | The fix text says `--no-keys`, which is refused | B · **FIXED in P037 (77e6652)** | Correct the `SETUP_KEY_SERVICE_SETTINGS_MISSING` action and `.fix`, regenerate the catalogue, assert the text in `theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted` | S |
| xc-codes-setup-1 | Catalogue texts of two setup codes | Variable names missing in `.fix` | B · **FIXED in P037 (77e6652)** | Write the variable names into `SetupRefusal.codes.properties`, regenerate, and have the catalogue check refuse empty `()` / `, ,` gaps | S |
| xc-catalogue-4 | `INTEGRATION_SETTINGS_INVALID` is a `PlatformCode` | String prefix only | B · **FIXED in P037 (2a21e26)** | One `SettingsCode` family (or each module's own code enum) with `*_SETTINGS_INVALID`; config exceptions implement `HasPlatformCode`; regenerate the catalogue | M (rows 4–12 together) |
| xc-catalogue-5 | `OUTBOUND_SETTINGS_INVALID` is a `PlatformCode` | String prefix only | B · **FIXED in P037 (2a21e26)** | As row 4 | (in 4) |
| xc-catalogue-6 | `IDENTITY_PUSH_SETTINGS_INVALID` is a `PlatformCode` | Plain `IllegalStateException` with a prefix; no failure analyzer | B · **FIXED in P037 (2a21e26)** | As row 4, plus a dedicated exception and failure analyzer | (in 4) |
| xc-catalogue-7 | Erase-log settings carry their code | `EraseLogConfigException` and the missing-DataSource refusal do not carry `ERASE_LOG_SETTINGS_INVALID` | B · **FIXED in P037 (2a21e26)** | `EraseLogConfigException implements HasPlatformCode` returning `ERASE_LOG_SETTINGS_INVALID`; use it for the DataSource refusal too | S |
| xc-catalogue-8 | Crypto settings carry their code | `CryptoConfigException` carries no code | B · **FIXED in P037 (2a21e26)** | Prefix and carry `CRYPTO_SETTINGS_INVALID` (`HasPlatformCode`) | S |
| xc-catalogue-9 | Licensing settings have a code | No code | B · **FIXED in P037 (2a21e26)** | Add `LIC_SETTINGS_INVALID` and carry it | (in 4) |
| xc-catalogue-10 | Notifications settings have a code | No code | B · **FIXED in P037 (2a21e26)** | Add `NOTIF_SETTINGS_INVALID` and carry it | (in 4) |
| xc-catalogue-11 | Model-governance settings have a code | No code (`ModelGovConfig.Invalid`, missing DataSource) | B · **FIXED in P037 (2a21e26)** | Add `MOD_SETTINGS_INVALID` and carry it | (in 4) |
| xc-catalogue-12 | Monitoring settings are checked with a code | No settings check; a bad threshold gives a bare `IllegalArgumentException` | B · **FIXED in P037 (2a21e26)** | `MonitoringConfig.from` listing every problem with `MON_SETTINGS_INVALID` | S |
| xc-catalogue-13 | `ERASE_HANDLER_ERROR` in the catalogue | String code, not a `PlatformCode` | B · **FIXED in P037 (59178ca)** | Add `ERASE_HANDLER_ERROR` to `EraseLogError` and use `.name()` at the three places | S |
| xc-catalogue-18 | Code-carrying exceptions implement `HasPlatformCode` | 16 exception types carry a code without it | B · **FIXED in P037 (59178ca)** | Add `implements HasPlatformCode` (return the code); add a test that scans for exceptions with a `code()` of a `PlatformCode` type | M |
| xc-catalogue-19 | Store and read failures have a code | 6 store or read exceptions without a code | B · **FIXED in P037 (00560bc)** | Add `*_STORE_FAILED` (FAILED, with the SQL state as a safe detail) per module and carry it | M |
| xc-catalogue-4 | Type change when the values check cannot answer | No platform code | C · **FIXED in P037 (bf7d99c)** | Wrap in `FieldDefinitionException` with a new `FIELD_VALUES_CHECK_FAILED` code | S |
| crypto-111 | P032 codes in design | Not stated; command codes not in the table | C · **FIXED in P037 (c3fa330)** | Design section 6: note `PlatformCode` + `.codes.properties`; add the five command codes | S |
| crypto-114 | Command code texts | `--tenant` instead of `--tenant-id` | C · **FIXED in P037 (77e6652)** | Fix `CryptoCommand.Code.codes.properties` and regenerate the catalogue | S |
| crypto-115 | Crypto code fix texts | `CRYPTO_SECRET_MISSING` and `CRYPTO_PERMISSION_DENIED` fixes describe other things | C · **FIXED in P037 (77e6652)** | Rewrite both fix texts and regenerate the catalogue | S |
| error-codes-4 | Codes never renamed | no guard | C · **FIXED in P037 (77e6652)** | Commit the list of code names; a test fails when a name disappears | S |
| error-codes-6 | User messages plain, nothing about internals | no automatic check | C · **FIXED in P037 (77e6652)** | Catalogue test refuses messages naming SQL, exceptions, stack or account terms | S |
| error-codes-9 | Every code enum implements `PlatformCode` | detection by name pattern only; no negative test; new modules added by hand | C · **FIXED in P037 (59178ca)** | Also flag any enum with a `*.codes.properties` or in a `*Code`-like role; test that every reactor module is on the catalogue classpath | S |
| error-codes-13 | Longest prefix wins | untested | C · **FIXED in P037 (77e6652)** | Add a second, longer prefix to the sample family | S |
| error-codes-15 | `LicenceRefusal.fix()` is its `action()` | untested | C · **FIXED in P037 (77e6652)** | Assert `fix()` equals `action()` for every constant | S |
| error-codes-18 | Check refuses no message / no fix | only no-cause tested | C · **FIXED in P037 (77e6652)** | Sample codes without message and without fix in `ErrorsTest` | S |
| error-codes-19 | Check refuses a name in two modules | branch untested | C · **FIXED in P037 (77e6652)** | Test `Catalogue.problems` with two entries of one name from two modules | S |
| error-codes-22 | Regenerate command | generated header omits `-am` | C · **FIXED in P037 (77e6652)** | Use the design's command in `Catalogue.markdown` and regenerate | S |
| error-codes-33 | Log levels per kind | NEEDS_PERSON, INFO untested | C · **FIXED in P037 (77e6652)** | Add both kinds to the failure-log test | S |
| error-codes-35 | Last 200 kept | cap untested | C · **FIXED in P037 (77e6652)** | Record 201 and assert 200 remain, oldest dropped | S |
| error-codes-44 | Catalogue module never deployed, not in the BOM | DOC GAP | C · **FIXED in P037 (c3fa330)** | Add one sentence to error-codes.md section 1 | S |
| licensing-93 … licensing-105 | P010 and P011 decisions (expected tenant, broken row, version rules, reset use, tolerance cap, health codes, `LicensingClock`, notification timing, bands, handover, keys, usage levels, purge / catalogue / release date) | Only in the reports; the design body just points to them | C · **FIXED in P037 (c3fa330)** | Write them into section 8 of licensing.md | M |
| notifications-3 | Removed event types ignored | no test removes a type between two catalogues (settings, summaries, conditions) | C · **FIXED in P037 (77e6652)** | test: build a second catalogue without a type and check buckets / conditions / settings for it are dropped | S |
| xc-catalogue-1 | Codes use `{placeholders}` (P032) | `FailureCode.codes.properties` causes keep `%s` (e.g. `WEBHOOK_ADDRESS_INVALID.cause=%s webhook address no longer valid`), shown raw in the catalogue | C · **FIXED in P037 (77e6652)** | write `{channel}` in the properties and fill it in `describe` | S |
| xc-catalogue-3 | Catalogue check covers every module | No check that the catalogue pom lists every module; `platform-model-governance-spring` is absent; finds enums by name only | C · **FIXED in P037 (59178ca)** | Add every non-testkit module to the pom and a test comparing that list with the root `<modules>` | S |
| xc-catalogue-15 | `FLD_PARTIAL_SEARCH_UNAVAILABLE` in the catalogue | Info code given as a string | C · **FIXED in P037 (00560bc)** | Add it as an INFO code to `FieldValuesError` (or a fields info family) | S |
| xc-catalogue-16 | Test sign-in reasons | Reason tokens look like codes | C · **FIXED in P037 (77e6652)** | Document them as reasons under `IDN_TEST_ISSUER_UNREACHABLE`, or rename to lower case | S |
| xc-catalogue-17 | Import conflict reasons in the catalogue | `ImportReport.Conflict` values not catalogued | C · **FIXED in P037 (77e6652)** | Rename to `ImportReport.ConflictCode implements PlatformCode` with texts | S |
| xc-catalogue-20 | Codes carry their module prefix | Notifications schema codes unprefixed | C · **NOT DONE: no rename (P032 "no code renamed"); an alias would give one code two names. Question 1** | Decide (new task) whether to add `NOTIF_` aliases; renaming is its own task | S |
| xc-public-api-11 | Only intended types public (errors) | `com.documentplatform.errors.CodeTexts` (`of`, `family`, `resourceName`) is used only by `PlatformCode` in the same package. | C · **FIXED in P037 (77e6652)** | Make it package-private. | S |

## S2-3 Never-leak and safety tests

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| integration-module-120 | `Location` status reference stays on the base origin | Test | B · **FIXED in P037 (cdedb4f)** | AcceptedLaterTest: 202 with a Location on another host/port → UNCLEAR/refused, no call made | S |
| integration-module-134 | NEXT_LINK same origin only (`INT_POLL_NEXT_PAGE_INVALID`) | Test | B · **FIXED in P037 (fde3d04)** | PollingTest: MockSource next link to another host → pass stops with the code, no call there | S |
| integration-module-157 | ICAP 200 without a threat header = infected | Test | B · **FIXED in P037 (6e38f5a)** | MockScanner switch "200 without header"; ScannerTest asserts INFECTED | S |
| integration-module-129 / 155 | `IntakeFile` self-check; changed file → `_problem` `INT_WATCH_FILE_CHANGED` | Test | B · **FIXED in P037 (7496d86)** | WatchedFolderTest: handler that rewrites a file while reading → `_problem` with the code; unit test of `IntakeFile.Changed` | S |
| integration-module-46 | `token.requiredClaims` → `INT_TOKEN_CLAIMS_MISSING` | Test | B · **FIXED in P037 (edd4edb)** | SignInTest: mock `tokenClaims`; missing claim → SIGN_IN_FAILED with the code, nothing sent; present → SENT | S |
| integration-module-153d (code side) | Sensitive field refused at send time for API push (`INT_SENSITIVE_FIELD_NOT_ALLOWED`, CallEngine ~407) | Test (save-time only tested) | B · **FIXED in P037 (cb60d3a)** | CallEngineTest: delivery marks a mapped field sensitive, not allowed → NOT_CONFIGURED, nothing sent | S |
| xc-never-leak-23 | never log raw claims | `AuthenticatedIdentity` (public record) prints all raw claims, email and name in its default `toString` | B · **FIXED in P037 (cb30810)** | Override `toString` (provider, issuer, subject hash or ID, group count) and assert it in an identity-core test | S |
| xc-never-leak-48 | never print secrets in command output | licence command has no test for a wrong DB password or a password in the JDBC URL | B · **FIXED in P037 (51712a3)** | Add a `LicenceCommandTest` case like setup's `noSecretInAnyOutput` (wrong password + `?password=` in URL) | S |
| xc-never-leak-75 | sensitive fields only where allowed | CallEngine's send-time `INT_SENSITIVE_FIELD_NOT_ALLOWED` is never exercised | B · **FIXED in P037 (cb60d3a)** | Test a stored config that skipped the save check (sensitive `{field:}` in a path/header and in JSON without allow-list) → NOT_CONFIGURED, value not sent | S |
| xc-never-leak-5 | never show tokens in health / bundle | `HttpServiceCheck.shown()` dropping query and user-info is not tested | B · **FIXED in P037 (4f8a292)** | Add a `BuiltInChecksTest` case with `http://u:pw@host/health?token=x` and assert the cause shows neither | S |
| xc-public-api-2 | case-fields: values are erased only through the erase log (`VALUES_ERASE_OUTSIDE_LOG`) | `FieldValues.erase` refuses any caller except `CaseFieldsEraseHandler` (`platform-fields/src/main/java/com/documentplatform/fields/values/FieldValues.java` `erase`). But `com.documentplatform.fields.jdbc.PostgresValueStore`, which the app builds itself because there is no fields-spring, has a public `delete(StoredRecord)`. The `ValueStore.delete` interface method is also public. That deletes the record and its envelope with no erase-log entry, so a restored backup would bring the values back unnoticed. Also, the caller check compares a class name, which on the classpath (no JPMS) any jar can define. | B · **FIXED in P037 (7734282)** | Make deletion need a capability only `FieldValues` can create (for example a package-private-constructible `EraseTicket` parameter on `ValueStore.delete`). Document `ValueStore` as SPI that apps never call. | M |
| xc-public-api-3 | notifications: webhook addresses carry a token and are https only | `com.documentplatform.notifications.webhook.WebhookOptions.allowingPlainHttpForTests()` is public, and so is the `allowPlainHttp` record component. Any app code can turn on plain-http webhook posting, and the token-bearing URL then travels unencrypted. Unlike identity's http issuer, nothing ties this to a development-key licence or localhost. Spring never sets it, which is correct. | B · **FIXED in P037 (d66654b)** | Move the switch to the testkit (or limit it to loopback hosts like `LocalTestIssuers`) and drop the boolean from the public record. | S |
| xc-never-leak-19 | values never in `toString` | `Fingerprinter.Input.toString` untested | C · **FIXED in P037 (4a5a142)** | Assert in `RecordEnvelopeVaultTest` (or `NoSecretInLogsTest`) | S |
| xc-never-leak-27 | answer bodies never in `toString` | `IssuerFetch.Response.toString` untested | C · **FIXED in P037 (8e6dff5)** | Assert in `OidcTestSignInTest` | S |
| xc-never-leak-28 | store errors name the operation only | identity-jdbc log lines of `IdentityStoreException` not captured | C · **FIXED in P037 (5adcd13)** | Capture logs in an identity-jdbc test with a broken DataSource carrying a planted password | S |
| xc-never-leak-29 | no claims / group names in logs | `EntityMembershipResolver` log and identity-spring error texts not asserted | C · **FIXED in P037 (fa23448)** | Add a log-capture assertion to the resolver's unknown-role test | S |
| xc-never-leak-43 | no keys / signatures in logs | licensing and licensing-spring log lines not captured | C · **FIXED in P037 (cccfef4)** | Capture logs in `LicensingWiringTest` / `LicenceServiceTest` and assert no signature or key text | S |
| xc-never-leak-53 | store texts only | setup's `SETUP_FAILED` message includes an `IllegalStateException` text, untested | C · **FIXED in P037 (8da5b79)** | Covered by adding the failing-store case to `SetupCommandTest.noSecretInAnyOutput` | S |
| xc-never-leak-60 | nothing secret in erase-log logs / health | erase-log-spring logs and health not asserted | C · **FIXED in P037 (9a8d50b)** | Assert the health details map exactly in `EraseLogWiringTest` | S |
| xc-never-leak-62 | nothing secret in logs | model-governance-spring worker logs not asserted | C · **FIXED in P037 (6567e4b)** | Fine by construction; add one capture in the worker test or accept | S |
| xc-never-leak-64 | headers / bodies never in `toString` | `Outbound.Call` / `Answer` `toString` untested | C · **FIXED in P037 (54111fe)** | Assert in `OutboundRulesTest` | S |
| xc-never-leak-69 | answer text never repeated | `SAFE_CODE` filter on destination codes untested | C · **FIXED in P037 (ff887ca)** | Mock answers a code with spaces / 41+ chars → `destinationCode()` null in `ClassificationRulesTest` | S |
| xc-never-leak-74 | values never in `toString` | `CaseFields` / `CaseFields.Field` `toString` untested | C · **FIXED in P037 (3d806da)** | Add both `toString`s to `NoSecretsInLogsTest` outputs | S |
| xc-never-leak-81 | callback body never in `toString` | `CallbackEntry.toString` untested | C · **FIXED in P037 (3d806da)** | Add `outbox` entries' `toString` to `NoSecretsInLogsTest.nothingSecretFromCertificatesOrCallbacks` | S |
| xc-never-leak-84 | exception text never logged | `IntegrationWorker` log lines not captured | C · **FIXED in P037 (d8b6cbe)** | Capture in the integration-spring worker test with a throwing outbox | S |
| xc-never-leak-85 | no personal data in logs | drop / item IDs (customer file names, source IDs) logged as they are; no decision whether they count as content | C · **FIXED in P037 (c84f79c)** | Decide in `docs/design/integration-module.md` (log as-is vs. hash); then test | S |

## S2-4 Schema rights and verify

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| model-governance-84 | Readings deleted only inside the purge | any user with DELETE can set `mg.purge_before`; no 30-day floor | B · **FIXED in P037 (0b08363)** | Purge through a SECURITY DEFINER function owned by the schema owner that enforces the floor; revoke DELETE on `mg_measure` from the runtime user | M |
| xc-schemas-2 | Licensing runtime user cannot run DDL | No DDL-refusal test | B · **FIXED in P037 (f273af7)** | Add create/alter as the DML user to `PostgresStoresTest`, asserting permission denied | S |
| xc-schemas-3 | Notifications runtime user cannot run DDL | No DDL-refusal test | B · **FIXED in P037 (f273af7)** | Add the same to `StartupSchemaCheckTest` `runsWithOnlySelectInsertUpdateDeleteRights` | S |
| xc-schemas-4 | Fields runtime user cannot run DDL | No DDL-refusal test | B · **FIXED in P037 (f273af7)** | Add the same to `FieldSchemaTest` | S |
| xc-schemas-6 | Erase-log verify outcomes tested | VERSION_UNKNOWN / OLDER / NEWER, TABLES_INCOMPLETE untested | B · **FIXED in P037 (7def3cf)** | Add a `startupRefusalCodes` test like the identity and fields ones | S |
| xc-schemas-7 | Integration verify outcomes tested | VERSION_UNKNOWN, TABLES_INCOMPLETE untested | B · **FIXED in P037 (7def3cf)** | Extend `theStartupCheckOnlyReadsAndSaysWhatIsWrong` | S |
| xc-schemas-9 | Over-privileged runtime user detected | No verify detects an owner or DDL-capable runtime user | C · **FIXED in P037 (7def3cf)** | Optional warning in each verify (or a monitoring HAVING_PROBLEMS) when the user owns the tables or has CREATE on the schema | M |
| xc-schemas-10 | `*_CHECK_FAILED` tested | Untested in all 7 runs | C · **FIXED in P037 (7def3cf)** | One test per verify with a DataSource whose connection fails mid-check | S |
| xc-schemas-11 | Notifications guide: separate run, history table, codes | Guide differs from design B4 and lists messages only | C · **FIXED in P037 (03f7138)**: codes listed; the guide keeps the code's run (in the app's own schema run, no notif_schema_history), the design's B4 is stale (P040) | State the separate run with `notif_schema_history` and list the `NotificationSchemaException.Problem` codes | S |
| xc-schemas-12 | Integration guide: location, history table, codes | Not stated | C · **FIXED in P037 (03f7138)** | Add the location, history table and `INT_*` schema codes to §1 | S |

## S2-5 Untested rules: fields, erase log, crypto, licensing

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| erase-log-22 | Admin screen shows the pending entry and its handler code | The guard state and health have only a pending count; no entry number, kind, record type or ID, or handler code while running | B · **FIXED in P038 (b192e03)** | Keep the last apply failure (seq, kind, record type and ID, handler code) in `EraseGuard.State` and the `eraseLog` health details; test it | M |
| erase-log-51 | Advisory lock: instances apply one at a time, in order | No concurrency test of `PostgresErasePosition.withLock` | B · **FIXED in P038 (cd56444)** | Test two erasers (and an eraser plus a replay) on one database in parallel: one chain, position in order, nothing applied twice out of order | M |
| erase-log-57 | One transaction when the handler uses `Context.connection()` | Untested | B · **FIXED in P038 (cd56444)** | Test handler that writes through `ctx.connection()` and fails after: neither its write nor the position commits | S |
| erase-log-67 | `fullCheck()` never records a restore | Untested | B · **FIXED in P038 (cd56444)** | Test: pending entry while running, `fullCheck()` → may take work, `replay_required` still false | S |
| erase-log-97 | Worker: daily full check and pruning | `daily()` effects not asserted; schedule untested | B · **FIXED in P038 (e4ab697)** | Assert pruning and a blocked-state skip in `EraseLogWiringTest` with a moved clock | S |
| erase-log-99 | Health DOWN for the other codes | DOWN untested | B · **FIXED in P038 (e4ab697)** | Wiring test with an unreachable location or a broken chain → DOWN with its code | S |
| erase-log-44 | File lock between processes | Only same-JVM writers tested | B · **FIXED in P038 (23fefb1)** | Test with a second JVM (ProcessBuilder) appending to the same location | M |
| erase-log-73 | A verify check that fails is `ERASE_VERIFY_FAILED` | Throwing verify untested | B · **FIXED in P038 (cd56444)** | Handler whose `verify` throws → `ERASE_VERIFY_FAILED` with its code, still blocked | S |
| erase-log-40 | Escape or missing field refused (`ERASE_LOG_FORMAT`) | Only an extra field tested | B · **FIXED in P038 (23fefb1)** | Add a line with `\"` and a line missing `auditId` to `FileEraseLogStoreTest` | S |
| case-fields-63 | A record without primary values is a record of its own | Untested | B · **FIXED in P038 (6681ae1)** | Claim twice with no primary value under two IDs → two records | S |
| case-fields-73 | Sealed values bound to field and position | Untested | B · **FIXED in P038 (6681ae1)** | Move a `pci1.` value to another field / position in the table → it does not open | S |
| case-fields-113 | `FieldSchema` checks DELETE on `fld_record` | Untested | B · **FIXED in P038 (7678f9b)** | `FieldSchemaTest`: user with all but DELETE on `fld_record` → `FLD_PRIVILEGES_MISSING` naming DELETE | S |
| crypto-11 | Keys never exportable, no plaintext backup | No test | B · **FIXED in P038 (39472e1)** | In `VaultCryptoTest`, read `transit/keys/<name>` after `createKeys` and assert `exportable=false`, `allow_plaintext_backup=false` for both keys | S |
| crypto-23 | Value layout and AAD fixed | No known-answer vector | B · **FIXED in P038 (4f4b467)** | Known-answer test: fixed key service (fixed data key and wrapped text), fixed nonce, expected `pcv1.` text | S |
| crypto-28 | Record envelope / item layout fixed | No known-answer vector; tamper code not asserted | B · **FIXED in P038 (4f4b467)** | Known-answer test for `pck1.` / `pci1.`; assert `CRYPTO_CIPHERTEXT_INVALID` on a tampered envelope | S |
| crypto-40 | Fingerprint input fixed | No known-answer HMAC | B · **FIXED in P038 (4f4b467)** | Known-answer test with the in-memory key service's known fingerprint key, against an HMAC computed outside Java | S |
| crypto-51 | Login repeated once on a refused token | Retry untested; 401 not retried as the doc says | B · **FIXED in P038 (39472e1)** | Vault test with an AppRole `token_ttl` of a few seconds: a call after expiry succeeds; align the doc (403) or the code (401 too) | S |
| crypto-60 | Nothing secret leaves | Record envelope paths and `Fingerprinter.Input` not in the no-secret test | B · **FIXED in P038 (5c6529a)** | Add `RecordCipher` seal / open / damaged / wrong-customer and `RecordKey` / `Input` `toString` to `NoSecretInLogsTest` | S |
| crypto-63 | Redirects never followed | No test | B · **FIXED in P038 (2e3436b)** | Test with a local server that answers 307 to another port: assert `CRYPTO_KEY_SERVICE_ERROR` and that the second server got no token | S |
| crypto-94 | Re-created keys are new | No test | B · **FIXED in P038 (e224458)** | In `KeyDestructionVaultTest`, `createKeys` again after destruction and assert old value / envelope / stream still fail | S |
| licensing-file-format-27 | Signed message bytes; the Console must produce the same bytes | No fixed-bytes test; the doc's sample licence and public key are never verified | B · **FIXED in P038 (ec88c4e)** | Golden test: verify the doc's sample file with its key, and check canonical bytes / `payloadSha256` of a fixed payload (also ledger hash and export chain values) | S |
| licensing-file-format-50 | Vault's signature checked before writing | `ISSUE_SIGNATURE_MISMATCH` untested | B · **FIXED in P038 (1aac687)** | Test with a signer double that returns another key's signature: refused, nothing written | S |
| licensing-file-format-25 | Unknown payload fields refused | Untested | B · **FIXED in P038 (ed1eb0d)** | Test: sign a payload with an extra field, expect `LICENCE_MALFORMED` | S |
| licensing-file-format-31 | `LICENCE_MALFORMED` cases | Extra envelope field and non-64-byte signature untested | B · **FIXED in P038 (ed1eb0d)** | Add both cases to `LicenceVerifierTest.malformed` | S |
| licensing-file-format-16 | Products without repeats | Repeat refusal untested | B · **FIXED in P038 (2bb79be)** | Add a repeated-product case to `LicenceModelTest` | S |
| licensing-file-format-62 | `EXPORT_RECORD_OUTSIDE_PERIOD` | Untested | B · **FIXED in P038 (d00dab5)** | Test: a record with a period outside the header range (re-chained) gives the code | S |
| licensing-44 / licensing-79 | Never log secrets; refusals logged with the code only | Log lines of `LicenceVerifier`, `LicenceService`, `LicenceStartupCheck`, `LicenceCommand` not captured by any test | B · **FIXED in P038 (24f6fac)** | Capture `com.documentplatform.licensing` logs in the verifier and wiring tests; assert no signature, key or file text | S |
| licensing-92 | Licence warnings left out of overall health, HTTP 200 | Aggregate status and HTTP mapping untested | B · **FIXED in P038 (44284a5)** | Actuator test: grace and grace-over give overall UP and HTTP 200; edited row gives DOWN | S |

## S2-6 Untested rules: setup, identity, notifications, monitoring, model governance

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| monitoring-28 | `platform.monitoring.every` | setting has no effect | B | Use it as the interval of the built-in checks (or drop it from the properties and guide) | S |
| monitoring-30 | Key service check from the bean, required per `customer-keys-required` | untested | B | Wiring test with an in-memory `KeyService`, down, both settings | S |
| monitoring-34 | Mail check from the notifications settings | wrong port default; false DOWN without `from-address` under Microsoft 365 | B | Read the mail settings through notifications-spring's checked config (or mirror its port and sender defaults) and test it | S |
| model-governance-19 | Customer pass marks never looser | an entity mark becomes looser when the app tightens its default, and stays in force | B | On a default change, refuse it or treat looser entity marks as replaced by the default (both validation and monitoring marks); test it | S |
| model-governance-33 | A change of where the model runs goes through the gate | only makes the review due (warning by default) | B | Owner to confirm P034 D2; if the gate must refuse, record `runsWhere` in the validation and compare it in `requireLive` | M |
| model-governance-105 | P034 D4 retention in the database | same as 84 | B | See model-governance-84 | — |
| install-model-governance-5 | `forbidden-roles` | may be empty silently; separation then lost | B | Refuse (or warn loudly at startup) when `forbidden-roles` is empty; say in the guide it must be set | S |
| notifications-65 | Outbox stores roles, never addresses | no test that no e-mail address reaches a `notif_` table | B | add recipient addresses to the never-list of `SecretsNeverLeakTest` table dump | S |
| notifications-78 | Released SQL files never edited | no guard; rule kept so far (V1 unchanged since v0.3.0) | B | build guard: checksum of every released `db/platform/**` file pinned in a test | S |
| notifications-145 | Told once with several instances (Postgres) | `SKIP LOCKED` claim tested only in memory | B | Postgres test: two threads call `tellExpired` on 30 requests; each told once | S |
| setup-98 | A certificate caller needs the customer's ACTIVE connection | No test | B | Add a certificate case with a DISABLED connection to `PushCallerTest` (memory and Postgres) | S |
| setup-115 | Grants of slots the file does not name count as "not in the file"; older issuer untouched | Not in setup.md; no test | B | State it in setup.md §5; add a setup test that drops every row of one slot and one for a grant under another issuer | S |
| setup-116 | Slots of a waiting renewal are stored at once | Not in setup.md; no test | B | State it in setup.md §5; add a setup test with a renewal that starts later | S |
| setup-117 | An app-DISABLED connection is a conflict | Not in setup.md; no test for the DISABLED case | B | State the "changed in the app" rule in setup.md §5; add the DISABLED case to `SetupIdentityChangesTest` | S |

## S2-7 Design docs and install guides catch up

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| install-monitoring-1 | `every` property | does nothing | B | See monitoring-28 | — |
| install-monitoring-3 | `channel-mail` | false DOWN / wrong port | B | See monitoring-34 | — |
| install-notifications-1 | Separate Flyway run, `notif_schema_history` | not in the guide | B | add the B4 rule (own run, own history table) to section 4 | S |
| xc-independence-1 | platform-core never names or assumes a product | Two root notes name apps and describe one app's schema behaviour; the guard skips them | B | Move their generic content into `docs/install/identity.md` (the identity schema contract) and delete both files | S |
| erase-log-31 | Value-like id is `ERASE_ENTRY_VALUE_REFUSED` | The test accepts either code | C | Pin `ERASE_ENTRY_VALUE_REFUSED` for the e-mail, sealed, fingerprint and ciphertext samples | S |
| erase-log-26 | Only dependency platform-fields, optional | Out of date: platform-errors since P032 | C | Update the design sentence | S |
| erase-log-76 | Replay report holds counts, codes, entry numbers only | Message also has kind, record type and ID, location, handler text | C | Either say so in the design, or move ids and handler text out of `describe()` | S |
| erase-log-DG-1 | Everyday erase compares head only; full walk at startup, daily, prune, replay | Not in the design body | C | Add to the design "Guard" section | S |
| erase-log-DG-2 | One database, one log; move files to move it | Not in the design or install guide | C | Add a line to the design and install section 1 | S |
| erase-log-DG-3 | `auditId` / `by` id form; `recheck` 5 s – 1 h | Code-only | C | Add both to the design entry table and the install property table | S |
| case-fields-DG-1 … DG-14 | P017, P019 decisions (keys and capitals, primary one value, search mode, normalisation order, per-type forms, tab and 50 values, extensions, `change()` rules, import all-or-none, delivery rules and 2 MB, limits in messages, no cause in exceptions, no Spring module, `pg_indexes` detection) | In reports only | C | Add a "Decisions from P017–P019" subsection to `docs/design/case-fields.md` | M |
| install-fields-1 | `f1.` fingerprint texts | Guide names one version only | C | Say `f<version>.` | S |
| install-fields-2 | Backups hold `pci1.` only | `pck1.` envelope not named | C | Name the envelope column too | S |
| install-fields-3 | Import conflict "type differs" | Currency and key-capitals conflicts not named | C | List all three conflicts | S |
| install-fields-4 | Runtime grants | Grants are wider than the check needs | C | Leave the grants as they are; say the check needs fewer | S |
| install-fields-6 | How the app builds `FieldValues` | Missing | C | Add a short "wiring in the app" paragraph | S |
| install-erase-log-1 | `recheck` range | Missing | C | Add "5 seconds to 1 hour" | S |
| install-erase-log-2 | Report entry number | Printed only inside the message | C | Print `stopped at entry N` as its own line in `describe()` | S |
| install-erase-log-3 | Health details | Not listed | C | List the details (and the pending-entry fields once erase-log-22 is fixed) | S |
| install-erase-log-4 | Moving a log | Missing | C | See erase-log-DG-2 | S |
| crypto-1 | No runtime dependencies | Design text out of date (platform-errors since P032) | C | Design section 1: "depends only on platform-errors" | S |
| crypto-30 | platform-fields contexts | Context form not pinned | C | Fields test: a sealed item copied to another record does not open (`CRYPTO_CIPHERTEXT_INVALID`) | S |
| crypto-42 | `fingerprintAll` one call | Doc says one call; it is two | C | Design section 4: "`ofAll` one call; `fingerprintAll` two (versions + one batch)" | S |
| crypto-62 | Only the stated log lines | No test | C | In `NoSecretInLogsTest`, assert the captured `com.documentplatform.crypto` records are the AppRole DEBUG line only | S |
| crypto-65 | Policy texts in docs = test resources | No guard | C | Test that the design and install guide contain each `.hcl` resource's `path` lines verbatim (as licensing does) | S |
| crypto-77 | `CustomerId.of(tenantId)` in section 8 | Doc names the wrong method | C | Design section 8 step 2: `CustomerId.fromTenantId(tenantId)` | S |
| crypto-82 | `VaultClient` closed on shutdown, AppRole token revoked | No test | C | Spring test: close the context and check `revoke-self` reached a stub | S |
| crypto-88 | One customer per installation in Spring | Not in the design | C | Design section 10: the `CustomerId` bean is the installation's only customer; multi-customer apps derive their own | S |
| crypto-112 | Destroyed keys look like never created | Not stated; fix text suggests running setup | C | Design section 11 + `CRYPTO_KEY_MISSING.fix`: "after a customer exit this is expected; do not re-create" | S |
| crypto-113 | `destroyCustomerKeys` javadoc | Says set-up login | C | Javadoc: "with the set-up and offboard login" | S |
| install-crypto-1 | Set-up env variables | Three variables and the one-login rule missing | C | Add `_NAMESPACE`, `_TRANSIT_MOUNT`, `_APPROLE_MOUNT` and "exactly one login" to section 5 | S |
| install-crypto-2 | `--confirm` | Not mentioned | C | Mention `--confirm TEN-…` for runs without a console | S |
| install-crypto-3 | tenant-id wording | Setup writes the licensing key | C | "setup writes `platform.licensing.tenant-id`, which this falls back to" | S |
| install-crypto-4 | `PLATFORM_CRYPTO_VAULT_SECRET_ID` | Untested binding | C | Spring test binding the secret ID from that environment name, or document `…SECRETID` | S |
| monitoring-71 | Webhook path is a secret, never shown or called | DOC GAP | C | Add to monitoring.md section 1 | S |
| monitoring-72 | `FileLocationCheck.Disk` replaceable | DOC GAP | C | Add to monitoring.md section 1 | S |
| model-governance-108 | P033 D1 | DOC GAP | C | Add to model-governance.md section 9 | S |
| model-governance-109 | P033 D2 | DOC GAP | C | Add to section 9 | S |
| model-governance-110 | P033 D3 | DOC GAP | C | Add to section 9 | S |
| model-governance-111 | P033 D5 | DOC GAP | C | Add to section 9 | S |
| model-governance-112 | P034 report D8 | DOC GAP | C | Add to section 10's Spring line | S |
| install-monitoring-2 | `tick` property | not in the guide | C | Document it (or fold it into `every`) | S |
| install-monitoring-4 | Checks table | `model-governance` missing | C | Add the row | S |
| install-monitoring-5 | Bundle table versions | `mg_` missing | C | See monitoring-58 | — |
| install-monitoring-6 | Folder thresholds | no range; bare exception | C | Give the range in the guide; list the problem with the property name at startup | S |
| install-model-governance-1 | Startup codes | two codes not listed | C | Add `MG_VERSION_UNKNOWN`, `MG_CHECK_FAILED` | S |
| install-model-governance-2 | Runtime grants | message and SQL header contradict the guide; narrow grants untested | C | Make the message and header give the narrow grants; schema test with exactly the guide's grants | S |
| install-model-governance-3 | `enabled` | not in the guide | C | Document it | S |
| install-model-governance-4 | Setting limits | not in the guide | C | Document 1d–3650d and ≥ 1 minute | S |
| integration-module-32 | Test action for API_POLL / WATCHED_FOLDER | Kind not supported in FULL mode | C | Test of intake kinds = health + sign-in (CHECK_ONLY), or document that the Test is health only | S |
| integration-module-5, 33, 35, 41, 44, 49, 63 | Product profile / pinned spec / product contract tests | Removed by P030; doc still says them | C | Rewrite those design lines to "made-up profiles; owner's contract kit" | S |
| integration-module-150d…165 | 16 DOC GAPs (listed above) | Decisions not in the design body | C | Add a short "Decisions carried from the reports" section to integration-module.md | S |
| install-integration-1 | Guide section 7 ignores monitoring-spring | Doc | C | Add: "with platform-monitoring-spring the entries are in `dependencies` (docs/install/monitoring.md)" | S |
| install-integration-3 | `INT_WATCH_CLAIM_LOST` never given | Doc/code | C | Remove it from the guide table (or give it in `WatchResult`) | S |
| licensing-83 | Q5: wiped usage tables show `EXPORT_START_MISMATCH` | Only when the app's export anchor is lost too; untested | C | State in the design that the anchor is the app's; add a test that a wiped ledger exports with `recordCount` 0 against the kept anchor | S |
| licensing-file-format-32 | Check order malformed before unsupported | Format compared before the envelope field set | C | Align the doc's table with the code (format first) | S |
| licensing-file-format-48 | Run log fields | `resetId`, `resetTo`, `notAfter` not in the doc | C | Add them to format section 4 | S |
| licensing-12 | Install licence 30–60 days | Superseded by P009-7; code follows | C | Change section 1 to "at most 60 days" | S |
| licensing-33 | Issuing from an input file | Built from options | C | Say "from command options" in section 5 | S |
| licensing-41 | Setup never deletes | Later setup decisions remove grants / switch bindings off on request | C | Point section 6 to setup.md's rules | S |
| licensing-file-format-45 | `"development": true` in the trusted-keys format | Not in section 3 | C | Add the optional field to section 3 | S |
| install-licensing-1 | `product-code` required | Guide does not say required | C | Mark it required in the guide | S |
| install-licensing-2 | `clock-tolerance` at most 1 day | Not in the guide | C | Add the limit | S |
| install-licensing-3 | Licence command options | `--tenant-id`, `--product-code`, `--clock-tolerance` missing; first store accepts any tenant | C | List the options and advise `--tenant-id` on the first store | S |
| install-licence-signing-1 | `ISSUE_VAULT_SEALED` covers standby | Standby gives `ISSUE_VAULT_ERROR` | C | Reword the guide row (sealed: 503; standby: use the active node) | S |
| install-licence-signing-2 | Vault mount options | `--vault-mount`, `--vault-login-mount` not in the guide | C | Add them with defaults | S |
| install-licence-signing-3 | Vault TLS / sealed / error codes | Untested | C | Tests with a stub Vault: untrusted certificate, 503, 500 | S |
| notifications-85 | Own daemon threads, no `@EnableScheduling` | not asserted | C | test: context without `@EnableScheduling` runs the tick; worker threads are daemon | S |
| notifications-157..162 | P015 report decisions 1–4, 8–11 | not in design body | C | add a "Decisions made in P015" list to design section 5 | S |
| notifications-167 | `validFor` 5 min – 30 days | undocumented, bounds untested | C | state it in section 6; add a bounds test | S |
| notifications-168 | Approval delivery codes | only in the install guide | C | list the `ACTION_*` codes in section 6 | S |
| install-notifications-2 | `CHECK_FAILED` startup message | not in the table | C | add the row "Notifications tables could not be checked" | S |
| install-notifications-4 | Proxy for the Microsoft 365 token client | not stated | C | say whether `https.proxyHost` applies to `login.microsoftonline.com` too | S |
| install-notifications-5 | `teams.<id>` wording | ambiguous | C | "key = destination id, value = path of the secret" | S |
| xc-names-1 | No product names in docs | `docs/design/notifications.md` sections 1 and 3 name the two apps; the guard does not scan `docs/design/` | C | replace with "the apps" or a neutral label; decide whether the guard should cover `docs/design/` | S |
| setup-1 | No "tenant" in user-facing text | No test; two texts say tenant | C | Reword the two texts; add the word to a text check over the setup and identity code files | S |
| install-setup-1 | Refusal code list | Two time-zone codes missing from §5 | C | Add them to the §5 list | S |
| install-setup-3 | Sign-in fields | §2 says unshown fields refuse, but three optional fields are accepted | C | Show the optional fields in the §2 example or reword the sentence | S |
| install-setup-4 | Profile check order | "before anything else" is not exact | C | Reword to "before the database is touched" | S |
| install-identity-1 | Connection refusals | `IDN_TEST_FAILED` not listed | C | Add it to the §6 list | S |
| xc-javadoc-setup-1 | `ProductRoles` class comment | Says platform roles "are never granted through a mapping file"; since P028 they are, on `*` rows | C | Correct the comment | S |
| xc-independence-2 | The guard covers every text file a reader or app sees | The guard skips `*.md` outside CLAUDE.md, README and `docs/install/`, and skips `.csv`, `.hcl`, `.sh`, `.ps1` | C | Check every text file outside `docs/work/` and `docs/design/`, with an allow-list of binary types; a test plants a name in each kind | S |

## S2-8 Public API: hide internals

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| xc-public-api-1 | B2.6: public API is a deliberate surface | No module has `module-info.java`, an `internal` package convention, an `@Internal` marker or a guard test. All 550 public top-level types are equally "API" to the apps, and so are the public members of the about 60 internal candidates listed above. | C | Decide one policy before the integration release: either `module-info.java` with `exports` per module, or `*.internal` packages plus a guard test (like `NoProductNamesGuardTest`) that fails on new public types outside the documented list. | M |
| xc-public-api-4 | Never expose secrets outside the call | `com.documentplatform.integration.signin.SignInHeaders` is public. `prepare(...)` returns `Prepared.headers()` with resolved secret header values as plain `String`s. It is used only by `engine`. | C | Make `SignInHeaders` package-private inside `engine` (move it), or at least make `Prepared` non-public. | S |
| xc-public-api-5 | licensing: the issuing command is "our side only" | `com.documentplatform.licensing.issue.*` (`IssueLicenceCommand`, `VaultTransitSigner`, `Signer`, `IdGenerator`, `IssuedRecords`), `LicenceSigner` and `Ed25519.generate/encodePrivate/decodePrivate` ship in `platform-licensing`, which the BOM gives to every app. No private key ships with them, so nothing is exposed, but the customer-side jar carries the issuing tool. | C | Split the issuing side into a separate module that is not in the BOM (for example `platform-licensing-issue`). | M |
| xc-public-api-6 | Only intended types public (setup) | `com.documentplatform.setup.{Plan, ProductRoles, SigninFile, SetupKeys, SetupException}` are public, but only their own package uses them. The constructor `SetupCommand(Map, Clock, SetupKeys.Connector)` is a test seam. | C | Make them package-private; keep `SetupCommand.main`, the exit codes and the `SetupRefusal` codes public. | S |
| xc-public-api-7 | One strict JSON, not public per module | There are several JSON helpers. Public: `com.documentplatform.licensing.json.{Json, Fields, JsonException}` (setup uses them), `com.documentplatform.fields.json.{Json, JsonFields, JsonException}`, `com.documentplatform.integration.json.Json` and `com.documentplatform.notifications.webhook.Json`. Package-private: `identity.signin.MiniJson`, `crypto.vault.VaultJson`, `licensing.issue.VaultJson` and `notifications.email.TokenJson`. Apps can depend on any of the public ones, and their parse rules differ. | C | Hide `fields.json` and `notifications.webhook.Json` now (map `JsonException` to the module's own exception); later one shared internal JSON (its own task). | M |
| xc-public-api-8 | Only intended types public (integration) | Same-package helpers are public: `com.documentplatform.integration.credential.CredentialFiles` (returns a `ClientCertificate` with the private key), `config.Classification`, `folder.SlotIds`, `folder.ExportManifest` and `watch.TriggerValues`. | C | Make them package-private; for `Classification`, keep `DEFAULTS` reachable for documentation. | S |
| xc-public-api-9 | Only intended types public (notifications) | `com.documentplatform.notifications.webhook.WebhookClient` (its public `post` takes a caller-chosen address check), `webhook.Json`, `SummaryBucket`, and the public static helpers `TeamsChannel.payload` / `retiredConnector`. | C | Make them package-private, moving `WebhookClient` beside the channels or behind a package-private factory. | S |
| xc-public-api-10 | Only intended types public (licensing) | `com.documentplatform.licensing.{LicenceFormat, SignedDocument, LicenceSigner}`, `usage.UsageTotals` and `issue.{IdGenerator, IssuedRecords}` are used only inside their packages. `ClockGuard` and `ClockReading` are cross-module only (licensing-spring, setup). | C | Package-private where same-package. For `ClockGuard`, mark it as not for apps in the javadoc. | S |
| xc-public-api-12 | crypto: apps use `KeyService`, not raw Vault | `com.documentplatform.crypto.vault.VaultClient.readSecret/writeSecret` give public KV v2 access. `KeyService.destroyCustomerKeys` is callable on the app's `KeyService` bean. Only the Vault policy guards both (the app's policy should not allow deletion). | C | State in `docs/install/crypto.md` that the app's Vault policy must not allow key deletion or KV writes. Optionally make `VaultClient`'s KV methods package-private with a narrow accessor for crypto-spring and setup. | S |
| xc-public-api-13 | erase-log: only `Eraser` / `EraseReplay` move the position or lift the guard | `ErasePosition.Locked.applyAndAdvance` (with any supplier, a no-op included) and `verified(Instant)` are public interface methods, and `PostgresErasePosition` is public. An app could advance past an entry without erasing, or mark a replay verified. | C | Document `ErasePosition` and `EraseLogStore` as store SPI that apps never call. Optionally add a guard test that no app-facing class calls them. | S |
| xc-public-api-14 | Only intended types public (Spring modules) | Wiring types in every Spring module are public: `*Config` / `*Settings` records, `*ConfigException`, `*FailureAnalyzer`, `*HealthIndicator`, `NotificationChannels`, `DestinationHealth`, `SenderSignal`, `ModelGovCheck`, `IntegrationHealthContributor`, `LicensingNotices` and the `MonitoringMetrics` class. Spring can create non-public classes. | C | Make them package-private, keeping auto-configurations, `*Properties`, app-facing beans and the metric-name constants public. | M |
| xc-public-api-15 | No test hooks on production API | These test hooks are public: `UsageRecord.withAmount(long)` ("For tests"; builds a record whose hash does not match), `Ed25519.generate / encodePrivate / decodePrivate` ("tests and key ceremonies"), `AddressGuard(boolean, Resolver)` ("Replaceable for tests") and `WebhookOptions.allowingPlainHttpForTests()` (see gap 3). Errors already does this right: `FailureLog.clear` and `ReferenceId.useClock` are package-private. | C | Move each into the module's testkit, or make it package-private like errors does. | S |
| xc-public-api-16 | identity-core exposes setup's parsers | `com.documentplatform.identity.mapping.{RoleMappingFile, MappingPlan, MappingRow, MappingError, SlotTargets, ClientBindingsFile}` are needed only by platform-setup. `com.documentplatform.identity.mapping.MappingTargets` has no user anywhere and no doc. | C | Mark the mapping package as "for the setup command" in its javadoc. Check whether `MappingTargets` is dead API and remove or document it. | S |
| xc-public-api-17 | Store SPI vs app API not separated | Store interfaces and their row records are public and used by apps, testkits and jdbc modules alike, with no marking of which are "implement" and which are "call". Examples: `identity.store.*`, `notifications.{OutboxStore, OutboxEntry, SummaryStore, SummaryItem, ConditionStore, ConditionState}`, `fields.values.ValueStore`, `eraselog.{EraseLogStore, ErasePosition}`, `integration.store.IntegrationStore`, `licensing.usage.UsageLedger`. | C | Add one line to each store interface ("SPI: implemented by stores; apps call `<Service>`") and list the SPI types in each install guide. | S |
| xc-public-api-18 | Secret port is in the notifications package | `com.documentplatform.notifications.secret.{SecretRef, SecretResolver, Secret}` are the platform-wide secret port: outbound, integration and monitoring depend on platform-notifications for them, and apps implement `SecretResolver`. | C | Record it as a known wart. Moving them is a package rename and therefore its own task (CLAUDE.md "Keep Java package names stable"). | L |

## S2-9 Polish after the release

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| erase-log-39 | fsync before `append` returns | Not provable by a unit test | C | Note it as a code-review item; optionally a test store that counts `force` calls | S |
| erase-log-41 | Windows line ends accepted | Untested | C | Rewrite the log with CRLF in `FileEraseLogStoreTest`, verify intact | S |
| erase-log-46 | Newest entries read from the end | Untested | C | Test with a large log that `head()` and `after(head-1)` read only the tail (or time-bound) | S |
| erase-log-34 | Hash formula | No known-answer vector | C | Add a fixed entry with its expected SHA-256 hex | S |
| erase-log-35 | Inserted entry breaks the chain | Untested | C | Insert a forged line between two entries → `ERASE_LOG_CHAIN_BROKEN` | S |
| erase-log-87 | `backup-retention-days` default 35 (Spring) | Default unasserted | C | Assert the health detail 35 with no property set | S |
| erase-log-88 | `recheck` default 1 minute | Default unasserted | C | Assert `EraseLogConfig.recheck()` is 1 minute with no property set | S |
| erase-log-89 | `exit-after-replay` default true; false does not exit | Neither tested | C | Wiring test: default runner exits; with `false` it does not | S |
| case-fields-60 | Record type / ID form refused `VALUES_RECORD_REF_INVALID` | Untested | C | Add bad record type and ID cases to `PostgresValueStoreTest` | S |
| case-fields-81 | Schema step goes on without `pg_trgm` | Untested | C | Run V1 on a database where the extension cannot be created (for example a role without CREATE) and check the schema verifies | M |
| case-fields-99 | Switching back on does not make a field primary again | Untested | C | Assert `assertFalse(on.spec().primary())` in `removingSwitchesOffAndNothingIsDeleted` | S |
| case-fields-105 | Erase retries when the record changed meanwhile | Untested | C | Test store that reports one `VALUES_CHANGED_MEANWHILE`, then succeeds | S |
| crypto-14 | New data key per value | Test cannot tell a reused data key | C | Assert the wrapped keys of two seals differ, or count `newDataKey` calls per seal | S |
| crypto-15 | Data key wiped after use | Only `DataKey.close` proven | C | Test through a recording key service that keeps the returned arrays and checks they are zeroed after `open` / `decrypt` | S |
| crypto-26 | One call per seal | `seal` not counted | C | Add a call count around `seal` in `outagesHaveTheirCodes` | S |
| crypto-32 | `RecordKey.close` wipes, closed key unusable | No test | C | Test: after `close`, `seal` / `open` throw `IllegalStateException` | S |
| crypto-34 | Random nonce per item | No test | C | Assert two seals of the same item value differ | S |
| crypto-37 | Chunks written only once authenticated | No test | C | Damage chunk 2 of 3: assert the output holds exactly chunk 1 | S |
| crypto-38 | `EnvelopeInfo.ciphertextSha256` correct | Never compared | C | Assert it equals the SHA-256 of the encrypted bytes in `streamsRoundTrip` | S |
| crypto-45 | KeyService method list | `fingerprints`, `destroyCustomerKeys` missing from section 5 | C | Add both and a Vault mapping row for destroy | S |
| crypto-48 | `CRYPTO_KEY_SERVICE_TLS` | No test | C | Test against a local TLS server with an untrusted certificate | S |
| crypto-49 | `CRYPTO_KEY_SERVICE_SEALED` | No test | C | Local stub answering 503 to a call and to `sys/health` | S |
| crypto-54 | `CRYPTO_KEY_SERVICE_ERROR` | No test | C | Local stub answering 500 / non-JSON 200 | S |
| crypto-61 | No cause on `CryptoException` | No test | C | Assert `getCause() == null` in an existing failure test | S |
| crypto-71 | Namespace header | No test | C | Stub server records `X-Vault-Namespace` | S |
| crypto-84 | Every bean backs off | Only `KeyService` tested | C | `CryptoWiringTest`: app-defined `ValueCipher` / `RecordCipher` / `StreamCipher` / `Fingerprinter` / `CustomerId` / health indicator win | S |
| crypto-92 | Default destroy refuses | No test | C | Assert `CRYPTO_KEY_SERVICE_ERROR` from a `KeyService` without the override (for example OtherId) | S |
| crypto-96 | Capability check unanswerable | No test | C | Vault test with a token without the default policy (`no_default_policy`): steps run, Vault refuses the delete | S |
| crypto-102 | Default result file name | Untested | C | Run without `--result-out` in a temp working folder (or make the folder injectable) and assert the name | S |
| monitoring-1 | Default limit 10 s, interval 60 s | untested | C | Assert the interface defaults | S |
| monitoring-32 | Licence check from the bean | untested | C | Wiring test with a `LicenceService` bean | S |
| monitoring-33 | Login-system check from beans and `product-code` | untested | C | Wiring test with store, `IssuerFetch` and product code | S |
| monitoring-39 | Readiness includes the app's own readiness state | REFUSING_TRAFFIC branch untested | C | Wiring test publishing `ReadinessState.REFUSING_TRAFFIC` | S |
| monitoring-40 | Spring Boot's own `readinessState` backs off | untested | C | Wiring test with Actuator's availability auto-configuration | S |
| monitoring-48 | `platform.integration.attempts` | untested | C | Wiring test: one call through `CallEngine`, counter incremented with tags | S |
| monitoring-50 | State-change log line | untested; RECOVERED line has no code | C | Capture the log in `MonitoringCoreTest`; add `code=` to the RECOVERED line | S |
| monitoring-54 | Platform versions from released jars | untested | C | Test `platformVersions` against a built jar on a test class loader | S |
| monitoring-55 | Bundle configuration from the app's property sources | source selection untested | C | Wiring test: system property and env absent, app property present | S |
| monitoring-57 | Last 50 failures in the bundle | untested | C | Record 60 failures, assert 50 lines | S |
| monitoring-58 | Schema versions in the bundle | `mg_schema_version` missing | C | Add it to `VERSION_TABLES` and test the list against every module's `VERSION_TABLE` | S |
| monitoring-73 | `model-governance` check | DOC GAP | C | Add a row to monitoring.md's table | S |
| model-governance-70 | Flyway run with `mg_schema_history` | untested | C | Schema test running the files through Flyway with that history table | S |
| model-governance-100 | Monitoring's database check verifies `mg_` | untested | C | Wiring test with a `ModelGovStore` bean and missing `mg_` tables | S |
| model-governance-101 | Worker: notices and daily purge | untested | C | Test `runOnce` with a fixed clock: a notice raised, old readings purged once a day | S |
| integration-module-34 | Live-system tests on request | None | C | Drop the rule (the contract kit covers it) or add an opt-in tag | S |
| integration-module-50 | 408 = busy | Test | C | Add 408 to `classificationDefaults` | S |
| integration-module-59 | Mixed replays = SENT | Test | C | MockSwitchesTest: replay on one call only → SENT, steps show ALREADY_RECEIVED | S |
| integration-module-75 | `INT_FOLDER_WRITE_FAILED` | Test | C | MockFolder generic IO failure switch → OUTAGE with the code | S |
| integration-module-93 | Busy is UP in the contributor | Test | C | IntegrationWiringTest: busy health → UP with outcome BUSY | S |
| integration-module-95 | Retries, cookies, compression off | Test | C | OutboundRulesTest: server sets a cookie / gzip, answer is not decompressed, cookie not sent back | S |
| integration-module-115 | Worker makes timed certificate switches | Test | C | IntegrationWiringTest: NEXT with a past switch time → `runOnce` switches it | S |
| integration-module-119 | Unknown state = UNCLEAR `INT_STATUS_UNKNOWN` | Test | C | AcceptedLaterTest: mock state "ODD" → UNCLEAR with the code | S |
| integration-module-137 | Early stop drops the connection; `INT_POLL_STORAGE_FAILED` | Test | C | PollingTest: unwritable work folder → the code; oversize download closes without reading the rest | S |
| integration-module-163d | Hosted refuses a missing scanner | Deferred | C | Hosted go-live task | S |
| integration-module-163e | ICAP over TLS | Deferred | C | When a customer needs it | M |
| integration-module-7, 39, 56, 85 | Superseded rules (push callers, plumbing reuse, JVM proxy option, `_DISABLED_BY_JVM`) | Code follows the later decision; doc body stale | C | Mark each line as replaced, naming the decision | S |
| integration-module-161d | User-Agent `platform-integration` | Test too | C | OutboundRulesTest asserts the User-Agent header | S |
| integration-profile-format-4 | Bad profile name pattern | Test | C | ProfileValidatorTest: name `Bad Name` → `INT_PROFILE_SCHEMA /name` | S |
| integration-profile-format-6 | Neither steps nor packaging | Test | C | ProfileValidatorTest: remove both → `INT_PROFILE_STEPS` | S |
| integration-profile-format-11 | Profile `limits` | Test | C | ProfileMockTest: completed config carries the profile's limits | S |
| integration-profile-format-22 | `{name?}` misplaced in a profile → `INT_PROFILE_TEMPLATE` | Test | C | ProfileValidatorTest case | S |
| integration-profile-format-26 | Over 2 MiB unreadable | Test | C | ProfileValidatorTest: 2 MiB + 1 file → `INT_PROFILE_UNREADABLE` | S |
| integration-profile-format-31 | Unknown header refused by the contract kit | Test | C | ProfileContractTest: plant a header the spec lacks | S |
| integration-profile-format-35 | Server-check refusal path | Test | C | ProfileContractTest: mock refuses with an unnamed code → problem reported | S |
| integration-standards-5 | Scanning always on for hosted | Deferred | C | Hosted go-live task (same as 163d) | S |
| licensing-82 | Resets stay out of the issued records | Untested | C | Assert the records file is unchanged after `--reset-last-seen` | S |
| licensing-55 | Reset valid 7 days by default | Untested | C | Issue a reset without `--valid-days`; assert `notAfter` = now + 7 days | S |
| licensing-76 | `RST-` IDs checked against all issued IDs | Earlier reset IDs are not checked (resets only in the run log) | C | Also read reset IDs from the run log into the taken set, or note that 80 random bits suffice | S |
| licensing-64 / licensing-file-format-8 | Whitespace is not signed | Untested | C | Test: re-format a signed file (compact and other indent), it still verifies | S |
| licensing-87 | Hourly check | Default 1 h and the 24 h bound untested | C | Wiring test of the default interval and `check-interval=25h` refused | S |
| licensing-90 | Clock behind repeats every 4 hours | Untested | C | Run the notices test 5 hours and expect one repeat | S |
| licensing-file-format-7 | Pretty layout (two spaces, LF, sorted) | Untested | C | Assert `Json.pretty` output of a small object exactly | S |
| licensing-file-format-9 | Strict date and time forms | Untested | C | Test non-canonical dates/times in a signed payload give `LICENCE_MALFORMED` | S |
| licensing-file-format-12 | `LIC-` / `TEN-` / `ENT-` ID shape | Reader accepts any ID shape | C | Either state that readers accept any ID shape, or check the prefix pattern on read | S |
| licensing-file-format-14 | Customer 1–200 printable characters | DEL and other non-printables accepted; untested | C | Refuse `Character.isISOControl` and test | S |
| licensing-23 | Clock rollback as tampering | Superseded by O1; code follows | C | Strike the old sentence (pointer already there) | S |
| notifications-10 | No personal opt-out | absence only | C | accept as structural; no action | S |
| notifications-51 / -72 | A2 / B1 XOAUTH2 "still open" | doc stale after P015; Google mail OAuth not built and not stated | C | edit A2 / B1 to "Microsoft 365 decided and built in P015; Google not supported" | S |
| notifications-54 | JVM proxy settings used | no test through a proxy; token client's proxy behaviour unstated | C | test webhook send through `MockProxy`-like CONNECT proxy via JVM properties; state token-client proxy rule | S |
| notifications-62 | Sender defaults | defaults `sender.batch-size` 100 and `sender.poll-interval` 10 s not asserted | C | add two asserts to `PropertyValidationTest.aCompleteConfigIsAcceptedWithDefaults` | S |
| notifications-70 | Removed-type summary dropped silently | untested | C | test with notifications-3 | S |
| notifications-87 | Shutdown waits 10 s | untested | C | test `NotificationWorkers.stop` with a slow channel finishing within 10 s | S |
| notifications-90 | `SecretResolver` only when a channel is configured | untested both ways | C | wiring test: no channels and no resolver starts; a channel without one names the bean | S |
| notifications-91 | Suite passes on Boot 3.5.x | not in CI | C | CI matrix job with `-Dspring.boot.version=3.5.x` for the `-spring` modules | M |
| notifications-101 | Certificate rotation by replacing the secret | untested | C | test: change the credential secret after a token expires; next fetch uses the new certificate | S |
| notifications-130 | Approval request: event on/off and destinations still apply | untested | C | test: request event switched off gives DISABLED; chosen Teams destination only | S |
| notifications-135 | One approver's temporary failure re-sends to all | untested | C | test: one recipient refused temporarily, all get it again under the same per-person keys | S |
| notifications-144 | "Expired" (and the other three) events not continuous | not-continuous refusal untested | C | add a continuous event case to `registrationIsChecked` | S |
| notifications-146 | Told request stays undecided | check constraint untested | C | add an UPDATE on a told request to `theTableRefusesWhatTheRulesForbid` | S |
| notifications-147 | Approval housekeeping failure logged once | untested | C | test with a failing store: one warning, then one "cleared" line | S |
| notifications-149 | `EntityZones` as a Spring bean | wiring untested | C | add an `EntityZones` bean case to `IdentityZoneWiringTest` | S |
| notifications-163..166 | P016 report 8 (part), 9 (part), 13; P017 report 14 (part) | not in design body | C | add the four lines to the P016 / P017 decision lists | S |
| setup-19 | Hub mode | Deferred by design | C | None now; hub-mode task after the proof of concept | L |
| setup-28 | Organization claim in the lookup key | Deferred by design | C | None now; hub-mode task | M |
| setup-48 | Grants under an older issuer listed as "not in the file" | Deferred by owner answer | C | None now; hub-mode task | S |
| setup-65 | Client IDs compared case-sensitive | No test | C | Add a two-client-IDs-differing-in-case line to `ClientBindingsFileTest` and a `PushCallerTest` lookup | S |
| setup-81 | Admission interfaces equal to the earlier library | No compatibility test | C | None needed beyond the release notes; optionally a signature test of the three types | S |
| setup-100 | Push refusals neutral to the caller | No test | C | Test `PushCallerResult.NEUTRAL` holds no code or cause; say in setup.md §8 that the app sends it | S |
| setup-112 | `--keys`, `--product-roles` required | Not in setup.md; no test | C | State in setup.md §5; add a missing-option case to `wrongOptionsAndHubModeAreRefused` | S |
| setup-113 | DB settings from the environment only, SQL state only | Not in setup.md | C | State in setup.md §5 | S |
| setup-114 | Flagged items do not block the rest | Not in setup.md | C | State in setup.md §5 | S |
| setup-118 | Licensing stores join setup's transaction | Not in setup.md | C | State in setup.md §5 | S |
| setup-119 | Config fragment rules | Not in setup.md; `NO_KEY` path untested | C | State in setup.md §5; add a test with an existing fragment without the key | S |
| setup-120 | Format names and canonical role spelling | Not in setup.md | C | State in setup.md §5 | S |
| setup-121 | Sign-in issuer and unknown-field rule | Not in setup.md | C | State in setup.md §5 | S |
| setup-122 | Demo licence perpetual NON_PRODUCTION, key kept nowhere | Not in setup.md | C | State in setup.md §2 item 8 | S |
| setup-123 | No run log for unparseable options | Not in setup.md; no test | C | State in setup.md §5; assert no log file in `wrongOptionsAndHubModeAreRefused` | S |
| setup-124 | The customer-keys step | Not in setup.md | C | Add a "customer keys" paragraph to setup.md (step, output line, `keys=`, order before commit, refusal before writes) | S |
| setup-125 | Two customer zones | Not in setup.md | C | Add one line to setup.md "Time zones" | S |
| setup-126 | Valid zone names; unknown stored name reads as none | Not in setup.md; last part untested | C | State in setup.md "Time zones"; add a read test with an unknown stored name | S |
| setup-127 | Client bound under two matching customers refused | Not in setup.md; no test | C | State in setup.md §8; add a two-customer case to `PushCallerTest` | S |
| setup-128 | Platform grants from two customers refused | Not in setup.md; no test | C | State in setup.md §8; add a two-customer case to `PlatformGrantsTest` | S |
| setup-129 | `*` rows only in setup files | Not in setup.md | C | State in setup.md §8 | S |
| setup-130 | When the binding checks run | Not in setup.md | C | State in setup.md 6.1 | S |
| setup-131 | Integration profiles in the sign-in file | Not in setup.md | C | Add a "profiles" paragraph to setup.md (fields, tag rule, codes, stored nowhere) | S |
| setup-132 | `discardDraft` | Not in setup.md; no Postgres test | C | State in setup.md §7; add discard to `LoginConnectionDraftsTest` | S |
| setup-133 | Rollback target rule | Not in setup.md | C | State in setup.md §7 | S |
| setup-134 | Run-log field order | Not in setup.md; no test | C | State in setup.md; assert the order in one setup test | S |
| xc-strict-1 | DONE needs a test | Some rules counted DONE are held by construction only (licence check never enforces; monitoring stores nothing in the database; liveness never depends on checks; erase-log health details hold no values; structural rules proven by the build) | C | Add one assertion test each where a behaviour (not a structure) is meant | S |

