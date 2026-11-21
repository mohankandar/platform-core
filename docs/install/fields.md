# Case fields — install guide for IT

The app keeps its case field definitions (the business's own fields such as contract number, borrower name, property
address) in a few tables of its own database, prefixed `fld_`. This page says how they are created and what the app's
database user needs. Field **values** are not stored here (they come with value storage, encrypted when sensitive).

## 1. Database

The tables are created only by the app's schema step, never at app startup.

- **Schema step:** a separate Flyway run with location `classpath:db/platform/fields` and history table
  `fld_schema_history`, run as the schema owner, in the same database schema the app uses. One file today:
  `V1__fields.sql`.
- **Tables:** `fld_schema_version`, `fld_field_definition`, `fld_field_definition_history`, `fld_field_extension`.
- **Runtime user:** data rights only:

  ```sql
  grant select, insert, update, delete on fld_field_definition, fld_field_definition_history, fld_field_extension
      to app_runtime;
  grant select on fld_schema_version to app_runtime;
  ```

  Even with DELETE granted, the database refuses to delete a field definition or a history entry, to change a
  field's key, or to change a history entry: removing a field means switching it off.

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
ones, and never changes an existing field; a field whose type differs is reported as a conflict.
