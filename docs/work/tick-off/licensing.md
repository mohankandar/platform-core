# Tick-off: licensing.md, licensing-file-format.md

Modules read: `platform-licensing`, `platform-licensing-testkit`, `platform-licensing-spring` (plus the places in
`platform-setup` and `platform-identity-spring` that use them). Install guides: `docs/install/licensing.md`,
`docs/install/licence-signing.md`. DOC GAP rows come from the Decisions sections of `docs/work/P009-report.md`,
`P010-report.md`, `P011-report.md` and `P031-report.md`. The other reports that mention licensing (P013, P014, P017,
P018, P019, P029, P032) record identity, setup, fields or crypto decisions, which belong to those docs' tick-offs.

Paths are shortened: `lic/` = `platform-licensing/src/main/java/com/documentplatform/licensing/`,
`lic-t/` = `platform-licensing/src/test/java/com/documentplatform/licensing/`,
`kit-t/` = `platform-licensing-testkit/src/test/java/com/documentplatform/licensing/`,
`spr/` = `platform-licensing-spring/src/main/java/com/documentplatform/licensing/spring/`,
`spr-t/` = `platform-licensing-spring/src/test/java/com/documentplatform/licensing/spring/`.

Counts:
- licensing.md: DONE 74 · UNTESTED 6 · PARTLY 4 · MISSING 0 · DIFFERENT 4 · APP-SIDE 4 · DOC GAP 13 (105 rules)
- licensing-file-format.md: DONE 58 · UNTESTED 8 · PARTLY 3 · MISSING 0 · DIFFERENT 3 · APP-SIDE 0 · DOC GAP 1 (73 rules)
- Install guides: licensing.md 3 differences (32 other items match); licence-signing.md 3 differences (30 match)
- Gaps: A 1 · B 8 · C 25

## licensing.md — section 1 The licence

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-1 | One signed licence per customer (tenant), listing products and entity slots, each slot with its own ID and allowances | DONE | `lic/Licence.java`, `lic/EntitySlot.java` | `lic-t/LicenceVerifierTest.java` `aSignedLicenceVerifiesAndReadsBackEqual`; `lic-t/issue/IssueLicenceCommandTest.java` `issuesASignedLicenceThatVerifies` | |
| licensing-2 | Contents: customer, tenant ID, products, slots, kind, start and expiry, deployment, key ID | DONE | `lic/LicenceFormat.java` `payload` / `fromPayload` | `lic-t/LicenceVerifierTest.java` `aSignedLicenceVerifiesAndReadsBackEqual` | |
| licensing-3 | Products are product codes, never app names hard-coded in shared logic | DONE | `lic/Licence.java` `requireProductCode` | `lic-t/LicenceModelTest.java` `slotAndAllowanceRules` | The name guard covers the rest |
| licensing-4 | Allowance types: pages per month, storage, concurrent seats, named seats | DONE | `lic/AllowanceType.java` | `lic-t/LicenceModelTest.java` `allFourAllowanceTypesHaveAUnit` | |
| licensing-5 | Signing is Ed25519 | DONE | `lic/Ed25519.java`, `lic/SignedDocument.java` | `lic-t/LicenceVerifierTest.java` `forgedWhenSignedByAnotherKeyUnderOurKeyId` | |
| licensing-6 | Private key only in the secret store, never in the repo, never exported | DONE | `lic/issue/VaultTransitSigner.java` (exportable / plaintext-backup keys refused) | `lic-t/issue/VaultTransitSignerTest.java` `keysThatCouldLeaveVaultOrAreNotEd25519AreRefused`, `thePolicyAllowsSigningAndReadingPublicKeysOnly` | |
| licensing-7 | Apps ship public keys only | DONE | `lic/TrustedKeys.java`, `spr/LicensingConfig.java` | `spr-t/LicensingWiringTest.java` `theShippedTrustedKeysFileIsReadAndTheTenantIdIsRequired` | |
| licensing-8 | Each licence names its key ID, so keys rotate without breaking old licences | DONE | `lic/SignedDocument.java` `open` (key by `keyId`) | `lic-t/LicenceVerifierTest.java` `keyRotationOldLicencesKeepVerifying`; `lic-t/issue/VaultTransitSignerTest.java` `afterARotationNewLicencesUseTheNewVersionAndOldOnesStillVerify` | |
| licensing-9 | Slot IDs are pre-issued by us, unique across all customers | DONE | `lic/issue/IdGenerator.java` `next` | `lic-t/issue/IssueLicenceCommandTest.java` `idsAreUniqueAcrossManyCustomers`, `aCollidingIdIsDrawnAgain` | |
| licensing-10 | The Business Admin activates and names a slot in the app | APP-SIDE | | | App screen over identity's `SlotActivation` / `LicenceSlots` (identity tick-off) |
| licensing-11 | Adding entities or changing allowances is a new licence version | DONE | `lic/issue/IssueLicenceCommand.java` (`--tenant-id`, version + 1, `--slot ID@…`) | `lic-t/issue/IssueLicenceCommandTest.java` `aNewVersionKeepsTheTenantAndChosenSlotsAndAddsNewOnes` | |
| licensing-12 | Install licence is short: 30–60 days | DIFFERENT | `lic/issue/IssueLicenceCommand.java` `MAX_INSTALL_DAYS` | `lic-t/issue/IssueLicenceCommandTest.java` `anInstallLicenceRunsAtMostSixtyDays` | Changed by P009 decision 7 (at most 60, no minimum); the code follows the later decision |
| licensing-13 | Usage under an install licence is recorded but not billable | DONE | `lic/LicenceKind.java` `usageBillable`, `lic/usage/UsageRecorder.java` `record` | `kit-t/UsageRecorderTest.java` `billableOnlyForProductionUsageNotMarkedFree` | |

## licensing.md — section 2 Where the licence lives

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-14 | The setup or licence command stores the signed licence in the database; the app reads it from there | DONE | `lic/command/LicenceCommand.java`, `lic/jdbc/PostgresLicenceStore.java` | `lic-t/command/LicenceCommandTest.java` `storeFirstThenAgainIsIdempotent`; `spr-t/LicensingWiringTest.java` `aDmlOnlyUserRunsEverything` | Setup side: setup tick-off |
| licensing-15 | The signature is verified on every read; an edited row is detected | DONE | `lic/LicenceService.java` `status` | `kit-t/LicenceServiceTest.java` `aTamperedStoredRowIsRefusedOnRead`, `aTamperedWaitingRowIsRefusedToo`; `spr-t/LicensingWiringTest.java` `anEditedRowWhileRunningShowsDownInHealth` | |
| licensing-16 | A renewal takes effect without a restart | DONE | `lic/LicenceService.java` `store`, `status` | `kit-t/LicenceServiceTest.java` `aRenewalThatStartsNowReplacesTheCurrentLicence`; `lic-t/command/LicenceCommandTest.java` `anEarlyRenewalWaitsAndThenTakesOver` | |

## licensing.md — section 3 Licence states

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-17 | Valid: the app starts, intake allowed | DONE | `lic/LicenceStatus.java` `stateOn`, `lic/LicenceState.java` | `lic-t/LicenceStatusTest.java` `beforeExpiry` | |
| licensing-18 | Expiring: warnings 60, 30, 7 and 1 days before | DONE | `lic/LicenceStatus.java` `warningDay`, `warningThreshold` | `lic-t/LicenceStatusTest.java` `beforeExpiry`; `spr-t/notifications/LicensingNoticesTest.java` `expiringIsPublishedOncePerBandForTheActivatedSlotOnly` | |
| licensing-19 | Expired in the 30-day grace: the app starts, intake continues, daily critical warnings | DONE | `lic/LicenceStatus.java`, `spr/notifications/LicensingEvents.java` (`LICENCE_IN_GRACE` critical, 24 h repeat) | `lic-t/LicenceStatusTest.java` `afterExpiry`; `spr-t/LicensingWiringTest.java` `aRestartDuringGraceStarts`; `spr-t/notifications/LicensingNoticesTest.java` `graceAlertsOnceAndDailyThenIntakeStoppedTakesOverAndARenewalRecovers` | |
| licensing-20 | Grace over: the app starts, intake stops with a clear message | DONE | `lic/LicenceState.java` `EXPIRED_GRACE_OVER`, `lic/LicenceStatus.java` `message` | `lic-t/LicenceStatusTest.java` `afterExpiry`, `messagesSayWhatHappens`; `spr-t/LicensingWiringTest.java` `aRestartDuringGraceStarts` | Viewing and export are the app's (licensing-24) |
| licensing-21 | Missing, forged, tampered, wrong customer, unknown key ID: the app does not start, specific message and stable code | DONE | `spr/LicenceStartupCheck.java`, `spr/LicensingFailureAnalyzer.java`, `lic/LicenceVerifier.java` | `spr-t/LicensingWiringTest.java` `startupRefusesWhenNoLicenceWasEverStored`, `startupRefusesAForgedLicence`, `startupRefusesATamperedLicence`, `startupRefusesAnotherCustomersLicence`, `startupRefusesAnUnknownKeyId` | |
| licensing-22 | A restart never takes the app down because of expiry | DONE | `spr/LicenceStartupCheck.java` (states only logged) | `spr-t/LicensingWiringTest.java` `aRestartDuringGraceStarts` ("grace over still starts") | |
| licensing-23 | A clock moved backwards beyond a tolerance is reported as tampering | DIFFERENT | `lic/ClockGuard.java` `read` | `lic-t/ClockGuardTest.java` `aClockBehindIsReportedWithTheExactDifferenceAndNeverRefused` | Replaced by owner decision O1; the code follows O1 (never a refusal) |
| licensing-24 | The module reports the state; each app decides what "intake" means and stops it after grace | APP-SIDE | `lic/LicenceService.java` `intakeAllowed` | | The app's intake gate calls `LicenceService.intakeAllowed()` |

## licensing.md — section 4 Usage

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-25 | Never stop at a limit: 80 %, 100 % and over-limit reported, work continues, overage recorded | DONE | `lic/usage/UsageRecorder.java`, `lic/usage/UsageLevel.java` | `kit-t/UsageRecorderTest.java` `levelsAreReportedAndNothingIsEverBlocked`; `lic-t/usage/UsageLevelTest.java` `levels` | |
| licensing-26 | The app records units at its own meter point | APP-SIDE | `lic/LicenceService.java` `recordUsage` | | The app calls `recordUsage` / `usage().record` |
| licensing-27 | Records carry slot, period, amount, billable flag and a hash chained to the previous record | DONE | `lic/usage/UsageChain.java` `link` | `kit-t/UsageLedgerTest.java` `recordsAreChainedFromGenesis`, `aFlippedBillableFlagBreaksTheChain` | |
| licensing-28 | Edits are detected | DONE | `lic/usage/UsageChain.java` `verify` | `kit-t/UsageLedgerTest.java` `anEditedRecordBreaksTheChain`; `lic-t/jdbc/PostgresStoresTest.java` `anEditedRowIsDetected` | |
| licensing-29 | Usage per entity per month can be read | DONE | `lic/usage/UsageLedger.java` `usage`, `lic/usage/UsageRecorder.java` `assess` | `kit-t/UsageLedgerTest.java` `usagePerEntityPerMonthSumsPagesAndTakesThePeakOfReadings` | |
| licensing-30 | Usage file export for any period, on demand, tamper-evident | DONE | `lic/usage/UsageExport.java` `create`, `lic/usage/UsageExportVerifier.java` | `kit-t/UsageExportTest.java` `aFileVerifiesOnItsOwnAndHoldsOnlyUsageFields`, `anEditedRecordIsDetectedAndLocated` | |
| licensing-31 | The export holds no document data and no prices | DONE | `lic/usage/UsageExport.java` `recordLine` | `kit-t/UsageExportTest.java` `aFileVerifiesOnItsOwnAndHoldsOnlyUsageFields` | |
| licensing-32 | Nothing in the software reacts to a late usage file | DONE | (no such code) | — | A "never" with nothing to call; confirmed by reading: no schedule or check on exports in any licensing module |

## licensing.md — section 5 Licence-issuing command

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-33 | Builds and signs a licence from an input file | DIFFERENT | `lic/issue/IssueLicenceCommand.java` | `lic-t/issue/IssueLicenceCommandTest.java` `issuesASignedLicenceThatVerifies` | Built from command options (`--customer`, `--slot` …), no input file |
| licensing-34 | Generates tenant and slot IDs, random, collision-checked against its own issued records | DONE | `lic/issue/IdGenerator.java`, `lic/issue/IssuedRecords.java` `allIds` | `lic-t/issue/IssueLicenceCommandTest.java` `aCollidingIdIsDrawnAgain`, `aRandomSourceThatOnlyRepeatsEndsWithACollisionCode` | |
| licensing-35 | Signing key from the secret store; since P011 the key never leaves it (Vault Transit) | DONE | `lic/issue/VaultTransitSigner.java` `sign` | `lic-t/issue/VaultTransitSignerTest.java` `signsInVaultAndTheLicenceVerifiesWithTheExportedPublicKey` | |
| licensing-36 | A record of every licence issued, with no key material | DONE | `lic/issue/IssuedRecords.java` `append` | `lic-t/issue/IssueLicenceCommandTest.java` `recordsAndRunLogHoldNoKeyMaterialAndNoSignature` | |
| licensing-37 | Every run is logged | DONE | `lic/issue/IssueLicenceCommand.java` (run log) | `lic-t/issue/IssueLicenceCommandTest.java` `recordsAndRunLogHoldNoKeyMaterialAndNoSignature` ("every run is logged") | |
| licensing-38 | Later wrapped by the Platform Console (request and approval) | APP-SIDE | | | Future Platform Console, outside this repo |

## licensing.md — section 6 Setup command

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-39 | Setup reads and stores the licence, creates customer, tenant and slots, loads the sign-in config | DONE | `platform-setup/.../setup/SetupCommand.java` | `platform-setup/.../setup/SetupCommandTest.java` `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing` | Detail in the setup tick-off |
| licensing-40 | Idempotent: never duplicates | DONE | `platform-setup/.../setup/SetupCommand.java` | `SetupCommandTest.java` `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing`, `aRerunAfterAMidRunFailureFinishesWhatWasMissing` | |
| licensing-41 | Setup never deletes | DIFFERENT | `platform-setup` | | Later setup decisions: `--remove-unlisted-grants`, and a removed client-binding line switches the binding off (setup.md); check in the setup tick-off |
| licensing-42 | No inserts at app startup; the schema step stays separate | DONE | `lic/jdbc/LicensingSchema.java` `verify` (read only) | `spr-t/LicensingWiringTest.java` `missingTablesStopTheStartAndNothingIsCreated`; `lic-t/jdbc/PostgresStoresTest.java` `schemaCheckCodes` ("the check created nothing") | |

## licensing.md — section 7 Rules that always apply

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-43 | No generic errors: every refusal has a stable code and an exact cause | DONE | `lic/LicenceRefusal.java`, `lic/ResetRefusal.java`, `lic/usage/UsageError.java`, `lic/issue/IssueError.java`, `ExportCheck.Problem`, `ChainCheck.Problem`, `LicensingSchemaException.Problem` (all `PlatformCode`, texts in `*.codes.properties`) | `lic-t/LicenceVerifierTest.java` `refusalMessagesNeverContainTheSignatureOrKeys` (`CODE: ` prefix); `lic-t/issue/IssueLicenceCommandTest.java` `refusalsHaveSpecificCodes` | |
| licensing-44 | Never log or print key material, full signatures, secrets or document data | DONE (P038, 24f6fac) | `lic/LicenceVerifier.java` (logs the code), `lic/LicenceService.java` (logs IDs), `lic/command/LicenceCommand.java` | Outputs: `lic-t/issue/VaultTransitSignerTest.java` `noTokenPasswordOrSignatureAppearsInAnyOutput`; `lic-t/issue/IssueLicenceCommandTest.java` `recordsAndRunLogHoldNoKeyMaterialAndNoSignature`; `spr-t/LicensingWiringTest.java` `healthShowsNoKeysOrSignatures`; `spr-t/notifications/LicensingNoticesTest.java` `messagesCarryNoKeysSignaturesPricesOrCustomerName` | No test captures the log lines of the verifier, `LicenceService`, `LicenceStartupCheck` or `LicenceCommand` |
| licensing-45 | Tables only via the app's schema step: separate Flyway run, own history table | DONE | `platform-licensing/src/main/resources/db/platform/licensing/V1__licensing.sql`, `lic/jdbc/LicensingSchema.java` | `lic-t/jdbc/PostgresStoresTest.java` `schemaCheckCodes` (message names `lic_schema_history`) | |
| licensing-46 | Runtime user DML only; startup only verifies | DONE | `lic/jdbc/LicensingSchema.java` `requireRights` | `lic-t/jdbc/PostgresStoresTest.java` `schemaCheckCodes` (`LIC_PRIVILEGES_MISSING`); `spr-t/LicensingWiringTest.java` `aDmlOnlyUserRunsEverything` | |

## licensing.md — section 8 Decisions

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-47 | O1: effective time = later of server clock and latest time seen; turning the clock back never extends a licence | DONE | `lic/ClockGuard.java` `read`, `lic/ClockReading.java` | `lic-t/ClockGuardTest.java` `theEffectiveTimeIsTheLaterOfServerClockAndLatestSeen`, `rollingTheClockBackCannotExtendALicence`; `spr-t/LicensingWiringTest.java` `aClockRolledBackStartsWithTheFlagAndCannotExtendExpiry` | |
| licensing-48 | O1: tolerance default 1 hour, `platform.licensing.clock-tolerance` | DONE | `lic/ClockGuard.java` `DEFAULT_TOLERANCE`, `spr/LicensingProperties.java` | `lic-t/ClockGuardTest.java` `exactlyTheToleranceBehindIsNotFlaggedOneSecondMoreIs` | |
| licensing-49 | O1: a clock behind is flagged with the exact difference; never a refusal | DONE | `lic/ClockReading.java` `message` | `lic-t/ClockGuardTest.java` `aClockBehindIsReportedWithTheExactDifferenceAndNeverRefused` | |
| licensing-50 | O1: `LICENCE_CLOCK_ROLLBACK` removed | DONE | `lic/LicenceRefusal.java` | `platform-error-catalogue` catalogue check | Not in any code, properties or the catalogue |
| licensing-51 | O1: signed last-seen reset `platform-last-seen-reset/1`, issued with `--reset-last-seen`, applied with `apply-reset` | DONE | `lic/LastSeenReset.java`, `lic/issue/IssueLicenceCommand.java`, `lic/command/LicenceCommand.java` | `lic-t/issue/IssueLicenceCommandTest.java` `issuesASignedLastSeenResetForAKnownTenant`; `lic-t/command/LicenceCommandTest.java` `aPoisonedLastSeenIsFlaggedAndASignedResetRecovers` | |
| licensing-52 | O1: a reset works once | DONE | `lic/ClockGuard.java` `apply`, `lic/jdbc/PostgresLastSeenStore.java` | `lic-t/ClockGuardTest.java` `aVerifiedResetSetsTheLatestTimeBackOnce`; `lic-t/jdbc/PostgresStoresTest.java` `lastSeenOnlyMovesForwardExceptThroughAReset` | |
| licensing-53 | O1: a reset works only for its tenant | DONE | `lic/LastSeenReset.java` `verify` | `lic-t/command/LicenceCommandTest.java` `aResetForAnotherCustomerIsRefused` | |
| licensing-54 | O1: a reset works only until its `notAfter` | DONE | `lic/ClockGuard.java` `apply` (`RESET_EXPIRED`) | `lic-t/ClockGuardTest.java` `aResetPastItsValidityIsRefused`; `kit-t/LicenceServiceTest.java` `aResetIsUselessLaterAndForAnotherKey` | |
| licensing-55 | O1 / Q3: reset validity 7 days by default | UNTESTED | `lic/issue/IssueLicenceCommand.java` `DEFAULT_RESET_DAYS` | — | Every test passes `--valid-days` or builds the reset itself |
| licensing-56 | O1 / Q3: reset validity at most 30 days | DONE | `lic/issue/IssueLicenceCommand.java` `MAX_RESET_DAYS` | `lic-t/issue/IssueLicenceCommandTest.java` `resetRefusals` (`--valid-days 31`) | Enforced when issuing only; the verifier takes any signed `notAfter` |
| licensing-57 | O2: a renewal before its start date is stored as waiting; the current licence stays in force until the effective date reaches the start, then the renewal takes over | DONE | `lic/LicenceService.java` `store`, `status`; `lic/jdbc/PostgresLicenceStore.java` `promoteWaiting` | `kit-t/LicenceServiceTest.java` `anEarlyRenewalWaitsAndIntakeNeverStops`; `lic-t/jdbc/PostgresStoresTest.java` `licenceSlotsAndPromotion` | |
| licensing-58 | O2: intake never stops because of an early renewal | DONE | `lic/LicenceService.java` `status` | `kit-t/LicenceServiceTest.java` `anEarlyRenewalWaitsAndIntakeNeverStops` | |
| licensing-59 | O2: on a first install the licence is current even before its start date (`NOT_STARTED`) | DONE | `lic/LicenceService.java` `store` | `kit-t/LicenceServiceTest.java` `theFirstLicenceStoredBeforeItsStartIsCurrentAndNotStarted` | |
| licensing-60 | O3: one allowance per product and type in a slot | DONE | `lic/EntitySlot.java` | `lic-t/LicenceModelTest.java` `slotAndAllowanceRules` | |
| licensing-61 | O3: storage in whole GB | DONE | `lic/AllowanceType.java` `STORAGE` (unit GB, `long` amount) | `lic-t/LicenceModelTest.java` `allFourAllowanceTypesHaveAUnit` | |
| licensing-62 | O4: chain head stored apart, moved in the same transaction as every append, checked on read; removed newest records give `USAGE_CHAIN_HEAD_MISMATCH` | DONE | `lic/jdbc/PostgresUsageLedger.java`, `lic/usage/UsageChain.java` `verify(records, head)` | `lic-t/jdbc/PostgresStoresTest.java` `deletingTheNewestRecordsIsDetected`, `deletingAllRecordsAndEditingTheHeadAreDetected`; `kit-t/UsageLedgerTest.java` `deletingTheNewestRecordIsDetectedByTheHead` | |
| licensing-63 | P009-1: modules platform-licensing and -testkit; P010 added -spring | DONE | root `pom.xml`, `platform-bom` | build | Structural |
| licensing-64 | P009-2: one JSON file with the signature block inside; the signature covers the canonical payload, not the file text | UNTESTED | `lic/SignedDocument.java` `open` (re-canonicalises the payload) | — | No test re-formats a signed file (other whitespace) and checks it still verifies |
| licensing-65 | P009-3: `LICENCE_MALFORMED` and `LICENCE_UNSUPPORTED_FORMAT` refuse start-up too | DONE | `lic/LicenceVerifier.java`, `spr/LicenceStartupCheck.java` | `lic-t/LicenceVerifierTest.java` `malformed`, `unsupportedFormatOrAlgorithm` | Start-up path is the same as licensing-21 |
| licensing-66 | P009-3: storing can be refused with `LICENCE_OLDER_VERSION` / `LICENCE_VERSION_CONFLICT`; the stored licence stays in force | DONE | `lic/LicenceService.java` `store` | `lic-t/command/LicenceCommandTest.java` `anOlderVersionIsRefused` | |
| licensing-67 | P009-4: UTC dates; `expiresOn` last valid day; expiring 0–60 days; warning days exactly 60/30/7/1 and every grace day; grace days 1–30; intake stops day 31 | DONE | `lic/LicenceStatus.java` | `lic-t/LicenceStatusTest.java` `beforeExpiry`, `afterExpiry`, `theExpiryDayLastsUntilMidnightUtc` | |
| licensing-68 | P009-5: `NOT_STARTED` before the start date: app starts, intake stopped (since O2 only on a first install) | DONE | `lic/LicenceState.java` `NOT_STARTED` | `lic-t/LicenceStatusTest.java` `beforeTheStartDate` | |
| licensing-69 | P009-6: a subscription covers every release; "updates until" only on perpetual licences | DONE | `lic/Licence.java` `coversRelease` | `lic-t/LicenceStatusTest.java` `aSubscriptionCoversEveryRelease`, `perpetualUpdatesUntilCoversReleasesUpToThatDate`; `lic-t/LicenceModelTest.java` `typeRules` | |
| licensing-70 | P009-7: install licence is a subscription of at most 60 days, checked when issuing; no minimum | DONE | `lic/issue/IssueLicenceCommand.java` | `lic-t/issue/IssueLicenceCommandTest.java` `anInstallLicenceRunsAtMostSixtyDays`, `installThenProductionShareATenantButNonProductionNeverDoes` (60 days accepted) | |
| licensing-71 | P009-8: each allowance names its product | DONE | `lic/Allowance.java` | `lic-t/LicenceModelTest.java` `slotAndAllowanceRules` | |
| licensing-72 | P009-9: pages summed per month; storage and both seat types the month's highest reading | DONE | `lic/AllowanceType.java` `Aggregation`, `lic/usage/UsageTotals.java` | `kit-t/UsageLedgerTest.java` `usagePerEntityPerMonthSumsPagesAndTakesThePeakOfReadings`; `lic-t/jdbc/PostgresStoresTest.java` `peakTypesTakeTheHighestReading` | |
| licensing-73 | P009-10: use without an allowance recorded and `OVER_LIMIT`; unknown slot / product refused with `USAGE_UNKNOWN_SLOT` / `USAGE_UNKNOWN_PRODUCT` | DONE | `lic/usage/UsageRecorder.java` | `kit-t/UsageRecorderTest.java` `useWithoutAnAllowanceIsRecordedAndReportedOverLimit`, `unknownSlotOrProductHasItsOwnCode` | |
| licensing-74 | P009-11: billable = kind `PRODUCTION` and not marked free | DONE | `lic/usage/UsageRecorder.java` `record` | `kit-t/UsageRecorderTest.java` `billableOnlyForProductionUsageNotMarkedFree` | |
| licensing-75 | P009-12: install and production share a tenant; non-production its own; new version with `--tenant-id`, kept slot with `--slot SLOT_ID@…` | DONE | `lic/issue/IssueLicenceCommand.java` | `lic-t/issue/IssueLicenceCommandTest.java` `installThenProductionShareATenantButNonProductionNeverDoes`, `aNewVersionKeepsTheTenantAndChosenSlotsAndAddsNewOnes` | |
| licensing-76 | P009-13: IDs `TEN-`, `ENT-`, `LIC-`, `RST-` + 16 Crockford base32, checked against all issued IDs | PARTLY | `lic/issue/IdGenerator.java`; reset path `IssueLicenceCommand` (`ids.next("RST", records.allIds())`) | `lic-t/issue/IssueLicenceCommandTest.java` `idsAreUniqueAcrossManyCustomers`, `theIdGeneratorChecksAllPrefixes` | Resets are not in the records file (Q4), so an `RST-` ID is never checked against earlier resets |
| licensing-77 | P009-14 / P011: own signing interface `Signer.sign(keyId, bytes)`; production signer Vault Transit | DONE | `lic/issue/Signer.java`, `lic/issue/VaultTransitSigner.java` | `lic-t/issue/VaultTransitSignerTest.java` `signsInVaultAndTheLicenceVerifiesWithTheExportedPublicKey` | |
| licensing-78 | P009-16: the export has its own chain across files; the ledger's check (with the head) goes in the header; an export is never refused | DONE | `lic/usage/UsageExport.java` `create` | `kit-t/UsageExportTest.java` `aRunOfMonthlyFilesIsContinuous`, `aBrokenLedgerIsStatedInTheExportNotHidden` | |
| licensing-79 | P009-17: refusals logged with the code only; the issuing command logs IDs and codes | DONE (P038, 24f6fac) | `lic/LicenceVerifier.java` `verify`, `lic/issue/IssueLicenceCommand.java` | `lic-t/issue/VaultTransitSignerTest.java` `noTokenPasswordOrSignatureAppearsInAnyOutput` (issuing logs captured) | The verifier's log line is not captured by any test |
| licensing-80 | Q1: a licence without this app's product code stops intake; start-up not refused | DONE | `lic/LicenceService.java` `productLicensed`, `intakeAllowed` | `kit-t/LicenceServiceTest.java` `aLicenceWithoutThisAppsProductStopsIntakeButStarts`; `spr-t/notifications/LicensingNoticesTest.java` `aLicenceWithoutThisProductAlertsTheItAdminAndRecovers` | |
| licensing-81 | Q2: `platform.licensing.tenant-id` required, message "platform.licensing.tenant-id is missing"; setup writes `platform-tenant.properties` | DONE | `spr/LicensingConfig.java` `from` | `spr-t/LicensingWiringTest.java` `theShippedTrustedKeysFileIsReadAndTheTenantIdIsRequired`; setup: `SetupCommandTest.java` (`--config-out`) | |
| licensing-82 | Q4: resets stay out of the issued records (run log only) | UNTESTED | `lic/issue/IssueLicenceCommand.java` (reset path does not append a record) | — | `issuesASignedLastSeenResetForAKnownTenant` checks the run log but not that the records file is unchanged |
| licensing-83 | Q5: wiping both usage tables looks like a new ledger; the next export shows `EXPORT_START_MISMATCH` | UNTESTED | `lic/usage/UsageExport.java`, `lic/usage/ExportAnchor.java` | — | The export anchor is kept by the app (no `lic_` table), so the mismatch shows only if the app's anchor is lost too; no test wipes the tables |
| licensing-84 | P011: licensing notifications in licensing-spring, platform-notifications optional | DONE | `spr/notifications/LicensingNotificationsAutoConfiguration.java` | `spr-t/notifications/LicensingNotificationsWiringTest.java` `withoutPlatformNotificationsLicensingWorksAndPublishesNothing`, `withANotifierTheCheckIsWiredAndRunning` | |
| licensing-85 | P011: the app registers the event types and supplies `ActivatedSlots` | DONE | `spr/notifications/LicensingEvents.java` `register`, `LicensingNotificationsAutoConfiguration` | `spr-t/notifications/LicensingNotificationsWiringTest.java` `businessEventsWithoutActivatedSlotsStopTheStartWithAClearMessage` | The app's own `ActivatedSlots` bean |
| licensing-86 | P011: business events per activated slot; clock and version events technical, per tenant | DONE | `spr/notifications/LicensingNotices.java` `Run` | `spr-t/notifications/LicensingNoticesTest.java` `expiringIsPublishedOncePerBandForTheActivatedSlotOnly`, `usageNearAtAndOverLimitPerActivatedSlot`, `aClockBehindAlertsTheItAdminOnceAndRecovers`, `aVersionNotCoveredByUpdatesIsReportedOnceToTheItAdmin` | |
| licensing-87 | P011: an hourly check publishes | UNTESTED | `spr/LicensingProperties.java` `checkInterval` (1 h), `spr/notifications/LicensingNoticeWorker.java` | — | Default and the 24 h upper bound not tested (10 s refused is tested) |
| licensing-88 | P011: one-off events claimed in `lic_notice_sent` first; restarts and several instances never publish twice; a failed publish is retried | DONE | `spr/notifications/LicensingNotices.java` `once`, `lic/jdbc/PostgresNoticeLog.java` | `lic-t/jdbc/PostgresStoresTest.java` `aNoticeIsClaimedOnceAcrossInstancesAndCanBeReleasedAndPurged`; `spr-t/notifications/LicensingNoticesTest.java` `aPublishThatFailsIsTriedAgainOnTheNextCheck` | |
| licensing-89 | P011: conditions use `conditionFailing` / `conditionCleared` | DONE | `spr/notifications/LicensingNotices.java` | `spr-t/notifications/LicensingNoticesTest.java` `graceAlertsOnceAndDailyThenIntakeStoppedTakesOverAndARenewalRecovers`, `aRenewalDuringGraceSendsRecoveredForGrace` | |
| licensing-90 | P011: clock behind repeats every 4 hours | UNTESTED | `spr/notifications/LicensingEvents.java` (`ConditionTiming` 4 h) | — | `aClockBehindAlertsTheItAdminOnceAndRecovers` runs one hour only |
| licensing-91 | P011: key ID = Transit name + version (`lic-2026:1`); key-ID rule allows `:VERSION` | DONE | `lic/Licence.java` `KEY_ID`, `requireKeyId` | `lic-t/issue/VaultTransitSignerTest.java` `signsInVaultAndTheLicenceVerifiesWithTheExportedPublicKey`; `spr-t/notifications/LicensingNotificationsWiringTest.java` `aVaultKeyIdWithItsVersionBindsWhenWrittenInBrackets` | |
| licensing-92 | P010-10: health warnings use `LICENCE_WARNING` / `INTAKE_STOPPED`, left out of the overall status (HTTP 200); `DOWN` only for a refusal while running | DONE (P038, 44284a5) | `spr/LicensingHealthIndicator.java` | `spr-t/LicensingWiringTest.java` `aRestartDuringGraceStarts`, `anEditedRowWhileRunningShowsDownInHealth` | The statuses are tested; the overall status and HTTP mapping with Actuator are not |
| licensing-93 | P010-3: expected tenant = `tenant-id`, else the stored licence's tenant; the first licence sets it | DOC GAP | `lic/LicenceService.java` `store` | | Only in P010-report; code follows it |
| licensing-94 | P010-4: a broken stored row never blocks storing a good licence | DOC GAP | `lic/LicenceService.java` `readable` | | Only in P010-report; code follows it (no test) |
| licensing-95 | P010-5: versions only go up; same licence ID = `ALREADY_STORED`; a licence that starts now drops an older waiting one | DOC GAP | `lic/LicenceService.java` `store` | | Only in P010-report |
| licensing-96 | P010-7: a reset applies at or before `notAfter`; `NOT_NEEDED` still records the reset ID as used | DOC GAP | `lic/ClockGuard.java` `apply` | | Format doc section 7 lists `NOT_NEEDED` but not that the ID is used up |
| licensing-97 | P010-9: clock tolerance capped at 1 day in the Spring config | DOC GAP | `spr/LicensingConfig.java` | | Also missing from the install guide (install-licensing-2) |
| licensing-98 | P010-10/11: health status codes, and no customer name in health | DOC GAP | `spr/LicensingHealthIndicator.java` | | Install guide has the statuses; design body does not |
| licensing-99 | P010-12: the Spring clock is a `LicensingClock` bean; an app `Clock` is never picked up | DOC GAP | `spr/LicensingClock.java` | | |
| licensing-100 | P011-1/2: "daily" = 08:00 summary in the tenant's zone; critical conditions alert at once and repeat every 24 h; clock behind every 4 h | DOC GAP | `spr/notifications/LicensingEvents.java` | | In the install guide only |
| licensing-101 | P011-3: expiring is sent once per band (60/30/7/1), not only on the exact day; band 1 includes the last valid day | DOC GAP | `spr/notifications/LicensingNotices.java` `licence` | | |
| licensing-102 | P011-4: grace hands over to intake stopped without Recovered (`conditionSuperseded`) | DOC GAP | `spr/notifications/LicensingNotices.java` | | |
| licensing-103 | P011-5: licence conditions keyed by event, tenant and entity (no licence ID); usage conditions by `PRODUCT:TYPE` | DOC GAP | `spr/notifications/LicensingNotices.java` | | |
| licensing-104 | P011-6: near limit at the admin's percentage (default 80, 50–99); at limit when used ≥ allowance (skips near); over limit as a condition; no notice for use without an allowance | DOC GAP | `spr/notifications/LicensingNotices.java` `usage` | | |
| licensing-105 | P011-7/9/11: claims purged after 400 days; only catalogue types published; "version not covered" needs `release-date` | DOC GAP | `spr/notifications/LicensingNotices.java` `KEEP_CLAIMS`, `registeredIn`, `release` | | |

## licensing-file-format.md — section 1 JSON rules

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-1 | Values: objects, arrays, strings, 64-bit whole numbers, booleans | DONE | `lic/json/Json.java` | `lic-t/json/JsonTest.java` `refusesWhatIsNotInTheSubset` (`99999999999999999999`) | |
| licensing-file-format-2 | No `null`, fractions, exponents, leading zeros, `-0`, duplicate keys | DONE | `lic/json/Json.java` `Parser` | `lic-t/json/JsonTest.java` `refusesWhatIsNotInTheSubset` | |
| licensing-file-format-3 | Nesting at most 32 deep | DONE | `lic/json/Json.java` `MAX_DEPTH` | `lic-t/json/JsonTest.java` `refusesDeepNesting` | Boundary (32 vs 33) not tested |
| licensing-file-format-4 | Text is UTF-8 | DONE | `lic/SignedDocument.java` `utf8` | `lic-t/LicenceVerifierTest.java` `malformed` (bytes FF FE) | |
| licensing-file-format-5 | Canonical: keys sorted by UTF-16 code units, no whitespace | DONE | `lic/json/Json.java` `canonical` (`TreeMap<String>`) | `lic-t/json/JsonTest.java` `canonicalSortsKeysAndHasNoWhitespace` | |
| licensing-file-format-6 | Strings escaped only where JSON requires; other controls `\u00xx` lower-case hex | DONE | `lic/json/Json.java` `string` | `lic-t/json/JsonTest.java` `canonicalEscapesOnlyWhatJsonRequires` | |
| licensing-file-format-7 | Files written pretty: sorted keys, two-space indent, LF | UNTESTED | `lic/json/Json.java` `pretty` | `lic-t/json/JsonTest.java` `prettyAndCanonicalParseToTheSameValue` (round trip only) | Layout itself not asserted |
| licensing-file-format-8 | Whitespace is not signed | UNTESTED | `lic/SignedDocument.java` `open` | — | Same gap as licensing-64 |
| licensing-file-format-9 | Dates `YYYY-MM-DD`, months `YYYY-MM`, times `Instant.toString()` UTC | UNTESTED | `lic/LicenceFormat.java` `date`, `instant` | — | Non-canonical forms are refused in code; no test |
| licensing-file-format-10 | Hashes SHA-256 lower-case hex; keys and signatures standard Base64 with padding | DONE | `lic/Ed25519.java` `sha256Hex`, `encodePublic` | `lic-t/LicenceVerifierTest.java` `trustedKeysFileRoundTrips`, `forgedWhenTheSignatureValueIsChanged` | |

## licensing-file-format.md — section 2 Licence file

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-11 | Envelope `format`, `licence`, `signature {algorithm, payloadSha256, value}` | DONE | `lic/SignedDocument.java` `sign`, `open` | `lic-t/LicenceVerifierTest.java` `malformed` | |
| licensing-file-format-12 | `licenceId` `LIC-XXXX-XXXX-XXXX-XXXX`, new for every licence and version | PARTLY | `lic/issue/IssueLicenceCommand.java`; `lic/Licence.java` `ID` | `lic-t/issue/IssueLicenceCommandTest.java` `aNewVersionKeepsTheTenantAndChosenSlotsAndAddsNewOnes` | The reader accepts any `[A-Z0-9][A-Z0-9-]{2,63}`; the `LIC-` shape is only what the issuer writes (same for `TEN-`, `ENT-`) |
| licensing-file-format-13 | `version` 1, 2, 3 … per tenant | DONE | `lic/issue/IssueLicenceCommand.java` | `lic-t/issue/IssueLicenceCommandTest.java` `aNewVersionKeepsTheTenantAndChosenSlotsAndAddsNewOnes` | |
| licensing-file-format-14 | `customer` 1–200 printable characters | PARTLY | `lic/Licence.java` constructor | — | Only characters below U+0020 refused (DEL and other non-printables pass); no test |
| licensing-file-format-15 | `tenantId` kept across versions; never shared between production/install and non-production | DONE | `lic/issue/IssueLicenceCommand.java` | `lic-t/issue/IssueLicenceCommandTest.java` `installThenProductionShareATenantButNonProductionNeverDoes` | |
| licensing-file-format-16 | `products` codes `[A-Z][A-Z0-9_]{1,31}`, no repeats | DONE (P038, 2bb79be) | `lic/Licence.java` (`a product is listed twice`) | `lic-t/LicenceModelTest.java` `slotAndAllowanceRules` (pattern only) | Repeat refusal not tested |
| licensing-file-format-17 | `entitySlots` `[{slotId, allowances[{product,type,amount}]}]` | DONE | `lic/LicenceFormat.java` | `lic-t/LicenceVerifierTest.java` `aSignedLicenceVerifiesAndReadsBackEqual` | |
| licensing-file-format-18 | `type`, `kind`, `deployment` enums | DONE | `lic/LicenceFormat.java` `enumValue` | `lic-t/LicenceVerifierTest.java` `aSignedLicenceVerifiesAndReadsBackEqual`, `aPerpetualLicenceVerifies` | |
| licensing-file-format-19 | `expiresOn` SUBSCRIPTION only (inclusive); `updatesUntil` PERPETUAL only | DONE | `lic/Licence.java` | `lic-t/LicenceModelTest.java` `typeRules`; `lic-t/LicenceVerifierTest.java` `theSignedContentMustBeAValidLicence` | |
| licensing-file-format-20 | `keyId` name `[a-z0-9][a-z0-9._-]{0,63}` with optional `:VERSION` | DONE | `lic/Licence.java` `requireKeyId` | `spr-t/notifications/LicensingNotificationsWiringTest.java` `aVaultKeyIdWithItsVersionBindsWhenWrittenInBrackets` | |
| licensing-file-format-21 | `issuedAt` time | DONE | `lic/LicenceFormat.java` | `lic-t/issue/IssueLicenceCommandTest.java` `issuesASignedLicenceThatVerifies` | |
| licensing-file-format-22 | Allowance units: pages, GB (10^9), seats | DONE | `lic/AllowanceType.java` | `lic-t/LicenceModelTest.java` `allFourAllowanceTypesHaveAUnit` | |
| licensing-file-format-23 | `amount` ≥ 1; allowance product among `products`; one allowance per product and type | DONE | `lic/Allowance.java`, `lic/EntitySlot.java`, `lic/Licence.java` | `lic-t/LicenceModelTest.java` `slotAndAllowanceRules` | |
| licensing-file-format-24 | `INSTALL` is a `SUBSCRIPTION` of at most 60 days (enforced when issuing) | DONE | `lic/issue/IssueLicenceCommand.java`, `lic/Licence.java` | `lic-t/issue/IssueLicenceCommandTest.java` `anInstallLicenceRunsAtMostSixtyDays`; `lic-t/LicenceModelTest.java` `typeRules` | |
| licensing-file-format-25 | Unknown fields are refused | DONE (P038, ed1eb0d) | `lic/LicenceFormat.java` `fromPayload` (`only(PAYLOAD)`) | — | No test signs a payload with an extra field (an added field after signing is caught as TAMPERED) |
| licensing-file-format-26 | IDs: 16 random Crockford base32 characters (80 bits) in groups of four | DONE | `lic/issue/IdGenerator.java` | `lic-t/issue/IssueLicenceCommandTest.java` `issuesASignedLicenceThatVerifies` (regex) | |
| licensing-file-format-27 | Signed message = UTF-8 of `"platform-licence/1"` + LF + canonical(payload); `payloadSha256` of the same message; the Console must produce the same bytes | DONE (P038, ec88c4e) | `lic/SignedDocument.java` `signedMessage` | — | Every test signs and verifies through the same function; no test checks fixed bytes, and the doc's sample file and key are not verified by any test |
| licensing-file-format-28 | `payloadSha256` tells an edited file (TAMPERED) from a wrong signer (FORGED) | DONE | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `tamperedWhenTheContentChanges`, `tamperedWhenTheKeyIdIsSwappedToAnotherTrustedKey`, `forgedWhenSignedByAnotherKeyUnderOurKeyId` | |
| licensing-file-format-29 | Ed25519 is deterministic: same payload and key give the same file | DONE | `lic/Ed25519.java` | `lic-t/LicenceVerifierTest.java` `signingIsDeterministic` | |
| licensing-file-format-30 | `LICENCE_MISSING`: null, empty, blank | DONE | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `missing` | |
| licensing-file-format-31 | `LICENCE_MALFORMED`: not UTF-8, not JSON, missing / extra envelope fields, signature not 64 bytes of Base64 | DONE (P038, ed1eb0d) | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `malformed` | Extra envelope field and a non-64-byte signature not tested |
| licensing-file-format-32 | Check order: malformed envelope before unsupported format | DIFFERENT | `lic/SignedDocument.java` `open` | | `format` is compared before the envelope's field set: an unknown format with extra fields gives `LICENCE_UNSUPPORTED_FORMAT`, not `MALFORMED`. Harmless; doc or code to agree |
| licensing-file-format-33 | `LICENCE_UNSUPPORTED_FORMAT`: format not `platform-licence/1` or algorithm not Ed25519 | DONE | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `unsupportedFormatOrAlgorithm` | |
| licensing-file-format-34 | `LICENCE_UNKNOWN_KEY_ID` | DONE | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `unknownKeyId` | |
| licensing-file-format-35 | `LICENCE_TAMPERED` | DONE | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `tamperedWhenTheContentChanges`, `everySingleByteChangeIsRefused` | |
| licensing-file-format-36 | `LICENCE_FORGED` | DONE | `lic/SignedDocument.java` `open` | `lic-t/LicenceVerifierTest.java` `forgedWhenTheSignatureValueIsChanged` | |
| licensing-file-format-37 | A signed payload that breaks the model is `LICENCE_MALFORMED` | DONE | `lic/LicenceVerifier.java` `check` | `lic-t/LicenceVerifierTest.java` `theSignedContentMustBeAValidLicence` | |
| licensing-file-format-38 | `LICENCE_WRONG_CUSTOMER` when the tenant is known | DONE | `lic/LicenceVerifier.java` `check` | `lic-t/LicenceVerifierTest.java` `wrongCustomer` | |
| licensing-file-format-39 | Storing: `LICENCE_OLDER_VERSION` / `LICENCE_VERSION_CONFLICT` | DONE | `lic/LicenceService.java` `store` | `lic-t/command/LicenceCommandTest.java` `anOlderVersionIsRefused` | |
| licensing-file-format-40 | Messages `CODE: meaning. exact cause.` with IDs and dates only, never signatures or keys | DONE | `lic/LicenceException.java`, `lic/SignedDocument.java` `printable` | `lic-t/LicenceVerifierTest.java` `refusalMessagesNeverContainTheSignatureOrKeys` | |
| licensing-file-format-41 | A server clock behind is not a refusal (O1) | DONE | `lic/ClockGuard.java` | `lic-t/ClockGuardTest.java` `aClockBehindIsReportedWithTheExactDifferenceAndNeverRefused` | |

## licensing-file-format.md — section 3 Trusted keys file

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-42 | `platform-licence-keys/1`, `publicKey` Base64 X.509 SubjectPublicKeyInfo | DONE | `lic/TrustedKeys.java` `parse`, `toJson` | `lic-t/LicenceVerifierTest.java` `trustedKeysFileRoundTrips` | |
| licensing-file-format-43 | A rotation adds a key; old keys stay | DONE | `lic/TrustedKeys.java` | `lic-t/LicenceVerifierTest.java` `keyRotationOldLicencesKeepVerifying` | |
| licensing-file-format-44 | P031: optional `"development": true` per key; a key so marked is a development key in the app | DONE (P036, dd2d3e8) | `lic/TrustedKeys.java`; `spr/LicensingConfig.java` `from` keeps the file's development IDs (`TrustedKeys.of(keys, development)`); inline keys carry no mark | `lic-t/TrustedKeysDevelopmentTest.java`; `platform-identity-spring` `DevelopmentKeyWiringTest` (development-signed licence + local http issuer allowed, production-signed refused) | was DIFFERENT: the marks were dropped (xc-devkey-1). Fixed in P036 |
| licensing-file-format-45 | P031-1: the `development` field is not described in section 3 | DOC GAP | | | Only in CLAUDE.md and P031-report |

## licensing-file-format.md — section 4 Issuing command files

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-46 | Issued records: JSON Lines, append only, the listed fields, no key material, no signature; new IDs checked against every ID in the file | DONE | `lic/issue/IssuedRecords.java` | `lic-t/issue/IssueLicenceCommandTest.java` `recordsAndRunLogHoldNoKeyMaterialAndNoSignature`, `idsAreUniqueAcrossManyCustomers` | Fields asserted: `licenceId`, `slotIds`; the rest by reading |
| licensing-file-format-47 | Run log: default `<records>.runs.log`, one line per run with the listed fields and outcomes; never a token, key or signature | DONE | `lic/issue/IssueLicenceCommand.java` (run log) | `lic-t/issue/IssueLicenceCommandTest.java` `recordsAndRunLogHoldNoKeyMaterialAndNoSignature`; `lic-t/issue/VaultTransitSignerTest.java` `signsInVaultAndTheLicenceVerifiesWithTheExportedPublicKey` (`signerAuditRefs`), `noTokenPasswordOrSignatureAppearsInAnyOutput` | |
| licensing-file-format-48 | Run log field list | DIFFERENT | `lic/issue/IssueLicenceCommand.java` | | A reset line also carries `resetId`, `resetTo`, `notAfter`; the doc's list leaves them out |
| licensing-file-format-49 | Signing through `Signer.sign`; `VaultTransitSigner` posts to `/v1/transit/sign/<name>`, only the signature comes back | DONE | `lic/issue/VaultTransitSigner.java` `sign` | `lic-t/issue/VaultTransitSignerTest.java` `signsInVaultAndTheLicenceVerifiesWithTheExportedPublicKey`, `aResetIsSignedInVaultToo` | |
| licensing-file-format-50 | Each signature checked against the key's public key before the file is written | DONE (P038, 1aac687) | `lic/issue/VaultTransitSigner.java` `sign` (`ISSUE_SIGNATURE_MISMATCH`) | — | No test returns a wrong signature |

## licensing-file-format.md — section 5 Usage ledger record

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-51 | Chain head apart, moved with every append; newest record ≠ head gives `USAGE_CHAIN_HEAD_MISMATCH` | DONE | `lic/jdbc/PostgresUsageLedger.java`, `lic/usage/UsageChain.java` | `lic-t/jdbc/PostgresStoresTest.java` `theLedgerChainsAndMovesTheHead`, `deletingTheNewestRecordsIsDetected` | |
| licensing-file-format-52 | Record fields incl. `unit` from the type; `previousHash` 64 zeros for the first | DONE | `lic/usage/UsageChain.java` `GENESIS`, `seal` | `kit-t/UsageLedgerTest.java` `recordsAreChainedFromGenesis` | |
| licensing-file-format-53 | `hash = SHA-256(canonical(every field except hash))` | DONE | `lic/usage/UsageChain.java` `hashOf` | `kit-t/UsageLedgerTest.java` `anEditedRecordBreaksTheChain` | Round trip only (no fixed-bytes check) |
| licensing-file-format-54 | Codes `USAGE_CHAIN_SEQUENCE_GAP`, `_LINK_BROKEN`, `_RECORD_EDITED` | DONE | `lic/usage/UsageChain.java` `links` | `kit-t/UsageLedgerTest.java` `aDeletedRecordBreaksTheChain`, `aReChainedRecordThatSkipsItsPredecessorIsALinkBreak`, `anEditedRecordBreaksTheChain` | |
| licensing-file-format-55 | Monthly usage sum / highest; billable rule | DONE | `lic/usage/UsageTotals.java` | `kit-t/UsageLedgerTest.java` `usagePerEntityPerMonthSumsPagesAndTakesThePeakOfReadings`; `kit-t/UsageRecorderTest.java` `billableOnlyForProductionUsageNotMarkedFree` | |

## licensing-file-format.md — section 6 Usage export file

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-56 | Structure: format, header (incl. `ledgerChain`), records, footer, with only the listed fields | DONE | `lic/usage/UsageExport.java` `create` | `kit-t/UsageExportTest.java` `aFileVerifiesOnItsOwnAndHoldsOnlyUsageFields` | |
| licensing-file-format-57 | `startChainValue` = previous `finalChainValue` (zeros first); `sequence` + 1; `periodFrom` the month after | DONE | `lic/usage/UsageExport.java`, `lic/usage/UsageExportVerifier.java` | `kit-t/UsageExportTest.java` `aRunOfMonthlyFilesIsContinuous`, `theFirstFileMustStartFromGenesis`, `aGapInMonthsIsDetected`, `aSequenceGapIsDetected` | |
| licensing-file-format-58 | Header chain, per-record chain, final chain value (header value when no records) | DONE | `lic/usage/UsageExport.java` `link` | `kit-t/UsageExportTest.java` `anEmptyPeriodStillExportsAndChains`, `anEditedHeaderIsDetected` | Round trip only |
| licensing-file-format-59 | The ledger's chain check is in the header; an export is never refused | DONE | `lic/usage/UsageExport.java` | `kit-t/UsageExportTest.java` `aBrokenLedgerIsStatedInTheExportNotHidden` | |
| licensing-file-format-60 | No document data and no prices | DONE | `lic/usage/UsageExport.java` `recordLine` | `kit-t/UsageExportTest.java` `aFileVerifiesOnItsOwnAndHoldsOnlyUsageFields` | |
| licensing-file-format-61 | Verifier codes `EXPORT_MALFORMED`, `_UNSUPPORTED_FORMAT`, `_HEADER_EDITED`, `_RECORD_EDITED` (index; removed / inserted record), `_FOOTER_MISMATCH`, `_START_MISMATCH`, `_SEQUENCE_GAP`, `_PERIOD_GAP`, `_TENANT_MISMATCH` | DONE | `lic/usage/UsageExportVerifier.java` | `kit-t/UsageExportTest.java` `unreadableAndUnsupportedFiles`, `anEditedHeaderIsDetected`, `anEditedRecordIsDetectedAndLocated`, `aRemovedRecordIsDetected`, `aMissingFileIsDetected`, `aSequenceGapIsDetected`, `aGapInMonthsIsDetected`, `aFileOfAnotherTenantIsDetected` | |
| licensing-file-format-62 | `EXPORT_RECORD_OUTSIDE_PERIOD` | DONE (P038, d00dab5) | `lic/usage/UsageExportVerifier.java` | — | No test anywhere produces it |

## licensing-file-format.md — section 7 Last-seen reset

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-63 | Same envelope and signing as the licence; body `reset` with the listed fields | DONE | `lic/LastSeenReset.java` | `lic-t/LastSeenResetTest.java` `aSignedResetVerifies`, `aLicenceFileIsNotAReset`, `everySingleByteChangeIsRefused` | |
| licensing-file-format-64 | `apply-reset`: latest time seen set to `resetTo`, `resetId` recorded so it works once | DONE | `lic/ClockGuard.java` `apply` | `lic-t/ClockGuardTest.java` `aVerifiedResetSetsTheLatestTimeBackOnce`; `lic-t/command/LicenceCommandTest.java` `aPoisonedLastSeenIsFlaggedAndASignedResetRecovers` | |
| licensing-file-format-65 | Refused when the server clock is past `notAfter` | DONE | `lic/ClockGuard.java` `apply` | `lic-t/ClockGuardTest.java` `aResetPastItsValidityIsRefused` | |
| licensing-file-format-66 | Codes `RESET_MISSING` … `RESET_EXPIRED` | DONE | `lic/ResetRefusal.java` | `lic-t/LastSeenResetTest.java` `refusals`; `lic-t/ClockGuardTest.java` `aResetPastItsValidityIsRefused` | |
| licensing-file-format-67 | Outcomes `APPLIED`, `ALREADY_APPLIED`, `NOT_NEEDED` | DONE | `lic/LastSeenReset.java` `Outcome` | `lic-t/ClockGuardTest.java` `aVerifiedResetSetsTheLatestTimeBackOnce`, `aResetWhenNothingIsAheadChangesNothing` | |

## licensing-file-format.md — section 8 Tables

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| licensing-file-format-68 | Separate Flyway run, history table `lic_schema_history`; startup only checks | DONE | `V1__licensing.sql`, `lic/jdbc/LicensingSchema.java` | `lic-t/jdbc/PostgresStoresTest.java` `schemaCheckCodes`; `spr-t/LicensingWiringTest.java` `missingTablesStopTheStartAndNothingIsCreated` | |
| licensing-file-format-69 | The seven tables (`lic_schema_version` … `lic_notice_sent`) | DONE | `lic/jdbc/LicensingSchema.java` `TABLES` | `lic-t/jdbc/PostgresStoresTest.java` `schemaCheckCodes` (`LIC_TABLES_INCOMPLETE`) | |
| licensing-file-format-70 | `lic_licence` holds CURRENT and WAITING files as received; the copied columns are never trusted | DONE | `lic/jdbc/PostgresLicenceStore.java`, `lic/LicenceService.java` | `lic-t/jdbc/PostgresStoresTest.java` `licenceSlotsAndPromotion`; `kit-t/LicenceServiceTest.java` `aTamperedStoredRowIsRefusedOnRead` | |
| licensing-file-format-71 | `lic_last_seen` one row, forward only; `lic_clock_reset` IDs of applied resets | DONE | `lic/jdbc/PostgresLastSeenStore.java` | `lic-t/jdbc/PostgresStoresTest.java` `lastSeenOnlyMovesForwardExceptThroughAReset` | |
| licensing-file-format-72 | No secrets, key material, prices or document data in any table | DONE | `V1__licensing.sql` | `lic-t/jdbc/PostgresStoresTest.java` `noTableHoldsSecretsPricesOrDocumentData` | |
| licensing-file-format-73 | Runtime user: SELECT, INSERT, UPDATE, DELETE on `lic_` tables, SELECT on `lic_schema_version` | DONE | `lic/jdbc/LicensingSchema.java` `requireRights` | `lic-t/jdbc/PostgresStoresTest.java` `schemaCheckCodes`; `spr-t/LicensingWiringTest.java` `aDmlOnlyUserRunsEverything` | |

## Install guide check: docs/install/licensing.md

| Item in guide | Kind (property/format/option/code/default) | In code? | Difference |
|---|---|---|---|
| install-licensing-1: `platform.licensing.product-code` "set by the app's own install guide" | property | Yes | The code requires it: start-up stops with `platform.licensing.product-code is missing`. The guide does not say it is required |
| install-licensing-2: `platform.licensing.clock-tolerance` optional, default `1h` | property / default | Yes | The code also caps it at 1 day (`clock-tolerance must be at most 1 day`); not in the guide |
| install-licensing-3: `licence store / status / apply-reset` with `--file --jdbc-url --db-user --keys`; "a licence for another customer is refused" | option | Yes | The command also takes `--tenant-id`, `--product-code`, `--clock-tolerance` (not in the guide). Without `--tenant-id`, the first `store` accepts any customer's licence; the refusal holds only once a licence is stored or the tenant is given |

All other items match: 32 checked (Flyway location, `V1__licensing.sql`, `lic_schema_history`, grants, `LIC_TABLES_MISSING`,
`LIC_PRIVILEGES_MISSING`, `trusted-keys-file` path and `classpath:`, bracketed `trusted-keys.<key ID>`, the same-key
rule, `tenant-id` required with its message, `platform-tenant.properties`, `PLATFORM_LICENSING_DB_PASSWORD`,
`LICENCE_TAMPERED` / `_FORGED` / `_WRONG_CUSTOMER`, store idempotent, renewal waits, status line, refusal list at
start-up, `/actuator/health/licensing` and its details, `LICENCE_WARNING` / `INTAKE_STOPPED` / `DOWN`, the nine
events with kind, default and recipients, near limit 80 % (50–99), hourly check per instance, `lic_notice_sent`,
`notifications.enabled` default true, `check-interval` default 1 h (1 min–24 h), `link`, `release-version`,
`release-date`).

## Install guide check: docs/install/licence-signing.md

| Item in guide | Kind (property/format/option/code/default) | In code? | Difference |
|---|---|---|---|
| install-licence-signing-1: `ISSUE_VAULT_SEALED` "Vault is sealed or in standby" | code | Yes | Only HTTP 503 maps to it; a standby node's redirect (3xx) or 429 gives `ISSUE_VAULT_ERROR` (a redirect gets a hint to use the active node) |
| install-licence-signing-2: Vault options `--vault-addr`, `--vault-login`, `--vault-user` | option | Yes | `--vault-mount` (default `transit`) and `--vault-login-mount` (default `userpass`) exist and are not in the guide |
| install-licence-signing-3: codes `ISSUE_VAULT_TLS`, `ISSUE_VAULT_SEALED`, `ISSUE_VAULT_ERROR`, `ISSUE_SIGNATURE_MISMATCH` | code | Yes | They exist with the guide's meaning, but no test produces any of them |

All other items match: 30 checked (audit device text, Transit `ed25519` key, `lic-<year>` name rule, `exportable` /
`allow_plaintext_backup` refused as `ISSUE_KEY_EXPORTABLE`, the `licensing-signer` policy (a test checks the guide shows
it verbatim), Vault 1.17.6, `VAULT_ADDR`, `VAULT_NAMESPACE`, `VAULT_TOKEN`, userpass login with hidden password and
token revoked, the issue example's options, `--key-id NAME` = latest version and `NAME:VERSION`, `--reset-last-seen`,
`--export-trusted-keys` with commas and `--run-log`, the printed bracketed config, rotation behaviour, run-log and
records contents, `ISSUE_VAULT_NO_TOKEN`, `_LOGIN_REFUSED`, `_PERMISSION_DENIED`, `_UNREACHABLE`, `ISSUE_KEY_NOT_FOUND`,
`ISSUE_KEY_INVALID`, nothing written when signing fails).

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| xc-devkey-1 (licensing-file-format-44) | A key marked `"development": true` counts as a development key in the app | `LicensingConfig.from` drops the development set, so in Spring apps `DevelopmentLicence` is always false and a local http issuer is refused even under a development-key licence; untested | A · **FIXED in P036 (dd2d3e8)** | Keep the file's development IDs when merging keys (`TrustedKeys.of(keys, development)`) and add a wiring test through identity-spring | S |
| licensing-file-format-27 | Signed message bytes; the Console must produce the same bytes | No fixed-bytes test; the doc's sample licence and public key are never verified | B · **FIXED in P038 (ec88c4e)** | Golden test: verify the doc's sample file with its key, and check canonical bytes / `payloadSha256` of a fixed payload (also ledger hash and export chain values) | S |
| licensing-file-format-50 | Vault's signature checked before writing | `ISSUE_SIGNATURE_MISMATCH` untested | B · **FIXED in P038 (1aac687)** | Test with a signer double that returns another key's signature: refused, nothing written | S |
| licensing-file-format-25 | Unknown payload fields refused | Untested | B · **FIXED in P038 (ed1eb0d)** | Test: sign a payload with an extra field, expect `LICENCE_MALFORMED` | S |
| licensing-file-format-31 | `LICENCE_MALFORMED` cases | Extra envelope field and non-64-byte signature untested | B · **FIXED in P038 (ed1eb0d)** | Add both cases to `LicenceVerifierTest.malformed` | S |
| licensing-file-format-16 | Products without repeats | Repeat refusal untested | B · **FIXED in P038 (2bb79be)** | Add a repeated-product case to `LicenceModelTest` | S |
| licensing-file-format-62 | `EXPORT_RECORD_OUTSIDE_PERIOD` | Untested | B · **FIXED in P038 (d00dab5)** | Test: a record with a period outside the header range (re-chained) gives the code | S |
| licensing-44 / licensing-79 | Never log secrets; refusals logged with the code only | Log lines of `LicenceVerifier`, `LicenceService`, `LicenceStartupCheck`, `LicenceCommand` not captured by any test | B · **FIXED in P038 (24f6fac)** | Capture `com.documentplatform.licensing` logs in the verifier and wiring tests; assert no signature, key or file text | S |
| licensing-92 | Licence warnings left out of overall health, HTTP 200 | Aggregate status and HTTP mapping untested | B · **FIXED in P038 (44284a5)** | Actuator test: grace and grace-over give overall UP and HTTP 200; edited row gives DOWN | S |
| licensing-82 | Resets stay out of the issued records | Untested | C | Assert the records file is unchanged after `--reset-last-seen` | S |
| licensing-55 | Reset valid 7 days by default | Untested | C | Issue a reset without `--valid-days`; assert `notAfter` = now + 7 days | S |
| licensing-76 | `RST-` IDs checked against all issued IDs | Earlier reset IDs are not checked (resets only in the run log) | C | Also read reset IDs from the run log into the taken set, or note that 80 random bits suffice | S |
| licensing-83 | Q5: wiped usage tables show `EXPORT_START_MISMATCH` | Only when the app's export anchor is lost too; untested | C | State in the design that the anchor is the app's; add a test that a wiped ledger exports with `recordCount` 0 against the kept anchor | S |
| licensing-64 / licensing-file-format-8 | Whitespace is not signed | Untested | C | Test: re-format a signed file (compact and other indent), it still verifies | S |
| licensing-87 | Hourly check | Default 1 h and the 24 h bound untested | C | Wiring test of the default interval and `check-interval=25h` refused | S |
| licensing-90 | Clock behind repeats every 4 hours | Untested | C | Run the notices test 5 hours and expect one repeat | S |
| licensing-file-format-7 | Pretty layout (two spaces, LF, sorted) | Untested | C | Assert `Json.pretty` output of a small object exactly | S |
| licensing-file-format-9 | Strict date and time forms | Untested | C | Test non-canonical dates/times in a signed payload give `LICENCE_MALFORMED` | S |
| licensing-file-format-12 | `LIC-` / `TEN-` / `ENT-` ID shape | Reader accepts any ID shape | C | Either state that readers accept any ID shape, or check the prefix pattern on read | S |
| licensing-file-format-14 | Customer 1–200 printable characters | DEL and other non-printables accepted; untested | C | Refuse `Character.isISOControl` and test | S |
| licensing-file-format-32 | Check order malformed before unsupported | Format compared before the envelope field set | C | Align the doc's table with the code (format first) | S |
| licensing-file-format-48 | Run log fields | `resetId`, `resetTo`, `notAfter` not in the doc | C | Add them to format section 4 | S |
| licensing-12 | Install licence 30–60 days | Superseded by P009-7; code follows | C | Change section 1 to "at most 60 days" | S |
| licensing-23 | Clock rollback as tampering | Superseded by O1; code follows | C | Strike the old sentence (pointer already there) | S |
| licensing-33 | Issuing from an input file | Built from options | C | Say "from command options" in section 5 | S |
| licensing-41 | Setup never deletes | Later setup decisions remove grants / switch bindings off on request | C | Point section 6 to setup.md's rules | S |
| licensing-93 … licensing-105 | P010 and P011 decisions (expected tenant, broken row, version rules, reset use, tolerance cap, health codes, `LicensingClock`, notification timing, bands, handover, keys, usage levels, purge / catalogue / release date) | Only in the reports; the design body just points to them | C · **FIXED in P037 (c3fa330)** | Write them into section 8 of licensing.md | M |
| licensing-file-format-45 | `"development": true` in the trusted-keys format | Not in section 3 | C | Add the optional field to section 3 | S |
| install-licensing-1 | `product-code` required | Guide does not say required | C | Mark it required in the guide | S |
| install-licensing-2 | `clock-tolerance` at most 1 day | Not in the guide | C | Add the limit | S |
| install-licensing-3 | Licence command options | `--tenant-id`, `--product-code`, `--clock-tolerance` missing; first store accepts any tenant | C | List the options and advise `--tenant-id` on the first store | S |
| install-licence-signing-1 | `ISSUE_VAULT_SEALED` covers standby | Standby gives `ISSUE_VAULT_ERROR` | C | Reword the guide row (sealed: 503; standby: use the active node) | S |
| install-licence-signing-2 | Vault mount options | `--vault-mount`, `--vault-login-mount` not in the guide | C | Add them with defaults | S |
| install-licence-signing-3 | Vault TLS / sealed / error codes | Untested | C | Tests with a stub Vault: untrusted certificate, 503, 500 | S |
