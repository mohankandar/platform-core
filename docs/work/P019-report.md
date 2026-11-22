# P019 report — case field values: encrypted storage and search

Date: 2026-11-22
Branch: feature/field-values (merged into main by fast-forward, deleted locally; it was never on origin)
Outcome: PARTLY DONE (all building, testing, merging and pushing done; the old branch `feature/case-fields` is still
not deleted, because the remote delete was refused again, see "Not done")

## Result (tests per module, merge and push results)

- `mvn clean verify`, then `mvn install`: **744 tests, 0 failures**. That is the 712 existing tests, all passing, plus
  32 new ones.

| Module | Tests |
|---|---|
| platform-identity-core | 86 |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 128 |
| platform-notifications-spring | 39 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 24 |
| platform-identity-testkit | 5 |
| platform-crypto | 14 (was 10; Vault in Testcontainers) |
| platform-crypto-testkit | 7 |
| platform-setup | 18 (was 15) |
| platform-fields | 86 (was 70) |
| platform-fields-testkit | 14 (was 10) |
| **platform-crypto-spring** (new) | 5 |

- **Merge:** fast-forward, `main` 975352c → 736075d, pushed. This report is the next commit on main, pushed.
- **A1:** `git push origin --delete feature/case-fields` was refused again by this session's permission check (as in
  P018). I went on as the task says.
- **A2:** branch `feature/field-values` was made from main. The task file `docs/work/P019.md` was committed on it
  unchanged.

## What "primary" means in each app today (section C, read only, nothing changed)

**LexaVault** (changelogs 008, 019, 020, 022; `BusinessIdentityService`, `CreatePackageService`,
`JpaBusinessRecordRepositoryAdapter`)

- There is a real business record, `business_records`. Packages point to it through `document_packages.business_record_id`,
  and many packages share one record. Values are stored once per record in `business_record_identifiers`
  (display `identifier_value`, `normalized_value`). There is one value per definition per record, so no multi-value
  fields.
- There is exactly one active primary field per tenant (partial unique index, 008).
- The primary value is unique per tenant and definition. A trigger enforces this (019), and since 020 it serialises
  writers with `pg_advisory_xact_lock`. Secondary values may repeat across records.
- When the same contract number arrives again, `resolveOrCreate` finds the record by its primary value and the new
  package joins that record. Secondary values the record lacks are added. A different secondary value is refused
  (`IDENTIFIER_CONFLICT`).
- With two concurrent writers of a new value, the loser gets SQLSTATE 23505. That surfaces as a generic database
  error, with no retry and no "use the existing record".
- "Entity" in LexaVault means the tenant.

**Vero** (changelogs 001, 003, 006, 024; `RecordKey`, `JdbcBusinessRecordRepository`, `SubmissionService`)

- There is no record table. A "record" is the group of submissions, within one tenant and sending system
  (`source_id`), whose business-identifier values are all equal. All identifier fields count together, and there is
  no single "primary".
- A submission without identifier values is a record of its own. A part or a superset of the identifier set is a
  different record.
- Values are stored per submission (`business_metadata_values`: `stored_value`, `normalized_value`). Nothing about
  them is unique.
- Repeat arrival makes new submission rows, which are grouped when read. Idempotency comes from `ON CONFLICT` on the
  submission key.
- Search runs per field: EXACT, PREFIX or PARTIAL with `position()`, ignoring case. There is no `pg_trgm`.

**In both apps**, values are plain text, and neither has multi-value fields.

**What I built for both:** one record per primary value set per entity, product and record type. A record's identity
is all of its primary field values together:
- With one primary field, that is LexaVault's rule.
- With several primary fields, it is Vero's identifier set.
- A record without primary values is a record of its own.

The database enforces this with a unique index. `claim` follows LexaVault's repeat-arrival behaviour (join the
record, add what it lacks, refuse a different value). With concurrent writers, every writer gets the same record
instead of an error. For Vero, this means a real record (record type of its choice) that its submissions point to.

## What was built

### B. Owner answers to P018 (recorded in `docs/design/crypto.md` section 9)

1. **Setup creates the keys.** `setup apply` creates the customer's wrapping key and fingerprint key (`SetupKeys`)
   just before the database part. It uses a login given to that run only: `PLATFORM_SETUP_VAULT_ADDR` plus one of a
   token, a token file or AppRole, from the environment, with the `platform-crypto-setup` policy.
   - `setup plan` only reads whether the keys exist.
   - Existing keys are left alone, so a rerun, or the run for the second product, shows `unchanged`.
   - A key service problem refuses the run before the database is touched (`SETUP_KEY_SERVICE_FAILED` with the
     `CRYPTO_` code). Missing or double login settings give `SETUP_KEY_SERVICE_SETTINGS_MISSING`.
   - `--no-customer-keys` skips the step.
   - The run log gets `keys=created|present|missing|skipped`. No setting is ever printed.
2. **Case envelope:** new `envelope.RecordCipher`. All sensitive values of one record are sealed under one data key:
   the envelope `pck1.` once per record, and one item `pci1.` per value. Each item is bound to the customer, the
   record and the item (field and position). Opening a record is one key service call. No plaintext key is cached:
   `RecordKey.close()` wipes it.
3. **platform-crypto-spring** (new module, in the root `<modules>` and the BOM):
   - `platform.crypto.*` properties, validated at startup with every problem listed and no secret in any message.
   - Vault login: APPROLE (default, secret ID inline or from a file), TOKEN_FILE, or TOKEN.
   - `VaultClient` and `VaultKeyService`, which back off when the app defines its own `KeyService`.
   - The ciphers, plus `CustomerId` (from `platform.crypto.tenant-id`, falling back to `platform.licensing.tenant-id`).
   - Vault is never called at startup.
   - Health indicator `keyService`: UP or DOWN plus the `CRYPTO_` code, no secrets.
   - Install guide: `docs/install/crypto.md`.
4. `VaultTransitSigner`: unchanged.

Also in platform-crypto:
- `CustomerId.fromTenantId`. Tenant IDs are `TEN-…` text, not UUIDs (decision 1).
- Batch fingerprints: `KeyService.fingerprints`, done in Vault as one `hmac` call with `batch_input`;
  `Fingerprinter.fingerprintAll` / `ofAll`.

### D. Value storage (platform-fields, package `values`; design in `docs/design/case-fields.md`, "Value storage (P019)")

- **`FieldValues`** (one per customer) offers `claim`, `update`, `findByPrimary`, `read`, `readSensitive`, `search`,
  `rewrap` and `searchStatus`. `RecordRef` is entity and product + record type + record ID. Values are checked by
  `FieldSet` (every type, multi-value, required).
- **Non-sensitive values** are stored as display value + normalised value + search form (lower case, single
  spaces). Search modes:
  - EXACT checks and normalises the term like a value.
  - PREFIX and PARTIAL use `LIKE` with the wildcards escaped.
  - PARTIAL is served by a `pg_trgm` GIN index (`fld_value_partial`). The schema step creates it when it can.
  - Without that index, partial search runs as prefix search and the result is marked `PARTIAL_AS_PREFIX`.
    `searchStatus()` then gives `FLD_PARTIAL_SEARCH_UNAVAILABLE` and names the optional DBA script
    `db/platform/fields-optional/partial-search.sql`.
- **Sensitive values:**
  - Stored only as `sealed_value` under the record envelope, plus a `fingerprint` when the field is searchable or
    primary. The fingerprint context is entity + product + field key, and it ignores capitals and extra spaces.
  - Check constraints refuse a sensitive row with any readable column filled in.
  - Prefix or partial search on a sensitive field is refused with `VALUES_SENSITIVE_EXACT_ONLY`.
  - Search tries every usable fingerprint key version.
- **Primary rule:** a unique index on `fld_record (entity_uuid, product_code, record_type, identity)`.
  - `insert … on conflict do nothing` waits for a concurrent writer and then returns the winner's record.
  - `claim` adds missing values, keeps equal ones, and refuses different ones (`VALUES_CONFLICT`, field keys only).
  - `update` refuses a primary value that another record has (`VALUES_PRIMARY_TAKEN`).
  - Lookups try one identity per usable fingerprint key version.
- **Reading:** `read` never calls the key service (sensitive fields are hidden, count only). `readSensitive` is the
  explicit call, and it costs one key service call per record. Nothing logs. No `toString()` or exception message
  holds a value.
- **Customers:** a record of another customer is refused (`VALUES_WRONG_CUSTOMER`), and search filters by customer.
  The envelope itself also refuses another customer (`CRYPTO_WRONG_CUSTOMER`).
- **Storage:**
  - New tables `fld_record` and `fld_value`, in `V1__fields.sql` (edited, never released).
  - `FieldSchema` checks them. The runtime user needs SELECT, INSERT, UPDATE on `fld_record` and SELECT, INSERT,
    DELETE on `fld_value`.
  - `PostgresValueStore` is also the app's `FieldValuesCheck`, so a field's type locks once a record holds a value.
  - Testkit: `InMemoryValueStore`, with the same rules.

## Tests asked for → where

| Asked | Where |
|---|---|
| every type stored and read back | `PostgresValueStoreTest.everyTypeIsStoredAndReadBack` (all 11 types, multi-value, ADDRESS with street2; plain read hides, `readSensitive` opens) |
| non-sensitive exact / prefix / partial | `PostgresValueStoreTest.nonSensitiveFieldsAreSearchedExactlyByPrefixAndByPart`; fallback: `.withoutTheTrigramIndexPartialSearchRunsAsPrefixAndSaysSo` (and the DBA script restores it); `FieldValuesInMemoryTest.searchRulesAndThePrefixFallback` |
| sensitive exact search via fingerprint, case and spacing ignored; prefix/partial refused | `PostgresValueStoreTest.sensitiveFieldsAreSearchedByExactMatchOnlyIgnoringCapitalsAndSpaces` |
| no sensitive value readable in any table column (raw rows) | `PostgresValueStoreTest.noSensitiveValueIsReadableInAnyTableColumn` (every `fld_` table dumped as text; the database refuses a readable sensitive row) |
| ... log or message | `PostgresValueStoreTest.noSensitiveValueInLogsMessagesOrToString` (JUL root and pgjdbc at ALL, refusal messages, `toString()`); `.invalidValuesAreRefusedWithTheirCodesAndNoValueInTheMessage`; `CryptoWiringTest` (no token or secret ID) |
| one key-service call per record | `PostgresValueStoreTest.openingARecordIsOneKeyServiceCallHoweverManySensitiveValues` (open = 1; write = 3 whether 1 or 6 sensitive values); `FieldValuesInMemoryTest.storesSealsAndReadsBack`; real Vault: `RecordEnvelopeVaultTest` |
| key rotation keeps search working | `PostgresValueStoreTest.keyRotationKeepsReadingAndSearchWorking` (wrapping key rotated, rewrap, old version retired; fingerprint key rotated); `.aSensitivePrimaryValueStillFindsItsRecordAfterTheFingerprintKeyRotates`; `RecordEnvelopeVaultTest.rotationAndRewrapLeaveTheItemsAsTheyAre` |
| wrong customer refused | `PostgresValueStoreTest.anotherCustomersRecordIsRefused`; `RecordEnvelopeVaultTest.manyValuesOneKeyAndTheyOpenOnlyInTheirPlace` |
| primary uniqueness under concurrent writers | `PostgresValueStoreTest.concurrentWritersOfTheSamePrimaryValueEndUpOnOneRecord` (8 threads, one record); `.theSamePrimaryValueAlwaysLeadsToTheSameRecord`; `.severalPrimaryFieldsIdentifyARecordTogether`; `FieldValuesInMemoryTest.concurrentClaimsEndUpOnOneRecord` |
| setup creates keys and a rerun leaves them | `SetupCommandTest.applyCreatesTheCustomersKeysAndARerunLeavesThem`, `.aKeyServiceProblemRefusesTheRunBeforeTheDatabaseChanges`, `.theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted` |
| crypto-spring wiring and health | `CryptoWiringTest` (5) |
| batch fingerprints, tenant-ID customer | `RecordEnvelopeVaultTest.batchFingerprintsEqualSingleOnesInOneCall`, `.aTenantIdAlwaysGivesTheSameCustomer` (UUID pinned, computed outside Java) |
| 712 existing still pass | 744 run, 0 failures |

Changes to existing tests:
- `FieldSchemaTest` expects the two new table names.
- `SetupCommandTest`'s helper now passes an in-memory key service, so the existing tests need no Vault. No existing
  assertion was changed.

## Files changed, commits

- 91f4b71 docs: P019 task.
- c5b0cfa feat(crypto) (P019 B):
  - `RecordCipher`, `CustomerId`, `KeyService`, `Fingerprinter`, `VaultKeyService`, `VaultJson`,
    `InMemoryKeyService`.
  - New module `platform-crypto-spring`; root POM and BOM.
  - `SetupKeys`, `SetupCommand`, `SetupRefusal`, setup POM and tests.
  - Docs: `docs/design/crypto.md`, `docs/install/crypto.md` (new), `docs/install/setup.md`.
- 736075d feat(fields) (P019 C, D):
  - Package `values`, `PostgresValueStore`, `FieldSchema`, `V1__fields.sql`, `fields-optional/partial-search.sql`,
    `InMemoryValueStore`, tests, POMs.
  - Docs: `docs/design/case-fields.md`, `docs/install/fields.md`, CLAUDE.md (module rows).
- This report (on main).

## Decisions I had to make

1. **Customer UUID from the tenant ID.** Licences and the identity tables use `TEN-…` text, while crypto's
   `CustomerId` is a UUID. The design text said the two were the same UUID, which was not accurate.
   `CustomerId.fromTenantId` derives the UUID the same way `EntityUuid` does, with the prefix `platform-customer|`
   (pinned by a test). That way setup and the app always arrive at the same key names. The design text is corrected.
2. **One record per primary value set, per entity + product + record type**, not per document. Both apps need this
   (section C above). The record type lets an app keep, for example, contract records and other kinds apart.
3. **Matching ignores capitals and extra spaces** for non-sensitive values too: search, the primary rule and claim's
   equality all compare the normalised value in lower case. This keeps them the same as sensitive fields, whose
   fingerprints must ignore case. With LexaVault's default UPPER normalisation nothing changes. Vero's exact search
   on identifiers was case-sensitive.
4. **Concurrent claim gives everyone the record**, instead of LexaVault's 23505 error for the loser.
5. **Write cost:** a new record with sensitive values costs 3 key service calls: the data key, the fingerprint key's
   versions, and one batch of fingerprints. This holds however many values there are. To get there I added batch
   fingerprints (Vault `batch_input`). Opening a record is 1 call, as asked.
6. **Partial search without `pg_trgm`:**
   - The schema step tries `create extension if not exists pg_trgm` inside a guarded block. If that fails, the step
     goes on and the index is left out.
   - Tests on `postgres:16-alpine` have it available.
   - An optional script, outside the Flyway location, lets the DBA add it later.
   - Detection at runtime: `pg_indexes` in the current schema.
7. **Setup keys are a separate step, not a plan item**, so the existing plan counts stay as they are. The output gets
   a "Customer keys:" line, the run log gets `keys=…`, and the step can be skipped with `--no-customer-keys`. The flag
   is not named `--no-keys`, because `--keys` already means the trusted-keys file.
8. **Errors:**
   - `FieldValuesException` carries no cause, so the driver's text, which can quote values, never travels with it.
   - The Postgres value store reports the SQL state only.
   - Crypto failures stay `CryptoException` with their own codes.
9. **Spring:** `platform.crypto.tenant-id` falls back to `platform.licensing.tenant-id`. The `CustomerId` bean exists
   only when a tenant ID is configured. No `platform-fields-spring` was asked for, so the app builds `FieldValues`
   itself from the beans.

## Not done, and why

- **Deleting `feature/case-fields`**, locally and on origin: the permission check refused
  `git push origin --delete feature/case-fields` again, and `git branch -d` needs the remote copy gone first. All of
  its work is in main. To finish, run `git push origin --delete feature/case-fields`, then
  `git branch -d feature/case-fields`.
- **Setup against a real Vault:** setup's tests use the in-memory key service. Vault key creation itself is tested
  in platform-crypto (`VaultCryptoTest`, `RecordEnvelopeVaultTest`). The setup module has no Vault container.

## Questions (numbered, each with a recommendation)

1. **Switching `sensitive` on or off once values exist.** `FieldDefinitions` allows it today. Existing rows stay as
   they were written: plain text stays readable after a field becomes sensitive, which breaks rule 6 for old values.
   *Recommendation:* lock the flag once values exist (like the type, a new code `FIELD_SENSITIVE_LOCKED`). If a field
   must become sensitive later, add a new field.
2. **Changing `primary` once values exist.** A stored record keeps its old identity until it is next written, so the
   primary rule is only fully in force for records written after the change. *Recommendation:* lock `primary` once
   values exist too. The alternative is a re-index job that recomputes identities.
3. **Removing records.** There is no delete of a record's values yet, which retention or "erase this case" will need.
   *Recommendation:* a later task: `FieldValues.erase(ref)`, deleting the rows and the envelope (crypto-shredding the
   record key).
4. **Concurrent-claim behaviour for LexaVault.** At its switch-over, the losing writer gets the existing record, not
   an error. *Recommendation:* keep it; this is what LexaVault's own `resolveOrCreate` wants.
5. **Setup and Vault in CI.** *Recommendation:* add a setup test against the Vault container when a shared Vault test
   helper module exists. It is not needed now.
