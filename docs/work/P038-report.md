# P038 report — untested rules: fields, erase log, crypto, licensing

Date: 2026-11-23
Branch: `test/fields-eraselog-crypto-licensing` (then `docs/p038-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs (green) | Merged |
|---|---|---|---|
| Tests, one fix, docs | #36 | 45223413551, 45223425332 | 20 commits, `4f4b467` … `2bcb1bf` |
| This report and the row marks | this PR | | |

The merge used the README's stop-on-failed-check line. No check failed or was re-run.

`mvn clean verify`: **1368 tests, 0 failures, 13 skipped.** The skips are the same as in P037.

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 27 | platform-integration | 55 |
| platform-crypto-spring | 5 | platform-integration-spring | 9 |
| platform-crypto-testkit | 16 | platform-integration-testkit | 204 (11 skipped) |
| platform-erase-log | 25 | platform-licensing | 152 |
| platform-erase-log-spring | 8 | platform-licensing-spring | 37 |
| platform-erase-log-testkit | 9 | platform-licensing-testkit | 48 |
| platform-error-catalogue | 9 | platform-model-governance-spring | 6 |
| platform-errors | 9 | platform-model-governance-testkit | 67 |
| platform-fields | 98 | platform-monitoring-spring | 6 |
| platform-fields-testkit | 20 | platform-monitoring-testkit | 17 |
| platform-identity-core | 120 | platform-notifications | 82 |
| platform-identity-jdbc | 66 | platform-notifications-spring | 42 |
| platform-identity-spring | 30 (2 skipped) | platform-notifications-testkit | 134 |
| platform-identity-testkit | 14 | platform-outbound | 9 |
| platform-setup | 40 | platform-outbound-spring | 4 |

## Independence

**No other product's name or behaviour in code.** The tests use `PRODUCT_A`, the format document's made-up sample
(`Example Customer Ltd`), and hosts under `example`.

## What was built

**Known-answer tests: every stored or signed format matches the design.** No stored format was changed, and there is
no question to stop on. Each expected value is kept in the test, and was also built a second time from the design
text alone. Where possible it was computed outside Java with openssl.

| Format | Test | Checked against |
|---|---|---|
| Value `pcv1.` (layout, associated data) | `crypto.envelope.KnownAnswerTest.aValueHasTheDesignsLayoutAndAssociatedData` | crypto.md section 4, a fixed key, wrapped text and nonce; the code's `seal` gives the kept text |
| Record envelope `pck1.` and item `pci1.` | `…aRecordEnvelopeAndItsItemsHaveTheDesignsLayoutAndAssociatedData` | crypto.md section 4; the code writes the envelope and reads the kept item; the item does not open at another position |
| Fingerprint | `…aFingerprintIsTheHmacOfTheDesignsInput` | HMAC-SHA256 computed with openssl over `"platform-crypto/fp/1" NUL context NUL canonical(value)` |
| What platform-fields seals and fingerprints | `fields.values.FieldsKnownAnswerTest` | the record context's SHA-256 (openssl), the fingerprint context, and the sealed form of a text and an address |
| Licence signed bytes | `licensing.FormatsKnownAnswerTest.theDocumentsSampleLicenceVerifiesAndItsSignedMessageIsFixed` | the format doc's sample verifies with its published key; the canonical payload written by hand; its SHA-256 (openssl) is the doc's `payloadSha256` |
| Ledger hash and export chain | `…aLedgerRecordsHashAndTheExportChainAreFixed` | SHA-256 (openssl) of the canonical record, header and record chain values |

For each test, changing the format on purpose made it fail: the value's associated-data prefix, and the export
chain's separator.

**One code change: erase-log-22.** `EraseGuard.State` keeps the last entry that could not be applied (sequence, kind,
record type and ID, handler code, code, time). The `eraseLog` health details show it as `lastFailure` while that
entry is pending, and it is cleared once the entry is applied. It holds IDs and codes only; the strict health-map test
still asserts the exact keys. The old constructors stay. The design and install guide say so.

## Tests asked for → where (row → result)

| Row | Result | Main commit | Test |
|---|---|---|---|
| erase-log-22 | FIXED | `b192e03` | `RestoreAndReplayTest.anEntryThatCouldNotBeAppliedIsInTheGuardsStateUntilItIsApplied`, `EraseLogWiringTest.healthDetailsAreExactlyTheSafeOnes` |
| erase-log-51 | TESTED | `cd56444` | `RestoreAndReplayTest.twoErasersAndAReplayAtOnceApplyEveryEntryOnceAndInOrder` |
| erase-log-57 | TESTED | `cd56444` | `…aHandlersWriteThroughThePositionsConnectionCommitsWithThePositionOrNotAtAll` |
| erase-log-67 | TESTED | `cd56444` | `…theFullCheckWhileRunningNeverRecordsARestore` |
| erase-log-73 | TESTED | `cd56444` | `…aVerifyCheckThatThrowsKeepsTheSystemBlockedWithItsCode` |
| erase-log-97 | TESTED | `e4ab697` | `EraseLogWiringTest.theDailyRunPrunesOldAppliedEntriesAndSkipsPruningWhileBlocked` |
| erase-log-99 | TESTED | `e4ab697` | `…theHealthIsDownWithItsCodeForABrokenChainTheWrongLogOrAnUnreachablePosition` |
| erase-log-44 | TESTED (two other JVMs) | `23fefb1` | `FileEraseLogStoreTest.writersInSeveralProcessesAtOnceKeepOneChain` |
| erase-log-40 | TESTED | `23fefb1` | `FileEraseLogStoreTest.aLineWithAnEscapeOrAMissingFieldIsRefused` |
| case-fields-63 | TESTED | `6681ae1` | `PostgresValueStoreTest.aRecordWithoutPrimaryValuesIsARecordOfItsOwn` |
| case-fields-73 | TESTED | `6681ae1` | `PostgresValueStoreTest.aSealedValueMovedToAnotherFieldOrPositionDoesNotOpen` |
| case-fields-113 | TESTED | `7678f9b` | `FieldSchemaTest.aUserWithEveryRightButDeleteOnTheRecordTableIsRefused` |
| crypto-11 | TESTED | `39472e1` | `VaultCryptoTest.bothKeysAreNeitherExportableNorInPlaintextBackups` |
| crypto-23, -28, -40 | TESTED (known answers) | `4f4b467` | `KnownAnswerTest` (three methods) |
| crypto-51 | TESTED; doc aligned (`e8b1f2c`) | `39472e1` | `VaultCryptoTest.anExpiredAppRoleTokenIsReplacedByOneNewLogin` |
| crypto-60 | TESTED | `5c6529a` | `NoSecretInLogsTest.secretsNeverLeave` |
| crypto-63 | TESTED | `2e3436b` | `VaultRedirectTest.aRedirectIsAnErrorAndTheTokenNeverGoesElsewhere` |
| crypto-94 | TESTED | `e224458` | `KeyDestructionVaultTest.keysCreatedAgainAfterDestructionAreNewAndOpenNothingOld` |
| licensing-file-format-27 | TESTED (known answers) | `ec88c4e` | `FormatsKnownAnswerTest` (two methods) |
| licensing-file-format-50 | TESTED | `1aac687` | `issue.SignatureMismatchTest.anotherKeysSignatureIsRefusedAndNothingIsWritten` |
| licensing-file-format-25, -31 | TESTED | `ed1eb0d` | `LicenceVerifierTest.aSignedPayloadWithAnUnknownFieldIsMalformed`, `…malformed` |
| licensing-file-format-16 | TESTED | `2bb79be` | `LicenceModelTest.slotAndAllowanceRules` |
| licensing-file-format-62 | TESTED | `d00dab5` | `UsageExportTest.aRecordOutsideTheHeadersPeriodIsDetected` |
| licensing-44 / -79 | TESTED | `24f6fac` | `LicenceVerifierTest.refusalsAreLoggedWithTheCodeOnly`, `LicensingWiringTest.logLinesShowNoKeysOrSignatures` |
| licensing-92 | TESTED | `44284a5` | `LicensingActuatorHealthTest` (two methods) |

**Each test fails without its rule.** For each row, the rule was broken on purpose in the code and the test was seen
to fail. Some examples:
- the advisory lock made a no-op;
- the file lock removed;
- the retry moved to 401;
- the period check removed;
- the signature check in `VaultTransitSigner` removed.

## Files changed, commits

20 commits on main, `4f4b467` … `2bcb1bf`. Two agents wrote the erase-log and case-fields rows, and the other crypto
and licensing rows, in separate worktrees, and I reviewed and cherry-picked them. I wrote the known-answer tests
myself.

Things worth knowing about those commits:
- **New test dependency:** `spring-boot-actuator-autoconfigure` in platform-licensing-spring, test scope only.
- **Test helper:** a short-lived AppRole role in the crypto tests' `VaultDev`.
- **New test files:**
  - `crypto.envelope.KnownAnswerTest`, `fields.values.FieldsKnownAnswerTest`, `licensing.FormatsKnownAnswerTest`;
  - `VaultRedirectTest`, `SignatureMismatchTest`, `LicensingActuatorHealthTest`;
  - the extra-process main class `AppendInAnotherProcess`.

## Decisions I had to make

1. **crypto-51:** Vault 1.17 answers **403** for an expired token, not 401. The code already logs in again once on a
   403, so the design's "401/403" was the part to change.
2. **Record envelope known answer:** `RecordCipher` has no fixed-nonce hook, and I didn't add one to production code.
   So the item is checked the other way round: the text built from the design is opened by the code, and the code's
   envelope is compared with the one built from the design.
3. **Where the design is silent, the format was frozen, not judged.** The design doesn't name the separator in the
   fields record context (the code uses `|`) or the sealed form of a value (kind letter, U+001E, U+001F). These are
   frozen by `FieldsKnownAnswerTest`; writing them into `case-fields.md` belongs to P040.
4. **erase-log-22:** the last failure is kept in memory by the instance that tried the erase, not in the database.
   Another instance shows it after its own attempt. Nothing new is stored.

## Not done, and why

Nothing.

## Questions (numbered, each with a recommendation)

None.
