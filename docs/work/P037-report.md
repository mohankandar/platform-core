# P037 report — every failure is a PlatformCode; never-leak and safety tests; schema rights

Date: 2026-11-23
Branch: `fix/codes-leaks-schemas` (then `docs/p037-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs (green) | Merged |
|---|---|---|---|
| Codes, tests, schema V3, docs | #34 | 45213355100, 45213365559 | 34 commits, `59178ca` … `c3fa330` |
| This report and the row marks | this PR | | |

The merge used the README's stop-on-failed-check line. No check failed or was re-run.

`mvn verify` (with the catalogue regenerated): **1339 tests, 0 failures, 13 skipped.** The skips were there before:
the EICAR-on-disk tests and two identity-spring tests.

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 20 | platform-integration | 55 |
| platform-crypto-spring | 5 | platform-integration-spring | 9 |
| platform-crypto-testkit | 16 | platform-integration-testkit | 204 (11 skipped) |
| platform-erase-log | 18 | platform-licensing | 147 |
| platform-erase-log-spring | 6 | platform-licensing-spring | 35 |
| platform-erase-log-testkit | 9 | platform-licensing-testkit | 47 |
| platform-error-catalogue | 9 | platform-model-governance-spring | 6 |
| platform-errors | 9 | platform-model-governance-testkit | 67 |
| platform-fields | 93 | platform-monitoring-spring | 6 |
| platform-fields-testkit | 20 | platform-monitoring-testkit | 17 |
| platform-identity-core | 120 | platform-notifications | 82 |
| platform-identity-jdbc | 66 | platform-notifications-spring | 42 |
| platform-identity-spring | 30 (2 skipped) | platform-notifications-testkit | 134 |
| platform-identity-testkit | 14 | platform-outbound | 9 |
| platform-setup | 40 | platform-outbound-spring | 4 |

## Independence

**No other product's name or behaviour in code.** The new tests use `PRODUCT_A`, the testkits' made-up tenants, and
hosts under `example` / `corp.example`.

## What was built

### New codes (20; no code renamed or removed; catalogue 648 → 668 entries)

| Code | Enum (module) | For |
|---|---|---|
| `OUTBOUND_SETTINGS_INVALID` | `OutboundSettingsCode` (outbound-spring) | start-up settings, missing beans |
| `INTEGRATION_SETTINGS_INVALID` | `IntegrationSettingsCode` (integration-spring) | same |
| `IDENTITY_PUSH_SETTINGS_INVALID` | `IdentitySettingsCode` (identity-spring) | same, new `PushCallerConfigException` and `IdentityFailureAnalyzer` |
| `LIC_SETTINGS_INVALID` | `LicensingSettingsCode` (licensing-spring) | same |
| `NOTIF_SETTINGS_INVALID` | `NotificationsSettingsCode` (notifications-spring) | same |
| `MOD_SETTINGS_INVALID` | `ModelGovSettingsCode` (model-governance-spring) | same, and the missing DataSource |
| `MON_SETTINGS_INVALID` | `MonitoringSettingsCode` (monitoring-spring) | new `MonitoringConfigException`: every problem listed |
| `FLD_STORE_FAILED`, `IDN_STORE_FAILED`, `LIC_STORE_FAILED`, `NOTIF_STORE_FAILED`, `MG_STORE_FAILED` | `*StoreCode` in each jdbc package | store and read failures (operation and SQL state) |
| `INT_CONFIG_UNREADABLE` | `IntegrationCode` | `ConfigJson.Unreadable` |
| `ERASE_HANDLER_ERROR` | `EraseLogError` | was a string code |
| `FLD_PARTIAL_SEARCH_UNAVAILABLE` (INFO) | `FieldValuesError` | was a string code; the name kept |
| `FIELD_VALUES_CHECK_FAILED` | `FieldDefinitionException.Code` | the app's values check failed during a locked change |
| `MON_DB_USER_TOO_POWERFUL` (INFO, HAVING_PROBLEMS) | `MonitoringCode` | the app's login owns tables or may create objects |
| `TYPE_DIFFERS`, `CURRENCY_DIFFERS`, `KEY_CASE_DIFFERS` | `ImportReport.Conflict` (now a `PlatformCode`) | field-list import conflicts; the names kept |

The crypto and erase-log settings exceptions now carry their existing `CRYPTO_SETTINGS_INVALID` and
`ERASE_LOG_SETTINGS_INVALID`.

### The two unsafe public entry points (S2-3)

- **`ValueStore.delete` (xc-public-api-2): no longer usable around the erase log.** It now takes an `EraseTicket`.
  `EraseTicket`'s constructor is package-private, so only `FieldValues` can make one, and it does so only during an
  erasure. Both built stores refuse a missing ticket, or one for another record, with `VALUES_ERASE_OUTSIDE_LOG`, and
  `ValueStore` is documented as an SPI that apps never call. I chose a capability over hiding the interface: the app
  builds the Postgres store itself, so the type must stay public.
- **`WebhookOptions.allowingPlainHttpForTests` (xc-public-api-3): guarded, not moved.** With the switch on, plain
  http is allowed only to localhost, 127.0.0.1 and [::1]; any other host stays https only, so production code cannot
  use the switch to send a token-bearing address unencrypted. I didn't move it to the testkit because the
  notifications module's own tests need it, and a testkit cannot reach a non-public switch.

### Schema (S2-4)

**Model-governance schema version 3** (`V3__modelgov_purge_guard.sql`):
- The guard allows a delete from `mg_measure` only when the table's owner runs it.
- That happens only inside `mg_purge_measures(timestamptz)`: SECURITY DEFINER, revoked from PUBLIC. It removes
  readings reported before the earlier of the time it is given and 30 days before the database's time.
- The runtime user needs EXECUTE on the function, and no longer DELETE on `mg_measure`.
- The startup check asks for the function (`MG_TABLES_INCOMPLETE`) and the right (`MG_PRIVILEGES_MISSING`).
- The install guide and the design doc say so.

## Tests asked for → where (row → result)

| Row | Result | Main commit | Test |
|---|---|---|---|
| xc-catalogue-1 (FLD_PARTIAL) / -15 | FIXED | `00560bc` | `CatalogueTest` (in the catalogue); `PostgresValueStoreTest` (code name) |
| xc-catalogue-2 (ERASE_HANDLER_ERROR) / -13 / -14 | FIXED | `59178ca` | `RestoreAndReplayTest` (handler code), `CatalogueTest` |
| xc-catalogue-3 (ERASE_LOG prefix) / -7 / -8 / xc-catalogue-2 (notifications) / -4 … -12, monitoring-69, install-integration-2 | FIXED | `2a21e26` | `CatalogueTest.everySpringModulesStartupRefusalCarriesItsSettingsCode`, `MonitoringWiringTest.badSettingsAreRefusedWithTheirCodeAndEveryProblemListed`, `PropertyValidationTest` |
| xc-catalogue-18 | FIXED (17 exceptions, 2 more than P035 found) | `59178ca` | `CatalogueTest.everyExceptionThatCarriesACodeImplementsHasPlatformCode` |
| xc-catalogue-19 | FIXED | `00560bc` | `CatalogueTest.everyStoreFailureCarriesItsCode` |
| xc-catalogue-3 (every module) / error-codes-9 | FIXED (model-governance-spring added) | `59178ca` | `CatalogueTest.everyPlatformModuleIsOnTheCataloguesClasspath`; enums with a texts file count as code enums |
| setup-111 / xc-codes-setup-1 | FIXED | `77e6652` | `SetupCommandTest` (Vault settings test); `ErrorsTest.theCatalogueCheckRefusesMissingTextsGapsSlotsAndInternals` |
| xc-catalogue-1 (`%s`) | FIXED (`{channel}`) | `77e6652` | catalogue gap check |
| crypto-114 / crypto-115 | FIXED | `77e6652` | catalogue regenerated |
| error-codes-4 | FIXED (`docs/errors/code-names.txt`, append only) | `77e6652` | `CatalogueTest.noCodeNameEverDisappears` |
| error-codes-6 / -13 / -18 / -19 | FIXED | `77e6652` | `ErrorsTest.theCatalogueCheckRefusesMissingTextsGapsSlotsAndInternals`, `aCodeNameInTwoModulesIsRefused` |
| error-codes-15 | FIXED | `77e6652` | `LicenceVerifierTest.everyRefusalsFixIsItsAction` |
| error-codes-22 | FIXED (`-am` in the header) | `77e6652` | catalogue regenerated |
| error-codes-27 | FIXED | `77e6652` | `MonitoringWiringTest.theRequestFilterEchoes…` (the failure's reference) |
| error-codes-33 / -35 | FIXED | `77e6652` | `ErrorsTest.theFailureLogWritesOneLineAndKeepsTheLastFailures` |
| error-codes-44, crypto-111, licensing-93 … -105 | FIXED (docs) | `c3fa330` | — |
| notifications-3 | FIXED | `77e6652` | `RemovedEventTypesTest` (fails with the guard broken) |
| xc-catalogue-16 | FIXED (documented as reasons) | `77e6652` | — |
| xc-catalogue-17 | FIXED (`Conflict` is a `PlatformCode`, the type name kept) | `77e6652` | `CatalogueTest` |
| xc-catalogue-4 (type change, C) | FIXED | `bf7d99c` | `FieldDefinitionsTest.aFailingValuesCheckRefusesWithItsCodeAndChangesNothing` |
| xc-public-api-11 | FIXED (`CodeTexts` package-private) | `77e6652` | compile |
| xc-catalogue-20 | **NOT DONE** | — | question 1 |
| integration-module-120 | TESTED | `cdedb4f` | `AcceptedLaterTest.aLocationOnAnotherServerIsNeverFollowed` |
| integration-module-134 | TESTED | `fde3d04` | `PollingTest.aNextLinkToAnotherServerStopsThePassWithoutCallingIt` |
| integration-module-157 | TESTED | `6e38f5a` | `ScannerTest.anIcap200WithoutAThreatHeaderIsInfected` |
| integration-module-129 / 155 | TESTED | `7496d86` | `IntakeFileTest`, `WatchedFolderTest.aFileChangedWhileTheAppReadsItGoesToProblem` |
| integration-module-46 | TESTED | `edd4edb` | `SignInTest.aTokenWithoutTheRequiredClaimsIsNeverUsed` |
| integration-module-153d, xc-never-leak-75 | TESTED | `cb60d3a` | `CallEngineTest.aSensitiveFieldIsRefusedAtSendTimeWhereTheConfigurationDoesNotAllowIt` |
| xc-never-leak-23 | FIXED (`AuthenticatedIdentity.toString`) | `cb30810` | `CoreRecordsTest.authenticatedIdentityToStringShowsNoClaimsEmailNameOrGroups` |
| xc-never-leak-48 | TESTED | `51712a3` | `LicenceCommandTest.noPasswordInAnyOutput` |
| xc-never-leak-5 | TESTED | `4f8a292` | `BuiltInChecksTest.httpServiceNeverShowsUserInfoOrQueryOfTheAddress` |
| xc-public-api-2 | FIXED | `7734282` | `FieldValuesInMemoryTest.aStoreDeletesNothingWithoutATicketForThatRecord`, `PostgresValueStoreTest.theStoreDeletesNothingWithoutATicketForThatRecord` |
| xc-public-api-3 | FIXED | `d66654b` | `WebhookChannelsTest.theTestSwitchAllowsPlainHttpToThisMachineOnly` |
| xc-never-leak-19 | TESTED | `4a5a142` | `NoSecretInLogsTest.secretsNeverLeave` |
| xc-never-leak-27 | TESTED | `8e6dff5` | `OidcTestSignInTest.anIssuerAnswerNeverShowsItsBody` |
| xc-never-leak-28 | TESTED | `5adcd13` | `BrokenStoreLogsTest.storeErrorsAndLogLinesNameTheOperationOnly` |
| xc-never-leak-29 | TESTED | `fa23448` | `EntityMembershipUnknownRoleTest.theErrorLineNamesTheEntityButNoGroupRoleOrClaim` |
| xc-never-leak-43 | TESTED | `cccfef4` | `LicensingWiringTest.logLinesShowNoKeysOrSignatures` |
| xc-never-leak-53 | FIXED (setup prints the failure's class only) | `8da5b79` | `SetupCommandTest.aFailureTextCarryingASecretNeverReachesAnyOutput` |
| xc-never-leak-60 | TESTED | `9a8d50b` | `EraseLogWiringTest.healthDetailsAreExactlyTheSafeOnes` |
| xc-never-leak-62 | TESTED | `6567e4b` | `ModelGovWiringTest.workerLogLinesCarryCountsOnly` |
| xc-never-leak-64 | TESTED | `54111fe` | `OutboundRulesTest.aCallAndAnAnswerNeverShowHeadersBodiesOrTheAddress` |
| xc-never-leak-69 | TESTED | `ff887ca` | `ClassificationRulesTest.aDestinationCodeThatIsNotACodeIsNeverRepeated` |
| xc-never-leak-74 / -81 | TESTED | `3d806da` | `NoSecretsInLogsTest` (two methods) |
| xc-never-leak-84 | TESTED | `d8b6cbe` | `IntegrationWorkerTest.aFailingRunLogsTheExceptionsClassNeverItsText` |
| xc-never-leak-85 | FIXED (log `drop=#<hex>` / `item=#<hex>`; decision in integration-module.md) | `c84f79c` | `NoSecretsInLogsTest.dropAndItemIdsAreLoggedAsReferencesOnly` |
| model-governance-84 | FIXED (schema V3) | `0b08363` | `ModelGovSchemaTest.onlyTheOwnersPurgeRemovesReadingsAndNeverOneYoungerThan30Days` |
| xc-schemas-2 / -3 / -4 | TESTED | `f273af7` | `theRuntimeUserCannotRunDdl` in `PostgresStoresTest`, `StartupSchemaCheckTest`, `FieldSchemaTest` |
| xc-schemas-6 / -10 | TESTED | `7def3cf` | `EraseLogSchemaTest`; `CatalogueTest.everySchemaCheckGivesCheckFailedWhenTheDatabaseFails` (all 7) |
| xc-schemas-7 | TESTED | `7def3cf` | `PostgresIntegrationStoreTest` (unknown version, incomplete, CHECK_FAILED) |
| xc-schemas-9 | FIXED (monitoring warning) | `7def3cf` | `BuiltInChecksTest.database` (owner: warning; DML-only login: WORKING) |
| xc-schemas-11 / -12 | FIXED (guides) | `03f7138` | — |

**Each new test fails without its rule.** Where the code was already right, the rule was broken on purpose and the
test was seen to fail. Examples:
- the removed-type guard in `ConditionTracker`;
- the DDL probe run as the owner;
- each never-leak `toString` or log line made to print its value.

The P037 commits record which check was broken for each row. For the code fixes, the new test failed against the
old code.

## Files changed, commits

- 34 commits on main, `59178ca` … `c3fa330`, about 120 files. Two parallel agents wrote the S2-3 test rows in
  separate worktrees, and I reviewed and cherry-picked them unchanged: 11 never-leak rows and 13 integration rows.
- New:
  - the seven `*SettingsCode` enums and five `*StoreCode` enums with their texts;
  - `PushCallerConfigException`, `IdentityFailureAnalyzer`, `MonitoringConfigException`;
  - `EraseTicket`;
  - `intake.LogRef`;
  - `V3__modelgov_purge_guard.sql`;
  - `docs/errors/code-names.txt`;
  - the tests named above.
- Docs:
  - `CLAUDE.md` (modelgov, monitoring, fields and catalogue lines);
  - `docs/design/` `error-codes.md`, `crypto.md`, `licensing.md` (P010 and P011 decisions),
    `model-governance.md` (Fix P037), `integration-module.md` (intake IDs in log lines);
  - `docs/install/` `model-governance.md`, `notifications.md`, `integration.md`, `identity.md`;
  - the regenerated catalogue.
- Row marks: `gaps.md` (80 FIXED, 1 NOT DONE) and the tick-off files, in this PR.

## Decisions I had to make

1. **One `*_SETTINGS_INVALID` code per Spring module covers both its settings problems and its missing beans**, as
   the gaps' fix proposed. The message lists every problem, each naming the setting or bean.
2. **Store codes are in a small enum per jdbc package** (`*StoreCode`). The schema `Problem` enums are about the
   schema, so they were the wrong home.
3. **Messages now start with their code** (for example `LIC_SETTINGS_INVALID: Licensing configuration …`). Tests
   that compared the old texts were updated. An app that compares these texts would need to do the same. Apps should
   read `platformCode()` instead.
4. **The purge floor clamps rather than refuses.** The database removes only readings older than 30 days of its own
   time, whatever the app asks. If the app's clock is a little ahead of the database's, nothing fails.
5. **Behaviour changes:**
   - A failing values check now refuses with `FIELD_VALUES_CHECK_FAILED` (before, the app's exception passed
     through).
   - A key-service plug-in's unchecked failure in setup is `SETUP_KEY_SERVICE_FAILED`, exit 2 (before,
     `SETUP_FAILED`, exit 1).
   - `MON_DB_USER_TOO_POWERFUL` is HAVING_PROBLEMS, never DOWN.
6. **xc-schemas-11:** the notifications guide already matches the code, which runs the notifications location in the
   app's own schema run with no `notif_schema_history`. So only the codes were added. The design body's B4 is stale
   and belongs to P040.
7. **`ImportReport.Conflict` and `FLD_PARTIAL_SEARCH_UNAVAILABLE` keep their names** ("no code renamed"). The type
   was not renamed to `ConflictCode`, because that would break the API.

## Not done, and why

- **xc-catalogue-20 (notifications schema codes without a module prefix).** Renaming them is against P032's "no code
  renamed". Adding `NOTIF_` aliases would give one code two names. The catalogue and every log line already say which
  module a code is from. See question 1.

## Questions (numbered, each with a recommendation)

1. **Unprefixed notifications schema codes** (`TABLES_MISSING`, …). Recommendation: keep them as they are; don't add
   aliases. This changes nothing a later task builds.
