# P020 report — P019 follow-ups, record erase, per-entity time zone

Date: 2026-11-22
Branch: feature/p020 (merged into main by fast-forward, deleted locally; it was never on origin)
Outcome: PARTLY DONE. Everything is built, tested, merged and pushed. Two things are open:
- Erase deletes the record, but it is **not** crypto-shredding: a backup copy can still be opened (question 1).
- The old branch `feature/case-fields` is still not deleted (the remote delete was refused again).

## Result (tests per module, merge and push results)

- `mvn clean verify`, then `mvn install`: **763 tests, 0 failures**. That is the 744 existing tests, all passing,
  plus 19 new ones.

| Module | Tests |
|---|---|
| platform-identity-core | 86 |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 130 (was 128) |
| platform-notifications-spring | 39 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 29 (was 24) |
| platform-identity-testkit | 6 (was 5) |
| platform-crypto | 14 |
| platform-crypto-testkit | 7 |
| platform-setup | 20 (was 18) |
| platform-fields | 91 (was 86) |
| platform-fields-testkit | 18 (was 14) |
| platform-crypto-spring | 5 |

- **Merge:** fast-forward, `main` c7e6e64 → 71c5ac3, pushed. This report is the next commit on main, pushed.
- **A1:** `feature/case-fields` is still on origin. `git push origin --delete feature/case-fields` was tried once and
  refused by this session's permission check (as in P018 and P019). The local branch stays, since it has a remote
  copy.
- **A2:** branch `feature/p020` was made from main. The task file was committed on it unchanged.

## What was built

### B1, B2. `sensitive` and `primary` lock once values exist (platform-fields)

- `FieldDefinitions.change` refuses switching `sensitive` on or off with **`FIELD_SENSITIVE_LOCKED`**, and making a
  field primary or not primary with **`FIELD_PRIMARY_LOCKED`**, once the app's `FieldValuesCheck` says values exist.
  This is the same check as the type lock.
- If the type changes too, `FIELD_TYPE_LOCKED` comes first.
- The check is asked only when the type, `sensitive` or `primary` changes. A label or rule change still needs no
  check.
- The messages name the field's key and the rule, never a value.
- Recorded in `docs/design/case-fields.md`, "Owner answers to P019".

### B3. Erasing a record (platform-fields, `values`)

- **`FieldValues.erase(ref)`** deletes the record's value rows, its envelope and the `fld_record` row, in one
  transaction.
  - It first checks that the record has not changed meanwhile. If it has, erase reads the record again and retries.
  - Afterwards `read`, `readSensitive`, `search` and `findByPrimary` no longer find the record.
  - The same primary value arriving later makes a new record.
  - Erase calls no key service.
- **Result `FieldValues.Erased`:** the record, the number of values, whether it had sealed values, and when. It holds
  no value. The caller writes the audit entry (who, why) from it.
- **Legal hold:** the app supplies `LegalHolds`, through a new constructor `FieldValues(…, holds)`. The old
  constructor means no record is ever held.
  - A held record is refused with `VALUES_LEGAL_HOLD`.
  - If the check throws, erase is refused with `VALUES_LEGAL_HOLD_UNKNOWN`.
  - In both cases nothing is erased.
  - Another customer's record is refused with `VALUES_WRONG_CUSTOMER`.
- **Stores:** `ValueStore.delete(record)` in Postgres and in memory.
- **Runtime user:** `FieldSchema` now also requires DELETE on `fld_record`. The install guide already granted it.
- **Not crypto-shredding:** see "Not done" and question 1.

### B4, B5

- B4: concurrent claims keep getting the existing record, as built in P019. Nothing changed.
- B5: setup against a real Vault is for later. Both are recorded in the design doc.

### C. Per-entity time zone (requirement 20)

**Identity V1 was edited, not V2.** `V1__identity.sql` came in P013 (commit 087983f) and was changed again in P014.
Both are after the only tags (`v0.2.1`, `v0.3.0`, the old Enterprise Identity library), and the module has only ever
been built as 0.1.0-SNAPSHOT. So V1 was never released, and the version stays 1.

- **Storage:** `idn_customer.time_zone` (the customer's zone) and `idn_entity.time_zone` (the entity's own zone).
  - Both are nullable IANA names.
  - The database refuses anything that does not look like a zone name, such as `+05:00`.
- **API (identity-core, `store`):**
  - `EntityTimeZones`:
    - `effective(entityUuid)` gives the entity's own zone, else the customer's.
    - `zone(slotId | entityUuid)` gives an `EntityTimeZone` with both zones, plus `effective()` and `ownZone()`.
    - `setEntityZone(slotId, name, actor)` sets the entity's zone; a blank name clears it, so the entity follows the
      customer's zone again.
    - `setCustomerZone(tenantId, name, actor)` sets the customer's zone.
  - The outcome is a `TimeZoneOutcome` with one of: `SET`, `CLEARED`, `UNCHANGED`, `TIME_ZONE_INVALID`,
    `SLOT_UNKNOWN`, `CUSTOMER_UNKNOWN`, `NOT_AN_ID`. The caller audits it.
  - `EntityStore` has four new methods, implemented in `PostgresEntityStore` and `InMemoryIdentityStore`.
- **Notifications:**
  - The app supplies a new `EntityZones` (tenant, entity → the entity's own zone, if any), with
    `Notifier.Builder.entityZones` or as a Spring bean (`ObjectProvider`, optional).
  - An event or summary of an entity with its own zone uses that zone for:
    - the daily summary time,
    - the summary period,
    - every time shown in the message (immediate messages, summaries, action requests and messages to one person).
  - Events without an entity, and entities without their own zone, keep the tenant's zone.
  - If `EntityZones` fails, the tenant's zone is used and a warning with the entity id is logged.
- **Setup:** the sign-in file takes two new optional fields, `timeZone` (the customer's) and `entityTimeZones`
  (slot → zone).
  - They are defaults: setup stores a zone only where none is stored yet, and keeps one that is already set
    (`unchanged … kept`). A rerun therefore changes nothing.
  - A name that is not an IANA name refuses the file with `SETUP_TIME_ZONE_INVALID`.
  - A zone for a slot outside the licence refuses the run with `SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE`, before
    anything is written.
- **Docs:**
  - `docs/install/identity.md` section 5,
  - `docs/install/setup.md` section 2,
  - `docs/install/notifications.md` ("Time zones per entity"),
  - `docs/design/notifications.md` section 7,
  - `docs/design/setup.md` ("Time zones"),
  - requirement 20 is marked as built in `docs/design/integration-standards.md`,
  - CLAUDE.md module rows.

## Tests asked for → where

| Asked | Where |
|---|---|
| B1 sensitive locked, tests | `FieldDefinitionsTest.theSensitiveFlagIsLockedOnceValuesExist`, `.beforeValuesExistSensitiveAndPrimaryMayChange`; on Postgres `PostgresValueStoreTest.sensitiveAndPrimaryLockOnceARecordHoldsAValueAndFreeAgainWhenAllAreErased` |
| B2 primary locked, tests | `FieldDefinitionsTest.thePrimaryFlagIsLockedOnceValuesExist` (also: type and primary together → `FIELD_TYPE_LOCKED`); the same Postgres test |
| B3 values gone | `PostgresValueStoreTest.erasingARecordRemovesItsValuesAndEnvelopeAndSearchNoLongerFindsIt` (record row, envelope and only its value rows gone; read/readSensitive → `VALUES_RECORD_UNKNOWN`; no key service call); `FieldValuesInMemoryTest.eraseRemovesTheRecordUnlessItIsHeld` |
| B3 search no longer finds it | same Postgres test: prefix search, sensitive exact search by fingerprint, `findByPrimary` (non-sensitive and sensitive primary) |
| B3 a backup copy can't be decrypted | **Not met; the opposite is pinned:** `PostgresValueStoreTest.aRestoredBackupOfAnErasedRecordStillOpensWhileTheCustomersKeyLives`. See question 1. |
| B3 legal-hold refusal | `PostgresValueStoreTest.aRecordUnderLegalHoldIsNotErased` (held → `VALUES_LEGAL_HOLD`, nothing erased; check throws → `VALUES_LEGAL_HOLD_UNKNOWN`; hold lifted → erased); `.anotherCustomersRecordIsNotErased` |
| C default from the customer | `EntityTimeZonesTest.anEntityWithoutItsOwnZoneFollowsTheCustomers`; `InMemoryTimeZonesTest` |
| C own zone wins | `EntityTimeZonesTest.theEntitysOwnZoneWins` (and clearing falls back) |
| C invalid name refused | `EntityTimeZonesTest.invalidZoneNamesAreRefusedWithTheirCode` (unknown, wrong case, offsets, `GMT+1`, trailing space, too long); `.theDatabaseRefusesAZoneThatIsNotAName`; setup: `SetupCommandTest.anInvalidTimeZoneOrOneForASlotOutsideTheLicenceIsRefused` |
| C daily summary at the entity's local time across a daylight-saving change | `SummaryTest.dailySummaryGoesAtTheEntitysLocalTimeAcrossADaylightSavingChange` (New York, 08:00 local: 12:00 UTC on 31 Oct (EDT), 13:00 UTC on 1 Nov (EST), not at 12:00; an entity without its own zone at 08:00 UTC; times in the message in EDT); `.eventsWithoutAnEntityKeepTheTenantsZoneAndAFailingLookupFallsBackToIt` |
| C setup defaults, rerun safe | `SetupCommandTest.timeZonesFromTheSignInFileAreDefaultsAndARerunKeepsWhatTheAppSet` |
| C DML-only user | `EntityTimeZonesTest` runs entirely as the DML-only app user; setup tests run as the app's everyday user |
| 744 existing still pass | 763 run, 0 failures. No existing assertion was changed. |

## Files changed, commits

- 4b1b072 docs: P020 task.
- b6841ed feat(fields) (P020 B):
  - `FieldDefinitions`, `FieldDefinitionException`.
  - `FieldValues`, `FieldValuesError`, `LegalHolds` (new), `ValueStore`, `PostgresValueStore`, `FieldSchema`,
    `InMemoryValueStore`.
  - Tests and `docs/design/case-fields.md`.
- 71c5ac3 feat(identity,notifications,setup) (P020 C):
  - Identity: `EntityTimeZone`, `EntityTimeZones`, `TimeZoneOutcome` (all new), `EntityStore`,
    `PostgresEntityStore`, `InMemoryIdentityStore`, `V1__identity.sql`.
  - Notifications: `EntityZones` (new), `Notifier`, `TenantSettings.withZone`, `NotificationsAutoConfiguration`.
  - Setup: `SigninFile`, `SetupRun`, `Plan`, `SetupRefusal`.
  - Tests, docs and CLAUDE.md.
- This report (on main).

## Decisions I had to make

1. **Erase deletes the whole record**, not only its values and envelope. Two reasons:
   - A remaining `fld_record` row would keep the identity hash. For a sensitive primary field, that hash comes from
     the fingerprint, so anyone with the fingerprint key could still confirm a value had been there.
   - The task says "erase", and later arrivals should make a new record.
2. **Switching a field off still clears its primary flag**, even when values exist:
   - Removing a field must always be possible (rule 5).
   - A switched-off field cannot be primary (`FIELD_PRIMARY_INACTIVE`).
   - Switching it back on does not make it primary again.
3. **There are two customer zones:**
   - The customer's zone in identity is new: `idn_customer.time_zone`. "Defaulting to the customer's time zone"
     needs one there, and the apps need it for windows and timers.
   - Notifications keeps its own tenant zone (`notif_tenant.zone_id`).
   - The app passes notifications the entity's **own** zone, not the effective one. That way an entity without its
     own zone gets the same zone as tenant-level events. See question 2.
4. **The sign-in file's zones are defaults:** setup never overwrites a zone that is already stored, and shows it as
   `unchanged … kept`. A rerun therefore never undoes a change made in the app.
5. **Valid zone names** are exactly the zones Java knows (`ZoneId.getAvailableZoneIds()`, which includes `UTC`). Fixed
   offsets (`+05:00`, `GMT+1`) are refused because they do not follow daylight saving. A stored name this Java does
   not know is treated as none when read.
6. **`platform-signin/1` stays the format name**, because the new fields are optional and old files work unchanged.
   An older setup release would refuse a file that has them.

## Not done, and why

- **Crypto-shredding (B3):** "destroying the record key makes its sensitive values unreadable everywhere, including
  backups" is not true with today's design, so I did not claim it:
  - The record key is stored *in the database* (the `pck1.` envelope in `fld_record`), wrapped by the customer's
    wrapping key.
  - Erase deletes the envelope from the live database, but a backup taken before the erase still holds it, and the
    customer's key, which lives on, still opens it.
  - The test the task asks for ("a backup copy of the old rows can't be decrypted") would fail. Instead,
    `aRestoredBackupOfAnErasedRecordStillOpensWhileTheCustomersKeyLives` pins what happens today, and the design doc
    says so.
  - Fixing this needs a design change that is yours to decide (question 1).
- **Deleting `feature/case-fields`:** the remote delete was refused again. To finish, run
  `git push origin --delete feature/case-fields`, then `git branch -d feature/case-fields`.

## Questions (numbered, each with a recommendation)

1. **Real crypto-shredding for erase.** To make a backup unreadable, erase must destroy something that is *not* in the
   database. The options:
   - **(a)** A per-record secret in the key service, destroyed by erase.
     - For example, the record's data key kept in Vault KV v2 (destroyed with `delete metadata`) instead of the
       wrapped key in `fld_record`.
     - Opening a record stays one call.
     - It adds one write per new record, and millions of entries in the key service.
     - On AWS or Azure, per-record secrets are costly.
     - The key service's own backups (Vault snapshots) still hold the key until they expire.
   - **(b)** Keep today's erase, and handle backups by retention: backups expire within a set period, and a restore
     re-applies the erasures from a log kept outside the database. This is the usual GDPR practice.
   - **(c)** Shred per customer (destroy the customer's keys), which covers "customer leaves" but not one case.

   *Recommendation:* (b) now, plus (c) for customer exit, both written into the retention design. Go to (a) only if a
   customer contract requires single-record shredding of backups.
2. **Two customer zones** (`idn_customer.time_zone` and notifications' `notif_tenant.zone_id`). They can differ if
   set separately. *Recommendation:* in a later task, notifications reads the tenant zone from identity when the app
   has it, and the IT Admin's settings screen writes one place. Until then, the apps set both from the same screen.
3. **Customer zone in setup.** The sign-in file's `timeZone` is optional, so a customer without it has no zone in
   identity until the app sets one, and `effective()` is then empty for entities without their own. *Recommendation:*
   make `timeZone` required in the sign-in file at the next format change (`platform-signin/2`), and until then have
   the apps fall back to their notifications tenant zone.
