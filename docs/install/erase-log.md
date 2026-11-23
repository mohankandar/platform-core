# Erase log — install guide for IT

The app removes erased data from its live database at once. Backups are kept only for the backup retention period.
If a backup of the database is ever restored, the erasures made after that backup must be applied again before the
app takes any work. The **erase log** makes this possible: one line per erasure (ids only, never the data), kept
**outside** the database. This page lists what the app needs (module `platform-erase-log-spring`). Design:
`docs/design/erase-log.md`.

## 1. The log location (install rule)

`platform.erase-log.location` is a directory. The rule:

- It must **not** be inside the database's storage: not in its data directory, not on a disk, volume or snapshot that
  the database's backups include.
- It must be **backed up on its own**, separately from the database. Keep those backups at least as long as the log
  keeps entries (backup retention + 30 days; 65 days by default).
- Every app instance of one installation uses **the same** location (a shared volume, for example NFS or an Azure
  Files / EFS share mounted on every instance). The app locks a file there while writing.
- The app's operating system user needs read and write access to it. Nobody else needs write access.
- One location per protected database. Do not point two products' databases at one location.

Why: if the log were in the database or in its backups, restoring the database would roll the log back with it, and
the erasures made after the backup would come back.

The app creates the directory and its files on first start: `erase-log.jsonl` (the entries), `erase-log.id`,
`erase-log.lock`, and later `erase-log.checkpoint`. Do not edit them: every entry is chained to the one before it, so
any change is detected and blocks the app.

## 2. The database table

The schema step creates the table `erl_position` (where the database stands in the log), like the other platform
tables:

- Flyway location `classpath:db/platform/erase-log`, history table `erl_schema_history`, run as the schema owner.
- Grant the app's everyday database user: `SELECT, INSERT, UPDATE` on `erl_position`, `SELECT` on
  `erl_schema_version`. No DELETE, no DDL.

The app checks the table at startup and stops with a specific message (`ERL_TABLES_MISSING`, `ERL_PRIVILEGES_MISSING`,
…) if it is missing or not usable. It never creates it.

## 3. App settings (`platform.erase-log.*`)

| Property | Default | |
|---|---|---|
| `platform.erase-log.location` | — | required, an absolute path (section 1) |
| `platform.erase-log.backup-retention-days` | `35` | the installation's backup retention, 30 to 90, as agreed by contract; the log keeps entries 30 days longer |
| `platform.erase-log.recheck` | `1m` | how often a blocked app looks again whether it may start work |
| `platform.erase-log.exit-after-replay` | `true` | with `--erase-replay`, the app exits after the replay |
| `platform.erase-log.enabled` | `true` | |

A wrong or missing setting stops the app at startup with a list of every problem.

## 4. Health

`/actuator/health/eraseLog`:

- **UP**: the log can be read, its chain is intact, the database is in step.
- **OUT_OF_SERVICE**, code `ERASE_LOG_RESTORE_DETECTED`: the database is behind the log. Run the replay (section 5).
- **DOWN**, with a code: `ERASE_LOG_UNREACHABLE` (location not mounted or no rights), `ERASE_LOG_CHAIN_BROKEN` (the
  log was changed), `ERASE_LOG_BEHIND_DATABASE` (the log lost entries: restore the log from its own backup),
  `ERASE_LOG_WRONG_LOG` (the app points at another log), `ERASE_LOG_GAP` (the database is older than the backup
  retention allows), `ERASE_POSITION_FAILED` (the table cannot be read).

While the state is not UP, the app takes no work.

While an erasure that was logged could not be applied on this instance (its handler failed, for example
`DOC_STORE_DOWN`), the details also hold `lastFailure`: the entry number (`seq`), `kind`, `recordType`, `recordId`,
the handler's code (`handlerCode`), the erase log's code (`code`) and when (`at`). IDs and codes only; an entry holds no
value. It goes away once that entry is applied (the next erasure or the replay applies it first).

## 5. Restore runbook (in plain words)

After restoring the app's database from a backup:

1. **Do not let users in yet.** The app would refuse work anyway: on start it sees that the database is behind the
   erase log and shows "Restored from backup: run the erase replay before starting work".
2. **Check the log location is mounted** and is the same as before (the log itself is never restored from the database
   backup; if the log's own storage was lost too, restore the log from its own backup first).
3. **Run the replay**, once, with the app's normal settings and database login:

   ```
   java -jar <app>.jar --erase-replay
   ```

   It applies again every erasure made after the backup (documents, copies, case fields), then **checks** that nothing
   in the log can be read any more. It prints a short report and exits: 0 when everything is erased and checked, 1 when
   not.
4. **If it says NOT complete:** the report names the code and the entry number where it stopped (for example
   `ERASE_HANDLER_FAILED (DOC_STORE_DOWN)`: the document store did not answer). Fix the cause and run step 3 again.
   Running it again is always safe; it goes on from where it stopped.
5. **Start the app normally.** Health shows UP; work may start. Every instance sees the result.

## 6. Retention

Once a day the app removes log entries older than backup retention + 30 days (65 days by default) that the database
has applied: by then every backup they could apply to is gone. What remains stays verifiable from a checkpoint.
State the backup retention in force (`backup-retention-days`) in the compliance report.
