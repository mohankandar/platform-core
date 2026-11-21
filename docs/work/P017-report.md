# P017 report — platform-fields: case field definitions

Built on `feature/case-fields` (from `main`), pushed to origin. **Not merged**, as the task says.

## Result

- `mvn verify`: **685 tests, 0 failures**. That is the 608 existing tests, all still passing, plus 77 new ones.
  `mvn install` ran with the same result.
- New tests by module:
  - platform-fields: 60 (model rules, every type, field set, file and import, delivery, the mapping, Postgres schema
    and store, no value in any log)
  - platform-fields-testkit: 10 (`FieldDefinitionsTest`: the change rules, in memory)
  - platform-notifications-testkit: 5 (`ApprovalExpiryTest`, owner answer Q2)
  - platform-notifications-spring: 2 (in `ApprovalWiringTest`, owner answers Q2 and Q3 through the real tick)
- **No existing test was changed.** The approval test fixture gained one event type ("Print request expired").
- **Commits:** the first commit on the branch is the two design files `docs/design/case-fields.md` and
  `docs/design/integration-standards.md`, exactly as they were in the working tree. Then one commit with the feature, this task
  and report.

## What was built

### platform-fields (`com.documentplatform.fields`; plain Java, no runtime dependencies)

- **Model.** `FieldSpec` is a field: `key` (`FieldKey`), `label`, `type`, rules, flags, `displayOrder`, `active`.
  Every rule is checked when a spec is made, so a spec that exists is valid; refusals are
  `FieldDefinitionException` with a stable `Code` (26 codes, for example `FIELD_CURRENCY_REQUIRED`,
  `FIELD_SENSITIVE_EXACT_ONLY`, `FIELD_TYPE_LOCKED`). `FieldScope` = entity UUID + product code. `FieldDefinition` =
  a stored spec with id, scope, version, times and who changed it last.
  - Types: TEXT, NUMBER, MONEY (one ISO 4217 currency), DATE, EMAIL, UUID, DROPDOWN (1–100 values), ADDRESS (street,
    city, state, ZIP, country; US only for now), CUSTOM_PATTERN.
  - Rules: required, exact/min/max length, pattern, normalisation (letter case PRESERVE/UPPER/LOWER; strip NONE,
    SEPARATORS or NON_DIGITS), lowest/highest value (NUMBER, MONEY). Each rule only on the types it fits.
  - Flags: primary, searchable with `SearchMode` (EXACT, PREFIX, PARTIAL), sensitive, multi-value, displayable.
  - **Sensitive by default:** every ADDRESS field, and fields whose key or label has a name or address word
    (`first_name`, "Borrower name", `mailing_address`). The Business Admin can change it.
- **Values.** `FieldValue` (text, or an address in parts). `FieldValidator` checks one value or a field's list of
  values and returns the display form and the normalised form (`ValidValue`), or a `ValueProblem` with a stable
  `ValueError` (28 codes) and a plain message naming the label and the rule, never the value. `FieldSet` checks a
  case's values together: unknown keys, switched-off fields, missing required fields, then each field.
  `toString()` of values never shows them.
- **Change rules: `FieldDefinitions`** (the app's API over a `FieldStore`):
  - a key is used once per entity and product, ignoring capitals, and never changes;
  - label, rules and flags may change any time; a change is saved only from the version read
    (`FIELD_CHANGED_MEANWHILE` otherwise);
  - the type (and a MONEY field's currency) changes only while the app's `FieldValuesCheck` says no value exists
    (`FIELD_TYPE_LOCKED` otherwise; a check that fails refuses the change);
  - removing = `switchOff` (also ends primary); `switchOn`; nothing is ever deleted;
  - every version is in the history, with who and when;
  - app-only settings attach to a field by key (`putExtension` / `extensions`); they are not part of `FieldSpec` and
    are never exported.
- **Field list file** `file.FieldListFile`, format `platform-fields/1` (JSON, strict subset, decimals as strings):
  write and read, strict (unknown members, repeated keys, bad fields refused with `FieldFileException` codes and the
  field's number). **Import** (`FieldDefinitions.previewImport` / `importFile`, `file.FieldImport`): matching by
  exact key; a new key is created (all new fields in one transaction); an existing one is never changed:
  UNCHANGED (same type; what the file has differently is listed) or CONFLICT (`TYPE_DIFFERS`, `CURRENCY_DIFFERS`,
  `KEY_CASE_DIFFERS`).
- **Delivery** `delivery.FieldDelivery`, format `platform-field-values/1`: each field as key + type (+ currency) +
  `value`, or `values` for a multi-value field; an address as an object of its parts. `write` from the sender's
  accepted values; `verify` on the receiving side against its own field list returns the checked values or every
  problem with a `DeliveryProblem.Code`: `DELIVERY_KEY_UNKNOWN`, `DELIVERY_TYPE_MISMATCH`,
  `DELIVERY_CURRENCY_MISMATCH`, `DELIVERY_VALUE_COUNT_MISMATCH`, `DELIVERY_FIELD_INACTIVE`,
  `DELIVERY_VALUE_INVALID` (with the `ValueError`), `DELIVERY_REQUIRED_MISSING`, `DELIVERY_KEY_REPEATED`,
  `DELIVERY_KEY_INVALID`, `DELIVERY_SHAPE_INVALID`, `DELIVERY_FORMAT_UNKNOWN`, `DELIVERY_UNREADABLE`,
  `DELIVERY_TOO_LARGE`.
- **Postgres** `jdbc.PostgresFieldStore`; SQL `db/platform/fields/V1__fields.sql` (tables `fld_schema_version`,
  `fld_field_definition`, `fld_field_definition_history`, `fld_field_extension`), own Flyway run with history table
  `fld_schema_history`, run only by the schema step. `FieldSchema.verify` at startup only reads (codes
  `FLD_TABLES_MISSING` … `FLD_CHECK_FAILED`). The runtime user needs only DML; even so, **the database refuses** to
  delete a definition or history entry, to change a key, to change a history entry, and every model rule that a
  column can hold (check constraints).
- **Not built**, as the task says: value storage and search (next task, rule 6).

### platform-fields-testkit

`InMemoryFieldStore` (same rules as Postgres: keys unique ignoring capitals, version check, history, no delete) and
`TestValuesCheck` (the test says when values exist, or makes the check fail).

### platform-notifications (owner answers to P016)

- **Q2:** `ApprovalType.whenExpired(eventType)` (optional fourth event type). `Approvals.tellExpired(now)` claims
  the requests of those types that expired undecided and were not yet told and sends each requester one e-mail, like
  the approved and declined outcomes. Told **once**: new column `notif_approval_request.expiry_told_at` (V1, still
  unreleased), claimed with `UPDATE … FOR UPDATE SKIP LOCKED`, so several instances never tell twice.
  `ApprovalStore.claimExpiredUndecided` in both stores.
- **Q3:** the Spring tick (`NotificationWorkers`) calls `tellExpired` every tick when approval links are on, and once
  a day (first tick after start, then every 24 hours) `purgeExpiredBefore(now − retention)`, with the outbox's
  `platform.notifications.retention` (default 30 days).
- **Q1, Q4:** nothing to build. All four answers are recorded in `docs/design/notifications.md` section 6.

### Docs

- `docs/design/case-fields.md`: new section "Mapping from the apps' current models (P017)" (the rules above it are
  unchanged).
- `docs/design/notifications.md` section 6: owner answers to P016, and what P017 added.
- `docs/install/fields.md` (new): schema step, grants, startup codes, moving a field list.
- `docs/install/notifications.md`: expiry notice, daily purge, `retention` row.
- CLAUDE.md: module table (two new rows; notifications rows updated).

## Type mapping from LexaVault and Vero today

Read from LexaVault `BusinessIdentifierDefinition`, `BusinessIdentifierValidator`, `BusinessIdentifierValidationType`,
`BusinessIdentifierCaseNormalization`, changelogs 008/019/020/022 and its admin screen; and from Vero
`MetadataFieldDefinition`, `MetadataValueValidator`, `MetadataFieldAdminService`, changelogs 001/003/032 and its
Integrations screen. Nothing was changed in either repo. `LegacyTypeMappingTest` checks each line against the old
validators' behaviour. The full table is in `docs/design/case-fields.md`; in short:

| Today | platform-fields |
|---|---|
| LexaVault TEXT | TEXT |
| LexaVault NUMERIC | NUMBER + pattern `[0-9]+` (leading zeros kept) |
| LexaVault ALPHANUMERIC | TEXT + pattern `[A-Za-z0-9]+` |
| LexaVault EMAIL, UUID, CUSTOM_PATTERN | the same type |
| LexaVault ALLOW_LIST | DROPDOWN |
| LexaVault case normalisation, strip separators | `letterCase`, `strip` SEPARATORS |
| LexaVault extractable | app-only setting by key |
| Vero TEXT, DATE, EMAIL, UUID | the same type |
| Vero INTEGER | NUMBER |
| Vero DECIMAL | MONEY (an amount) or TEXT + decimal pattern; see question 1 |
| Vero DATETIME | TEXT + ISO 8601 pattern; see question 1 |
| Vero BOOLEAN | DROPDOWN `true` / `false`, lower case |
| Vero normalisation NONE/TRIM/UPPERCASE/LOWERCASE/DIGITS_ONLY | trim always; PRESERVE / UPPER / LOWER / strip NON_DIGITS |
| Vero search mode, min/max, business identifier, versions, display order | `searchMode`, `minValue`/`maxValue`, `primary` (several), history, `displayOrder` |
| Vero record title fields | app-only setting by key |
| Vero per sending system (`source_id`) | per entity and product; see question 2 |

**What is not kept exactly** (all small; listed so nothing is lost silently): Vero DECIMAL and DATETIME (question 1);
Vero per-source lists (question 2); Vero `NONE` normalisation kept leading/trailing spaces, now always trimmed; Vero
INTEGER took a leading `+`; LexaVault's "one active primary per tenant" and "primary values unique" become a
LexaVault rule and a value-storage rule (question 3); a pattern on a Vero DATE/UUID field is not taken (none of the
screens offers it).

## Decisions I had to make

1. **Keys:** Vero's wider format (`[A-Za-z][A-Za-z0-9_.-]{0,99}`), which also covers LexaVault's. Kept exactly as
   given; matching (import, delivery) is by the exact key; two keys of one entity and product may not differ only in
   capitals (refused, and an import line for such a key is a `KEY_CASE_DIFFERS` conflict).
2. **Scope** is entity UUID (`entity_uuid`, as in the apps' tables) + product code (same format as on grants).
3. **Several primary fields are allowed** (Vero identifies a case by several); a primary field takes one value and
   must be switched on; switching a field off ends its primary flag. Question 3.
4. **Two rules added** so nothing of Vero's is lost: lowest/highest value (NUMBER, MONEY) and `searchMode`. Rule 6 is
   enforced in the model and the database: a sensitive field is searchable by EXACT only; a field that is not
   searchable keeps EXACT.
5. **Normalisation** always trims and makes single spaces first (both apps did, except Vero NONE); then strip, then
   case. It applies to TEXT, NUMBER, EMAIL, DROPDOWN and CUSTOM_PATTERN only; the other types have their own fixed
   form. Length rules and patterns apply to the normalised value, as in both apps.
6. **Per type:** NUMBER = digits with an optional minus, any size, leading zeros kept (account numbers). MONEY takes
   `1,250.50`, `$1,250.50`, `USD 1250`, at most the currency's decimals, normalised to `1250.50`; currencies without
   fixed decimals (gold, XXX) are refused. DATE takes ISO and US `M/d/yyyy`, normalised to ISO. UUID: the 36-character
   form, lower case. DROPDOWN: compared after normalisation; the display form is the listed value. ADDRESS: US only;
   state = USPS code (states, DC, territories, military); ZIP 5 digits or ZIP+4 (9 digits are formatted); 512
   characters in all; country empty means US.
7. **Values:** at most 512 characters (as both apps); a tab is allowed (LexaVault), other control characters and
   line separators are not. A multi-value field takes up to **50** distinct values (by normalised form). In a case's
   values a blank value counts as no value (Vero stored blanks).
8. **App-only settings** live in `fld_field_extension`, keyed by field; outside `FieldSpec`, never exported. Names
   `[a-z][a-z0-9_.-]{0,63}`, values up to 1000 characters.
9. **`change()` keeps the field on or off as it is**; switching uses `switchOff` / `switchOn`. A change that changes
   nothing makes no new version. A MONEY field's currency counts as part of its type.
10. **Import never changes an existing field at all**, not even its label: the report lists what the file has
    differently. New fields are created all or none (if someone adds one of the keys meanwhile, nothing is created).
    The new fields are created even when other lines are conflicts. Question 4.
11. **Delivery:** the sender sends its display values; the receiver checks them with its own rules and
    normalisation. Any problem refuses the delivery's case fields as a whole. An entry with the wrong type counts as
    delivered (no extra "required missing"). Messages name entry numbers, keys, labels and rules; never a value, and
    never a member name the sender made up (they are counted). Unknown keys are named: keys are identifiers, and the
    sender needs them to fix its mapping. The integrity check (#14 in integration-standards) comes with the delivery
    contract.
12. **JSON:** a copy of licensing's strict JSON reader/writer in `fields.json`, so platform-fields has no dependency on
    platform-licensing.
13. **Messages may name configured limits** ("must be at least 0.00", "must have at most 20 characters"), which are
    definitions, not values.
14. **Notifications:** the daily purge runs before the expiry notices, so a request that expired longer ago than the
    retention (after a long outage) is deleted, not announced a month late. A claimed request whose message cannot be
    queued is logged and not retried (the requester sees it in the app).

## Questions

1. **Vero's DECIMAL and DATETIME have no exact type.** Today they map to MONEY or a TEXT pattern (DECIMAL loses
   min/max unless it is money) and a TEXT pattern (DATETIME loses the calendar check). Add a DECIMAL type (number with
   decimals, range) and a DATE_TIME type? *Recommendation:* yes, both, before Vero's switch-over; small and nothing is
   lost.
2. **Vero keeps fields per sending system (`source_id`); the shared model keeps one list per entity and product.**
   *Recommendation:* one list per entity in Vero too; "which fields this source sends" becomes a Vero setting
   attached by key. If sources really need different fields with the same key, add an optional list name to the
   scope later.
3. **Several primary fields allowed** (decision 3)? *Recommendation:* yes in the shared model (Vero needs it);
   LexaVault keeps its "one active primary" as its own rule; primary value uniqueness goes into value storage.
4. **Import with the same key and type but another label or rules** (decision 10): keep the existing field as now, or
   offer "take the file's label and rules"? *Recommendation:* keep it as now; the report shows the differences.
5. **A second street line (apartment, suite) in ADDRESS?** *Recommendation:* yes, an optional `street2`, before any
   customer data exists.
