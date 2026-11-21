# P010 report — licensing: clock rule, storage, licence command, Spring wiring

Branch `feature/licensing-core`, pushed, not merged.

## What was built

### A. Owner decisions applied

All four owner decisions, and my P009 decisions 1–17 updated to match, are recorded in the new section 8
("Decisions") of `docs/design/licensing.md`. Sections 2 and 3 each have a one-line pointer to the decision that
replaces them.

- **O1, the clock.** `ClockGuard.read()` returns a `ClockReading`. Its effective time is the later of the server
  clock and the latest time seen, and every licence date is worked out from it. `LicenceStatus` now carries the
  reading:
  - `clockBehind()` is the alert flag. It is set when the server clock is behind the latest time seen by more than
    the tolerance.
  - `clock().message()` gives the exact difference, for example "The server clock is 3 hours behind the latest time
    this installation has seen (…). Licence dates use …, so turning the clock back does not extend the licence.
    Correct the server clock."

  `LICENCE_CLOCK_ROLLBACK` is removed.

  **Last-seen reset:** a new signed file, `platform-last-seen-reset/1` (`LastSeenReset`). It uses the same envelope
  as the licence, now shared in `SignedDocument`. It has its own codes (`ResetRefusal`, eight of them), and three
  outcomes: `APPLIED`, `ALREADY_APPLIED`, `NOT_NEEDED`. Our issuing command creates one with `--reset-last-seen <time>
  --tenant-id … [--valid-days 7]`, and every run is logged. A reset works once per ID, only for its own tenant, and
  only until `notAfter` (at most 30 days).
- **O2, early renewal.** The new `LicenceStore` has two slots, `CURRENT` and `WAITING`. When a renewal is stored
  before its start date, it goes in the waiting slot. The current licence stays in force until the effective date
  reaches the renewal's start; then `LicenceService.status()` promotes it in a single atomic step that is safe across
  several nodes.
- **O4, chain head.** A new `ChainHead` record and `UsageLedger.head()` / `snapshot()` keep the newest sequence and
  hash. `UsageChain.verify(records, head)` reports a new code, `USAGE_CHAIN_HEAD_MISMATCH`. The usage export now
  includes the head check in its header.

### B. Postgres storage (`com.documentplatform.licensing.jdbc`, plain JDBC)

- **SQL:** `src/main/resources/db/platform/licensing/V1__licensing.sql`. It creates `lic_schema_version`,
  `lic_licence`, `lic_last_seen`, `lic_clock_reset`, `lic_usage` and `lic_chain_head`.
- **Stores:** `PostgresLicenceStore`, `PostgresLastSeenStore` and `PostgresUsageLedger`.
  - The latest time seen can only move forward: the update uses `greatest()`. The one exception is a reset, which
    is written in the same transaction as its applied ID.
  - Each append locks the head row, inserts the record and moves the head, all in one transaction. The snapshot is
    read under repeatable read.
  - Monthly totals are computed in SQL, with `sum` or `max` depending on the allowance type.
- **Startup check:** `LicensingSchema.verify` only reads. It stops with one of these codes: `LIC_TABLES_MISSING`,
  `LIC_VERSION_UNKNOWN`, `LIC_VERSION_OLDER`, `LIC_VERSION_NEWER`, `LIC_TABLES_INCOMPLETE`, `LIC_PRIVILEGES_MISSING`,
  `LIC_CHECK_FAILED`. Each message names Flyway location `db/platform/licensing` and history table
  `lic_schema_history`.
- **What the tables hold:** the licence files as received, times, reset IDs, usage rows and hashes. No secrets, keys,
  prices or document data; a test checks every column name.

### Core `LicenceService` (plain Java)

`LicenceService` is the one object an app uses:

- `status()` re-verifies every stored row on every read, promotes a waiting renewal when its start date comes, and
  applies the clock rule.
- `intakeAllowed()` is true when the state allows intake and the licence lists this app's product code.
- `kind()` and `testEnvironment()` give the licence kind and the "Test environment" flag.
- `usage()` and `recordUsage(slot, type, amount, free)` record usage dated by the effective time, so usage cannot be
  backdated by changing the clock.
- `store(file)` stores a licence and returns `STORED_FIRST`, `STORED_CURRENT`, `STORED_WAITING` or `ALREADY_STORED`.
  It refuses with `LICENCE_WRONG_CUSTOMER`, `LICENCE_OLDER_VERSION` or `LICENCE_VERSION_CONFLICT`.
- `applyReset(file)` applies a last-seen reset.

Every refusal code now has an `action()`: the "what to do" text.

### C. Licence command, customer side (`command.LicenceCommand`)

```
licence store       --file licence.json  --jdbc-url … --db-user … --keys trusted-keys.json [--tenant-id …] [--product-code …]
licence status      --jdbc-url … --db-user … --keys …
licence apply-reset --file reset.json --jdbc-url … --db-user … --keys …
```

- The database password comes from `PLATFORM_LICENSING_DB_PASSWORD`, never from an option.
- The command checks the tables first, then verifies the file before storing it. Storing the same file again changes
  nothing.
- Exit codes: 0 done, 1 refused (the code and "What to do: …" are printed), 2 options wrong.
- The status line looks like this: `Licence LIC-0001 v1 for Example Customer Ltd (tenant TEN-…): SUBSCRIPTION,
  PRODUCTION, 2 entities, expires 2027-11-30. State VALID. The licence is valid until 2027-11-30.` If a renewal is
  waiting, a sentence about it is added, and so is the clock message when the clock is behind.

### D. `platform-licensing-spring` (new module, added to the build and the BOM)

Same layout as `platform-notifications-spring`:

- **Auto-configuration and properties:** `LicensingAutoConfiguration` and `LicensingProperties` (prefix
  `platform.licensing`: `trusted-keys.<id>`, `product-code`, `tenant-id`, `clock-tolerance`, `enabled`).
  `LicensingConfig` validates the properties and lists every problem by property key, without echoing values.
- **Beans:** the Postgres stores, which back off when the app defines its own, and the `LicenceService` bean.
- **Startup check:** `LicenceStartupCheck` reads the licence once at start. A refusal stops the app;
  `LicensingFailureAnalyzer` prints the code, the cause and the action.
- **Health:** `LicensingHealthIndicator`. Details: state, licence ID and version, type, kind, `testEnvironment`,
  expiry or updates-until, `daysUntilExpiry`, `graceEndsOn`, `intakeAllowed`, `productLicensed`, `clockBehind`,
  `clockBehindBy`, `renewalStartsOn`, and the message. Status: `UP`, `LICENCE_WARNING` or `INTAKE_STOPPED`; the last
  two are not counted in the overall health and return HTTP 200. `DOWN` with the code appears only if the stored row
  is refused while the app runs.

### Docs

- `docs/design/licensing.md`: section 8, Decisions.
- `docs/design/licensing-file-format.md`: clock row replaced; new sections 7 (reset file) and 8 (tables); chain head
  added to section 5.
- New install guide for IT: `docs/install/licensing.md`.
- CLAUDE.md: module rows updated, and a stray blank line that split the module table is removed.

## Tests

`mvn -o clean verify` and then `mvn -o clean install`: **473 tests, 0 failures, 0 errors**.

| Module | Tests |
|---|---|
| identity-core, identity-spring, notifications, notifications-testkit, notifications-spring | 73, 15, 65, 101, 29 (unchanged) |
| platform-licensing | 129 (was 100) |
| platform-licensing-testkit | 47 (was 31) |
| platform-licensing-spring | 14 (new) |

**Of the 414 existing tests, 409 pass unchanged. The other 5 were the P009 `ClockGuardTest` cases**, which checked
that a rollback is refused. O1 reverses that rule, so I rewrote them for the new behaviour (9 cases now). No other
existing test changed; `UsageLedgerTest` and `IssueLicenceCommandTest` only gained new tests.

Postgres tests run in Testcontainers (Postgres 16) and connect as a **DML-only user** (SELECT, INSERT, UPDATE, DELETE
only). The required scenarios are covered:

- **Restart during grace starts.** Covered in core and in Spring; intake continues during grace. After grace it
  still starts, with intake stopped.
- **Clock rolled back.** The app starts with `clockBehind` set and the message "3 hours". Turning the clock back into
  the valid period leaves the state at `EXPIRED_GRACE_OVER`. Usage stays dated by the effective time.
- **Poisoned last-seen time.** The state shows `EXPIRED_GRACE_OVER` with the flag. A valid reset brings it back to
  `VALID` with no flag; applying it again gives `ALREADY_APPLIED`. An expired, forged or other-tenant reset is
  refused. Every single byte changed in a reset file is refused.
- **Early renewal waits.** The current licence stays in force up to 23:59:59 on the day before; the renewal takes
  over at 00:00 on its start date. Intake is allowed throughout.
- **Deleted newest usage records are detected.** Shown in memory and in Postgres: deleting records 4–5 reports "the
  newest record(s) 4..5 were removed". Deleting all records, editing the head and editing a row are each detected.
  40 concurrent appends keep the chain intact.
- **Startup refusal codes.** `LICENCE_MISSING`, `LICENCE_FORGED`, `LICENCE_TAMPERED`, `LICENCE_WRONG_CUSTOMER` and
  `LICENCE_UNKNOWN_KEY_ID`, each with its action checked. Missing tables also stop the start, and the check creates
  nothing. A wrong config is listed by property key.
- **DML-only user runs everything.** Start, status, usage recording, assessment, chain check, export and verify, and
  a renewal stored through the running app. The licence command (store, status, apply-reset) also runs as that user.
- **Also tested:**
  - Licence command: storing is idempotent; another customer's licence is refused and nothing changes; older and
    conflicting versions are refused; a forged file is never stored.
  - The issuing command issues a reset and refuses wrong reset options.
  - Health shows no keys or signatures.
  - App-provided stores replace the Postgres ones, and `enabled=false` switches the module off.
  - A test-environment licence is flagged.

## How an app turns it on

1. Add the dependency. The version comes from `platform-bom`:

   ```xml
   <dependency>
       <groupId>com.documentplatform.platform</groupId>
       <artifactId>platform-licensing-spring</artifactId>
   </dependency>
   ```

2. Add a separate Flyway run to the schema step, never at app startup:

   ```java
   Flyway.configure().dataSource(ownerDataSource)
         .locations("classpath:db/platform/licensing").table("lic_schema_history")
         .load().migrate();
   ```

   Grant the app user SELECT, INSERT, UPDATE and DELETE on `lic_*`, and SELECT on `lic_schema_version`.

3. Add to the install config:

   ```yaml
   platform:
     licensing:
       product-code: PRODUCT_A
       trusted-keys:
         lic-2026-01: MCowBQYDK2VwAyEA...   # public key shipped with the release
   ```

4. Use the service:

   ```java
   @Service
   class Intake {
       private final LicenceService licence;
       Intake(LicenceService licence) { this.licence = licence; }

       void accept(Batch b) {
           if (!licence.intakeAllowed()) throw new IntakeStopped(licence.status().message());
           // ... process ...
           licence.recordUsage(b.slotId(), AllowanceType.PAGES_PER_MONTH, b.pages(), false);
       }
   }
   // UI banner: licence.status().message(), status().clock().message(); marking: licence.testEnvironment()
   ```

5. Ship `LicenceCommand` (its `main`) with the app distribution, next to the Postgres driver, so the customer can run
   `licence store …`.

## Decisions I had to make

1. **`LICENCE_MALFORMED` and `LICENCE_UNSUPPORTED_FORMAT` also refuse start-up.** They were not in your list of
   five, but an unreadable licence cannot be checked; they are the "tampered/unreadable" family. All other states
   start the app.
2. **A licence without this app's product code** does not stop start-up (not in the refusal list). It stops intake
   instead (`productLicensed=false`, health `INTAKE_STOPPED`).
3. **Expected tenant.** It is the optional `platform.licensing.tenant-id`. When that is not set, the tenant of the
   stored licence is used, so a later licence for another tenant is refused. On a first install nothing is
   expected yet, so the first licence sets the tenant.
4. **A broken stored row never blocks storing a good licence.** This is how a tampered row is repaired. Reading a
   broken row still refuses.
5. **Versions only go up.** An older version, or the same version as a different licence, is refused. Storing a
   licence that starts now drops an older waiting one. Storing the same licence ID again is `ALREADY_STORED`.
6. **First install before its start date** is stored as current with state `NOT_STARTED`, because there is nothing
   to keep in force.
7. **Reset rules.** A reset applies when the server clock is at or before `notAfter`; the default validity is 7
   days and the maximum 30. If the latest time seen is not ahead of `resetTo`, nothing changes (`NOT_NEEDED`), but
   the reset ID is still recorded as used.
8. **The issuing command does not record resets in the issued-records file**, only in the run log, so the records
   file stays one licence per line.
9. **The clock tolerance is capped at 1 day** in the Spring config.
10. **Health status codes.** Licence warnings use their own codes (`LICENCE_WARNING`, `INTAKE_STOPPED`), which are
    left out of the overall health, as notifications does with `DEGRADED`. `DOWN` is used only for a refusal while
    running.
11. **Health shows no customer name.** It shows the licence ID only, because health endpoints are often reachable
    by more people.
12. **The Spring clock is a `LicensingClock` bean,** so an app's own `Clock` bean is never picked up by accident,
    and tests can set the time.
13. **`LicenceChecker` (P009) was removed.** `LicenceService` replaces it.

## Questions

1. **Product code not in the licence.** Should start-up refuse instead of only stopping intake? *Recommendation:*
   keep it as stopped intake, since you limited refusals to five cases.
2. **Tenant ID in config.** Should `platform.licensing.tenant-id` become required once `platform-setup` exists?
   *Recommendation:* yes. Setup writes it, which closes the gap at first install.
3. **Reset validity.** Is a default of 7 days and a maximum of 30 right? *Recommendation:* yes.
4. **Resets in the issued records.** Should resets also go in the issued-records file (as a second record type)?
   *Recommendation:* not until the Platform Console needs a list of resets.
5. **Wiping both usage tables.** Deleting every usage row and the head row together still looks like a new, empty
   ledger. *Recommendation:* accept it. The next usage export shows a chain start that does not match the previous
   file (`EXPORT_START_MISMATCH`).
6. **Next tasks.** *Recommendation:* `platform-setup` (design section 6), then each app's switch-over task, which
   adds the separate licensing Flyway run and ships `LicenceCommand`.
