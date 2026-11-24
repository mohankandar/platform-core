# Licensing — install guide for IT

**Needs:** Spring Boot 4.2 and Java 21 in the app (`docs/design/platform-baseline.md`).

This page tells your IT team how to set up licensing: the database tables, the install config, and the licence
command you use to store your licence and its renewals. The app's own install guide says where its install config
file is and how the licence command is started. The settings below all start with `platform.licensing`.

> **Until the app turns licensing on, the licensing tables and the stored licence exist, but nothing checks the
> allowance. Licensing is not live.**

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
   - the schema usually holds other tables already: set `baselineOnMigrate=true` and `baselineVersion=0` for this run
     (Flyway command line: `-baselineOnMigrate=true -baselineVersion=0`), or Flyway refuses a schema it has no history for.
2. **Grant the app's database user:**
   - SELECT, INSERT, UPDATE and DELETE on `lic_licence`, `lic_usage`, `lic_chain_head` and `lic_notice_sent`;
   - SELECT, INSERT and UPDATE on `lic_last_seen` (the database lets the time move forward only);
   - SELECT and INSERT on `lic_clock_reset`;
   - SELECT on `lic_version_floor` (kept by the database itself) and `lic_schema_version`.

   Nothing more is needed, and the app's user must not own the tables.

**Upgrading to 0.7.0** (table version 2): run the schema step, then `grant select on lic_version_floor` and
`revoke delete on lic_last_seen` and `revoke update, delete on lic_clock_reset` for the app's user. Remove
`platform.licensing.trusted-keys` and `trusted-keys-file` from the config: an app that finds them does not start.

If the tables are missing, at another version, or the rights are short, the app does not start. It names the exact
problem (for example `LIC_TABLES_MISSING`, `LIC_PRIVILEGES_MISSING`) and what to do.

The tables hold no secrets, no prices and no document data.

## 2. Install config

| Setting | What to give |
|---|---|
| `platform.licensing.product-code` | **Required.** The app's product code as listed in licences (set by the app's own install guide). Without it the app does not start. |
| `platform.licensing.tenant-id` | **Required.** Your customer ID from the licence (`TEN-…`). The setup command writes it into `platform-tenant.properties`, which the app imports. A licence for any other customer is refused. Without it the app does not start and says `platform.licensing.tenant-id is missing`. |
| `platform.licensing.clock-tolerance` | Optional, default `1h`, zero to at most `1d` (1 day). How far the server clock may be behind before a warning is shown. |

The public keys that licences are checked with are part of the app itself (P045). No setting, file or option at an
installation adds or changes one, and nothing may be added to the app's classpath: a second copy of the keys stops the
app. (For products: the build puts `META-INF/platform/licence-keys.json` and `META-INF/platform/licence-build.properties`
with `built-at=<time>` in its jar; a development build, and only that, adds `development-keys=true`.)

A missing or wrong value stops the app with `LIC_SETTINGS_INVALID`; the start-up message lists every problem with
the property name and what is wrong. Codes: `docs/errors/platform-error-codes.md`.

## 3. Storing your licence

You receive a signed licence file from your account manager. Store it with the licence command:

```
licence store  --file licence.json  --jdbc-url jdbc:postgresql://dbhost/appdb  --db-user app_user
licence status --jdbc-url …  --db-user …
```

The database password is read from the environment variable `PLATFORM_LICENSING_DB_PASSWORD`, never from an
option.

| Option | What to give |
|---|---|
| `--file` | The licence file (`store`) or the clock reset file (`apply-reset`). Not used with `status`. |
| `--jdbc-url`, `--db-user` | **Required.** The app's database and its database user. |
| `--tenant-id` | Optional. Your customer ID (`TEN-…`), the same as `platform.licensing.tenant-id`. A licence for another customer is then refused. |
| `--product-code` | Optional. The app's product code, the same as `platform.licensing.product-code`, so the command checks the licence for this product as the app does. |
| `--clock-tolerance` | Optional, default `PT1H` (an ISO-8601 duration). The same as `platform.licensing.clock-tolerance`. |

**Give `--tenant-id` on the first store.** Without it, the first licence stored is accepted for any customer, and its
customer ID becomes the one later licences must match. With it, a licence for another customer is refused even on
the first store.

Exit codes: 0 done, 1 refused (the message starts with the code), 2 options wrong.

- The file is checked before it is stored. A file that was changed, was not signed by us, or belongs to another
  customer is refused with a code (`LICENCE_TAMPERED`, `LICENCE_FORGED`, `LICENCE_WRONG_CUSTOMER`) and nothing
  changes.
- Storing the same file again changes nothing.
- **Renewals:** store the new file as soon as you get it. If it starts later, it waits, and your current licence
  stays in force until the new one's start date. A restart is not needed.
- The command prints one status line: the licence, its type and kind, the number of entities, the expiry date (or
  "updates until" date) and the state.

The app refuses to start only when no licence was ever stored, or when the stored licence is unreadable, changed,
not signed by us, signed with a key this release does not know, for another customer,
or older than a licence stored before (`LICENCE_OLDER_VERSION`: store the newest file again). Each case has a stable
code, and the message says what to do.

## 4. The server clock

Licence dates use the latest of the server clock, the latest time the app has seen, the licence's issue time and the
time the app was built, so turning the clock back never extends a licence. If the server clock is behind by more than the tolerance, the app warns, for example "The
server clock is 3 hours behind …". Correct the clock (NTP); the warning clears on its own.

If the server clock was once set far into the **future**, licence dates stay at that future time. Ask your account
manager for a **clock reset** file and apply it:

```
licence apply-reset --file reset.json  --jdbc-url …  --db-user schema_owner
```

Run it with the **schema owner's** database user: the app's everyday user may not move the time back
(`RESET_NEEDS_OWNER`). A reset works only for your installation, only once, and only for a few days after it was issued.

## 5. Health

With Spring Boot Actuator, `/actuator/health/licensing` shows the state, licence ID and version, kind (a
test-environment licence is flagged), expiry, days to expiry, grace end, whether intake is allowed, and whether the
clock is behind. It never shows keys or signatures.

`LICENCE_WARNING` and `INTAKE_STOPPED` do not mark the app unhealthy, so a load balancer keeps it in service.
`DOWN` appears only if the stored licence is edited while the app runs.

## 6. Licensing notifications

When the app also uses platform notifications, licensing tells people about the licence and usage by e-mail, Teams
or Slack, through the notification settings your admins already use. Without platform notifications nothing is sent
and licensing works the same.

| Event | Kind | Default | Goes to |
|---|---|---|---|
| Licence expiring (60, 30, 7 and 1 days before) | business, per entity | once a day at 08:00 | Business Admin |
| Licence expired, grace period running | business, per entity, **critical** | at once, repeated daily, Recovered when renewed | Business Admin and IT Admin |
| Intake stopped, licence expired (after grace) | business, per entity, **critical** | at once, repeated daily, Recovered when renewed | Business Admin and IT Admin |
| Server clock behind | technical, **critical** | at once, repeated every 4 hours, Recovered when correct | IT Admin |
| Usage near limit (80%, adjustable 50–99%) | business, per entity | once a day at 08:00 | Business Admin |
| Usage at limit (100%) | business, per entity | once a day at 08:00 | Business Admin |
| Usage over limit | business, per entity | once a day at 08:00, repeated daily, Recovered at the new month | Business Admin |
| Version not covered by licence updates | technical | once a day at 08:00 | IT Admin |
| Licence does not cover this product (intake stopped) | technical, **critical** | at once, repeated daily, Recovered when a licence with the product is stored | IT Admin |

Business events are sent for each **activated** entity slot only. Admins can change frequency and recipients within
the usual rules (critical events stay on and go to the IT Admin). Messages contain the event, the entity, counts,
dates and a link into the app; never keys, signatures or prices.

The check runs on every app instance every hour; restarts and several instances never send anything twice. The table
`lic_notice_sent` remembers what was sent (it is created by the licensing schema step; no extra grant is needed
beyond the `lic_` grants in section 1).

| Setting | What to give |
|---|---|
| `platform.licensing.notifications.enabled` | Optional, default `true`. `false` switches licensing notifications off. |
| `platform.licensing.notifications.check-interval` | Optional, default `1h` (1 minute to 24 hours). |
| `platform.licensing.notifications.link` | Optional. The app page messages link to, as a path such as `/admin/licence` (set by the app's own guide). |
| `platform.licensing.notifications.release-version`, `release-date` | Set by the app's release; used for "Version not covered by licence updates". |
