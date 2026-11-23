# Tick-off: case-fields, erase-log

Counts (case-fields): DONE 105 · UNTESTED 6 · PARTLY 1 · MISSING 0 · DIFFERENT 0 · APP-SIDE 8 · DOC GAP 14
Counts (erase-log): DONE 74 · UNTESTED 6 · PARTLY 12 · MISSING 0 · DIFFERENT 2 · APP-SIDE 6 · DOC GAP 3

Read-only tick-off, P035 B1, slice: `docs/design/case-fields.md` and `docs/design/erase-log.md` against modules
platform-fields, platform-fields-testkit, platform-erase-log, platform-erase-log-testkit, platform-erase-log-spring.
Every status comes from reading the code and the tests, not from the docs or reports. Where the design names the apps,
this file says "the sending app" or "the receiving app".

Path abbreviations used in the tables:

| Short | Path |
|---|---|
| `F/` | `platform-fields/src/main/java/com/documentplatform/fields/` |
| `FR/` | `platform-fields/src/main/resources/db/platform/` |
| `FT/` | `platform-fields/src/test/java/com/documentplatform/fields/` |
| `FK/` | `platform-fields-testkit/src/test/java/com/documentplatform/fields/` |
| `E/` | `platform-erase-log/src/main/java/com/documentplatform/eraselog/` |
| `ER/` | `platform-erase-log/src/main/resources/db/platform/erase-log/` |
| `ET/` | `platform-erase-log/src/test/java/com/documentplatform/eraselog/` |
| `EK/` | `platform-erase-log-testkit/src/test/java/com/documentplatform/eraselog/` |
| `ES/` | `platform-erase-log-spring/src/main/java/com/documentplatform/eraselog/spring/` |
| `EST/` | `platform-erase-log-spring/src/test/java/com/documentplatform/eraselog/spring/` |

Rows that belong to another design doc's modules (key destruction, time zones) are marked as cross-references and
cite the test found there. The full check is in that doc's own tick-off.

---

## case-fields — The model

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-1 | A field definition is per entity and product, with key, label, type, rules, flags, display order and active | DONE | `F/FieldSpec.java` record; `F/FieldScope.java` | `FT/jdbc/PostgresFieldStoreTest.java` `everyKindOfFieldIsStoredAndReadBackExactly` | |
| case-fields-2 | Types: TEXT, NUMBER, DECIMAL, MONEY, DATE, DATE_TIME, EMAIL, UUID, DROPDOWN, ADDRESS, CUSTOM_PATTERN | DONE | `F/FieldType.java`; SQL check `FR/fields/V1__fields.sql` `field_type` | `FT/FieldValidatorTest.java` (one test per type); `FT/NewTypesTest.java` | |
| case-fields-3 | MONEY has one currency per field | DONE | `F/FieldSpec.java` `currency()`; SQL check `(field_type = 'MONEY') = (currency is not null)` | `FT/FieldSpecTest.java` `moneyHasOneCurrency` | |
| case-fields-4 | DROPDOWN has up to 100 values | DONE | `F/FieldSpec.java` `MAX_OPTIONS`, `options()` | `FT/FieldSpecTest.java` `dropdownsHaveUpTo100DistinctValues` | |
| case-fields-5 | ADDRESS is structured (street, optional street2, city, state, ZIP, country ready), US format first | DONE | `F/FieldValidator.java` `address()` | `FT/FieldValidatorTest.java` `address`; `FT/NewTypesTest.java` `addressHasAnOptionalSecondStreetLine` | Non-US country refused with `ADDRESS_COUNTRY_NOT_SUPPORTED` |
| case-fields-6 | Rules: required, exact/min/max length, pattern, normalisation (case, strip dashes and spaces) | DONE | `F/FieldSpec.java` `lengths()`, `pattern()`; `F/Normalisation.java` | `FT/FieldSpecTest.java` `lengthRules`, `patterns`; `FT/FieldValidatorTest.java` `normalisation`, `lengthsApplyToTheNormalisedValue` | |
| case-fields-7 | Values up to 512 characters | DONE | `F/FieldSpec.java` `MAX_VALUE_LENGTH`; `F/FieldValidator.java` `check` | `FT/FieldValidatorTest.java` (`"x".repeat(513)` gives `VALUE_TOO_LONG`) | |
| case-fields-8 | Flags: primary, searchable, sensitive, multi-value, displayable | DONE | `F/FieldSpec.java` | `FT/FieldSpecTest.java` `flagRules`, `defaults` | |
| case-fields-9 | Sensitive: hidden in logs | DONE | `F/values/FieldValues.java` logs nothing; `F/FieldDefinitions.java` logs keys and ids only | `FT/jdbc/NoValueInLogsTest.java` `sensitiveValuesNeverLeave`; `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueInLogsMessagesOrToString` | |
| case-fields-10 | Sensitive: shown only to roles that need it | APP-SIDE | `F/values/FieldValues.java` `readSensitive` is the explicit call | | App item: call `readSensitive` only for the roles allowed to see sensitive values |
| case-fields-11 | Multi-value fields (for example two borrowers) | DONE | `F/FieldValidator.java` `checkAll` | `FT/FieldValidatorTest.java` `multiValue` | |
| case-fields-12 | App-only settings stay in the app, attached by key, not in the shared model | DONE | `F/FieldDefinitions.java` `putExtension`; table `fld_field_extension` | `FT/jdbc/PostgresFieldStoreTest.java` `appSettingsAttachByKey`; `FK/FieldDefinitionsTest.java` `importFromTheOtherProductsExport` (not exported) | |

## case-fields — Rules

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-13 | Rule 1: each app keeps its own definitions per entity, on the shared model | DONE | `F/FieldScope.java` (entity + product); unique index `fld_field_definition_key` | `FT/jdbc/PostgresFieldStoreTest.java` `keysAreUniqueIgnoringCapitalsPerEntityAndProduct`; `FK/FieldDefinitionsTest.java` `beforeValuesExistTheTypeMayChange` (another entity's values do not count) | |
| case-fields-14 | A customer may buy only one product | APP-SIDE | | | A commercial and licensing rule. Nothing in platform-fields enforces or needs it |
| case-fields-15 | Rule 2: the receiving app can import the sending app's field list (shared export/import format) | DONE | `F/file/FieldListFile.java` (`platform-fields/1`); `F/FieldDefinitions.java` `exportFile`, `importFile` | `FT/file/FieldListFileTest.java` `exportAndImportRoundTrip`; `FK/FieldDefinitionsTest.java` `importFromTheOtherProductsExport` | |
| case-fields-16 | Import matching is by key | DONE | `F/file/FieldImport.java` `plan` | `FT/file/FieldListFileTest.java` `importMatchesByKeyAndNeverChangesAnExistingField` | |
| case-fields-17 | Rule 3: delivery carries each field as key + type + value (plus the multi-value list) | DONE | `F/delivery/FieldDelivery.java` (`platform-field-values/1`) | `FT/delivery/FieldDeliveryTest.java` `roundTrip` | |
| case-fields-18 | A delivered key the receiver does not know, or with another type, is refused with a specific code | DONE | `DELIVERY_KEY_UNKNOWN`, `DELIVERY_TYPE_MISMATCH` (+ `DELIVERY_CURRENCY_MISMATCH`) in `FieldDelivery.read` | `FT/delivery/FieldDeliveryTest.java` `unknownKeysAndTypeMismatchesHaveTheirCodes` | |
| case-fields-19 | Rule 4: the label may change at any time | DONE | `F/FieldDefinitions.java` `relabel`, `change` | `FK/FieldDefinitionsTest.java` `theLabelMayChangeAtAnyTime` | |
| case-fields-20 | The key never changes | DONE | `FIELD_KEY_LOCKED` in `FieldDefinitions.change`; trigger `fld_refuse_key_change` | `FK/FieldDefinitionsTest.java` `theKeyNeverChanges`; `FT/jdbc/FieldSchemaTest.java` `theDatabaseRefusesDeletesAndKeyChanges` | |
| case-fields-21 | The type never changes once values exist (to change a type, add a field) | DONE | `FIELD_TYPE_LOCKED` in `FieldDefinitions.change` | `FK/FieldDefinitionsTest.java` `theTypeIsLockedOnceValuesExist`; `FT/jdbc/PostgresValueStoreTest.java` `theStoreTellsDefinitionsThatValuesExist` | A MONEY field's currency counts as type (see DG-8) |
| case-fields-22 | Rule 5: removing a field = switching it off, never deleting; existing values stay | DONE | `F/FieldDefinitions.java` `switchOff`; trigger `fld_refuse_delete` | `FT/jdbc/PostgresFieldStoreTest.java` `removingSwitchesOffAndNothingIsDeleted`; `FK/FieldDefinitionsTest.java` `removingAFieldSwitchesItOffAndKeepsIt` | |
| case-fields-23 | Rule 6: a sensitive field is visible in the document (not redacted in that entity) | APP-SIDE | | | App item: redaction rules exclude case fields |
| case-fields-24 | Rule 6: sensitive values are stored only encrypted, never readable in any table, log or backup | DONE | `F/values/FieldValues.java` `seal`, `rows`; `fld_value` checks in `FR/fields/V1__fields.sql` | `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueIsReadableInAnyTableColumn` (dumps every table) | A backup holds the same rows |
| case-fields-25 | Searchable by exact match only, through a keyed fingerprint of the normalised value | DONE | `F/values/FieldValues.java` `search`, `draft` | `FT/jdbc/PostgresValueStoreTest.java` `sensitiveFieldsAreSearchedByExactMatchOnlyIgnoringCapitalsAndSpaces` | |
| case-fields-26 | Fingerprint matching ignores capitals and extra spaces | DONE | `Fingerprinter.canonical` via `ValueCodec.search` | same test (`"  ALICE    smith "`) | |
| case-fields-27 | No partial search on sensitive fields (definition and search) | DONE | `FIELD_SENSITIVE_EXACT_ONLY` in `FieldSpec`; SQL `check (not sensitive or search_mode = 'EXACT')`; `VALUES_SENSITIVE_EXACT_ONLY` in `FieldValues.search` | `FT/FieldSpecTest.java` `flagRules`; `FT/jdbc/FieldSchemaTest.java` `theDatabaseRefusesDeletesAndKeyChanges`; `FT/jdbc/PostgresValueStoreTest.java` `sensitiveFieldsAreSearchedByExactMatchOnly…` | |
| case-fields-28 | Partial search only on fields that are not sensitive | DONE | `F/values/FieldValues.java` `search` | `FT/jdbc/PostgresValueStoreTest.java` `nonSensitiveFieldsAreSearchedExactlyByPrefixAndByPart` | |
| case-fields-29 | Name and address fields are sensitive by default | DONE | `F/FieldSpec.java` `suggestsSensitive`, `Builder.build` | `FT/FieldSpecTest.java` `nameAndAddressFieldsAreSensitiveByDefault` | |
| case-fields-30 | The Business Admin decides (the default can be overridden) | DONE | `F/FieldSpec.java` `Builder.sensitive(boolean)` | `FK/FieldDefinitionsTest.java` `theSensitiveFlagIsLockedOnceValuesExist` (borrower name built with `sensitive(false)`) | |
| case-fields-31 | Encryption and the fingerprint key come from the shared crypto capability | DONE | `F/values/FieldValues.java` (`KeyService`, `RecordCipher`, `Fingerprinter`) | `FT/jdbc/PostgresValueStoreTest.java` `openingARecordIsOneKeyServiceCallHoweverManySensitiveValues` | |
| case-fields-32 | Rule 7: app rules stay in the apps (case field vs redaction item; locked items never case fields) | APP-SIDE | | | App item: each app's own field admin |

## case-fields — Mapping from the apps' current models (P017)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-33 | Every mapping line keeps what the app's own validator accepted and how it normalised | DONE | `F/FieldValidator.java`, `F/FieldSpec.java` | `FT/LegacyTypeMappingTest.java` (10 tests, one per mapping group) | |
| case-fields-34 | Digits-only identifier → NUMBER with pattern `[0-9]+`; NUMBER alone takes a leading minus; leading zeros kept | DONE | `F/FieldValidator.java` `WHOLE` | `FT/LegacyTypeMappingTest.java` `identifierNumericIsNumberWithDigitsOnlyAndLeadingZerosKept` | |
| case-fields-35 | UUID is stored in lower case | DONE | `F/FieldValidator.java` UUID case | `FT/FieldValidatorTest.java` `uuid` | |
| case-fields-36 | Allow-list → DROPDOWN (up to 100); boolean → DROPDOWN `true`/`false` with LOWER | DONE | `F/FieldSpec.java` `options()` | `FT/LegacyTypeMappingTest.java` `identifierEmailUuidAllowListAndCustomPattern`, `metadataBooleanIsADropdown` | |
| case-fields-37 | DATE also takes `11/21/2026`, stored as `2026-11-21` | DONE | `F/FieldValidator.java` `date()` | `FT/FieldValidatorTest.java` `date` | |
| case-fields-38 | INTEGER → NUMBER; a leading `+` is no longer taken | DONE | `F/FieldValidator.java` `WHOLE` | `FT/LegacyTypeMappingTest.java` `metadataIntegerAndRanges` | |
| case-fields-39 | A pattern is not taken on DATE, DATE_TIME, DECIMAL, UUID, DROPDOWN, MONEY or ADDRESS | DONE | `F/FieldType.java` `takesPattern` | `FT/FieldSpecTest.java` `patterns`; `FT/NewTypesTest.java` `newTypesTakeOnlyTheirRules` | |
| case-fields-40 | `minValue` / `maxValue` for NUMBER, DECIMAL and MONEY only | DONE | `F/FieldType.java` `takesRange`; `FieldSpec.bound` | `FT/FieldSpecTest.java` `ranges`; `FT/NewTypesTest.java` `newTypesTakeOnlyTheirRules` | |
| case-fields-41 | Key `[A-Za-z][A-Za-z0-9_.-]{0,99}`, label up to 200 | DONE | `F/FieldKey.java`; `FieldSpec.label`; SQL checks | `FT/FieldSpecTest.java` `keysStartWithALetterAndHaveAtMost100Characters`, `labelsAndProducts` | |
| case-fields-42 | The one-primary-per-tenant rule stays in the sending app; the shared model allows several | APP-SIDE | | | App item: the sending app's own one-primary check (the shared part is case-fields-58) |
| case-fields-43 | Version plus one history entry per change (`fld_field_definition_history`) | DONE | `F/jdbc/PostgresFieldStore.java` | `FT/jdbc/PostgresFieldStoreTest.java` `labelAlwaysChangesTypeOnlyWithoutValues` (versions 1–4 in history) | |
| case-fields-44 | Normalisation: always trimmed with single spaces, then PRESERVE/UPPER/LOWER or strip NON_DIGITS | DONE | `F/Normalisation.java` `apply` | `FT/FieldValidatorTest.java` `textIsTrimmedWithSingleSpaces`, `normalisation`; `FT/LegacyTypeMappingTest.java` `metadataNormalisationsAndSearchModes` | |
| case-fields-45 | `searchMode` EXACT / PREFIX / PARTIAL | DONE | `F/SearchMode.java` | `FT/LegacyTypeMappingTest.java` `metadataNormalisationsAndSearchModes` | |
| case-fields-46 | "Which fields a source sends" is a setting of the receiving app, by key | APP-SIDE | `F/FieldDefinitions.java` `putExtension` | | App item: the receiving app's source setting |

## case-fields — Owner answers to P017 (task P018)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-47 | DECIMAL: digits, optional minus and decimal part; no commas, exponent or leading `+` | DONE | `F/FieldValidator.java` `DECIMAL_FORM` | `FT/NewTypesTest.java` `decimalTakesDigitsWithAnOptionalDecimalPart` | |
| case-fields-48 | DECIMAL normalised without trailing zeros (`4.50` → `4.5`, `100.0` → `100`) | DONE | `F/FieldSpec.java` `plain` | same test | |
| case-fields-49 | DECIMAL takes `minValue`/`maxValue` with any decimals; no length, pattern or normalisation | DONE | `F/FieldSpec.java` `bound` | `FT/NewTypesTest.java` `decimalRangeAndRules`, `newTypesTakeOnlyTheirRules` | |
| case-fields-50 | DECIMAL refusal code `VALUE_NOT_A_DECIMAL` | DONE | `F/ValueError.java` | `FT/NewTypesTest.java` `decimalTakesDigitsWithAnOptionalDecimalPart` | |
| case-fields-51 | DATE_TIME: ISO 8601 with offset, seconds and a fraction of up to 9 digits optional, calendar checked | DONE | `F/FieldValidator.java` `ISO_DATE_TIME` (STRICT) | `FT/NewTypesTest.java` `dateTimeIsIsoWithAnOffsetAndNormalisedToUtc` | |
| case-fields-52 | DATE_TIME display = as given; normalised = same moment in UTC with ms (ns when given) | DONE | `F/FieldValidator.java` `UTC_MILLIS` / `UTC_NANOS` | same test | |
| case-fields-53 | DATE_TIME: no range, length, pattern or normalisation; code `VALUE_NOT_A_DATE_TIME` | DONE | `F/FieldType.java` | `FT/NewTypesTest.java` `newTypesTakeOnlyTheirRules`, `dateTimeIsIso…` | |
| case-fields-54 | One field list per entity and product in the receiving app too | DONE | `F/FieldScope.java` | see case-fields-13 | |
| case-fields-55 | Several primary fields are allowed in the shared model | DONE | `F/values/FieldValues.java` `identity` | `FT/jdbc/PostgresValueStoreTest.java` `severalPrimaryFieldsIdentifyARecordTogether` | |
| case-fields-56 | Import keeps the existing field; the report shows the differences | DONE | `F/file/FieldImport.java`; `F/file/ImportReport.java` | `FT/file/FieldListFileTest.java` `importMatchesByKeyAndNeverChangesAnExistingField` | |
| case-fields-57 | ADDRESS optional `street2`; delivery member `"street2"`, left out when there is none | DONE | `F/FieldValue.java` `Address.street2`; `F/delivery/FieldDelivery.java` | `FT/delivery/FieldDeliveryTest.java` `anAddressTravelsWithItsSecondStreetLine` | |
| case-fields-58 | `street2` tidied, blank means none, counts towards 512; the first street line stays required | DONE | `F/FieldValidator.java` `address` | `FT/NewTypesTest.java` `addressHasAnOptionalSecondStreetLine`, `anAddressWithOnlyASecondLineIsNotBlankInACase` | |

## case-fields — Value storage (P019)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-59 | Values belong to an app record: `RecordRef` = entity + product + record type + record ID; many documents may point to one record | DONE | `F/values/RecordRef.java`; `fld_record_ref` unique index | `FT/jdbc/PostgresValueStoreTest.java` `theSamePrimaryValueAlwaysLeadsToTheSameRecord` | |
| case-fields-60 | Record type and record ID forms are checked (`VALUES_RECORD_REF_INVALID`) | UNTESTED | `F/values/RecordRef.java` | none | No test passes a bad record type or ID |
| case-fields-61 | One record per identity per entity, product and record type, by a unique index, safe with concurrent writers | DONE | `fld_record_identity` in `FR/fields/V1__fields.sql` | `FT/jdbc/PostgresValueStoreTest.java` `concurrentWritersOfTheSamePrimaryValueEndUpOnOneRecord` | |
| case-fields-62 | With several primary fields, a part of the set is another identity | DONE | `F/values/FieldValues.java` `identity` | `FT/jdbc/PostgresValueStoreTest.java` `severalPrimaryFieldsIdentifyARecordTogether` | |
| case-fields-63 | A record without primary values is a record of its own | DONE (P038, 6681ae1) | `FieldValues.identity` returns null; the index skips nulls | none | |
| case-fields-64 | Identity = SHA-256 over primary values in key order (search form or fingerprint); no sensitive value readable in it | DONE | `F/values/FieldValues.java` `identity` | `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueIsReadableInAnyTableColumn` (includes `fld_record`), `aSensitivePrimaryValueStillFindsItsRecordAfterTheFingerprintKeyRotates` | |
| case-fields-65 | Identity comparison ignores capitals and extra spaces | DONE | `ValueCodec.search` | `FT/jdbc/PostgresValueStoreTest.java` `theSamePrimaryValueAlwaysLeadsToTheSameRecord` (`"ab 1001"`) | |
| case-fields-66 | `claim`: same identity → that record whatever the candidate's ID; missing fields added; equal values stay | DONE | `FieldValues.claim`, `merge` | same test | |
| case-fields-67 | `claim`: a different value is refused `VALUES_CONFLICT`, keys only, nothing written | DONE | `FieldValues.merge` | same test (record count and version unchanged) | |
| case-fields-68 | Concurrent claims of a new value all get the same record (`insert … on conflict do nothing`) | DONE | `F/jdbc/PostgresValueStore.java` `insert` | `FT/jdbc/PostgresValueStoreTest.java` `concurrentWritersOfTheSamePrimaryValueEndUpOnOneRecord`; `FK/FieldValuesInMemoryTest.java` `concurrentClaimsEndUpOnOneRecord` | |
| case-fields-69 | `update` replaces the given fields; a primary value another record has is refused `VALUES_PRIMARY_TAKEN` | DONE | `FieldValues.update`; `PostgresValueStore.replace` | `FT/jdbc/PostgresValueStoreTest.java` `theSamePrimaryValueAlwaysLeadsToTheSameRecord` | |
| case-fields-70 | `findByPrimary` looks a record up | DONE | `FieldValues.findByPrimary` | same test | |
| case-fields-71 | Values are checked by `FieldSet` / `FieldValidator` (all types, multi-value, required) | DONE | `FieldValues.claim` | `FT/jdbc/PostgresValueStoreTest.java` `invalidValuesAreRefusedWithTheirCodesAndNoValueInTheMessage` | |
| case-fields-72 | Non-sensitive: stored as display, normalised and search form | DONE | `FieldValues.plain` | `FT/jdbc/PostgresValueStoreTest.java` `everyTypeIsStoredAndReadBack` | |
| case-fields-73 | Sensitive values of a record sealed under one record envelope (`pck1.` + one `pci1.` per value), bound to the record, field and position | DONE (P038, 6681ae1) | `FieldValues.seal`, `itemContext`, `recordContext` | `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueIsReadableInAnyTableColumn` (formats), `anotherCustomersRecordIsRefused` (envelope moved to another record does not open) | Binding to the field and position is not tested: no test moves a sealed value to another field or position |
| case-fields-74 | A sensitive value of a searchable or primary field also gets a fingerprint, context `fld:<entity>:<product>:<key>` | DONE | `FieldValues.draft`, `fingerprintContext` | `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueIsReadableInAnyTableColumn` (4 fingerprints) | |
| case-fields-75 | Check constraints refuse a sensitive row with any readable column | DONE | `fld_value` checks | same test (`update … set display_value` refused) | |
| case-fields-76 | Search EXACT (term checked and normalised like a value), PREFIX, PARTIAL per the field's mode | DONE | `FieldValues.search` | `FT/jdbc/PostgresValueStoreTest.java` `nonSensitiveFieldsAreSearchedExactlyByPrefixAndByPart` | |
| case-fields-77 | A wider mode than the field offers is refused `VALUES_SEARCH_MODE_NOT_ALLOWED` | DONE | `FieldValues.search` | same test | |
| case-fields-78 | Sensitive search covers every usable fingerprint key version | DONE | `Fingerprinter.searchTerms` | `FT/jdbc/PostgresValueStoreTest.java` `keyRotationKeepsReadingAndSearchWorking` | |
| case-fields-79 | Sensitive prefix or partial search refused `VALUES_SENSITIVE_EXACT_ONLY` | DONE | `FieldValues.search` | `FT/jdbc/PostgresValueStoreTest.java` `sensitiveFieldsAreSearchedByExactMatchOnly…` | |
| case-fields-80 | The schema step creates the `pg_trgm` trigram index when it can | DONE | `do $$` block in `FR/fields/V1__fields.sql` | `FT/jdbc/PostgresValueStoreTest.java` `nonSensitiveFieldsAreSearchedExactlyByPrefixAndByPart` (`searchStatus().partialSearch()`) | |
| case-fields-81 | Without the extension the schema step goes on without the index | UNTESTED | same block (`exception when others`) | none | Tests always run on a database that has `pg_trgm` |
| case-fields-82 | Without the index, partial runs as prefix, the result says `PARTIAL_AS_PREFIX`, `searchStatus()` gives `FLD_PARTIAL_SEARCH_UNAVAILABLE` and names the optional script | DONE | `FieldValues.search`, `searchStatus` | `FT/jdbc/PostgresValueStoreTest.java` `withoutTheTrigramIndexPartialSearchRunsAsPrefixAndSaysSo` (also runs the script) | `FLD_PARTIAL_SEARCH_UNAVAILABLE` is a bare string, not a `PlatformCode` (xc-catalogue-1) |
| case-fields-83 | `read` never decrypts (sensitive hidden, count only) | DONE | `FieldValues.read(ref, false)` | `FT/jdbc/PostgresValueStoreTest.java` `openingARecordIsOneKeyServiceCallHoweverManySensitiveValues` (0 calls), `everyTypeIsStoredAndReadBack` | |
| case-fields-84 | `readSensitive` is explicit: one key service call per record | DONE | `FieldValues.readSensitive` | same test (1 call) | |
| case-fields-85 | Values never in a `toString()`, a log or an exception message | DONE | `FieldValues`, `RecordValues`, `FieldValue` `toString` | `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueInLogsMessagesOrToString`; `FT/jdbc/NoValueInLogsTest.java` | |
| case-fields-86 | New record with sensitive values: 3 key service calls however many values | DONE | `FieldValues.draft`, `seal` | `FT/jdbc/PostgresValueStoreTest.java` `openingARecordIsOneKeyServiceCallHoweverManySensitiveValues` (`assertEquals(3, one)`) | |
| case-fields-87 | Older wrapping key versions keep opening; `rewrap` moves an envelope to the latest version | DONE | `FieldValues.rewrap` | `FT/jdbc/PostgresValueStoreTest.java` `keyRotationKeepsReadingAndSearchWorking` | |
| case-fields-88 | A rotated fingerprint key keeps search and the primary rule working | DONE | `FieldValues.draft` (one identity per usable version) | `FT/jdbc/PostgresValueStoreTest.java` `aSensitivePrimaryValueStillFindsItsRecordAfterTheFingerprintKeyRotates` | |
| case-fields-89 | One `FieldValues` per customer; another customer's record refused `VALUES_WRONG_CUSTOMER` | DONE | `FieldValues.mine` | `FT/jdbc/PostgresValueStoreTest.java` `anotherCustomersRecordIsRefused` | |
| case-fields-90 | Search filters by customer | DONE | `PostgresValueStore.search` (`v.customer_id = ?`) | same test (empty result) | |
| case-fields-91 | The envelope itself refuses another customer's key (`CRYPTO_WRONG_CUSTOMER`) | DONE | `RecordCipher` (platform-crypto) | same test | |
| case-fields-92 | Tables `fld_record`, `fld_value` in `V1__fields.sql`; startup only verifies (`FieldSchema.verify`) | DONE | `F/jdbc/FieldSchema.java` | `FT/jdbc/FieldSchemaTest.java` `theTablesComeOnlyFromTheSchemaRun`, `startupRefusalCodes` | |
| case-fields-93 | The runtime user needs DML only | DONE | `FieldSchema.RIGHTS` | `FT/jdbc/FieldSchemaTest.java` `theTablesComeOnlyFromTheSchemaRun` (DML user enough); `FT/jdbc/PostgresValueStoreTest.java` `theAppUserNeedsOnlyDataRights` | |
| case-fields-94 | The value store is also the `FieldValuesCheck`: a field's type locks once a record holds a value | DONE | `F/jdbc/PostgresValueStore.java` `valuesExist` | `FT/jdbc/PostgresValueStoreTest.java` `theStoreTellsDefinitionsThatValuesExist` | |
| case-fields-95 | Testkit `InMemoryValueStore` has the same rules; partial search can be switched off | DONE | `platform-fields-testkit/…/inmemory/InMemoryValueStore.java` | `FK/FieldValuesInMemoryTest.java` `searchRulesAndThePrefixFallback`, `thePrimaryRuleClaimsAndConflicts` | |

## case-fields — Owner answers to P019 (task P020)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-96 | `sensitive` is locked once values exist: `FIELD_SENSITIVE_LOCKED` | DONE | `FieldDefinitions.change` | `FK/FieldDefinitionsTest.java` `theSensitiveFlagIsLockedOnceValuesExist`; `FT/jdbc/PostgresValueStoreTest.java` `sensitiveAndPrimaryLockOnceARecordHoldsAValueAndFreeAgainWhenAllAreErased` | |
| case-fields-97 | `primary` is locked once values exist: `FIELD_PRIMARY_LOCKED` | DONE | `FieldDefinitions.change` | `FK/FieldDefinitionsTest.java` `thePrimaryFlagIsLockedOnceValuesExist` | |
| case-fields-98 | Switching a field off still takes its primary flag away | DONE | `FieldDefinitions.switchOff` | `FT/jdbc/PostgresFieldStoreTest.java` `removingSwitchesOffAndNothingIsDeleted` | |
| case-fields-99 | Switching it back on does not make it primary again | UNTESTED | `FieldDefinitions.switchOn` | `PostgresFieldStoreTest.removingSwitchesOffAndNothingIsDeleted` switches on but does not assert `primary()` | |
| case-fields-100 | If both the type and a flag change, `FIELD_TYPE_LOCKED` is given | DONE | `FieldDefinitions.change` (kind first) | `FK/FieldDefinitionsTest.java` `thePrimaryFlagIsLockedOnceValuesExist` (last assertion) | |
| case-fields-101 | Erasing a record goes through the erase log (`Eraser.erase`, kind `case-fields`) | DONE | `E/fields/CaseFieldsEraseHandler.java` | `EK/CaseFieldsEraseHandlerTest.java` `caseFieldsAreErasedOnlyThroughTheEraseLog` | |
| case-fields-102 | Concurrent arrivals get the existing record (kept) | DONE | see case-fields-68 | see case-fields-68 | |

## case-fields — Erasing a record (P020)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-103 | `FieldValues.erase` accepts only `CaseFieldsEraseHandler` as its caller; others get `VALUES_ERASE_OUTSIDE_LOG`, nothing erased | DONE | `FieldValues.erase` (`StackWalker` caller check, `ERASE_LOG_HANDLER`) | `EK/CaseFieldsEraseHandlerTest.java` `caseFieldsAreErasedOnlyThroughTheEraseLog` (direct call and method reference both refused) | |
| case-fields-104 | Erase deletes the value rows, the envelope and the `fld_record` row in one transaction | DONE | `F/jdbc/PostgresValueStore.java` `delete` | `FT/jdbc/PostgresValueStoreTest.java` `erasingARecordRemovesItsValuesAndEnvelopeAndSearchNoLongerFindsIt` | |
| case-fields-105 | If the record changed meanwhile, erase reads again and retries | UNTESTED | `FieldValues.eraseRecord` retry on `VALUES_CHANGED_MEANWHILE` | none | |
| case-fields-106 | Afterwards `read`, `readSensitive`, `search`, `findByPrimary` no longer find it; the same primary value later makes a new record | DONE | | `FT/jdbc/PostgresValueStoreTest.java` `erasingARecordRemovesItsValues…` | |
| case-fields-107 | Erase makes no key service call | DONE | `FieldValues.eraseRecord` | same test (`keys.calls()` unchanged) | |
| case-fields-108 | Erase is never automatic; the result `Erased` holds no value | DONE | `FieldValues.Erased` | same test (`toString` has no value) | |
| case-fields-109 | The caller writes the audit entry (who and why) | APP-SIDE | `EraseRequest.auditId` carries the app's audit ID | | App item: the audit record for each erasure |
| case-fields-110 | The app supplies `LegalHolds`; without it no record is held | DONE | `FieldValues` constructors, `LegalHolds.NONE` | `FK/FieldValuesInMemoryTest.java` `eraseRemovesTheRecordUnlessItIsHeld` | |
| case-fields-111 | Held → `VALUES_LEGAL_HOLD`; the check throws → `VALUES_LEGAL_HOLD_UNKNOWN`; nothing erased in either case | DONE | `FieldValues.eraseRecord` | `FT/jdbc/PostgresValueStoreTest.java` `aRecordUnderLegalHoldIsNotErased` | |
| case-fields-112 | Another customer's record: `VALUES_WRONG_CUSTOMER` | DONE | `FieldValues.mine` | `FT/jdbc/PostgresValueStoreTest.java` `anotherCustomersRecordIsNotErased` | |
| case-fields-113 | The runtime user needs DELETE on `fld_record`; `FieldSchema` checks it | DONE (P038, 7678f9b) | `FieldSchema.RIGHTS` (`fld_record` incl. DELETE) | `theAppUserNeedsOnlyDataRights` uses a SELECT-only user, which fails on INSERT first | No test for a user that lacks only DELETE |
| case-fields-114 | Not crypto-shredding: a restored backup of an erased record still opens while the customer's key lives (pinned) | DONE | | `FT/jdbc/PostgresValueStoreTest.java` `aRestoredBackupOfAnErasedRecordStillOpensWhileTheCustomersKeyLives` | |

## case-fields — Erase and backups (owner, 2026-11-22)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-115 | (b) Everyday erase removes live; a restore is followed by re-applying the erasures from a log outside the database | DONE | `E/Eraser.java`, `E/EraseReplay.java` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWorkUntilTheReplayHasRunAndVerified` | |
| case-fields-116 | (c) Customer exit: destroying the customer's keys makes all their data unreadable, backups included | DONE | platform-crypto `KeyService.destroyCustomerKeys` (cross-reference) | `platform-crypto/…/vault/KeyDestructionVaultTest.java` `destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable` | Full check in the crypto tick-off |
| case-fields-117 | (a) Per-record keys in the key service: not now | DONE | nothing built | pinned by case-fields-114 | |
| case-fields-118 | Both go into each app's retention design | APP-SIDE | | | App item: each app's retention and deletion work |
| case-fields-119 | Built-in handler: the legal hold is checked before the entry is logged | DONE | `CaseFieldsEraseHandler.check` | `EK/CaseFieldsEraseHandlerTest.java` `aRecordUnderLegalHoldIsRefusedBeforeAnythingIsLogged` | |
| case-fields-120 | Built-in handler: the verify step reads the value store | DONE | `CaseFieldsEraseHandler.verify` | `EK/CaseFieldsEraseHandlerTest.java` `verifySeesTheOldRecordAsReadableAndANewOneUnderTheSameIdAsGone` | |

## case-fields — DOC GAP (decisions in task reports, not in the design body)

| ID | Decision (report) | Status | Code | Test | Note |
|---|---|---|---|---|---|
| case-fields-DG-1 | Keys are kept exactly as given and matched exactly; two keys of one entity and product may not differ only in capitals; such an import line is a `KEY_CASE_DIFFERS` conflict (P017-1) | DOC GAP | `F/FieldKey.java` `folded`; unique index on `lower(field_key)` | `FT/jdbc/PostgresFieldStoreTest.java` `keysAreUniqueIgnoringCapitalsPerEntityAndProduct`; `FT/file/FieldListFileTest.java` `importMatchesByKey…` | Code and test done; the design body does not say it |
| case-fields-DG-2 | A primary field takes one value and must be switched on (`FIELD_PRIMARY_MULTI_VALUE`, `FIELD_PRIMARY_INACTIVE`) (P017-3) | DOC GAP | `F/FieldSpec.java` | `FT/FieldSpecTest.java` `flagRules` | |
| case-fields-DG-3 | A field that is not searchable keeps search mode EXACT; the sensitive EXACT rule is also enforced by the database (P017-4) | DOC GAP | `FieldSpec` (`FIELD_SEARCH_MODE_INVALID`); SQL checks | `FT/FieldSpecTest.java` `flagRules`; `FT/jdbc/FieldSchemaTest.java` `theDatabaseRefusesDeletesAndKeyChanges` | |
| case-fields-DG-4 | Normalisation order: trim and single spaces, then strip, then case; only for TEXT, NUMBER, EMAIL, DROPDOWN, CUSTOM_PATTERN; length rules and patterns apply to the normalised value (P017-5) | DOC GAP | `F/Normalisation.java`; `F/FieldType.java` `takesNormalisation` | `FT/FieldSpecTest.java` `normalisationOnlyForTextLikeTypes`; `FT/FieldValidatorTest.java` `lengthsApplyToTheNormalisedValue` | |
| case-fields-DG-5 | Per-type forms: MONEY takes `1,250.50`, `$1,250.50`, `USD 1250`, at most the currency's decimals, normalised `1250.50`; currencies without fixed decimals refused; ADDRESS state = USPS code (states, DC, territories, military), ZIP 5 or ZIP+4, empty country = US (P017-6) | DOC GAP | `F/FieldValidator.java` `money`, `address`; `FieldSpec.currency` | `FT/FieldValidatorTest.java` `money`, `address`; `FT/FieldSpecTest.java` `moneyHasOneCurrency` | |
| case-fields-DG-6 | A tab is allowed, other control characters and line separators are not; a multi-value field takes up to 50 distinct values; a blank value counts as no value (P017-7) | DOC GAP | `F/FieldValidator.java` `CONTROL`, `checkAll`; `FieldSpec.MAX_VALUES` | `FT/FieldValidatorTest.java` `multiValue`; `FT/FieldSetTest.java` `blankCountsAsNoValue` | The number 50 is not in the design |
| case-fields-DG-7 | App-only settings: names `[a-z][a-z0-9_.-]{0,63}`, values up to 1000 characters, never exported (P017-8) | DOC GAP | `F/FieldDefinitions.java` `putExtension` | `FT/jdbc/PostgresFieldStoreTest.java` `appSettingsAttachByKey` | |
| case-fields-DG-8 | `change()` keeps the field on or off as it is; a change that changes nothing makes no version; a MONEY field's currency counts as part of its type (P017-9) | DOC GAP | `FieldDefinitions.change`; `FieldSpec.sameValueKind` | `FK/FieldDefinitionsTest.java` `removingAFieldSwitchesItOffAndKeepsIt`, `staleChangesAreRefused`, `theTypeIsLockedOnceValuesExist` | |
| case-fields-DG-9 | Import never changes an existing field, not even its label; new fields are created all or none (P017-10) | DOC GAP | `FieldDefinitions.importFile`; `PostgresFieldStore.create` | `FK/FieldDefinitionsTest.java` `anImportIsAllOrNothing`; `FT/jdbc/PostgresFieldStoreTest.java` `importCreatesNewKeysOnlyInOneTransaction` | |
| case-fields-DG-10 | Delivery: any problem refuses the delivery's case fields as a whole; a wrong-type entry counts as delivered; messages name entry numbers, keys, labels and rules, never a value or a member name the sender made up; unknown keys are named (P017-11). Also code-only: a delivery is at most 2 MB (`DELIVERY_TOO_LARGE`) | DOC GAP | `F/delivery/FieldDelivery.java` | `FT/delivery/FieldDeliveryTest.java` `noMessageRepeatsAValueOrAMemberTheSenderMadeUp`, `everyOtherRefusalHasItsCode` | The 2 MB limit is in no doc |
| case-fields-DG-11 | Messages may name configured limits ("must be at least 0.00"), which are definitions, not values (P017-13) | DOC GAP | `FieldValidator.range`, `length` | `FT/FieldValidatorTest.java` `messagesNameTheLabelAndTheRuleNeverTheValue` | |
| case-fields-DG-12 | `FieldValuesException` carries no cause (the driver text can quote values); the value store reports the SQL state only (P019-8) | DOC GAP | `F/values/FieldValuesException.java`; `PostgresValueStore.run` | `FT/jdbc/PostgresValueStoreTest.java` `noSensitiveValueInLogsMessagesOrToString` | |
| case-fields-DG-13 | No `platform-fields-spring`: the app builds `FieldValues` itself from the crypto beans (P019-9) | DOC GAP | | | Install guide `docs/install/fields.md` does not say it either |
| case-fields-DG-14 | Partial search availability is detected at run time from `pg_indexes` in the current schema (P019-6) | DOC GAP | `PostgresValueStore.partialSearchAvailable` | `FT/jdbc/PostgresValueStoreTest.java` `withoutTheTrigramIndex…` | |

---

## erase-log — The rule

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-1 | (b) Erasures remove data from the live database at once | DONE | `E/Eraser.java` `erase` (applies right after logging) | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWorkUntilTheReplayHasRunAndVerified` (`e1` ERASED, record gone) | Documents and copies: the app's handlers |
| erase-log-2 | Backups are kept only for the backup retention, then destroyed | APP-SIDE | | | Installation item: backup policy |
| erase-log-3 | After a restore, every erasure after the backup is re-applied from the log before the system takes any work | DONE | `E/EraseGuard.java`, `E/EraseReplay.java` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWorkUntilTheReplayHasRunAndVerified` | |
| erase-log-4 | (c) Customer exit: destroying the keys makes all their data unreadable, backups included | DONE | platform-crypto (cross-reference) | `platform-crypto/…/vault/KeyDestructionVaultTest.java` `destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable` | Full check in the crypto tick-off |
| erase-log-5 | (a) Per-case keys: not now | DONE | nothing built | `FT/jdbc/PostgresValueStoreTest.java` `aRestoredBackupOfAnErasedRecordStillOpens…` pins it | |

## erase-log — Backup retention

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-6 | Default 35 days; allowed 30–90 | DONE | `E/EraseRetention.java` | `EK/testkit/EraseLogInMemoryTest.java` `backupRetentionIsThirtyToNinetyDaysAndTheLogKeepsThirtyMore` | |
| erase-log-7 | The compliance report states the period in force | APP-SIDE | health detail `backupRetentionDays` (`ES/EraseLogHealthIndicator.java`) | `EST/EraseLogWiringTest.java` `theAppsHandlersAreWiredAndTheGuardLetsWorkStartWhenInStep` (detail present) | App item: the compliance report |

## erase-log — The erase log

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-8 | Kept outside the database it protects, backed up separately | DONE | `E/file/FileEraseLogStore.java`; only the position is in the database (`ER/V1__erase_log.sql`) | `ET/FileEraseLogStoreTest.java` `entriesAreNumberedChainedAndReadBackAfterReopening` | Where the directory sits is the installation's (install guide section 1) |
| erase-log-9 | Holds no values: entity, record ID, what was erased, when, by which rule or person, audit ID | DONE | `E/EraseRequest.java` | `ET/EraseRequestTest.java` `anEntryWithAValueIsRefused` | |
| erase-log-10 | Kept at least backup retention + 30 days (65 by default), then pruned | DONE | `EraseRetention.logRetention`; `EraseLog.prune` | `ET/FileEraseLogStoreTest.java` `pruningKeepsTheChainVerifiableFromTheCheckpoint` | |
| erase-log-11 | Restore procedure: restore → replay → check nothing in the log is readable → only then take work | DONE | `E/EraseReplay.java` `run` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…`, `somethingStillReadableAfterTheReplayKeepsTheSystemBlocked` | |
| erase-log-12 | Replaying twice is safe | DONE | `EraseReplay.run` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` (`twice`) | |
| erase-log-13 | Shared part: replay with app-registered handlers per kind (one per kind); platform-fields' erase is one handler | DONE | `E/EraseHandlers.java` | `EK/testkit/EraseLogInMemoryTest.java` `aKindWithoutHandlerOrARefusalLogsNothing` ("one handler per kind") | |
| erase-log-14 | A guard so a restored system does not take work before the replay | DONE | `E/EraseGuard.java` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` | |
| erase-log-15 | The apps adopt it in their switch-over and retention work | APP-SIDE | | | App items: each app's switch-over |

## erase-log — Customer key destruction

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-16 | Destroy the customer's wrapping and fingerprint keys; explicit command with confirmation, audited, producing the certificate data | DONE | platform-crypto `command.CryptoCommand` (cross-reference) | `platform-crypto-testkit/…/KeyDestructionInMemoryTest.java` `theResultHoldsTheCertificateDataAndTheDefaultFileNameHasTheTenantAndTime` | Full check in the crypto tick-off |
| erase-log-17 | The offboarding runbook lists it; the certificate lists the keys and the date | APP-SIDE | | | Operations item: offboarding runbook |

## erase-log — Owner answers to P020 and P021

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-18 | (b) + (c); no per-case keys for now | DONE | see erase-log-1 to -5 | see erase-log-1 to -5 | |
| erase-log-19 | Erase only through the log now: `FieldValues.erase` refuses every caller but the built-in handler, before anything is read | DONE | `FieldValues.erase` | `EK/CaseFieldsEraseHandlerTest.java` `caseFieldsAreErasedOnlyThroughTheEraseLog` | |
| erase-log-20 | Separate offboarding policy `platform-crypto-offboard` (delete only) | DONE | platform-crypto (cross-reference) | `platform-crypto/…/vault/KeyDestructionVaultTest.java` `theSetupLoginAloneCannotDestroyAndChangesNothing` | Full check in the crypto tick-off |
| erase-log-21 | A failing logged entry blocks later erasures with the same code; nothing later is logged meanwhile | DONE | `Eraser.erase` (pending applied first) | `ET/RestoreAndReplayTest.java` `anEraseThatFailedHalfWayIsFinishedByTheNextEraseWhileRunning` | |
| erase-log-22 | The admin screen can show the pending entry: number, kind, record type and ID, and the handler's code, from the `eraseLog` health details (`pending`, `code`) or `EraseGuard`'s state | DONE (P038, b192e03) | `ES/EraseLogHealthIndicator.java`; `E/EraseGuard.java` `State` | none | While running, a pending failing entry leaves the guard UP with `code` null and message "In step; 1 erase(s) in progress". Health has `pending` (a count) only. Kind, record type, record ID and the handler's code are in neither the health details nor `State`; only the exception thrown to the caller of `Eraser.erase` has them |
| erase-log-23 | Handlers keep this rare by refusing in `check` (legal hold) before anything is logged | DONE | `EraseHandler.check`; `CaseFieldsEraseHandler.check` | `EK/CaseFieldsEraseHandlerTest.java` `aRecordUnderLegalHoldIsRefusedBeforeAnythingIsLogged` | |
| erase-log-24 | Key service snapshot retention at most the backup retention | APP-SIDE | | | Installation item, `docs/install/crypto.md` section 6 |
| erase-log-25 | The offboarding certificate data carries two dates (live data destroyed; unreadable everywhere from) | DONE | platform-crypto (cross-reference) | `platform-crypto-testkit/…/KeyDestructionInMemoryTest.java` `theCertificateDataCarriesBothDatesWithTheDefaultOrTheGivenRetention` | |

Not counted here (other docs' modules): P020 answer 2 (customer time zone from identity, notifications falls back)
and answer 3 (`timeZone` required at `platform-signin/2`, later). They belong to the notifications and setup
tick-offs.

## erase-log — Built (P021): modules

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-26 | Plain Java, no Spring; the only dependency is platform-fields, optional | DONE (P040, bbf2289) | `platform-erase-log/pom.xml` | | Since P032 the module also depends on `platform-errors` (not optional). The code follows P032; the sentence in the design is out of date |

## erase-log — Entry

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-27 | Forms: entity UUID; product `[A-Z][A-Z0-9_]{1,31}`; recordType `[a-z][a-z0-9_.-]{0,63}`; recordId `[A-Za-z0-9_.:@/-]{1,200}`; kind `[a-z][a-z0-9-]{0,39}` | DONE | `E/EraseRequest.java` patterns | `ET/EraseRequestTest.java` `idsAreAccepted`, `anEntryWithAValueIsRefused` (bad product, bad kind) | |
| erase-log-28 | `erasedAt`, `by` (RULE + rule ID or PERSON + actor ID) and `auditId` are required | DONE | `EraseRequest`, `ErasedBy` | `ET/EraseRequestTest.java` `anEntryWithAValueIsRefused` (`erasedAt` null) | Form of `auditId` and `by` ids is code-only (erase-log-DG-3) |
| erase-log-29 | `seq` from 1 without gaps, `loggedAt`, `prev`, `hash` added by the log | DONE | `EraseEntry.link`; `FileEraseLogStore.append` | `ET/FileEraseLogStoreTest.java` `entriesAreNumberedChainedAndReadBackAfterReopening`, `severalWritersAtOnceKeepOneChain` | |
| erase-log-30 | Anything outside those forms is `ERASE_ENTRY_INVALID` | DONE | `EraseRequest.require` | `ET/EraseRequestTest.java` `anEntryWithAValueIsRefused` | |
| erase-log-31 | An id that looks like a value (e-mail, `pcv1.`/`pci1.`/`pck1.`, fingerprint, key service ciphertext) is `ERASE_ENTRY_VALUE_REFUSED` | DONE (P040, bbf2289, 522a53e) | `EraseRequest.notAValue` (`VALUE_LIKE`) | `ET/EraseRequestTest.java` `anEntryWithAValueIsRefused` | Refusal is tested, but the test accepts `ERASE_ENTRY_INVALID` or `_VALUE_REFUSED` for each sample, so the specific code is not pinned |
| erase-log-32 | Messages name the field, never what was given | DONE | `EraseRequest.require`, `notAValue` | `ET/EraseRequestTest.java` `anEntryWithAValueIsRefused` (`refused` helper) | |
| erase-log-33 | A person is named by actor ID, not by e-mail or name | DONE | `ErasedBy` (`notAValue`) | `ET/EraseRequestTest.java` `anEntryWithAValueIsRefused` (`ErasedBy.person(v)`) | A name with only letters cannot be told from an ID; spaces and commas are refused by the form |
| erase-log-34 | `hash` = SHA-256 of `platform-erase-log/1\|<log ID>\|<seq>\|<loggedAt>\|<fields…>\|<prev>`; first `prev` is 64 zeros or the checkpoint's hash | PARTLY | `EraseEntry.hash`, `GENESIS` | `ET/FileEraseLogStoreTest.java` `entriesAreNumberedChained…` (GENESIS, `hashMatches`), `pruningKeepsTheChainVerifiableFromTheCheckpoint` | No fixed test vector: a changed formula still passes the round trip, but would break existing logs |
| erase-log-35 | `verify()`: a changed, removed, reordered or inserted entry is `ERASE_LOG_CHAIN_BROKEN` | PARTLY | `EraseLog.verify` (`Walk`) | `ET/FileEraseLogStoreTest.java` `aChangedOrRemovedLineBreaksTheChain` (changed, removed, reordered); `EK/testkit/EraseLogInMemoryTest.java` `aChangedOrMissingEntryIsDetectedAndBlocksWork` | An inserted entry is not tested |
| erase-log-36 | Entries cut from the end show as `ERASE_LOG_BEHIND_DATABASE` | DONE | `E/InStep.java` `problem` | `ET/RestoreAndReplayTest.java` `theWrongLogALostLogAndADatabaseOlderThanTheLogAreEachRefused` | |

## erase-log — Store (outside the database)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-37 | Only `file.FileEraseLogStore` is built | DONE | `E/file/FileEraseLogStore.java` | `ET/FileEraseLogStoreTest.java` | |
| erase-log-38 | `erase-log.jsonl`: one flat JSON line per entry, appended | DONE | `FileEraseLogStore.append`; `E/file/Lines.java` | `ET/FileEraseLogStoreTest.java` `entriesAreNumberedChainedAndReadBackAfterReopening` (line count and shape) | |
| erase-log-39 | Each line forced to disk (`fsync`) before `append` returns | UNTESTED | `FileEraseLogStore.append` (`ch.force(true)`, `forceDirectory`) | none | Hard to prove in a unit test; a code-review check at least |
| erase-log-40 | A line with any other field, an escape or a missing field is refused when read (`ERASE_LOG_FORMAT`) | DONE (P038, 23fefb1) | `E/file/Lines.java` | `ET/FileEraseLogStoreTest.java` `aChangedOrRemovedLineBreaksTheChain` (an extra field) | Escape and missing field are not tested |
| erase-log-41 | Windows line ends are accepted | UNTESTED | `Lines` (`line.endsWith("\r")`) | none | |
| erase-log-42 | `erase-log.id` made once; missing while entries exist → refused (restore it from the log's backup) | DONE | `FileEraseLogStore.readOrCreateId` | `ET/FileEraseLogStoreTest.java` `theLocationMustBeADirectoryAndTheIdFileMayNotGoMissing` | |
| erase-log-43 | `erase-log.checkpoint`: the last pruned entry's number and hash | DONE | `FileEraseLogStore.pruneThrough` | `ET/FileEraseLogStoreTest.java` `pruningKeepsTheChainVerifiableFromTheCheckpoint` | |
| erase-log-44 | `erase-log.lock` held while writing, so several instances on one location append one at a time (plus a lock per location in one JVM) | DONE (P038, 23fefb1) | `FileEraseLogStore.locked` (`FileLock` + `ReentrantLock`) | `ET/FileEraseLogStoreTest.java` `severalWritersAtOnceKeepOneChain` (4 stores in one JVM) | One JVM only: the JVM lock serialises the test, so the file lock between processes is not proven |
| erase-log-45 | A line cut short by a crash is ignored when reading and removed before the next write | DONE | `FileEraseLogStore` | `ET/FileEraseLogStoreTest.java` `aLineCutShortByACrashIsIgnoredAndRemovedBeforeTheNextWrite` | |
| erase-log-46 | The newest entries are read from the end of the file (an everyday erase stays cheap) | UNTESTED | `FileEraseLogStore.head` / `after` | none | No test with a long log |

## erase-log — Applied position (in the app's database)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-47 | `PostgresErasePosition` on `erl_position` (SQL `db/platform/erase-log`, history table `erl_schema_history`, schema step only); startup check `EraseLogSchema.verify` | DONE | `E/jdbc/PostgresErasePosition.java`, `E/jdbc/EraseLogSchema.java` | `ET/RestoreAndReplayTest.java` `theDatabaseNeverMovesItsPositionBackAndTheAppUserNeedsOnlyDml` (`ERL_TABLES_MISSING`) | |
| erase-log-48 | One row per log: `applied_seq`, `applied_hash`, `replay_required`, `restore_detected_at`, `verified_at` | DONE | `ER/V1__erase_log.sql` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` (`replayRequired`, `restoreDetected`) | |
| erase-log-49 | The database refuses to move a position back or to delete it | DONE | triggers `erl_position_forward`, `erl_position_no_delete` | `ET/RestoreAndReplayTest.java` `theDatabaseNeverMovesItsPositionBack…` | |
| erase-log-50 | The app user needs SELECT, INSERT and UPDATE (no DDL) | DONE | `EraseLogSchema.RIGHTS` | same test (`ERL_PRIVILEGES_MISSING` without UPDATE; DDL refused) | |
| erase-log-51 | `withLock` holds an advisory lock per log, so all instances apply entries one at a time and in order | DONE (P038, cd56444) | `PostgresErasePosition.withLock` (`pg_advisory_lock`) | none | No test with two erasers or an eraser and a replay at once |
| erase-log-52 | Each applied entry is committed at once | DONE | `PostgresErasePosition.applyAndAdvance` | `ET/RestoreAndReplayTest.java` `aHandlerFailureStopsTheReplayWithItsCodeAndARerunGoesOn` ("entry 1 stays applied") | |

## erase-log — Write order (`Eraser.erase`)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-53 | 1. The handler checks first; a refusal logs nothing | DONE | `Eraser.erase` | `EK/testkit/EraseLogInMemoryTest.java` `aKindWithoutHandlerOrARefusalLogsNothing` | |
| erase-log-54 | A kind without a handler is `ERASE_HANDLER_MISSING`, nothing logged | DONE | `Eraser.erase` | same test | |
| erase-log-55 | 2. With the position locked, entries in the log but not applied here are applied first | DONE | `Eraser.erase` (`caughtUp`) | `ET/RestoreAndReplayTest.java` `anEraseThatFailedHalfWayIsFinishedByTheNextEraseWhileRunning` | |
| erase-log-56 | 3. The entry is written to the log, durably, before the erase | DONE | `Eraser.erase` | `ET/RestoreAndReplayTest.java` `aStopBetweenTheLogWriteAndTheEraseIsFinishedByTheReplay` ("the entry was written first") | Durability itself: erase-log-39 |
| erase-log-57 | 4. Erase and position move in one transaction when the handler uses `Context.connection()` | DONE (P038, cd56444) | `Applying.apply`; `PostgresErasePosition.applyAndAdvance` | none | No test handler uses the position's connection |
| erase-log-58 | Otherwise the position moves right after the handler's own commit; the position only ever moves after the erase; applying twice is harmless | DONE | `Applying.apply` | `ET/RestoreAndReplayTest.java` `aStopBetweenTheLogWriteAndTheErase…` (position 0 after the crash); `EK/CaseFieldsEraseHandlerTest.java` `verifySeesTheOldRecord…` (ALREADY_GONE twice) | |
| erase-log-59 | A failure after step 3 leaves the entry pending; the next erase or the replay finishes it | DONE | | `ET/RestoreAndReplayTest.java` `anEraseThatFailedHalfWay…`, `aStopBetweenTheLogWriteAndTheErase…` | |
| erase-log-60 | Until then later erasures stop with the same handler code and are not logged | DONE | | `ET/RestoreAndReplayTest.java` `anEraseThatFailedHalfWay…` (`DOC_STORE_DOWN`, head stays 1) | |

## erase-log — Guard (`EraseGuard`)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-61 | `checkAtStartup()` walks the chain, then with the position locked checks wrong log, database ahead, gap, log unreadable, position unreadable | DONE | `EraseGuard.full`; `InStep.problem` | `ET/RestoreAndReplayTest.java` `theWrongLogALostLogAndADatabaseOlderThanTheLogAreEachRefused` (WRONG_LOG, BEHIND_DATABASE, GAP); `EK/testkit/EraseLogInMemoryTest.java` `anUnreachableLogOrDatabaseBlocksWorkWithItsCode` (UNREACHABLE, POSITION_FAILED), `aChangedOrMissingEntryIsDetectedAndBlocksWork` | |
| erase-log-62 | Database behind the log at startup → records `replay_required`; state `ERASE_LOG_RESTORE_DETECTED` with "Restored from backup: run the erase replay before starting work" | DONE | `EraseGuard.full`; `InStep.RESTORE_MESSAGE` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` | |
| erase-log-63 | Every instance sees the recorded flag | DONE | `EraseGuard.refresh` reads `replayRequired` | same test ("another instance sees it on its next recheck") | |
| erase-log-64 | `mayTakeWork()`: any code means no work; not checked yet means no work | DONE | `EraseGuard` initial state | `EK/testkit/EraseLogInMemoryTest.java` `anUnreachableLogOrDatabaseBlocksWorkWithItsCode` ("not checked yet: no work") | |
| erase-log-65 | The app checks `mayTakeWork()` before taking work | APP-SIDE | | | App item: intake, case work, exports and work-screen sign-ins check the guard |
| erase-log-66 | `refresh()` is a cheap recheck, so the block lifts after the replay | DONE | `EraseGuard.refresh` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` (`guard.refresh().mayTakeWork()`) | Only a full check clears a broken chain: `EraseLogInMemoryTest.aChangedOrMissingEntry…` |
| erase-log-67 | `fullCheck()` is the daily chain walk while running; it never records a restore | DONE (P038, cd56444) | `EraseGuard.fullCheck` (`full(false)`) | none | `EraseLogWiringTest` calls `worker.daily()` but asserts only `applied`; no test has a pending entry during `fullCheck` |

## erase-log — Replay and verify (`EraseReplay.run()`)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-68 | 1. With the position locked, the same chain and position checks | DONE | `EraseReplay.run` | `EK/testkit/EraseLogInMemoryTest.java` `aChangedOrMissingEntry…` (CHAIN_BROKEN); `ET/RestoreAndReplayTest.java` `theWrongLogALostLog…` (WRONG_LOG) | |
| erase-log-69 | 2. Every entry after the position goes through its handler (ERASED or ALREADY_GONE); the position moves with each | DONE | `EraseReplay.run`; `Applying.apply` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` (2 erased, 1 already gone) | |
| erase-log-70 | A handler failure stops the replay with `ERASE_HANDLER_FAILED` and the handler's code | DONE | `EraseReplay.run` | `ET/RestoreAndReplayTest.java` `aHandlerFailureStopsTheReplayWithItsCodeAndARerunGoesOn` | |
| erase-log-71 | A kind without a handler stops it with `ERASE_HANDLER_MISSING` | DONE | `Applying.apply` (`handlers.require`) | `EK/testkit/EraseLogInMemoryTest.java` `aKindWithoutHandlerOrARefusalLogsNothing` | |
| erase-log-72 | What was applied stays applied; a rerun goes on from there | DONE | | `ET/RestoreAndReplayTest.java` `aHandlerFailureStops…` (`rerun.from() == 1`) | |
| erase-log-73 | 3. Verify: every handler checks every kept entry; anything READABLE or a check that fails is `ERASE_VERIFY_FAILED`, and the system stays blocked | DONE (P038, cd56444) | `EraseReplay.Verify` | `ET/RestoreAndReplayTest.java` `somethingStillReadableAfterTheReplayKeepsTheSystemBlocked` (READABLE) | A verify check that throws (`EraseHandlerException` or another exception) is not tested |
| erase-log-74 | 4. Only then is `replay_required` cleared and the guard lifts | DONE | `locked.verified(now)` | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…`, `somethingStillReadable…` (stays required) | |
| erase-log-75 | Running it twice is safe | DONE | | `ET/RestoreAndReplayTest.java` `aRestoredDatabaseTakesNoWork…` | |
| erase-log-76 | The report holds counts, codes and entry numbers only | DONE (P040, bbf2289) | `EraseReplay.Report.describe`; `Applying.apply`; `InStep.problem` | `ET/RestoreAndReplayTest.java` `aHandlerFailureStops…` | The report's message also carries the entry's kind, record type and record ID, the log location and the handler's own message text. These are ids, not values, so nothing leaks under the entry rules, but it is more than the design says and the handler's message is app text |

## erase-log — Built-in handler `fields.CaseFieldsEraseHandler`

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-77 | `check` asks the app's `LegalHolds` | DONE | `CaseFieldsEraseHandler.check` | `EK/CaseFieldsEraseHandlerTest.java` `aRecordUnderLegalHoldIsRefusedBeforeAnythingIsLogged` (held and unknown) | |
| erase-log-78 | `apply` is `FieldValues.erase`; an unknown record counts as already gone | DONE | `CaseFieldsEraseHandler.apply` | `EK/CaseFieldsEraseHandlerTest.java` `verifySeesTheOldRecord…`; `ET/RestoreAndReplayTest.java` (`gone-1` ALREADY_GONE) | |
| erase-log-79 | The only class `FieldValues.erase` accepts (`ERASE_LOG_HANDLER`, `StackWalker`); others get `VALUES_ERASE_OUTSIDE_LOG` | DONE | `FieldValues.erase` | `EK/CaseFieldsEraseHandlerTest.java` `caseFieldsAreErasedOnlyThroughTheEraseLog` | A guard against mistakes, as the design says |
| erase-log-80 | `verify`: no record, or a record made after the erasure under the same ID, is GONE | DONE | `CaseFieldsEraseHandler.verify` | `EK/CaseFieldsEraseHandlerTest.java` `verifySeesTheOldRecordAsReadableAndANewOneUnderTheSameIdAsGone` | |

## erase-log — Retention and pruning

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-81 | `EraseRetention` 30–90 (default 35); the log keeps backup retention + 30 days | DONE | `E/EraseRetention.java` | `EK/testkit/EraseLogInMemoryTest.java` `backupRetentionIsThirtyToNinetyDaysAndTheLogKeepsThirtyMore` | |
| erase-log-82 | `prune` removes only entries older than that and applied in the database | DONE | `EraseLog.prune` | `ET/FileEraseLogStoreTest.java` `pruningKeepsTheChainVerifiableFromTheCheckpoint` ("only what the database applied") | |
| erase-log-83 | The newest pruned entry becomes the checkpoint; the chain stays verifiable from it | DONE | `FileEraseLogStore.pruneThrough` | same test | |
| erase-log-84 | A broken chain is not pruned | DONE | `EraseLog.prune` | `EK/testkit/EraseLogInMemoryTest.java` `aChangedOrMissingEntryIsDetectedAndBlocksWork` | |
| erase-log-85 | Pruning writes the checkpoint, then the shorter log, each by atomic rename; a crash in between is harmless | DONE | `FileEraseLogStore.pruneThrough` (`ATOMIC_MOVE`) | `ET/FileEraseLogStoreTest.java` `aCrashBetweenTheCheckpointAndTheShorterLogIsHarmless` | |

## erase-log — Spring (`platform-erase-log-spring`)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-86 | `platform.erase-log.location` required, absolute | DONE | `ES/EraseLogConfig.java` `from` | `EST/EraseLogWiringTest.java` `badSettingsStopTheAppWithEveryProblemListed` | |
| erase-log-87 | `backup-retention-days` default 35, allowed 30–90 | PARTLY | `ES/EraseLogProperties.java`; `EraseLogConfig.from` | `EST/EraseLogWiringTest.java` `badSettings…` (100 refused) | The Spring default 35 is not asserted (the tests set 40 or leave it unread) |
| erase-log-88 | `recheck` default 1 minute | PARTLY | `EraseLogProperties.recheck` | `EST/EraseLogWiringTest.java` `badSettings…` (`PT1S` refused) | The default is not asserted. The 5 s – 1 h limit is code-only (erase-log-DG-3) |
| erase-log-89 | `exit-after-replay` default true | PARTLY | `EraseLogProperties`; `EraseReplayRunner` | `EST/EraseLogWiringTest.java` builds the runner with `true` itself | Neither the default nor the `false` path (no exit) is tested |
| erase-log-90 | `enabled` | DONE | `@ConditionalOnProperty` | `EST/EraseLogWiringTest.java` `badSettings…` (`enabled=false` → no guard) | |
| erase-log-91 | A wrong setting stops the app with every problem listed | DONE | `ES/EraseLogConfigException.java` | `EST/EraseLogWiringTest.java` `badSettings…` (3 problems) | Exception message prefix `ERASE_LOG_SETTINGS_INVALID`; that code exists in `EraseLogError` |
| erase-log-92 | Beans: file store and `EraseLog` | DONE | `ES/EraseLogAutoConfiguration.java` | `EST/EraseLogWiringTest.java` `theAppsHandlersAreWired…` (`FileEraseLogStore`) | |
| erase-log-93 | `PostgresErasePosition` on the app's `DataSource` after `EraseLogSchema.verify` | DONE | `EraseLogAutoConfiguration.erasePosition` | `EST/EraseLogWiringTest.java` `onPostgresTheTableIsCheckedAtStartupAndTheDmlUserIsEnough` | |
| erase-log-94 | `EraseHandlers` from the app's `EraseHandler` beans | DONE | `eraseHandlers` | `EST/EraseLogWiringTest.java` `theAppsHandlersAreWired…` | |
| erase-log-95 | `Eraser` and `EraseReplay` beans | DONE | | same tests | |
| erase-log-96 | `EraseGuard`'s startup check runs when it is made; a restore does not stop the app: it starts blocked | DONE | `eraseGuard` | `EST/EraseLogWiringTest.java` `aRestoredDatabaseStartsOutOfServiceAndTheReplayRunLiftsIt` | |
| erase-log-97 | `EraseLogWorker`: the recheck, plus the daily full check and pruning | DONE (P038, e4ab697) | `ES/EraseLogWorker.java` | `EST/EraseLogWiringTest.java` `theAppsHandlersAreWired…` (`recheck`), `onPostgres…` (`daily` called) | The recheck is tested. `daily()` is called but neither the full check nor the pruning is asserted; the schedule is not tested |
| erase-log-98 | `EraseReplayRunner` (`--erase-replay`): exit 0 when passed, 1 when not | DONE | `ES/EraseReplayRunner.java` | `EST/EraseLogWiringTest.java` `aRestoredDatabaseStartsOutOfService…` (0, and no run without the option), `aFailingReplayRunExitsWithOneAndTheAppStaysBlocked` (1) | |
| erase-log-99 | Health `eraseLog`: UP; OUT_OF_SERVICE after a restore until the replay verified; DOWN otherwise | DONE (P038, e4ab697) | `ES/EraseLogHealthIndicator.java` | `EST/EraseLogWiringTest.java` `theAppsHandlersAreWired…` (UP), `aRestoredDatabaseStartsOutOfService…` (OUT_OF_SERVICE then UP) | DOWN is not tested |
| erase-log-100 | Health details hold no values | DONE | `EraseLogHealthIndicator.doHealthCheck` (ids, counts, codes, the location) | `ET/EraseRequestTest.java` (no value can enter an entry); `EST/EraseLogWiringTest.java` (details read) | Holds by construction: the guard state has no value to show |

## erase-log — DOC GAP

| ID | Decision (report) | Status | Code | Test | Note |
|---|---|---|---|---|---|
| erase-log-DG-1 | The everyday erase does not walk the whole chain: it compares the log's last entry with the position. The whole chain is walked at startup, daily, before pruning and by the replay (P021-7) | DONE (P040, bbf2289) | `Eraser.erase`; `EraseLog.prune`; `EraseGuard.full` | `ET/RestoreAndReplayTest.java` | The design body states only the guard's side |
| erase-log-DG-2 | A database belongs to one log; moving to a new location means moving the files, not starting a new log (P021-8) | DONE (P040, bbf2289) | `InStep.problem` (`ERASE_LOG_WRONG_LOG`) | `ET/RestoreAndReplayTest.java` `theWrongLogALostLog…` | The install guide says "one location per protected database" but not "move the files" |
| erase-log-DG-3 | Code-only numbers: `auditId` and `by` ids `[A-Za-z0-9_.:@/-]{1,128}`; `recheck` 5 seconds to 1 hour | DONE (P040, bbf2289) | `EraseRequest.ID`; `EraseLogConfig.from` | `EST/EraseLogWiringTest.java` `badSettings…` (`PT1S`) | Not from a report; in neither the design nor the install guide |

---

## Install guide check: docs/install/fields.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-fields-1: section 4 "keyed fingerprints (`f1.` texts)" | format | `f<version>.` (`fld_value.fingerprint` check `^f[1-9][0-9]{0,8}\.`) | After a fingerprint key rotation the texts are `f2.`, `f3.` …; the guide names only `f1.` |
| install-fields-2: section 4 "sensitive values only encrypted (`pci1.` texts)" | format | `pci1.` in `fld_value`, plus the record envelope `pck1.` in `fld_record.envelope` | The guide leaves out the `pck1.` envelope column that a backup also holds |
| install-fields-3: section 3 "a field whose type differs is reported as a conflict" | option | `ImportReport.Conflict` TYPE_DIFFERS, CURRENCY_DIFFERS, KEY_CASE_DIFFERS | The guide names only the type conflict |
| install-fields-4: section 1 runtime grants | property | `FieldSchema.RIGHTS` checks fewer rights (for example no UPDATE on `fld_value`, no DELETE on definitions) | Not a conflict: the guide grants more than the check needs, and the triggers refuse the deletes. Listed so the step-2 docs task can decide whether to say so |
| install-fields-5: `FLD_PARTIAL_SEARCH_UNAVAILABLE` "in its search status" | code | string in `FieldValues.searchStatus` | Not a `PlatformCode` and not in the catalogue (xc-catalogue-1) |
| install-fields-6: no word on how the app builds `FieldValues` (there is no Spring module) | option | | Missing in the guide (see case-fields-DG-13) |

All other items match: 22 checked (Flyway location `db/platform/fields`, history table `fld_schema_history`, file
`V1__fields.sql`, six table names, `pg_trgm` and index `fld_value_partial`, the fallback and `PARTIAL_AS_PREFIX`, script
`db/platform/fields-optional/partial-search.sql` (not Flyway, safe to rerun), `FieldSchema.verify` read-only, codes
`FLD_TABLES_MISSING`, `FLD_VERSION_UNKNOWN`, `FLD_VERSION_OLDER`, `FLD_VERSION_NEWER`, `FLD_TABLES_INCOMPLETE`,
`FLD_PRIVILEGES_MISSING`, `FLD_CHECK_FAILED`, the refusal of deletes, key changes and history changes, the refusal of a
readable sensitive row, `platform-fields/1` definitions only, import by key never changing an existing field).

## Install guide check: docs/install/erase-log.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-erase-log-1: `platform.erase-log.recheck` default `1m` | property/default | `Duration.ofMinutes(1)`; allowed 5 s – 1 h (`EraseLogConfig.from`) | The guide does not give the allowed range |
| install-erase-log-2: section 5 step 4 "the report names the code and the entry number where it stopped" | format | `Report.describe()` prints the code and handler code; the entry number is only inside the message text ("goes on from entry N") | `stoppedAt` is in the report but not printed as its own line |
| install-erase-log-3: section 4 health details | format | details also hold `location`, `logId`, `logHead`, `applied`, `pending`, retention days | Not listed in the guide. The admin screen data the design promises (erase-log-22) is not there |
| install-erase-log-4: section 1 "One location per protected database" | option | `ERASE_LOG_WRONG_LOG` | The guide does not say how to move a log (move the files; erase-log-DG-2) |

All other items match: 24 checked (location required and absolute, `backup-retention-days` 35 and 30–90,
`exit-after-replay` true, `enabled` true, every problem listed at startup, the four file names, the app creating the
directory and files, Flyway location `db/platform/erase-log`, history table `erl_schema_history`, grants SELECT, INSERT,
UPDATE on `erl_position` and SELECT on `erl_schema_version`, `ERL_TABLES_MISSING`, `ERL_PRIVILEGES_MISSING`, health path
`/actuator/health/eraseLog`, UP / OUT_OF_SERVICE / DOWN, the restore message text, the six DOWN codes, `--erase-replay`,
exit 0 / 1, "NOT complete" and `ERASE_HANDLER_FAILED (DOC_STORE_DOWN)` text, rerun from where it stopped, daily pruning
of applied entries older than retention + 30).

## Cross-check findings from this slice

| ID | Finding |
|---|---|
| xc-catalogue-1 | `FLD_PARTIAL_SEARCH_UNAVAILABLE` is a bare string in `FieldValues.SearchStatus`, not a `PlatformCode`; it is not in the catalogue |
| xc-catalogue-2 | `ERASE_HANDLER_ERROR` (handler gave no outcome, or threw) and `VALUES_RECORD_REF_INVALID` as a handler code are strings in `E/Applying.java`, `E/EraseReplay.java`, `E/fields/CaseFieldsEraseHandler.java`. `VALUES_RECORD_REF_INVALID` is a `FieldValuesError`; `ERASE_HANDLER_ERROR` is in no code enum |
| xc-catalogue-3 | `EraseLogConfigException` uses `ERASE_LOG_SETTINGS_INVALID` as a message prefix (the P032 question 2 pattern). The code exists in `EraseLogError`, but the exception does not carry it as a `PlatformCode` |
| xc-catalogue-4 | A type change while the app's `FieldValuesCheck` cannot answer is refused by the app's own `IllegalStateException` passing through (`FK/FieldDefinitionsTest.java` `whenTheAppCannotSayIfValuesExistTheTypeChangeIsRefused`), with no platform code |
| xc-schema-1 | Fields run: start-up verify and DML-only rights documented and tested (`FieldSchemaTest`). Gap: the DELETE right on `fld_record` is checked but untested (case-fields-113) |
| xc-schema-2 | Erase-log run: start-up verify and DML-only rights documented and tested (`RestoreAndReplayTest.theDatabaseNeverMovesItsPositionBack…`, `EraseLogWiringTest.onPostgres…`) |

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| erase-log-22 | Admin screen shows the pending entry and its handler code | The guard state and health have only a pending count; no entry number, kind, record type or ID, or handler code while running | B · **FIXED in P038 (b192e03)** | Keep the last apply failure (seq, kind, record type and ID, handler code) in `EraseGuard.State` and the `eraseLog` health details; test it | M |
| erase-log-51 | Advisory lock: instances apply one at a time, in order | No concurrency test of `PostgresErasePosition.withLock` | B · **FIXED in P038 (cd56444)** | Test two erasers (and an eraser plus a replay) on one database in parallel: one chain, position in order, nothing applied twice out of order | M |
| erase-log-57 | One transaction when the handler uses `Context.connection()` | Untested | B · **FIXED in P038 (cd56444)** | Test handler that writes through `ctx.connection()` and fails after: neither its write nor the position commits | S |
| erase-log-67 | `fullCheck()` never records a restore | Untested | B · **FIXED in P038 (cd56444)** | Test: pending entry while running, `fullCheck()` → may take work, `replay_required` still false | S |
| erase-log-97 | Worker: daily full check and pruning | `daily()` effects not asserted; schedule untested | B · **FIXED in P038 (e4ab697)** | Assert pruning and a blocked-state skip in `EraseLogWiringTest` with a moved clock | S |
| erase-log-99 | Health DOWN for the other codes | DOWN untested | B · **FIXED in P038 (e4ab697)** | Wiring test with an unreachable location or a broken chain → DOWN with its code | S |
| erase-log-44 | File lock between processes | Only same-JVM writers tested | B · **FIXED in P038 (23fefb1)** | Test with a second JVM (ProcessBuilder) appending to the same location | M |
| erase-log-73 | A verify check that fails is `ERASE_VERIFY_FAILED` | Throwing verify untested | B · **FIXED in P038 (cd56444)** | Handler whose `verify` throws → `ERASE_VERIFY_FAILED` with its code, still blocked | S |
| erase-log-39 | fsync before `append` returns | Not provable by a unit test | C | Note it as a code-review item; optionally a test store that counts `force` calls | S |
| erase-log-40 | Escape or missing field refused (`ERASE_LOG_FORMAT`) | Only an extra field tested | B · **FIXED in P038 (23fefb1)** | Add a line with `\"` and a line missing `auditId` to `FileEraseLogStoreTest` | S |
| erase-log-41 | Windows line ends accepted | Untested | C | Rewrite the log with CRLF in `FileEraseLogStoreTest`, verify intact | S |
| erase-log-46 | Newest entries read from the end | Untested | C | Test with a large log that `head()` and `after(head-1)` read only the tail (or time-bound) | S |
| erase-log-31 | Value-like id is `ERASE_ENTRY_VALUE_REFUSED` | The test accepts either code | C · **FIXED in P040 (bbf2289, 522a53e)** | Pin `ERASE_ENTRY_VALUE_REFUSED` for the e-mail, sealed, fingerprint and ciphertext samples | S |
| erase-log-34 | Hash formula | No known-answer vector | C | Add a fixed entry with its expected SHA-256 hex | S |
| erase-log-35 | Inserted entry breaks the chain | Untested | C | Insert a forged line between two entries → `ERASE_LOG_CHAIN_BROKEN` | S |
| erase-log-87 | `backup-retention-days` default 35 (Spring) | Default unasserted | C | Assert the health detail 35 with no property set | S |
| erase-log-88 | `recheck` default 1 minute | Default unasserted | C | Assert `EraseLogConfig.recheck()` is 1 minute with no property set | S |
| erase-log-89 | `exit-after-replay` default true; false does not exit | Neither tested | C | Wiring test: default runner exits; with `false` it does not | S |
| erase-log-26 | Only dependency platform-fields, optional | Out of date: platform-errors since P032 | C · **FIXED in P040 (bbf2289)** | Update the design sentence | S |
| erase-log-76 | Replay report holds counts, codes, entry numbers only | Message also has kind, record type and ID, location, handler text | C · **FIXED in P040 (bbf2289)** | Either say so in the design, or move ids and handler text out of `describe()` | S |
| erase-log-DG-1 | Everyday erase compares head only; full walk at startup, daily, prune, replay | Not in the design body | C · **FIXED in P040 (bbf2289)** | Add to the design "Guard" section | S |
| erase-log-DG-2 | One database, one log; move files to move it | Not in the design or install guide | C · **FIXED in P040 (bbf2289)** | Add a line to the design and install section 1 | S |
| erase-log-DG-3 | `auditId` / `by` id form; `recheck` 5 s – 1 h | Code-only | C · **FIXED in P040 (bbf2289)** | Add both to the design entry table and the install property table | S |
| case-fields-60 | Record type / ID form refused `VALUES_RECORD_REF_INVALID` | Untested | C | Add bad record type and ID cases to `PostgresValueStoreTest` | S |
| case-fields-63 | A record without primary values is a record of its own | Untested | B · **FIXED in P038 (6681ae1)** | Claim twice with no primary value under two IDs → two records | S |
| case-fields-73 | Sealed values bound to field and position | Untested | B · **FIXED in P038 (6681ae1)** | Move a `pci1.` value to another field / position in the table → it does not open | S |
| case-fields-81 | Schema step goes on without `pg_trgm` | Untested | C | Run V1 on a database where the extension cannot be created (for example a role without CREATE) and check the schema verifies | M |
| case-fields-99 | Switching back on does not make a field primary again | Untested | C | Assert `assertFalse(on.spec().primary())` in `removingSwitchesOffAndNothingIsDeleted` | S |
| case-fields-105 | Erase retries when the record changed meanwhile | Untested | C | Test store that reports one `VALUES_CHANGED_MEANWHILE`, then succeeds | S |
| case-fields-113 | `FieldSchema` checks DELETE on `fld_record` | Untested | B · **FIXED in P038 (7678f9b)** | `FieldSchemaTest`: user with all but DELETE on `fld_record` → `FLD_PRIVILEGES_MISSING` naming DELETE | S |
| case-fields-DG-1 … DG-14 | P017, P019 decisions (keys and capitals, primary one value, search mode, normalisation order, per-type forms, tab and 50 values, extensions, `change()` rules, import all-or-none, delivery rules and 2 MB, limits in messages, no cause in exceptions, no Spring module, `pg_indexes` detection) | In reports only | C · **FIXED in P040 (d1c3774)** | Add a "Decisions from P017–P019" subsection to `docs/design/case-fields.md` | M |
| install-fields-1 | `f1.` fingerprint texts | Guide names one version only | C · **FIXED in P040 (d1c3774)** | Say `f<version>.` | S |
| install-fields-2 | Backups hold `pci1.` only | `pck1.` envelope not named | C · **FIXED in P040 (d1c3774)** | Name the envelope column too | S |
| install-fields-3 | Import conflict "type differs" | Currency and key-capitals conflicts not named | C · **FIXED in P040 (d1c3774)** | List all three conflicts | S |
| install-fields-4 | Runtime grants | Grants are wider than the check needs | C · **FIXED in P040 (d1c3774)** | Leave the grants as they are; say the check needs fewer | S |
| install-fields-6 | How the app builds `FieldValues` | Missing | C · **FIXED in P040 (d1c3774)** | Add a short "wiring in the app" paragraph | S |
| install-erase-log-1 | `recheck` range | Missing | C · **FIXED in P040 (bbf2289)** | Add "5 seconds to 1 hour" | S |
| install-erase-log-2 | Report entry number | Printed only inside the message | C · **FIXED in P040 (bbf2289)** | Print `stopped at entry N` as its own line in `describe()` | S |
| install-erase-log-3 | Health details | Not listed | C · **FIXED in P040 (bbf2289)** | List the details (and the pending-entry fields once erase-log-22 is fixed) | S |
| install-erase-log-4 | Moving a log | Missing | C · **FIXED in P040 (bbf2289)** | See erase-log-DG-2 | S |
| xc-catalogue-1 | `FLD_PARTIAL_SEARCH_UNAVAILABLE` | Not a `PlatformCode` | B · **FIXED in P037 (00560bc)** | Add it to a fields code enum with message, cause, fix; regenerate the catalogue | S |
| xc-catalogue-2 | `ERASE_HANDLER_ERROR` | Not a `PlatformCode` | B · **FIXED in P037 (59178ca)** | Add it to `EraseLogError` (or a handler code enum) with texts; regenerate the catalogue | S |
| xc-catalogue-3 | `ERASE_LOG_SETTINGS_INVALID` as message prefix | Exception does not carry the code | B · **FIXED in P037 (2a21e26)** | Make `EraseLogConfigException` implement `HasPlatformCode` (with the P032 question 2 group) | S |
| xc-catalogue-4 | Type change when the values check cannot answer | No platform code | C · **FIXED in P037 (bf7d99c)** | Wrap in `FieldDefinitionException` with a new `FIELD_VALUES_CHECK_FAILED` code | S |

No A gaps found in this slice.
