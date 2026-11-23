# platform-erase-log — erase log and customer key destruction (decided 2026-11-22)

Source: owner decision (b) + (c), confirmed by the LexaVault thread (master), 2026-11-22. LexaVault's record: `docs/work/erase-and-backups.md` in secure-document-platform. Project doc: `Erase-Log-Design.md`. Related: `docs/design/case-fields.md` ("Erasing a record", "Erase and backups"), `docs/design/crypto.md`.

## The rule

- **(b) Everyday erase and retention:** erasures remove data from the live database and storage at once. Backups are kept only for the backup retention period, then destroyed. If a backup is restored, every erasure made after that backup is **re-applied from the erase log before the restored system takes any work**.
- **(c) Customer exit:** destroying the customer's keys makes all their data unreadable everywhere, backups included. The offboarding certificate rests on this.
- **(a) Per-case keys:** not now; only if a customer contract requires it.

## Backup retention

- Default **35 days**; a setting per installation, allowed **30–90 days**, set by contract. The compliance report states the period in force.

## The erase log (shared: LexaVault and Vero both write to it)

- **Kept outside the database it protects** (its own store, backed up separately), so a restore can't roll it back.
- **Holds no values:** entity, case/record ID, what was erased (e.g. document, redacted copy, case fields, export copy), when, by which rule or person, the audit record ID.
- **Kept at least backup retention + 30 days** (default 35 + 30 = 65 days); then pruned, because every backup it could apply to is gone.
- **Restore procedure:** restore → **replay the erase log** → **check** that nothing in the log is readable → only then take work. **Replaying twice is safe.**
- **Shared part:** the log store, writing an entry, replay with app-registered handlers per "what" (platform-fields' record erase is one handler; documents and copies are the apps'), the verify step, idempotency, pruning, and a guard so a restored system doesn't take work before replay.
- **LexaVault** uses it in switch-over part 1; Vero with its retention work.

## Customer key destruction (customer exit)

- Part of the crypto module (P018 follow-up): destroy a customer's wrapping and fingerprint keys in the key service. Irreversible, so an explicit command with confirmation, audited, producing the data for the offboarding certificate (customer, keys destroyed, date).
- The offboarding runbook lists it as a step; the certificate lists the keys destroyed and the date.
- Built in P021: `KeyService.destroyCustomerKeys` and the `crypto destroy-customer-keys` command
  (`docs/design/crypto.md` section 11).

## Owner answers to P020 (2026-11-22, task P021)

1. Erase and backups: **(b) + (c)**, as above. **No per-case keys for now.**
2. Two customer time zones: **identity is the one source.** Notifications reads the customer's zone from identity
   (`idn_customer.time_zone`) when present, and falls back to its own tenant zone only when identity has none (built
   in P021, `docs/design/notifications.md` section 7).
3. `timeZone` becomes required in the sign-in file at the next format change (`platform-signin/2`); not now
   (`docs/design/setup.md`, "Time zones").

## Owner answers to P021 (2026-11-22, task P022)

1. **Erase only through the log, now.** `FieldValues.erase` refuses every caller except the built-in
   `CaseFieldsEraseHandler` with `VALUES_ERASE_OUTSIDE_LOG`, before anything is read or erased (built in P022; see
   "Built-in handler" below). Apps erase case fields with `Eraser.erase`, kind `case-fields`.
2. **Separate offboarding policy:** `platform-crypto-offboard` (`delete` only, the same paths), given only to the
   offboarding run; `platform-crypto-setup` keeps `create`, `update`, `read` and lost `delete`
   (`docs/design/crypto.md` sections 7 and 11).
3. **A failing logged entry blocks later erasures: kept.** Erasures are applied strictly in order, which is what makes
   the applied position exact. An entry that was logged but keeps failing (its handler's code, for example
   `DOC_STORE_DOWN`) stops every later erasure with that same code until it is resolved; nothing later is logged
   meanwhile. **Each app's admin screen shows the pending entry and its code**: entry number, kind, record type and
   ID, and the handler's code, from the `eraseLog` health details (`pending`, and `lastFailure` with `seq`, `kind`,
   `recordType`, `recordId`, `handlerCode`) or `EraseGuard.State.lastFailure()` (kept by the instance that tried,
   until the entry is applied). No value is shown, as there is none in the entry. Handlers keep this rare by refusing in `check` (legal hold)
   before anything is logged.
4. **Key service backups:** Vault snapshot retention at most the backup retention (35 days by default,
   `docs/install/crypto.md` section 6). The offboarding certificate data carries two dates: live data destroyed on
   …, and unreadable everywhere including backups from … (destruction day + backup retention)
   (`docs/design/crypto.md` section 11).

## Built (P021): modules `platform-erase-log`, `-testkit`, `-spring`

Package `com.documentplatform.eraselog`. Plain Java, no Spring; the only dependency is platform-fields, optional, for
the built-in `case-fields` handler. Install guide: `docs/install/erase-log.md`.

### Entry

`EraseRequest` (what the app gives) and `EraseEntry` (what the log holds). Ids only, a fixed set of fields:

| Field | Form |
|---|---|
| `entity` | the entity UUID |
| `product` | product code, `[A-Z][A-Z0-9_]{1,31}` |
| `recordType` | `[a-z][a-z0-9_.-]{0,63}` (as in platform-fields) |
| `recordId` | the record or case ID, `[A-Za-z0-9_.:@/-]{1,200}` |
| `kind` | what was erased, app-defined, `[a-z][a-z0-9-]{0,39}`: `document`, `redacted-copy`, `case-fields`, `export-copy` |
| `erasedAt` | when the erasure was decided |
| `by` | `RULE` + rule ID, or `PERSON` + actor ID |
| `auditId` | the app's audit record ID |
| `seq`, `loggedAt`, `prev`, `hash` | added by the log: number from 1 without gaps, time written, SHA-256 chain |

- **No values:** anything outside those forms is `ERASE_ENTRY_INVALID`. An id that looks like a value is
  `ERASE_ENTRY_VALUE_REFUSED`: an e-mail address, platform-crypto sealed data (`pcv1.`, `pci1.`, `pck1.`), a
  fingerprint, a Vault ciphertext. Messages name the field, never what was given. A person is named by actor ID, not
  by e-mail or name.
- **Chain:** `hash` = SHA-256 of `platform-erase-log/1|<log ID>|<seq>|<loggedAt>|<fields…>|<prev>`; the first `prev`
  is 64 zeros (or the checkpoint's hash). `EraseLog.verify()` walks it: a changed, removed, reordered or inserted
  entry is `ERASE_LOG_CHAIN_BROKEN`. Entries cut from the end do not show in the chain alone; the database's applied
  position catches that (`ERASE_LOG_BEHIND_DATABASE`).

### Store (outside the database)

`EraseLogStore`. Built: **`file.FileEraseLogStore`** only; the separate-database store was not built (see the P021
report). One directory, the configured location:

- `erase-log.jsonl`: one flat JSON line per entry, appended, forced to disk (`fsync`) before `append` returns. Lines
  with any other field, an escape or a missing field are refused when read (`ERASE_LOG_FORMAT`), so a value cannot be
  slipped in by hand either. Windows line ends are accepted.
- `erase-log.id`: the log's UUID, made once. If it is missing while entries exist, the log is refused (restore the
  file from the log's backup).
- `erase-log.checkpoint`: after pruning, the last pruned entry's number and hash.
- `erase-log.lock`: a file lock held while writing, so several app instances on one shared location append one at a
  time (plus a lock per location inside one JVM).
- A line cut short by a crash (no line end) was never confirmed, so it was never acted on: it is ignored when reading
  and removed before the next write.
- The newest entries are read from the end of the file, so an everyday erase stays cheap however long the log is.

### Applied position (in the app's database)

`ErasePosition`. Built: `jdbc.PostgresErasePosition` on table `erl_position` (SQL in `db/platform/erase-log`, history
table `erl_schema_history`, run only by the schema step; startup check `jdbc.EraseLogSchema.verify`). One row per log:
`applied_seq`, `applied_hash`, `replay_required`, `restore_detected_at`, `verified_at`.

- The database refuses to move a position back or to delete it.
- The app user needs SELECT, INSERT and UPDATE.
- `withLock` holds a Postgres advisory lock per log, so all instances apply entries one at a time and in order. Each
  applied entry is committed at once.

### Write order (`Eraser.erase`)

1. The kind's handler checks the erasure may go ahead (`EraseHandler.check`; for case fields: the legal hold). A
   refusal logs nothing.
2. With the position locked: entries that are in the log but not applied here (an earlier erase that stopped
   half-way) are applied first.
3. The entry is written to the log, durably.
4. The handler erases (`apply`) and the position moves to the entry. This is one transaction when the handler uses the
   position's connection (`Context.connection()`); otherwise the position moves right after the handler's own commit.
   Both are safe: the position only ever moves **after** the erase, and applying twice is harmless.

A failure after step 3 leaves the entry pending: the next erase (step 2) or the replay finishes it. Until then, later
erasures stop with the same handler code and are not logged.

### Guard (`EraseGuard`)

- `checkAtStartup()` walks the chain, then, with the position locked, checks for:
  - the wrong log (`ERASE_LOG_WRONG_LOG`);
  - a database ahead of the log (`ERASE_LOG_BEHIND_DATABASE`);
  - a database older than the log's oldest kept entry (`ERASE_LOG_GAP`);
  - a log that cannot be read (`ERASE_LOG_UNREACHABLE`) or a position that cannot be read (`ERASE_POSITION_FAILED`).

  If the database is behind the log, it records `replay_required`, and the state is **`ERASE_LOG_RESTORE_DETECTED`:
  "Restored from backup: run the erase replay before starting work"**. Every instance sees the recorded flag.
- `mayTakeWork()` is what the app checks before taking work. Any of the codes above means no work.
- `refresh()` is a cheap recheck (log head against position), so the block lifts after the replay.
- `fullCheck()` is the daily chain walk while running. It never records a restore: an entry pending while the app runs
  is an erase in progress.

### Replay and verify (`EraseReplay.run()`)

1. With the position locked, the same chain and position checks as above.
2. Every entry after the applied position goes through its kind's handler (`ERASED` or `ALREADY_GONE`), and the
   position moves with each.
   - A handler failure stops the replay with `ERASE_HANDLER_FAILED` and the handler's own code.
   - A kind without a handler stops it with `ERASE_HANDLER_MISSING`.
   - What was applied stays applied; a rerun goes on from there.
3. **Verify:** every handler checks every kept entry. Anything `READABLE`, or a check that fails, is
   `ERASE_VERIFY_FAILED`, and the system stays blocked.
4. Only then is `replay_required` cleared, and the guard lifts.

Running it twice is safe. The report holds counts, codes and entry numbers only.

Built-in handler `fields.CaseFieldsEraseHandler` (`case-fields`):

- `check` asks the app's `LegalHolds`.
- `apply` is `FieldValues.erase`; an unknown record counts as already gone. Since P022 this handler is the only
  class `FieldValues.erase` accepts as its caller (`FieldValues.ERASE_LOG_HANDLER`, checked with `StackWalker`);
  any other caller gets `VALUES_ERASE_OUTSIDE_LOG` and nothing is erased. The check is a guard against mistakes,
  not against code that deliberately imitates the handler's class name.
- `verify` reads the value store. No record, or a record made after the erasure under the same ID, is `GONE`.

### Retention and pruning

- `EraseRetention`: backup retention 30–90 days (default 35); the log keeps entries backup retention + 30 days.
- `EraseLog.prune(now, retention, appliedSeq)` removes only entries that are older than that **and** applied in the
  database. The newest pruned entry becomes the checkpoint, so the chain stays verifiable from it.
- A broken chain is not pruned.
- Pruning writes the checkpoint first, then the shorter log, each by atomic rename, so a crash in between is harmless.

### Spring (`platform-erase-log-spring`)

- Properties: `platform.erase-log.location` (required, absolute), `backup-retention-days` (35; 30–90), `recheck`
  (1 minute), `exit-after-replay` (true), `enabled`.
- Beans:
  - the file store and `EraseLog`;
  - `PostgresErasePosition` on the app's `DataSource`, after `EraseLogSchema.verify`;
  - `EraseHandlers` from the app's `EraseHandler` beans;
  - `Eraser` and `EraseReplay`;
  - `EraseGuard`, whose startup check runs when it is made. A restore does not stop the app: it starts blocked;
  - `EraseLogWorker`: the recheck, plus the daily full check and pruning;
  - `EraseReplayRunner` (`--erase-replay`);
  - health indicator `eraseLog`: UP; OUT_OF_SERVICE after a restore until the replay verified; DOWN otherwise. Its
    details hold no values.
