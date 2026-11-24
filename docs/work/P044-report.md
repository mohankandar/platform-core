# P044 report — security fixes 2: erase-log integrity, key-service policies, integration secrets and callbacks

Date: 2026-11-23
Branch: `sec/eraselog-keys-callbacks` (then `docs/p044-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Merged as |
|---|---|---|---|
| FIX-B, FIX-C, FIX-F | #56 | 45299619829, 45299628124 | `0805b0c`, `3dcfeaa`, `e8b531c`, `600d3da`, `0f1024d` (catalogue), `2763f58`, `6f55624` (main CI 45300573372) |
| This report | this PR | — | — |

**Local `mvn verify`:** 1460 tests over 31 modules, 0 failures (the last full run also shows `platform-error-catalogue` passing once the new SQL file was pinned), 11 skipped.
- The skips are this laptop only: 10 TLS tests where TLS to 127.0.0.1 is intercepted, and 1 symbolic-link test (Windows rights). CI forbids skips and ran everything.
- One run on the way failed `WebhookChannelsTest.eachHttpReplyHasItsSpecificCode` (got `WEBHOOK_TIMEOUT` instead of `WEBHOOK_PAYLOAD_REJECTED`). The module has no change in this task, and the test passed when run twice alone right after. It is a timing flake on this laptop; it is not hidden, and CI was green.

Tests per module (new tests in brackets):

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 34 (+5) | platform-integration-testkit | 219 (+4) |
| platform-crypto-spring | 6 | platform-licensing | 52 |
| platform-crypto-testkit | 16 | platform-licensing-issue | 104 |
| platform-erase-log | 34 (+6) | platform-licensing-spring | 37 |
| platform-erase-log-spring | 11 (+3) | platform-licensing-testkit | 49 |
| platform-erase-log-testkit | 23 (+14) | platform-model-governance-spring | 7 |
| platform-error-catalogue | 15 | platform-model-governance-testkit | 74 |
| platform-errors | 9 | platform-monitoring-spring | 11 |
| platform-fields | 99 | platform-monitoring-testkit | 17 |
| platform-fields-testkit | 20 | platform-notifications | 85 |
| platform-identity-core | 121 | platform-notifications-spring | 44 |
| platform-identity-jdbc | 68 | platform-notifications-testkit | 135 |
| platform-identity-spring | 30 | platform-outbound | 9 |
| platform-identity-testkit | 14 | platform-outbound-spring | 6 |
| platform-integration | 55 | platform-setup | 47 |
| platform-integration-spring | 9 | | |

## Independence

No other product's name or behaviour in code.

## What was built

### Findings

| Finding | Result | Commit on main | Test (reproduces the attack or mistake; fails without the fix) |
|---|---|---|---|
| SEC-3 forged erasures | FIXED | `0805b0c` | `KeyedEraseLogTest`: forged entry without the key, or with another key, never erased by the next erase or the replay; removed seal; old entries rewritten consistently caught by the seal. `KeyedFileEraseLogTest`: an entry appended through the folder is named as the first bad one; changed seal; another customer's key. `KeyedEraseLogWiringTest`. |
| SEC-4 restore while running | FIXED | `0805b0c` | `KeyedEraseLogTest.aDatabaseRestoredWhileTheAppKeepsRunning…`, `theEraseItselfSeesARestore…`. Both failed with the high-water mark switched off. Control: `aHandlerThatFailedIsAnEraseInProgressNotARestore`. |
| SEC-5 erasure outside the log | FIXED | `0805b0c` | `CaseFieldsEraseHandlerTest.theHandlerRefusesAnErasureTheLogDidNotHandOver` (the review's call, now refused). `EraseOnlyThroughTheLogTest` (the build test: tokens cannot be made outside, every erasing public method reviewed, a replace that takes every value away refused). |
| SEC-49 hardening | FIXED | `0805b0c` | `KeyedFileEraseLogTest.aRecordedRestoreIsClearedOnlyByAVerifiedReplay` (schema V2, Postgres). The scan under the lock runs inside the existing prune tests. Owner-only folder and aborting a connection whose unlock failed have no dedicated test (decision 6). |
| SEC-2 set-up policy can destroy | FIXED | `3dcfeaa` | `SetupPolicyVaultTest` against a real Vault: the review's attack step by step (config `min_decryption_version`, trim, export, plaintext backup, import, delete) all 403; version 1 still opens. The first run, with `deny` rules on `customer-*/config`, got **200**, which proved the gap and the reason (decision 2). |
| SEC-30 Vault over http | FIXED | `3dcfeaa` | `VaultAddressTest.plainHttpIsRefusedUnlessItStaysOnThisMachine` |
| SEC-48 smaller crypto items | PARTLY FIXED | `3dcfeaa` | Deletion latch: `SetupPolicyVaultTest.aLoginWhoseRightsVaultCannotReportIsRefused…`. Reply size: `VaultAddressTest.aReplyLargerThanAnyVaultAnswerIsRefused`. Runbook note. **Fingerprint canonical form not changed** (decision 4, question 1). |
| SEC-8 callback resent under a new ID | FIXED | `e8b531c` | `CallbackTest.aCapturedCallbackSentAgainUnderANewIdIsRefused`; v2 test vector computed with openssl. |
| SEC-10 another entity's secret | FIXED | `600d3da` | `SecretScopeTest`: entity B names A's secret. Refused at save; at use, the secret store is never asked and nothing reaches B's token address. With the rule switched off, A's secret was resolved and sent. |

### FIX-B: erase log (SEC-3, SEC-4, SEC-5, SEC-49)

- **Keyed chain:**
  - `ChainKey` is the port. The built key, `crypto.KeyServiceChainKey`, is the customer's fingerprint key in the key service.
  - The erase log's MAC inputs start with `platform-erase-log/mac/1` or `…/seal/1` and NUL. Field fingerprints start with `platform-crypto/fp/1` and NUL, so no fingerprint is ever a valid erase-log MAC (checked in `Fingerprinter`).
  - Entries in format `platform-erase-log/2` carry `kv` and `mac` over their hash.
  - `erase-log.seal` (`Seal`) fixes the entry from which MACs are required and the hash before it.
  - A new log is sealed at its first entry. An existing one is sealed by the explicit migration `EraseLog.seal()` / `--erase-log-seal` (`EraseLogSealRunner`), which checks the chain first and rewrites no entry.
  - `EraseLog.verify` and `EraseLog.pending` (what the eraser and the replay act on) require the seal and the MACs, naming the first bad entry.
- **Spring wiring:** keyed whenever the app has `KeyService` and `CustomerId`. `platform.erase-log.keyed=true` refuses to start without a key; `false` opts out, with a warning.
- **New codes:** `ERASE_LOG_NOT_SEALED`, `ERASE_LOG_KEY_UNAVAILABLE`.
- **Restore while running:**
  - `erase-log.applied` (`EraseLogStore.appliedHighWater` / `recordApplied`) is raised after each applied entry, outside the database.
  - A database position below it is a restore. `Eraser.erase` and `EraseGuard.refresh` / `fullCheck` record it (`markRestoreDetected`, committed at once), so every instance stops until the replay.
  - A handler failure or a crash leaves the position equal to the mark, so it is still an erase in progress, as before.
- **Erasure only through the log:**
  - `Applying.Issued` is the handler context, made only by the erase log and bound to the entry.
  - `EraseHandler.Context.issuedFor` tells it apart. `CaseFieldsEraseHandler` refuses any other context (`VALUES_ERASE_OUTSIDE_LOG`).
  - `PostgresValueStore.replace`, the in-memory store's `replace` and `FieldValues.update` refuse to leave a record with no value at all.
- **Hardening (SEC-49):**
  - `FileEraseLogStore.scan` takes the (now reentrant) lock.
  - A location made by the store is `rwx------` where POSIX allows.
  - `PostgresErasePosition` aborts a connection whose advisory unlock failed.
  - Schema **V2** (`V2__erase_log_replay_guard.sql`, pinned in `ReleasedSqlFilesTest`) refuses clearing `replay_required` without a newer `verified_at`.
  - The install guide says record IDs must not be personal data.

### FIX-C: key service (SEC-2, SEC-30, SEC-48)

- **Policies:**
  - The set-up policy is `create`/`update`/`read` on `transit/keys/+` and `update` on `transit/keys/+/rotate`.
  - The offboard policy is `read`/`delete` on `transit/keys/+` and `update` on `transit/keys/+/config`, given alone to the offboarding run.
  - Install guide (checked by `PolicyTextInDocsTest`), design section 7 and section 11, and the command's Javadoc are updated, with the upgrade step for existing installations.
- **SEC-30:** `VaultClient` and `platform.crypto.vault.address` refuse `http` except to `localhost`, `127.0.0.1` and `[::1]`.
- **SEC-48:**
  - `destroyCustomerKeys` refuses (`CRYPTO_PERMISSION_DENIED`) when `sys/capabilities-self` cannot answer, before touching the key.
  - Vault replies are read up to 1 MiB (`CRYPTO_KEY_SERVICE_ERROR` beyond).
  - The runbook says how to finish a destruction that stopped half-way.

### FIX-F: integration (SEC-8, SEC-10)

- **SEC-8:**
  - `CallbackSignature.v2` = HMAC-SHA256 over `t "." id "." body`; the sender sends `v2` only.
  - `CallbackVerifier` accepts `v2`, and `v1` only with `CallbackVerifier.Policy.acceptV1`. In Spring that is `platform.integration.callbacks.accept-v1-signatures`, false by default, with a bean `CallbackVerifier.Policy`.
  - The repeat check keys on what was signed (the ID; for `v1`, the signature itself).
  - Receiver steps and the openssl example are rewritten (install guide section 9).
- **SEC-10:**
  - **The rule:** `config.SecretScope`, `int/<entity UUID or slot ID>/<name>`, for every secret reference an integration holds.
  - **At save:** `ConfigCheck` reports `INT_CONFIG_SECRET_SCOPE` per field.
  - **At use:** the engines stop with it (attempt, status, callback, poll, health), and `IntegrationCredentials` refuses an out-of-scope keystore password. Both happen before `SecretResolver`.
  - **The token cache:** references now differ per entity, so the cache can no longer cross entities.
  - **Testkit and tests:** `SlotIds` moved to `integration.internal`. `ProfileContract.ENTITY` and a scoped `ProfileMock.SECRET_REF` are in the testkit; the profile validator's placeholders are scoped too. Test fixtures use scoped references.
  - **Docs:** the naming rule is in the install guide, section 1 "Secret references", and in the codes table.
- **New code:** `INT_CONFIG_SECRET_SCOPE`. The catalogue is regenerated.

## Tests asked for → where

| Asked for | Where |
|---|---|
| SEC-3, a test that reproduces the forgery; the migration; the first bad entry named | `KeyedEraseLogTest`, `KeyedFileEraseLogTest`, `KeyedEraseLogWiringTest` (platform-erase-log, -testkit, -spring) |
| SEC-4, a restore while running noticed per write and on a schedule | `KeyedEraseLogTest` (erase, `refresh`, the replay lifting it) |
| SEC-5, every path closed, and a build test | `CaseFieldsEraseHandlerTest`, `EraseOnlyThroughTheLogTest` |
| SEC-2, a negative test against a real Vault (Testcontainers) | `SetupPolicyVaultTest` (platform-crypto, `hashicorp/vault:1.17.6`) |
| SEC-30, SEC-48 | `VaultAddressTest`, `SetupPolicyVaultTest` |
| SEC-8, v2, v1 off by default | `CallbackTest` |
| SEC-10, at save and at use | `SecretScopeTest` |

## Files changed, commits

- **Erase log:**
  - `platform-erase-log`: `ChainKey`, `Seal`, `EraseEntry`, `EraseLog`, `EraseLogStore`, `Eraser`, `EraseGuard`, `EraseReplay`, `Applying`, `EraseHandler`, `EraseLogError` (+ texts), `crypto/KeyServiceChainKey`, `fields/CaseFieldsEraseHandler`, `file/FileEraseLogStore`, `file/Lines`, `jdbc/EraseLogSchema`, `jdbc/PostgresErasePosition`, `V2__erase_log_replay_guard.sql`, pom (optional crypto).
  - `platform-erase-log-testkit`: `InMemoryEraseLogStore`.
  - `platform-erase-log-spring`: `EraseLogAutoConfiguration`, `EraseLogProperties`, `EraseLogSealRunner`, pom.
- **Fields:** `platform-fields` `FieldValues`, `PostgresValueStore`, texts; `platform-fields-testkit` `InMemoryValueStore`.
- **Crypto:**
  - `platform-crypto`: `VaultClient`, `VaultKeyService`, `CryptoCommand`, policies `crypto-setup.hcl` / `crypto-offboard.hcl`.
  - `platform-crypto-spring`: `CryptoConfig`.
- **Integration:**
  - `platform-integration`: `CallbackSignature`, `CallbackVerifier`, `CallEngine`, `PollEngine`, `ConfigCheck`, new `config/SecretScope`, `IntegrationCode` (+ texts), `IntegrationCredentials`, `ProfileValidator`, `internal/SlotIds` (moved).
  - `platform-integration-spring`: properties and bean.
  - `platform-integration-testkit`: `ProfileContract`, `ProfileMock`.
- **Tests:** the new tests listed above, and scoped references in the integration and monitoring tests.
- **Docs:**
  - install guides: erase-log, crypto, integration;
  - design: erase-log, crypto, integration-module;
  - `docs/errors/*`, `docs/work/security-findings.md` (findings marked), this report.
- **Commits on main:** `0805b0c`, `3dcfeaa`, `e8b531c`, `600d3da`, `0f1024d`, `2763f58`, `6f55624`.

## Decisions I had to make

1. **The erase log's key is the customer's fingerprint key, with the erase log's own domain prefix.** A key type of its own would have meant a new Transit key, set-up and policy changes in every installation. Field fingerprints carry `platform-crypto/fp/1` as their domain, and nothing else calls the key service's HMAC, so the two cannot collide.
2. **Vault policies use `+` segments, not `deny` rules.**
   - A `*` in the middle of a Vault policy path is not a wildcard, so `transit/keys/customer-*/config` matched nothing. The real-Vault test showed `config` still allowed (200).
   - A `+` rule cannot outrank the trailing glob either, because Vault gives a later first wildcard the higher priority.
   - So the policies list exactly what each step calls. Trade-off: they are scoped to the Transit mount, not the `customer-` prefix. The install guide says to keep other keys in other mounts.
3. **Sealing an existing erase log is explicit (`--erase-log-seal`), not automatic at start.** "Never a silent rewrite": sealing at start would also seal whatever someone wrote there before the upgrade, without anyone looking. The keyed app takes no work until IT ran it (`ERASE_LOG_NOT_SEALED`).
4. **The fingerprint canonical form (part of SEC-48) is unchanged.** Field fingerprints are stored, and they also make up a record's identity. A new form (Unicode spaces, NFKC) would make exact search miss existing values and let `claim` create duplicate records. That is a stored-format change, so per the task it is a question (1), not done. The finding marked it "consider".
5. **SEC-5's limit is said plainly.** Code that holds the app's `ValueStore` holds the database. It can still write rows, as anyone with the database login can. The fix closes every path through the platform's public API and adds a build test that keeps it closed.
6. **Owner-only folder permissions and aborting a connection whose unlock failed have no dedicated test.** The first is POSIX-only (this laptop is Windows; CI is Linux but has no permission assertion yet). The second needs a database that fails the unlock. Both are small, read by review, and noted here.
7. **Restore detection uses a high-water mark kept with the log**, not the lock alone. A lock cannot tell a restored database from an erase whose handler failed. The mark can: a failure leaves the database at the mark, a restore takes it below.

## Not done, and why

- **The fingerprint canonical form (SEC-48, part):** decision 4, question 1.

## Questions

1. **Fingerprint canonical form:** should a later task bring in a version-2 canonical form (Unicode white space, perhaps NFKC), with search computing both forms and record identity migrated per record? Recommendation: only if a customer reports missed exact matches. The current form has no collisions, only missed matches for values that contain non-breaking or full-width spaces. Not before the integration release. This changes nothing in P045.
2. **Installations on 0.6 with an erase log:** on upgrading to 0.7 with the key service, the app stays paused until IT runs `--erase-log-seal` once. Recommendation: put this step in the 0.7.0 release notes' upgrade list (P045 does) and in the installation runbook.
