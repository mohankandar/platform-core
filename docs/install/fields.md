# Case fields — install guide for IT

**Needs:** Spring Boot 4.2 and Java 21 in the app (`docs/design/platform-baseline.md`).

The app keeps its case field definitions (the business's own fields such as contract number, borrower name, property
address) and the field **values** of its records in tables of its own database, prefixed `fld_`. This page says how
they are created and what the app's database user needs. Sensitive values (names, addresses, and whatever the Business
Admin marks sensitive) are never stored readably: only encrypted, with keys held by the key service (Vault), see
`docs/install/crypto.md`.

## 1. Database

The tables are created only by the app's schema step, never at app startup.

- **Schema step:** a separate Flyway run with location `classpath:db/platform/fields` and history table
  `fld_schema_history`, run as the schema owner, in the same database schema the app uses. One file today:
  `V1__fields.sql`.
- **Tables:** `fld_schema_version`, `fld_field_definition`, `fld_field_definition_history`, `fld_field_extension`,
  `fld_record` (one row per record of the app that has field values), `fld_value` (one row per value).
- **Runtime user:** data rights only:

  ```sql
  grant select, insert, update, delete on fld_field_definition, fld_field_definition_history, fld_field_extension,
      fld_record, fld_value to app_runtime;
  grant select on fld_schema_version to app_runtime;
  ```

  The startup check (section 2) needs fewer rights than these: it does not ask for DELETE on
  `fld_field_definition`, UPDATE or DELETE on `fld_field_definition_history`, or UPDATE on `fld_value`. Granting the
  list above is still fine; leave it as it is.

  Even with DELETE granted, the database refuses to delete a field definition or a history entry, to change a
  field's key, or to change a history entry: removing a field means switching it off. It also refuses a sensitive
  value with any readable column filled in.

### Partial search (pg_trgm)

"Contains" search on fields that are not sensitive (for example part of a loan number) uses a trigram index, which
needs the PostgreSQL extension `pg_trgm` (shipped with PostgreSQL, in the contrib package).

- The schema step creates the extension and the index (`fld_value_partial`) when the schema owner may do so (on
  PostgreSQL 13 and later `pg_trgm` is a trusted extension: an owner with CREATE on the database can install it).
- If it cannot, the schema step goes on without it. The app then runs partial search as prefix search ("starts
  with"), marks every such result (`PARTIAL_AS_PREFIX`) and reports `FLD_PARTIAL_SEARCH_UNAVAILABLE` in its search
  status. Nothing else changes.
- To add it later: the DBA makes the extension available (`create extension pg_trgm;`, or allows the owner to), then
  runs `db/platform/fields-optional/partial-search.sql` (inside the platform-fields jar) as the owner of the `fld_`
  tables. It is not a Flyway file and is safe to run again.

## 2. Startup check

At startup the app calls `FieldSchema.verify(dataSource)`. It only reads, and stops the app with one of these codes
and a message saying what to do:

| Code | Means |
|---|---|
| `FLD_TABLES_MISSING` | The schema step was never run on this database schema. |
| `FLD_VERSION_UNKNOWN` | Some tables exist but `fld_schema_version` is missing or empty. |
| `FLD_VERSION_OLDER` | The schema step for this release was not run. |
| `FLD_VERSION_NEWER` | An older app release runs against an upgraded database. |
| `FLD_TABLES_INCOMPLETE` | A table was dropped by hand. |
| `FLD_PRIVILEGES_MISSING` | The runtime user lacks a right listed above. |
| `FLD_CHECK_FAILED` | The database refused the check itself (the SQL state is given). |

## 3. Moving a field list between products

The Business Admin can export an entity's field list from one product (`platform-fields/1` file, JSON, definitions
only: no values, no app-only settings) and import it into the other. Import matches fields by key, creates the new
ones, and never changes an existing field. Three kinds of line are reported as conflicts: a field whose type differs
(`TYPE_DIFFERS`), a MONEY field in another currency (`CURRENCY_DIFFERS`), and a key that differs from an existing one
only in capitals (`KEY_CASE_DIFFERS`).

## 4. Backups

A database backup holds sensitive values only encrypted (`pci1.` texts in `fld_value`, under each record's
envelope, a `pck1.` text in `fld_record.envelope`) and as keyed fingerprints (`f<version>.` texts: `f1.`, then `f2.`
and so on after a fingerprint key rotation). Neither
can be read or matched without the customer's keys in the key service, which are never exported. Restoring a backup
needs the same key service (or its own backup) to read sensitive values again.

## 5. Wiring in the app

There is no `platform-fields-spring` module (P019): the app builds the field objects itself, on its own `DataSource`
and the beans of `platform-crypto-spring` (`KeyService` and `CustomerId`, `docs/install/crypto.md`):

```java
FieldSchema.verify(dataSource);                                   // at startup; stops the app with an FLD_ code
PostgresFieldStore fieldStore = new PostgresFieldStore(dataSource);
PostgresValueStore valueStore = new PostgresValueStore(dataSource);
FieldDefinitions definitions = new FieldDefinitions(fieldStore, valueStore);   // the value store locks types
FieldValues values = new FieldValues(customerId, fieldStore, valueStore, keyService, Clock.systemUTC(), legalHolds);
```

`legalHolds` is the app's own `LegalHolds` (leave it out when the app has none). To erase case fields, the app also
registers `new CaseFieldsEraseHandler(values, valueStore, legalHolds)` as an `EraseHandler` bean for the erase log
(`docs/install/erase-log.md`). Codes: `docs/errors/platform-error-codes.md`.
