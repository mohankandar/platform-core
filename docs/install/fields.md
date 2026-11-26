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
  `fld_schema_history`, run as the schema owner, in the same database schema the app uses. Set `baselineOnMigrate=true` and `baselineVersion=0` for the run when
  the schema already holds other tables; Flyway refuses a schema it has no history for otherwise. Files today:
  `V1__fields.sql`, `V2__fields_search_path.sql` (since 0.7.0), `V3__fields_option_names.sql` (since 1.3.0: one nullable
  column, `fld_field_definition.option_names`, for the display names of dropdown values; no new rights). An app of 1.3.0
  starts only on version 3 (`FLD_VERSION_OLDER` otherwise), and an app of 1.2.x does not start on it (`FLD_VERSION_NEWER`):
  run the step while every instance is stopped.
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

**Back up the whole database**, not one schema only: a dump of a single schema (`pg_dump -n`) leaves out the extension
`pg_trgm`, and restoring it stops at the index `fld_value_partial`. If you must restore such a dump, first run
`create extension pg_trgm schema <the app's schema>;` in the target database.

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

An app none of whose fields is sensitive needs no key service (1.2.0): `FieldValues.withoutKeyService(customerId, fieldStore,
valueStore, Clock.systemUTC(), legalHolds)`. For results pages: `values.read(refs)` and `values.readSensitive(refs)`, up to 100
records, one database query each (and one key service call for the sensitive ones).

`legalHolds` is the app's own `LegalHolds` (leave it out when the app has none). To erase case fields, the app also
registers `new CaseFieldsEraseHandler(values, valueStore, legalHolds)` as an `EraseHandler` bean for the erase log
(`docs/install/erase-log.md`). Codes: `docs/errors/platform-error-codes.md`.

## 6. In the app's transaction (1.3.0)

By default each call of the field stores is its own transaction, as before. When the app's own row and the field values
must be committed together (or not at all), it hands its connection over for the call: `values.in(connection)` and
`definitions.in(connection)` (for a store alone: `PostgresValueStore.onConnection(c)`, `PostgresFieldStore.onConnection(c)`).

```java
// without Spring
c.setAutoCommit(false);
saveMyRow(c, ...);
values.in(c).claim(ref, given);
c.commit();

// with Spring, inside @Transactional: the connection of the current transaction, given back after the call
Connection c = DataSourceUtils.getConnection(dataSource);
try {
    values.in(c).claim(ref, given);
} finally {
    DataSourceUtils.releaseConnection(c, dataSource);
}
```

There is no Spring bean for this; the three lines above are all it takes.

- **The connection:** auto-commit off and **READ COMMITTED** (PostgreSQL's default). Anything else is refused before anything is
  written: `VALUES_TRANSACTION_UNSUITABLE` (definitions: `FIELD_TRANSACTION_UNSUITABLE`). REPEATABLE READ and SERIALIZABLE
  are refused because the retry after a concurrent change, and the claim of a primary value another transaction is writing,
  must see that other transaction's commit.
- **The store never ends the app's transaction:** no `commit`, no full `rollback`, no change of auto-commit or isolation,
  no `close`. The app commits or rolls back.
- **A refused write leaves the app's work usable:** each write runs in its own savepoint, rolled back to when the write is
  refused (`VALUES_PRIMARY_TAKEN`, `VALUES_CONFLICT`, `VALUES_CHANGED_MEANWHILE`). The same exception as before is thrown;
  the app decides whether to roll back everything.
- **Locks are held until the app commits.** Two claims of the same new primary value wait for each other that long; the second
  joins the first's record when it commits, or makes its own when it rolls back. Deadlocks between the app's rows and field rows
  become possible: PostgreSQL ends one (SQL state 40P01, `VALUES_STORE_FAILED`) and the app retries its whole transaction.
  Write several records in one order (for example by record ID).
- **Key service calls run inside the open transaction** (a claim with sensitive values: up to three calls). Keep such
  transactions short; never keep one open while a person decides.
- **Erasing stays outside:** `values.in(c).erase(...)` is refused with `VALUES_ERASE_OUTSIDE_LOG`; erasures go through the
  erase log, which has its own transaction.
- **The app must not** use the bound object after its transaction ended or from another thread, catch a driver error it
  caused and go on, or send a message or erase on the strength of values not yet committed.
- The in-memory stores of the testkit cannot join a transaction (`UnsupportedOperationException`).

## 7. Dropdown values with display names (1.3.0)

A DROPDOWN field's values are a **code** plus an optional **display name**, in the order the admin sets:
`FieldSpec.builder(...).namedOptions(new FieldSpec.Option("OPN", "Open"), new FieldSpec.Option("CLS", null))`.

- The code is what is stored, matched, searched, imported and fingerprinted; `options()` still lists the codes.
- The name is for display only (`spec.displayName(code)`, or the code itself when there is none). It can change at any time
  without touching a stored value. Names are unique within a field (ignoring capitals and extra spaces), with a label's limits:
  `FIELD_OPTION_NAME_REPEATED`, `FIELD_OPTION_NAME_INVALID`. They are configuration, not sensitive.
- Existing definitions read as code-only, in their stored order. In the field list file a named value is
  `{"code": "OPN", "name": "Open"}`; one without a name stays a string, so a file without names is written as before.
