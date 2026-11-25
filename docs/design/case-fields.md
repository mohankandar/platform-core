# platform-fields — case field definitions (agreed 2026-11-21)

Source: the owner's Shared capabilities thread (project doc `Case-Fields-Design.md`). Coding sessions read this copy. Do not change the rules without the owner.

## Why

Case fields carry the business's own labels and values (contract number, member number, loan number, first/last name, address, patient ID…). Before this module, two apps each had their own model: a document vault app (`business_identifier_definitions`) and a document intake app (`metadata_field_definitions`). **Decision (owner, 2026-11-21): the model moves to platform-core; both apps remove their own and use this one** (in each app's switch-over). Nothing is in production or test anywhere, so no data migration is needed.

## The model

- **Field definition**, per entity and product: stable `key` (e.g. `contract_number`), `label` ("Contract number"), `type`, rules, flags, display order, active.
- **Types:** TEXT, NUMBER (whole), DECIMAL (number with decimals, range), MONEY (decimal, **one currency per field**, e.g. USD), DATE, DATE_TIME (with its offset from UTC), EMAIL, UUID, DROPDOWN (up to 100 values), ADDRESS (**structured: street, optional second street line, city, state, ZIP, with a country part ready for later; US format first**), CUSTOM_PATTERN. DECIMAL, DATE_TIME and the second street line: owner answers to P017. The vault app's existing NUMERIC/ALPHANUMERIC/ALLOW_LIST map to NUMBER/TEXT-with-pattern/DROPDOWN.
- **Rules:** required, exact/min/max length, pattern, normalisation (case, strip dashes and spaces). Values up to 512 characters.
- **Flags:** primary identifier, searchable, **sensitive** (value encrypted at rest, hidden in logs, shown only to roles that need it), **multi-value** (e.g. two borrowers), displayable.
- **App-only settings stay in the app** (e.g. the vault app's "AI-extractable"; the intake app's "record title"), attached by key, not in the shared model.

## Rules

1. **Each app keeps its own field definitions per entity**, using this shared model. A customer may buy only one product.
2. **One app can import another app's field list** (export/import file in a shared format) so nobody types it twice. **Matching is by key.**
3. **A delivery from one app to another carries each field as key + type + value** (plus the multi-value list), so the receiving app knows exactly what it received. A value whose key the receiver doesn't know, or whose type differs, is refused with a specific code.
4. **After values exist:** the label may change any time; **key and type never change** (old documents would no longer match). To change a type, add a new field.
5. **Removing a field = switching it off, never deleting.** Existing values stay.
6. **Sensitive fields (owner, 2026-11-21, option A):** a sensitive case field (e.g. borrower name, address) is visible in the document (it is a case field, so not redacted in that entity) and stored **only encrypted**, never as readable text in any table, log or backup. It is **searchable by exact match only**, through a one-way fingerprint (keyed hash) of the normalised value; matching ignores capitals and extra spaces. No partial search on sensitive fields; partial search only on non-sensitive fields (e.g. contract or loan number). Name and address fields are marked sensitive by default; the Business Admin decides. Encryption and the fingerprint key come from the shared secrets/encryption capability (built with value storage, next task).
7. App rules stay in the apps, e.g. in a redaction app: a case field and a redaction item are mutually exclusive per entity; SSN, card number, CVV, PIN and other locked items can never be case fields.

## Mapping from the apps' current models (P017)

Recorded by P017 from the code of both apps (read only, 2026-11-21). Used in each app's switch-over; nothing is in
production, so there is no data migration. `LegacyTypeMappingTest` shows each line keeps what the app's own validator
accepts and how it normalises. The owner's answers to the P017 questions are in the section after the tables.

### The document vault app's `business_identifier_definitions` (changelogs 008, 019, 020, 022)

| Vault app before | platform-fields |
|---|---|
| `TEXT` | `TEXT` |
| `NUMERIC` (digits only, leading zeros kept) | `NUMBER` with pattern `[0-9]+` (NUMBER alone also takes a leading minus) |
| `ALPHANUMERIC` | `TEXT` with pattern `[A-Za-z0-9]+` |
| `EMAIL`, `UUID`, `CUSTOM_PATTERN` | the same type (`UUID` is stored in lower case; matching is unchanged) |
| `ALLOW_LIST` (+ `allowed_values`) | `DROPDOWN` (+ `options`, up to 100) |
| `allowed_values` on another type | `DROPDOWN` with those values (the values already had to pass the type) |
| `case_normalization` PRESERVE / UPPER / LOWER | `letterCase` PRESERVE / UPPER / LOWER (the vault app's default UPPER stays explicit) |
| `strip_separators_flag` | `strip` = `SEPARATORS` (dashes and spaces) |
| `exact_length`, `min_length`, `max_length`, `custom_pattern` | the same rules |
| `required_flag`, `primary_flag`, `searchable_flag`, `displayable_flag`, `display_order`, `active_flag` | the same flags (`searchMode` EXACT) |
| `extractable_flag` | app-only setting, attached by key (`FieldDefinitions.putExtension`, for example `extractable=true`) |
| `identifier_key` (`[a-z][a-z0-9_-]{0,63}`), `label` (128) | `key` (wider: `[A-Za-z][A-Za-z0-9_.-]{0,99}`), `label` (200) |
| one active primary per tenant (index 008-06); primary values unique (019, 020) | stays a vault app rule: the shared model allows several primary fields (as the intake app needs). Value uniqueness belongs to value storage (next task). Owner answer 3. |
| new: `sensitive`, `multiValue`, MONEY, DATE, ADDRESS | set by the Business Admin; names and addresses sensitive by default |

### The document intake app's `metadata_field_definitions` (changelogs 001, 003, 032)

| Intake app before | platform-fields |
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
| per `source_id` (sending system) | one list per entity and product; "which fields a source sends" is an intake app setting by key. Owner answer 2. |

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
2. **One field list per entity and product in the intake app too.** "Which fields a source sends" becomes an
   intake app setting
   attached by key (`FieldDefinitions.putExtension`). Nothing to build in platform-core.
3. **Several primary fields allowed in the shared model** (as built in P017). The vault app keeps its one-primary rule
   itself; primary value uniqueness comes with value storage.
4. **Import keeps the existing field**; the import report shows the differences (as built in P017).
5. **ADDRESS has an optional `street2`** (apartment, suite): `FieldValue.Address.street2`, in the delivery format
   as the member `"street2"` (left out when there is none). Tidied like the other parts; blank means none; counts
   towards the 512 characters of an address. The first street line stays required.

## Decisions from P017–P019

Decisions the coding tasks made, kept here so the design holds them (source in brackets: report and decision number).

- **Keys** (P017-1): kept exactly as given and matched exactly (import, delivery). Two keys of one entity and product
  may not differ only in capitals (a unique index on the folded key); such an import line is a `KEY_CASE_DIFFERS`
  conflict.
- **Primary** (P017-3): a primary field takes one value (`FIELD_PRIMARY_MULTI_VALUE`) and must be switched on
  (`FIELD_PRIMARY_INACTIVE`). Switching a field off ends its primary flag.
- **Search mode** (P017-4): a field that is not searchable keeps search mode EXACT (`FIELD_SEARCH_MODE_INVALID`).
  Rule 6 (a sensitive field is searched by EXACT only) is enforced by the model and by the database.
- **Normalisation order** (P017-5): trim and single spaces first, then strip, then letter case. Only for TEXT, NUMBER,
  EMAIL, DROPDOWN and CUSTOM_PATTERN; the other types have their own fixed form. Length rules and patterns apply to
  the normalised value.
- **Per type** (P017-6):
  - NUMBER: digits with an optional minus, any size, leading zeros kept.
  - MONEY: takes `1,250.50`, `$1,250.50`, `USD 1250`; at most the currency's decimals; normalised to `1250.50`.
    A currency without fixed decimals (gold, `XXX`) is refused.
  - DATE: ISO and US `M/d/yyyy`, normalised to ISO. UUID: the 36-character form, lower case. DROPDOWN: compared
    after normalisation; the display form is the listed value.
  - ADDRESS: US only for now. State = a USPS code (states, DC, territories, military). ZIP 5 digits or ZIP+4
    (9 digits are formatted). An empty country means US.
- **Values** (P017-7): a tab is allowed; other control characters and line separators are not. A multi-value field
  takes up to **50** distinct values (distinct by normalised form). A blank value counts as no value.
- **App-only settings** (P017-8): in `fld_field_extension`, keyed by field, outside `FieldSpec`, never exported.
  Names `[a-z][a-z0-9_.-]{0,63}`, values up to 1000 characters.
- **`change()`** (P017-9): keeps the field on or off as it is (`switchOff` / `switchOn` switch it). A change that
  changes nothing makes no new version. A MONEY field's currency counts as part of its type.
- **Import** (P017-10): never changes an existing field, not even its label; the report lists what the file has
  differently (`TYPE_DIFFERS`, `CURRENCY_DIFFERS`, `KEY_CASE_DIFFERS`). New fields are created all or none: if
  someone adds one of the keys meanwhile, nothing is created. New fields are created even when other lines are
  conflicts.
- **Delivery** (P017-11): the sender sends its display values; the receiver checks them with its own rules and
  normalisation. Any problem refuses the delivery's case fields as a whole. An entry with the wrong type counts as
  delivered (no extra "required missing"). Messages name entry numbers, keys, labels and rules; never a value, and
  never a member name the sender made up (those are counted). Unknown keys are named, so the sender can fix its
  mapping. Not from a report, code only: a delivery is at most 2,000,000 characters (about 2 MB) and 1000 fields
  (`DELIVERY_TOO_LARGE`).
- **Limits in messages** (P017-13): messages may name configured limits ("must be at least 0.00", "must have at
  most 20 characters"). These are definitions, not values.
- **No cause in exceptions** (P019-8): `FieldValuesException` carries no cause, because a driver's text can quote
  values. The Postgres value store reports the SQL state only. Crypto failures stay `CryptoException` with their own
  codes.
- **No Spring module** (P019-9): there is no `platform-fields-spring`. The app builds `FieldValues` itself from the
  crypto beans (`docs/install/fields.md`, "Wiring in the app").
- **Partial search detection** (P019-6): whether partial search is available is found at run time, from
  `pg_indexes` in the current schema (the trigram index `fld_value_partial`).

## Value storage (P019)

Built in P019 in `platform-fields` (package `com.documentplatform.fields.values`, Postgres store in `jdbc`), on
`platform-crypto`. Install guide: `docs/install/fields.md`.

### What "primary" means in the apps today (read in P019, nothing changed in either app)

- **The document vault app** has a real business record (`business_records`); packages point to it (`business_record_id`), many
  packages per record. Exactly one active primary field per tenant (index 008). The primary value is unique per tenant
  and definition (trigger 019, serialised with an advisory lock in 020); secondary values may repeat across records.
  When the same contract number arrives again, the package joins the existing record: secondary values it lacks are
  added, a different secondary value is refused (`IDENTIFIER_CONFLICT`). The loser of two concurrent writers of a new
  value gets a database error, not the record. "Entity" there is the tenant.
- **The document intake app** has no record table: a record is the group of submissions (per tenant and sending system) whose
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
  With one primary field this is the vault app's rule; with several it is the intake app's identifier set (a part of the set is
  another identity). A record without primary values is a record of its own. The identity is SHA-256 over the
  primary fields' values in key order, each as compared by search (non-sensitive) or by its fingerprint (sensitive),
  so no sensitive value is readable in it. Comparison ignores capitals and extra spaces, like fingerprints.
- **`FieldValues.claim(candidate, values)`** is how the app finds or makes the record for incoming values: a record
  with the same identity → that record (whatever ID the candidate has); fields it lacks get the given values, equal
  values stay, a different value is refused (`VALUES_CONFLICT`, keys only, nothing written: the vault app's
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
- **Sealing contexts and form** (pinned by `FieldsKnownAnswerTest`, P038):
  - record context: `fld/` + Base64url (no padding) of SHA-256 of `entity|product|recordType|recordId` (the four
    joined with `|`), so a sealed item copied to another record does not open;
  - item context: `<folded field key>/<position>` (the key in lower case);
  - fingerprint context: `fld:<entity>:<product>:<folded key>`;
  - the sealed form of a value: a kind letter (`T` text, `A` address), the display form, U+001E, the normalised
    form. An address's six parts (street, street2, city, state, ZIP, country; empty for none) are joined by U+001F.
- **Search** (`FieldValues.search`): EXACT (term checked and normalised like a value), PREFIX, PARTIAL, per the
  field's `SearchMode`; a wider mode than the field offers is refused (`VALUES_SEARCH_MODE_NOT_ALLOWED`). Sensitive
  fields: exact only, through the fingerprint over every usable fingerprint key version; prefix or partial is refused
  (`VALUES_SENSITIVE_EXACT_ONLY`). PARTIAL uses a `pg_trgm` trigram index created by the schema step when possible;
  without it partial runs as prefix and the result says so (`SearchResult.Note.PARTIAL_AS_PREFIX`;
  `searchStatus()` gives `FLD_PARTIAL_SEARCH_UNAVAILABLE` and names `db/platform/fields-optional/partial-search.sql`).
- **Reading:** `read` never decrypts (sensitive values hidden, count only); `readSensitive` is the explicit call the
  app makes only for roles allowed to see sensitive values: one key service call per record.
  `readSensitive(List<RecordRef>)` (1.1.0, P054) reads a page: at most 100 records (`IllegalArgumentException` above), one
  `SensitiveRead` per record in the order given, **one** batched key service call for all (none when there is nothing to
  open). Each result is what the single read gives: the values, or its exception (`VALUES_RECORD_UNKNOWN`,
  `VALUES_WRONG_CUSTOMER`, `CRYPTO_CIPHERTEXT_INVALID`, `CRYPTO_KEY_DISABLED`, …). Key service away, permission, missing
  customer key or a store failure throws for the whole page. Since 1.2.0 (P056) the store is asked **once** for the page
  (`ValueStore.findAll`; the default asks `find` per record, so a store of your own keeps working), and `read(List<RecordRef>)`
  reads a page the same way with the sensitive values hidden and no key service call. Values never appear in a
  `toString()`, a log (nothing is logged) or an exception message.
- **Without a key service (1.2.0):** `FieldValues.withoutKeyService(…)` for an app none of whose fields is sensitive; a call that
  would seal, open or fingerprint a sensitive value is `VALUES_KEY_SERVICE_REQUIRED`, nothing written.
- **Conflicts as data (1.2.0):** `FieldValuesException.conflictingKeys()` names the keys of a `VALUES_CONFLICT`.
- **A list of definitions, all or none (1.2.0):** `FieldDefinitions.change(scope, List<FieldChange>, actor)` in one transaction
  (`FieldStore.saveAll`; a store of your own must implement it, the default refuses and saves nothing).
- **Key service calls:** new record with sensitive values: the data key, plus, when any value needs a fingerprint,
  the fingerprint key's versions and one batch (3 in all, however many values). Opening a record: 1; opening a
  page with `readSensitive(List)`: 1. Key rotation:
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
3. **Erasing a record** (before the intake app's retention and the vault app's deletion): through the erase log (`Eraser.erase`, kind
   `case-fields`), which calls `FieldValues.erase(ref)`. See "Erasing a record" below.
4. **Concurrent arrivals get the existing record**: kept as built in P019.
5. **Setup against a real Vault**: later.

## Erasing a record (P020)

- **Only through the erase log (P022):** `FieldValues.erase` accepts only the erase log's `CaseFieldsEraseHandler`
  as its caller; any other caller is refused with `VALUES_ERASE_OUTSIDE_LOG` and nothing is erased. Apps call
  `Eraser.erase` with kind `case-fields` (`docs/design/erase-log.md`).
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

## Erase and backups (owner, 2026-11-22)

P020 found that erase removes a record from the live database, but a backup taken earlier can still be opened while the customer's key lives (the record key is stored in the database, wrapped by the customer's key). Decision: **(b) + (c)**.

- **(b) Everyday erase and retention:** erase removes the record live. Backups are kept only for a set retention period; if a backup is ever restored, the erasures are re-applied from an erase log kept outside the restored database. Standard practice (e.g. GDPR).
- **(c) Customer exit:** destroying the customer's keys makes all of that customer's data unreadable everywhere, backups included. This backs the offboarding certificate.
- **(a) Per-record keys in the key service:** not now; only if a customer contract requires single-record shredding of backups.
- Both go into the retention design of each app (the intake app's retention, the vault app's deletion). To be confirmed by the vault app's thread (master); confirmed 2026-11-22 (`docs/design/erase-log.md`).
- Built in P021: the erase log (`platform-erase-log`, `docs/design/erase-log.md`) with the built-in `case-fields`
  handler (`CaseFieldsEraseHandler`: legal hold checked before the entry is logged, `FieldValues.erase` as the erase,
  the value store read by the verify step), and customer key destruction (`docs/design/crypto.md` section 11). Apps
  erase case fields through `Eraser.erase` with kind `case-fields`; since P022 a direct call of `FieldValues.erase`
  is refused (`VALUES_ERASE_OUTSIDE_LOG`), so every erasure is in the log.
