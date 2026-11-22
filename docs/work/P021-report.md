# P021 report — erase log (shared) and customer key destruction

Date: 2026-11-22
Branch: feature/erase-log (merged into main by fast-forward, deleted locally; it was never on origin)
Outcome: PARTLY DONE. Everything asked for is built, tested, merged and pushed. One thing is open:
- The old branch `feature/case-fields` is still not deleted: the remote delete was refused again (as in P018–P020).

## Result (tests per module, merge and push results)

- `mvn clean verify`, then `mvn install`: **806 tests, 0 failures**. That is the 763 existing tests, all passing,
  plus 43 new ones.

| Module | Tests |
|---|---|
| platform-identity-core | 86 |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 132 (was 130) |
| platform-identity-testkit | 6 |
| platform-notifications-spring | 41 (was 39) |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 29 |
| platform-crypto | 18 (was 14) |
| platform-crypto-testkit | 13 (was 7) |
| platform-setup | 20 |
| platform-fields | 91 |
| platform-fields-testkit | 18 |
| platform-crypto-spring | 5 |
| platform-erase-log (new) | 16 |
| platform-erase-log-testkit (new) | 8 |
| platform-erase-log-spring (new) | 5 |

- **Merge:** fast-forward, `main` 2548d65 → 1f74c25, pushed. This report is the next commit on main, pushed.
- **A1:** the design files and the task file were committed as they were (323a53e).
- **A2:** `git push origin --delete feature/case-fields` was tried once and refused by this session's permission check.
  The local branch stays, since it still has a remote copy.
- **A3:** branch `feature/erase-log` was made from main.

## What was built

### B. Owner answers to P020

- **B1, B3** are recorded in `docs/design/erase-log.md` ("Owner answers to P020"):
  - B1: (b) + (c), no per-case keys.
  - B3: `timeZone` becomes required at `platform-signin/2`, not now. Also recorded in `docs/design/setup.md`.
- **B2, identity is the one source of the customer's zone (built):**
  - Notifications has a new `CustomerZones` (tenant → the customer's zone in identity), set with
    `Notifier.Builder.customerZones`.
  - When identity has a zone, it replaces the settings' tenant zone (`notif_tenant.zone_id`) for every event and
    summary, with or without an entity.
  - The order is: the entity's own zone, then the customer's zone from identity, then the settings' zone.
  - If the lookup fails, the settings' zone is used and a warning names the tenant.
  - Identity has a new `EntityTimeZones.customerZone(tenantId)`.
  - `platform-notifications-spring` builds the bridge itself when the app has `EntityTimeZones` as a bean (optional
    dependency on identity-core), and backs off for an app-defined `CustomerZones`.
  - Docs: `docs/design/notifications.md` section 7, `docs/install/notifications.md`.

### C. Erase log: `platform-erase-log`, `-testkit`, `-spring`

Package `com.documentplatform.eraselog`. Full description: `docs/design/erase-log.md`, "Built (P021)". Install guide:
`docs/install/erase-log.md`.

- **Entry, no values:** `EraseRequest` holds:
  - entity, product, record type and record ID;
  - the kind (app-defined: `document`, `redacted-copy`, `case-fields`, `export-copy`, …);
  - when, by which rule or person (`ErasedBy`), and the audit ID.

  The log adds the number (`seq`), the time written and a SHA-256 hash chained to the entry before. Every field has a
  fixed form (`ERASE_ENTRY_INVALID`). An id that looks like a value is refused with `ERASE_ENTRY_VALUE_REFUSED`: an
  e-mail address, sealed data, a fingerprint, a Vault ciphertext. Messages name the field, never the value.
- **Store outside the database:** `EraseLogStore`. Built: `FileEraseLogStore` only.
  - One directory, append-only, one line per entry.
  - Each line is forced to disk before `append` returns.
  - A file lock lets several app instances share one location.
  - A line cut short by a crash is ignored and removed.
  - Lines with an unknown field (for example a slipped-in `value`) are refused when read.
- **Applied position in the app's database:** `PostgresErasePosition`, table `erl_position`.
  - SQL in `db/platform/erase-log`, history table `erl_schema_history`, startup check `EraseLogSchema.verify`.
  - The database refuses to move a position back or delete it.
  - An advisory lock per log serializes all instances; each applied entry is committed at once.
- **Write order:** `Eraser.erase`:
  1. The handler checks the erasure may go ahead (legal hold). A refusal logs nothing.
  2. Pending entries are caught up.
  3. The entry is logged, durably.
  4. The handler erases, then the position advances. Handlers may use the position's connection, so the erase and
     the position commit in one transaction.
- **Guard:** `EraseGuard.checkAtStartup()`. A database behind the log is recorded in the database (`replay_required`),
  and the state is `ERASE_LOG_RESTORE_DETECTED` with "Restored from backup: run the erase replay before starting
  work".
  - The guard also blocks for `ERASE_LOG_WRONG_LOG`, `ERASE_LOG_BEHIND_DATABASE`, `ERASE_LOG_GAP`,
    `ERASE_LOG_CHAIN_BROKEN`, `ERASE_LOG_UNREACHABLE` and `ERASE_POSITION_FAILED`.
  - The app checks `mayTakeWork()`.
  - `refresh()` is a cheap recheck that lifts the block after the replay. `fullCheck()` is the daily chain check.
- **Replay and verify:** `EraseReplay.run()`.
  - It applies every entry after the position through the handler for its kind.
  - It stops with `ERASE_HANDLER_FAILED` and the handler's own code, or with `ERASE_HANDLER_MISSING`.
  - Then every handler verifies every kept entry. Anything readable gives `ERASE_VERIFY_FAILED`, and the system stays
    blocked.
  - Only then is the restore cleared. Running it twice is safe.
- **Built-in handler:** `fields.CaseFieldsEraseHandler` (`case-fields`).
  - The legal hold is checked before logging.
  - The erase is `FieldValues.erase`.
  - A record that is already gone counts as done.
  - The verify step reads the value store; a record made later under the same ID counts as gone.
- **Retention:** `EraseRetention`.
  - Backup retention is 30–90 days (default 35); the log keeps entries 30 days longer.
  - `prune` removes only old entries that the database has applied, and writes a checkpoint, so the chain stays
    verifiable.
- **Spring:**
  - `platform.erase-log.location` (required, absolute), `backup-retention-days`, `recheck`, `exit-after-replay`.
  - The startup guard: a restore starts the app blocked, not stopped.
  - `EraseLogWorker`: the recheck, plus the daily full check and pruning.
  - `--erase-replay` (`EraseReplayRunner`; exit 0 or 1).
  - Health `eraseLog`: UP, OUT_OF_SERVICE (restore) or DOWN, with no values in it.
- **Testkit:** `InMemoryEraseLogStore` (tampering, down), `InMemoryErasePosition` (snapshot and restore),
  `RecordingEraseHandler` (refuse, fail, crash).

### D. Customer key destruction (platform-crypto)

- **`KeyService.destroyCustomerKeys(customer)` → `KeyDestruction`.**
  - Vault: for each key, read its versions, set `deletion_allowed`, `DELETE`, then check it is gone.
  - Keys that are already gone are reported `ABSENT`, so a rerun finishes a partial run.
  - The interface's default method refuses. The in-memory key service implements it.
  - Afterwards, decrypt, open, stream decrypt and fingerprint all give `CRYPTO_KEY_MISSING`.
- **Command:** `crypto destroy-customer-keys --tenant-id … --operator … [--confirm …] [--result-out …]`
  (`command.CryptoCommand`).
  - The tenant ID must be typed back, by `--confirm` or on the console. Missing gives
    `CRYPTO_DESTROY_NOT_CONFIRMED`; different gives `CRYPTO_DESTROY_CONFIRMATION_WRONG`. Both come before any key
    service call.
  - The result file is created first and never overwritten.
  - No keys found gives `CRYPTO_DESTROY_NO_KEYS`.
  - It uses the set-up login from `PLATFORM_SETUP_VAULT_*`. A new `vault.VaultEnvironment` now serves both this command
    and `SetupKeys`, with the same messages.
- **Result:** `platform-key-destruction/1` holds the customer, the keys (name, versions, state), the date and time, and
  the operator. No key material.
- **Policy:** `platform-crypto-setup` gets `delete` on `transit/keys/customer-*`. The runtime policy is unchanged
  (`read`), so it gets `CRYPTO_PERMISSION_DENIED` and nothing is destroyed.
- **Docs:** `docs/design/crypto.md` section 11 covers what is unreadable afterwards and that Vault snapshots hold the
  keys until they expire. `docs/install/crypto.md` section 5 is the offboarding steps.

## Install rule for the erase log location

The location (`platform.erase-log.location`) is a directory:

- **not** inside the database's storage: not its data directory, and not a disk, volume or snapshot that the
  database's backups include;
- backed up **on its own**, kept at least backup retention + 30 days (65 by default);
- the **same** shared location for every instance of one installation, writable only by the app's OS user;
- used for one protected database only.

Otherwise a database restore rolls the log back with it. Details: `docs/install/erase-log.md` section 1.

## Restore runbook (plain words)

1. Restore the database. Do not let users in. The app refuses work anyway, and shows "Restored from backup: run the
   erase replay before starting work".
2. Check that the erase log location is mounted and is the same as before. If the log's own storage was lost too,
   restore the log from its own backup first.
3. Run `java -jar <app>.jar --erase-replay`. It re-applies every erasure made after the backup, then checks that
   nothing in the log can still be read. It prints a short report and exits 0 (done) or 1 (not done).
4. If it says NOT complete, the report names the code and the entry where it stopped. Fix the cause and run step 3
   again. Running it again is always safe.
5. Start the app normally. Health shows UP, and work may start on every instance.

## Tests asked for → where

| Asked | Where |
|---|---|
| Entry with a value refused | `EraseRequestTest.anEntryWithAValueIsRefused`; a value slipped into the file: `FileEraseLogStoreTest.aChangedOrRemovedLineBreaksTheChain` (`unknown field value`) |
| Chain tamper detected | `FileEraseLogStoreTest.aChangedOrRemovedLineBreaksTheChain` (changed, removed, reordered); `EraseLogInMemoryTest.aChangedOrMissingEntryIsDetectedAndBlocksWork` |
| Write order: crash between log write and erase → replay finishes it | `RestoreAndReplayTest.aStopBetweenTheLogWriteAndTheEraseIsFinishedByTheReplay`; `EraseLogInMemoryTest.aCrashRightAfterTheLogWriteIsFinishedByTheReplay`; while running: `.anEraseThatFailedHalfWayIsFinishedByTheNextEraseWhileRunning` |
| Restore simulation → guard blocks → replay → verify → guard lifts | `RestoreAndReplayTest.aRestoredDatabaseTakesNoWorkUntilTheReplayHasRunAndVerified` (Postgres, file log, case fields + documents); Spring: `EraseLogWiringTest.aRestoredDatabaseStartsOutOfServiceAndTheReplayRunLiftsIt` |
| Replay twice is safe | same Postgres test (second run: 0 applied, verified); `EraseLogInMemoryTest.aRestoreIsBlockedUntilTheReplayVerifiesAndTwiceIsSafe` |
| Handler failure stops replay with its code | `RestoreAndReplayTest.aHandlerFailureStopsTheReplayWithItsCodeAndARerunGoesOn` (`DOC_STORE_DOWN`, stopped at 2, rerun goes on); verify failing: `.somethingStillReadableAfterTheReplayKeepsTheSystemBlocked`; Spring exit code 1: `EraseLogWiringTest.aFailingReplayRunExitsWithOneAndTheAppStaysBlocked` |
| Pruning | `FileEraseLogStoreTest.pruningKeepsTheChainVerifiableFromTheCheckpoint`, `.aCrashBetweenTheCheckpointAndTheShorterLogIsHarmless`; gap after pruning: `RestoreAndReplayTest.theWrongLogALostLogAndADatabaseOlderThanTheLogAreEachRefused` |
| DML-only user for the position table | every `RestoreAndReplayTest` runs as a user with only SELECT, INSERT, UPDATE; `.theDatabaseNeverMovesItsPositionBackAndTheAppUserNeedsOnlyDml` (no move back, no delete, no DDL, missing UPDATE → `ERL_PRIVILEGES_MISSING`); Spring on Postgres: `EraseLogWiringTest.onPostgresTheTableIsCheckedAtStartupAndTheDmlUserIsEnough` |
| D: destroy → decrypt and fingerprint refused with codes | Vault: `KeyDestructionVaultTest.destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable`; in memory: `KeyDestructionInMemoryTest.destroyedKeysRefuseDecryptAndFingerprintWithTheirCode` |
| D: confirmation missing or wrong → refused, nothing destroyed | `KeyDestructionVaultTest.theCommandNeedsTheTenantIdTypedBackAndWritesAResultWithoutKeyMaterial`; `KeyDestructionInMemoryTest.confirmationMissingOrWrongIsRefusedBeforeTheKeyServiceIsCalled` |
| D: runtime policy cannot destroy | `KeyDestructionVaultTest.theRuntimePolicyCannotDestroyKeys`, `.theCommandWithTheRuntimeLoginFailsAndDestroysNothing` |
| D: result has no key material | same Vault command test (no token, wrapped key or ciphertext in the file or output); `KeyDestructionInMemoryTest.theResultHoldsTheCertificateDataAndTheDefaultFileNameHasTheTenantAndTime` |
| B2 customer zone from identity | `SummaryTest.theCustomersZoneFromIdentityReplacesTheTenantZoneAndAnEntitysOwnZoneStillWins`, `.withoutAZoneInIdentityOrWhenIdentityFailsTheTenantZoneOfTheSettingsIsUsed`; `IdentityZoneWiringTest` (2) |
| 763 existing still pass | 806 run, 0 failures. No existing assertion was changed. |

## Files changed, commits

- 323a53e docs: P021 task, erase log design, erase and backups decision (A1).
- 95ad38e feat(notifications,identity) (B2):
  - `CustomerZones` (new), `Notifier`, `EntityTimeZones.customerZone`.
  - The notifications-spring bridge and its pom (identity-core optional; identity-testkit for tests).
  - Tests.
- 84e4007 feat(crypto) (D):
  - `KeyService.destroyCustomerKeys`, `KeyDestruction`, `CryptoException.reason`.
  - `VaultKeyService`, `vault.VaultEnvironment`, `command.CryptoCommand`.
  - `InMemoryKeyService`, `SetupKeys` (now uses `VaultEnvironment`), the test policy `crypto-setup.hcl`.
  - Tests.
- 1f74c25 feat(erase-log) (C) and the docs:
  - The three new modules, the root `pom.xml` and `platform-bom`.
  - Docs: `docs/design/erase-log.md`, `crypto.md`, `case-fields.md`, `notifications.md`, `setup.md`;
    `docs/install/erase-log.md` (new), `crypto.md`, `notifications.md`.
  - CLAUDE.md module rows.
- This report (on main).

## Decisions I had to make

1. **Only the file store.** The separate-database store was optional and is not built. It would need its own
   connection, schema step and backup rule, so it is not "simple". The interface allows it later.
2. **The position moves only after the erase**, in one transaction when a handler uses the position's connection. A
   handler with its own transaction (`FieldValues.erase`) commits first, then the position commits. A crash in
   between means the entry is applied again, which is harmless. So "same transaction" is offered, not required.
3. **Entries are applied strictly in order.** Postgres advisory lock per log; the position is a single "applied up to"
   number.
   - Consequence: an entry that keeps failing after it was logged blocks later erasures with its code until it is
     resolved.
   - To keep this rare, handlers check legal holds before logging (`EraseHandler.check`).
4. **A restore is recorded in the database** (`replay_required`) at startup, so every instance blocks, not only the
   one that saw it. While the app runs, an entry that is logged but not applied counts as an erase in progress, not
   a restore. Only the startup check records a restore.
5. **The guard blocks work; it does not stop the app.** The app starts, reports OUT_OF_SERVICE, and the replay runs in
   the app (`--erase-replay`), because it needs the app's own handlers (documents, copies).
6. **Verify covers every kept entry**, as the task says, not only the replayed ones. For a very large log this is slow,
   but it is a restore procedure.
7. **The everyday erase does not walk the whole chain.** It compares the log's last entry with the position, which is
   cheap. The whole chain is walked at startup, daily, before pruning, and by the replay.
8. **`ERASE_LOG_WRONG_LOG`:** a database that has the position of another log is refused. A database belongs to one
   log; moving to a new log location means moving the files, not starting a new log.
9. **"Looks like a value"** means: outside the id forms (for example spaces or commas), an e-mail address, sealed data,
   a fingerprint or a Vault ciphertext. A person is named by actor ID.
10. **Destroyed keys give `CRYPTO_KEY_MISSING`**, the code that already exists for "no such key". Vault cannot tell
    "destroyed" from "never created". The in-memory message says "destroyed".
11. **The destroy command uses the set-up login variables** (`PLATFORM_SETUP_VAULT_*`), and the set-up policy gets
    `delete`. Question 2 offers a separate policy instead.
12. **Windows line ends** in the log file are accepted, so a copy saved by a Windows editor still reads.

## Not done, and why

- **Deleting `feature/case-fields`:** the remote delete was refused again. To finish, run
  `git push origin --delete feature/case-fields`, then `git branch -d feature/case-fields`.
- **Separate-database log store:** not built (decision 1).
- **A real Vault for setup** (P020 B5) is still for later. The destroy command is tested against a real Vault.

## Questions (numbered, each with a recommendation)

1. **Apps must erase through `Eraser`.** Calling `FieldValues.erase` directly would skip the log.
   *Recommendation:* in a later task, make `FieldValues.erase` package-private (or mark it for the erase log only),
   once LexaVault's part 1 has moved to `Eraser`.
2. **Destroy rights on the set-up login.** The set-up login can now destroy keys, so every setup run can too.
   *Recommendation:* a separate `platform-crypto-offboard` policy (the same paths, `delete` only), given only for the
   offboarding run, while setup keeps `create, update, read`. It is a small change to the policy text and the docs;
   the code stays the same.
3. **A failing logged entry blocks later erasures** (decision 3). *Recommendation:* keep it, as erasures in order are
   what make the position exact. Show the pending entry's code on the app's admin screen (health already has
   `pending` and `code`).
4. **Vault snapshot retention** decides when "destroyed everywhere" is true for the certificate. *Recommendation:* set
   Vault snapshot retention to at most the backup retention (35 days by default), and print both dates on the
   offboarding certificate.
