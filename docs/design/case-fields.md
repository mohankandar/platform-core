# platform-fields — case field definitions (agreed 2026-11-21)

Source: the owner's Shared capabilities thread (project doc `Case-Fields-Design.md`). Coding sessions read this copy. Do not change the rules without the owner.

## Why

Case fields carry the business's own labels and values (contract number, member number, loan number, first/last name, address, patient ID…). Today LexaVault (`business_identifier_definitions`) and Vero (`metadata_field_definitions`) each have their own model. **Decision (owner, 2026-11-21): the model moves to platform-core; LexaVault and Vero remove their own and use this one** (in each app's switch-over). Nothing is in production or test anywhere, so no data migration is needed.

## The model

- **Field definition**, per entity and product: stable `key` (e.g. `contract_number`), `label` ("Contract number"), `type`, rules, flags, display order, active.
- **Types:** TEXT, NUMBER (whole), DECIMAL (number with decimals, range), MONEY (decimal, **one currency per field**, e.g. USD), DATE, DATE_TIME (with its offset from UTC), EMAIL, UUID, DROPDOWN (up to 100 values), ADDRESS (**structured: street, optional second street line, city, state, ZIP, with a country part ready for later; US format first**), CUSTOM_PATTERN. DECIMAL, DATE_TIME and the second street line: owner answers to P017. LexaVault's existing NUMERIC/ALPHANUMERIC/ALLOW_LIST map to NUMBER/TEXT-with-pattern/DROPDOWN.
- **Rules:** required, exact/min/max length, pattern, normalisation (case, strip dashes and spaces). Values up to 512 characters.
- **Flags:** primary identifier, searchable, **sensitive** (value encrypted at rest, hidden in logs, shown only to roles that need it), **multi-value** (e.g. two borrowers), displayable.
- **App-only settings stay in the app** (e.g. LexaVault "AI-extractable"; Vero "record title"), attached by key, not in the shared model.

## Rules

1. **Each app keeps its own field definitions per entity**, using this shared model. A customer may buy only one product.
2. **Vero can import LexaVault's field list** (export/import file in a shared format) so nobody types it twice. **Matching is by key.**
3. **LexaVault → Vero delivery carries each field as key + type + value** (plus the multi-value list), so Vero knows exactly what it received. A value whose key Vero doesn't know, or whose type differs, is refused with a specific code.
4. **After values exist:** the label may change any time; **key and type never change** (old documents would no longer match). To change a type, add a new field.
5. **Removing a field = switching it off, never deleting.** Existing values stay.
6. **Sensitive fields (owner, 2026-11-21, option A):** a sensitive case field (e.g. borrower name, address) is visible in the document (it is a case field, so not redacted in that entity) and stored **only encrypted**, never as readable text in any table, log or backup. It is **searchable by exact match only**, through a one-way fingerprint (keyed hash) of the normalised value; matching ignores capitals and extra spaces. No partial search on sensitive fields; partial search only on non-sensitive fields (e.g. contract or loan number). Name and address fields are marked sensitive by default; the Business Admin decides. Encryption and the fingerprint key come from the shared secrets/encryption capability (built with value storage, next task).
7. App rules stay in the apps, e.g. LexaVault: a case field and a redaction item are mutually exclusive per entity; SSN, card number, CVV, PIN and other locked items can never be case fields.

## Mapping from the apps' current models (P017)

Recorded by P017 from the code of both apps (read only, 2026-11-21). Used in each app's switch-over; nothing is in
production, so there is no data migration. `LegacyTypeMappingTest` shows each line keeps what the app's own validator
accepts and how it normalises. The owner's answers to the P017 questions are in the section after the tables.

### LexaVault `business_identifier_definitions` (changelogs 008, 019, 020, 022)

| LexaVault today | platform-fields |
|---|---|
| `TEXT` | `TEXT` |
| `NUMERIC` (digits only, leading zeros kept) | `NUMBER` with pattern `[0-9]+` (NUMBER alone also takes a leading minus) |
| `ALPHANUMERIC` | `TEXT` with pattern `[A-Za-z0-9]+` |
| `EMAIL`, `UUID`, `CUSTOM_PATTERN` | the same type (`UUID` is stored in lower case; matching is unchanged) |
| `ALLOW_LIST` (+ `allowed_values`) | `DROPDOWN` (+ `options`, up to 100) |
| `allowed_values` on another type | `DROPDOWN` with those values (the values already had to pass the type) |
| `case_normalization` PRESERVE / UPPER / LOWER | `letterCase` PRESERVE / UPPER / LOWER (LexaVault's default UPPER stays explicit) |
| `strip_separators_flag` | `strip` = `SEPARATORS` (dashes and spaces) |
| `exact_length`, `min_length`, `max_length`, `custom_pattern` | the same rules |
| `required_flag`, `primary_flag`, `searchable_flag`, `displayable_flag`, `display_order`, `active_flag` | the same flags (`searchMode` EXACT) |
| `extractable_flag` | app-only setting, attached by key (`FieldDefinitions.putExtension`, for example `extractable=true`) |
| `identifier_key` (`[a-z][a-z0-9_-]{0,63}`), `label` (128) | `key` (wider: `[A-Za-z][A-Za-z0-9_.-]{0,99}`), `label` (200) |
| one active primary per tenant (index 008-06); primary values unique (019, 020) | stays a LexaVault rule: the shared model allows several primary fields (Vero). Value uniqueness belongs to value storage (next task). Owner answer 3. |
| new: `sensitive`, `multiValue`, MONEY, DATE, ADDRESS | set by the Business Admin; names and addresses sensitive by default |

### Vero `metadata_field_definitions` (changelogs 001, 003, 032)

| Vero today | platform-fields |
|---|---|
| `TEXT`, `DATE`, `EMAIL`, `UUID` | the same type (DATE also takes `11/21/2026`; stored as `2026-11-21`) |
| `INTEGER` | `NUMBER` (a leading `+` is no longer taken) |
| `DECIMAL` | `DECIMAL` (min/max kept as `minValue` / `maxValue`), or `MONEY` with its currency when it is an amount. Owner answer 1. |
| `DATETIME` | `DATE_TIME` (ISO 8601 with offset, as `OffsetDateTime.parse` took; calendar checked). Owner answer 1. |
| `BOOLEAN` | `DROPDOWN` with `true`, `false` and `letterCase` LOWER |
| `normalization` NONE / TRIM / UPPERCASE / LOWERCASE / DIGITS_ONLY | always trimmed with single spaces, then PRESERVE / PRESERVE / UPPER / LOWER / `strip` NON_DIGITS |
| `search_mode` EXACT / PREFIX / PARTIAL | `searchMode` EXACT / PREFIX / PARTIAL (sensitive fields: EXACT only, rule 6) |
| `validation_rules` exactLength, minLength, maxLength, pattern, min, max | `exactLength`, `minLength`, `maxLength`, `pattern`; `minValue` / `maxValue` (NUMBER, DECIMAL and MONEY). A pattern on DATE, DATE_TIME, DECIMAL, UUID, DROPDOWN, MONEY or ADDRESS is not taken. |
| `business_identifier` (several per source) | `primary` (several allowed) |
| `required`, `searchable`, `active` | the same flags |
| `version`, `effective_at`, `retired_at` (a new row per revision) | `version` plus one history entry per change (`fld_field_definition_history`) |
| `metadata_field_display_order` | `displayOrder` |
| record title fields | app-only setting, attached by key |
| `field_key` (`[A-Za-z][A-Za-z0-9_.-]{0,99}`), `display_label` (200) | `key`, `label` (same limits) |
| per `source_id` (sending system) | one list per entity and product; "which fields a source sends" is a Vero setting by key. Owner answer 2. |

## Owner answers to P017 (2026-11-22, task P018)

1. **DECIMAL and DATE_TIME types: yes.** Built in P018.
   - `DECIMAL`: digits with an optional leading minus and decimal part (`-4.125`); no thousands commas, no exponent,
     no leading `+`. Normalised without trailing zeros (`4.50` → `4.5`, `100.0` → `100`). Takes
     `minValue` / `maxValue` with any number of decimals. No length rules, pattern or normalisation (like MONEY).
     Refusal code `VALUE_NOT_A_DECIMAL`.
   - `DATE_TIME`: ISO 8601 date and time **with** its offset (`2026-11-21T10:15:30Z`,
     `2026-11-21T10:15:30.5-05:00`; seconds and a fraction of up to 9 digits optional), calendar checked. The display
     form is what was given; the normalised form is the same moment in UTC with milliseconds
     (`2026-11-21T15:15:30.500Z`; nanoseconds when the value has them), so the same moment matches whatever the
     offset. No range, length rules, pattern or normalisation. Refusal code `VALUE_NOT_A_DATE_TIME`.
2. **One field list per entity and product in Vero too.** "Which fields a source sends" becomes a Vero setting
   attached by key (`FieldDefinitions.putExtension`). Nothing to build in platform-core.
3. **Several primary fields allowed in the shared model** (as built in P017). LexaVault keeps its one-primary rule
   itself; primary value uniqueness comes with value storage.
4. **Import keeps the existing field**; the import report shows the differences (as built in P017).
5. **ADDRESS has an optional `street2`** (apartment, suite): `FieldValue.Address.street2`, in the delivery format
   as the member `"street2"` (left out when there is none). Tidied like the other parts; blank means none; counts
   towards the 512 characters of an address. The first street line stays required.

## Value storage (P019)

Built in P019 in `platform-fields` (package `com.documentplatform.fields.values`, Postgres store in `jdbc`), on
`platform-crypto`. Install guide: `docs/install/fields.md`.

### What "primary" means in the apps today (read in P019, nothing changed in either app)

- **LexaVault** has a real business record (`business_records`); packages point to it (`business_record_id`), many
  packages per record. Exactly one active primary field per tenant (index 008). The primary value is unique per tenant
  and definition (trigger 019, serialised with an advisory lock in 020); secondary values may repeat across records.
  When the same contract number arrives again, the package joins the existing record: secondary values it lacks are
  added, a different secondary value is refused (`IDENTIFIER_CONFLICT`). The loser of two concurrent writers of a new
  value gets a database error, not the record. "Entity" there is the tenant.
- **Vero** has no record table: a record is the group of submissions (per tenant and sending system) whose
  business-identifier values are all equal (`RecordKey`, every identifier field together; a submission without them
  is a record of its own). Values are stored per submission, nothing is unique, and a part or a superset of the
  identifier set is another record.
- Neither encrypts these values, and neither has multi-value fields.

### What is built

- **Records.** Values belong to an app record: `RecordRef` = entity + product (`FieldScope`) + the app's record type +
  the app's record ID. Many documents, packages or submissions may point to one record.
- **The primary rule** (serves both apps): a record's *identity* is the values of its primary fields together.
  **One record per identity in each entity, product and record type**, enforced by a unique index on
  `fld_record (entity_uuid, product_code, record_type, identity)`, so it holds with any number of concurrent writers.
  With one primary field this is LexaVault's rule; with several it is Vero's identifier set (a part of the set is
  another identity). A record without primary values is a record of its own. The identity is SHA-256 over the
  primary fields' values in key order, each as compared by search (non-sensitive) or by its fingerprint (sensitive),
  so no sensitive value is readable in it. Comparison ignores capitals and extra spaces, like fingerprints.
- **`FieldValues.claim(candidate, values)`** is how the app finds or makes the record for incoming values: a record
  with the same identity → that record (whatever ID the candidate has); fields it lacks get the given values, equal
  values stay, a different value is refused (`VALUES_CONFLICT`, keys only, nothing written: LexaVault's
  `IDENTIFIER_CONFLICT`). Otherwise a new record under the candidate's ID. Concurrent claims of a new value all get
  the same record (`insert … on conflict do nothing`, then read the winner). `update(ref, values)` replaces given
  fields; a primary value another record has is refused (`VALUES_PRIMARY_TAKEN`). `findByPrimary` looks a record up.
- **Values** are checked by `FieldSet` / `FieldValidator` (all types, multi-value, required). Not sensitive: stored as
  display value, normalised value and search form (normalised, lower case, single spaces; an address: its parts).
  **Sensitive (rule 6):** never readable in any table, log or backup. All sensitive values of a record are sealed
  under one record envelope (`RecordCipher`, owner answer P018-2): `fld_record.envelope` (`pck1.`) and one
  `fld_value.sealed_value` (`pci1.`) per value, bound to the record and the field and position. A sensitive value of a
  searchable or primary field also gets a fingerprint (`Fingerprinter`, context `fld:<entity>:<product>:<field key>`,
  capitals and extra spaces ignored). Check constraints refuse a sensitive row with any readable column.
- **Search** (`FieldValues.search`): EXACT (term checked and normalised like a value), PREFIX, PARTIAL, per the
  field's `SearchMode`; a wider mode than the field offers is refused (`VALUES_SEARCH_MODE_NOT_ALLOWED`). Sensitive
  fields: exact only, through the fingerprint over every usable fingerprint key version; prefix or partial is refused
  (`VALUES_SENSITIVE_EXACT_ONLY`). PARTIAL uses a `pg_trgm` trigram index created by the schema step when possible;
  without it partial runs as prefix and the result says so (`SearchResult.Note.PARTIAL_AS_PREFIX`;
  `searchStatus()` gives `FLD_PARTIAL_SEARCH_UNAVAILABLE` and names `db/platform/fields-optional/partial-search.sql`).
- **Reading:** `read` never decrypts (sensitive values hidden, count only); `readSensitive` is the explicit call the
  app makes only for roles allowed to see sensitive values: one key service call per record. Values never appear in a
  `toString()`, a log (nothing is logged) or an exception message.
- **Key service calls:** new record with sensitive values: the data key, plus, when any value needs a fingerprint,
  the fingerprint key's versions and one batch (3 in all, however many values). Opening a record: 1. Key rotation:
  older wrapping key versions keep opening; `FieldValues.rewrap(ref)` moves a record's envelope to the latest
  version before an old one is switched off; a rotated fingerprint key keeps search and the primary rule working
  (lookups try one identity per usable version).
- **Customers:** `FieldValues` serves one customer (`CustomerId.fromTenantId`); a record of another customer is
  refused (`VALUES_WRONG_CUSTOMER`), search filters by customer, and the envelope itself refuses another customer's
  key (`CRYPTO_WRONG_CUSTOMER`).
- **Storage:** `fld_record`, `fld_value` in `V1__fields.sql` (never released, so edited); startup only verifies
  (`FieldSchema.verify`); the runtime user needs DML only. `PostgresValueStore`; the store is also the app's
  `FieldValuesCheck`, so a field's type locks once a record holds a value. Testkit: `InMemoryValueStore` with the
  same rules (partial search can be switched off).

## Owner answers to P019 (2026-11-22, task P020)

1. **`sensitive` is locked once values exist**, like the type: `FieldDefinitions.change` refuses switching it on or
   off with `FIELD_SENSITIVE_LOCKED` once the app's `FieldValuesCheck` says a value exists. To keep such values the
   other way, add a new field. (Old plain-text values can then never sit under a sensitive field, and sealed values
   never under a plain one.)
2. **`primary` is locked once values exist**: `FIELD_PRIMARY_LOCKED`. Stored identities therefore always match the
   field list. Switching a field off still takes its primary flag away (a switched-off field is never primary, and
   removing a field must always be possible, rule 5); switching it back on does not make it primary again, and
   `change` cannot either while values exist. If both the type and a flag change, `FIELD_TYPE_LOCKED` is given.
3. **Erasing a record** (before Vero retention and LexaVault deletion): `FieldValues.erase(ref)`. See "Erasing a
   record" below.
4. **Concurrent arrivals get the existing record**: kept as built in P019.
5. **Setup against a real Vault**: later.

## Erasing a record (P020)

- `FieldValues.erase(ref)` deletes the record's value rows, its envelope (the record's wrapped data key) and the
  `fld_record` row itself, in one transaction, if the record has not changed meanwhile (otherwise it reads again and
  retries). Afterwards `read`, `readSensitive`, `search` and `findByPrimary` no longer find it; the same primary
  value(s) arriving later make a new record. No key service call.
- **Explicit and audited by the caller:** never automatic. The result `Erased` (record, number of values, whether it
  had sealed values, when) holds no value, for the caller's audit entry with who and why.
- **Legal hold:** the app supplies `LegalHolds` (`FieldValues(…, holds)`; without it no record is held). A held record
  is refused with `VALUES_LEGAL_HOLD`; if the check throws, the erase is refused with `VALUES_LEGAL_HOLD_UNKNOWN`.
  Nothing is erased in either case. Another customer's record: `VALUES_WRONG_CUSTOMER`.
- **The runtime user** now also needs DELETE on `fld_record` (the install guide already grants it; `FieldSchema`
  checks it).
- **Not crypto-shredding (open, P020 report question 1):** the envelope lives in `fld_record`, so a database backup
  taken before the erase still holds it, and the customer's wrapping key, which lives on, still opens it. Deleting the
  rows makes the values unreadable in the live database only. Making backups unreadable needs a per-record secret
  kept outside the database (in the key service) that erase destroys. `PostgresValueStoreTest
  .aRestoredBackupOfAnErasedRecordStillOpensWhileTheCustomersKeyLives` pins today's behaviour so it is not mistaken
  for crypto-shredding.
