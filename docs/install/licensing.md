# Licensing — install guide for IT

This page tells your IT team how to set up licensing: the database tables, the install config, and the licence
command you use to store your licence and its renewals. The app's own install guide says where its install config
file is and how the licence command is started. The settings below all start with `platform.licensing`.

Three rules hold throughout:

- **No secrets.** The app only holds our public keys. You never receive or store a private key.
- **The app never changes the database by itself.** You create the tables with the schema step (section 1). At
  startup the app only checks them.
- **A licence date never takes the app down.** Expiry, the 30-day grace period and a wrong server clock are shown as
  warnings. After the grace period, intake (taking in new work) stops; viewing and export keep working.

## 1. Database

The licensing tables all start with `lic_`: the stored licence, the latest time seen, applied clock resets, the
usage ledger and its chain head, and the schema version.

1. **Run the licensing schema step as its own Flyway run,** apart from the app's other tables:
   - location `classpath:db/platform/licensing` (files `V1__licensing.sql`, …)
   - history table `lic_schema_history`
   - run it as the schema owner, in the same schema the app connects to.
2. **Grant the app's database user** SELECT, INSERT, UPDATE and DELETE on the `lic_` tables, and SELECT on
   `lic_schema_version`. Nothing more is needed.

If the tables are missing, at another version, or the rights are short, the app does not start. It names the exact
problem (for example `LIC_TABLES_MISSING`, `LIC_PRIVILEGES_MISSING`) and what to do.

The tables hold no secrets, no prices and no document data.

## 2. Install config

| Setting | What to give |
|---|---|
| `platform.licensing.trusted-keys.<key ID>` | The public keys shipped with this release (key ID and Base64 key). Keep older keys after an upgrade. |
| `platform.licensing.product-code` | The app's product code (set by the app's own install guide). |
| `platform.licensing.tenant-id` | Optional. Your tenant ID from the licence. When set, a licence for any other tenant is refused. |
| `platform.licensing.clock-tolerance` | Optional, default `1h`. How far the server clock may be behind before a warning is shown. |

A wrong value stops the app with the property name and what is wrong.

## 3. Storing your licence

You receive a signed licence file from your account manager. Store it with the licence command:

```
licence store  --file licence.json  --jdbc-url jdbc:postgresql://dbhost/appdb  --db-user app_user  --keys trusted-keys.json
licence status --jdbc-url …  --db-user …  --keys …
```

The database password is read from the environment variable `PLATFORM_LICENSING_DB_PASSWORD`, never from an
option.

- The file is checked before it is stored. A file that was changed, was not signed by us, or belongs to another
  customer is refused with a code (`LICENCE_TAMPERED`, `LICENCE_FORGED`, `LICENCE_WRONG_CUSTOMER`) and nothing
  changes.
- Storing the same file again changes nothing.
- **Renewals:** store the new file as soon as you get it. If it starts later, it waits, and your current licence
  stays in force until the new one's start date. A restart is not needed.
- The command prints one status line: the licence, its type and kind, the number of entities, the expiry date (or
  "updates until" date) and the state.

The app refuses to start only when no licence was ever stored, or when the stored licence is unreadable, changed,
not signed by us, signed with a key this release does not know, or for another customer. Each case has a stable
code, and the message says what to do.

## 4. The server clock

Licence dates use the later of the server clock and the latest time the app has seen, so turning the clock back
never extends a licence. If the server clock is behind by more than the tolerance, the app warns, for example "The
server clock is 3 hours behind …". Correct the clock (NTP); the warning clears on its own.

If the server clock was once set far into the **future**, licence dates stay at that future time. Ask your account
manager for a **clock reset** file and apply it:

```
licence apply-reset --file reset.json  --jdbc-url …  --db-user …  --keys …
```

A reset works only for your installation, only once, and only for a few days after it was issued.

## 5. Health

With Spring Boot Actuator, `/actuator/health/licensing` shows the state, licence ID and version, kind (a
test-environment licence is flagged), expiry, days to expiry, grace end, whether intake is allowed, and whether the
clock is behind. It never shows keys or signatures.

`LICENCE_WARNING` and `INTAKE_STOPPED` do not mark the app unhealthy, so a load balancer keeps it in service.
`DOWN` appears only if the stored licence is edited while the app runs.
