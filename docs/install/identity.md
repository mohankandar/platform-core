# Identity storage — install guide for IT

This page tells your IT team how to set up the identity tables: your customer record, your entities, your login
system and which groups get which roles. The setup command fills them from your licence and input files; the app's
own screens change them afterwards. The app's own install guide says how its schema step is started.

Three rules hold throughout:

- **No secrets.** The tables hold references to where a secret is kept (for example
  `vault:kv/customers/TEN-…/login#client_secret`), never the secret itself. No tokens, passwords or document data.
- **The app never changes the database by itself.** You create the tables with the schema step (section 1). At
  startup the app only checks them.
- **Nothing is deleted.** Customers and entities stay. An entity that a newer licence no longer lists is marked
  `NOT_IN_LICENCE`; nobody can sign in to it, and it comes back as it was if a later licence lists it again (a
  suspended entity comes back suspended, never active).

## 1. Database

The identity tables all start with `idn_`:

| Table | Holds |
|---|---|
| `idn_customer` | Your customer ID (`TEN-…`) and name, from the licence |
| `idn_entity` | Your entity slots (`ENT-…`), each with its entity UUID, status (`NOT_ACTIVATED`, `ACTIVE`, `SUSPENDED`, `NOT_IN_LICENCE`), the name your Business Admin gave it, and who activated it and when |
| `idn_login_connection` | Your login system per product: provider, issuer address, client ID, API audience, a secret reference, status, version. One active connection per product, shared by all your entities |
| `idn_login_connection_history` | Every version of each login connection, with who changed it (setup, the app, a migration) |
| `idn_group_grant` | Which group of your login system gets which role, per entity and product |
| `idn_machine_binding` | Integration client bindings (version 2): which client ID of your login system may send to which entity, as which system, per product; ACTIVE or INACTIVE (never deleted). Loaded by the setup command from the client-binding file (`docs/install/setup.md` section 3a). Client IDs only, never secrets |
| `idn_machine_binding_history` | Every version of each client binding, with who changed it |
| `idn_grant_history` | Every grant added, removed or changed (version 3), by setup or in the app, with who, when and the setup run. Insert only: the database refuses changes and deletes, even by the schema owner |
| `idn_schema_version` | The version of the tables |

1. **Run the identity schema step as its own Flyway run,** apart from the app's other tables:
   - location `classpath:db/platform/identity` (files `V1__identity.sql`, `V2__machine_binding.sql`,
     `V3__grant_history.sql`; a database that ran only the earlier files gets the later ones at the next schema step,
     and the app refuses to start with `IDN_VERSION_OLDER` until then)
   - history table `idn_schema_history`
   - run it as the schema owner, in the same schema the app connects to.
2. **Grant the app's database user** SELECT, INSERT, UPDATE and DELETE on the `idn_` tables, and SELECT on
   `idn_schema_version`. Nothing more is needed (the history tables' ID columns need no sequence grant). On
   `idn_grant_history`, SELECT and INSERT are enough; the table refuses UPDATE and DELETE anyway.

If the tables are missing, at another version, or the rights are short, the app does not start. It names the exact
problem and what to do:

| Code | Means |
|---|---|
| `IDN_TABLES_MISSING` | None of the tables exist: run the schema step |
| `IDN_VERSION_UNKNOWN` | Some tables exist, but `idn_schema_version` is missing or empty |
| `IDN_VERSION_OLDER` | The schema step of this release was not run |
| `IDN_VERSION_NEWER` | The database is newer than the app: install the matching app release |
| `IDN_TABLES_INCOMPLETE` | The version is right but a table is missing |
| `IDN_PRIVILEGES_MISSING` | The app's user lacks a right on a table |
| `IDN_CHECK_FAILED` | The database refused the check itself |

## 2. Entity IDs

Each entity's UUID is worked out from its slot ID in the licence, the same way every time. The same entity therefore
has the same UUID in every product's database and in every installation, and nothing needs to be copied between
databases to keep them in step.

## 3. Separate databases or schemas per product

Each product normally has its own database (or its own schema in one database). Run the identity schema step in each.
Grants carry the product code, so one shared set of tables can also serve both products.

## 4. Activating, suspending and resuming entities

The setup command (`docs/install/setup.md`) stores your entity slots as `NOT_ACTIVATED`. Your Business Admin then
activates and names each one in the app. The app records who did what.

- **Activate** and **resume** need a licence in force that lists the entity: valid, expiring, or within the 30-day
  grace period after expiry. After grace they are refused (`LICENCE_EXPIRED`), and before the licence's start date
  too (`LICENCE_NOT_STARTED`), the same rule as for new work (intake).
- **Suspend** is always allowed. A suspended entity keeps its name and its licence allowance, but nobody can sign in
  to it until it is resumed.
- If a newer licence drops a suspended entity and a later one lists it again, it comes back **suspended**
  (`idn_entity.status_before_removal` remembers it while the entity is `NOT_IN_LICENCE`).

## 5. Time zones per entity

Entities can be separate companies in different places, so each entity may have its own time zone for off-hours
windows, reports, retention timers and notification times.

- **Columns:** `idn_customer.time_zone` (the customer's zone) and `idn_entity.time_zone` (the entity's own zone),
  both IANA names such as `America/New_York`. An entity without its own zone follows the customer's.
- **Setting them:** the setup command stores the defaults the sign-in file gives (`docs/install/setup.md`, section 2).
  After that, the app's settings screen uses `EntityTimeZones` (identity-core): `setEntityZone(slotId, name, actor)`
  (a blank name clears the entity's own zone) and `setCustomerZone(tenantId, name, actor)`. The app audits each call
  from the result.
- **Reading:** `EntityTimeZones.effective(entityUuid)` gives the zone that applies (the entity's own, else the
  customer's); `zone(…)` gives both.
- **Refusals:** a name that is not an IANA name, including offsets such as `+05:00`, gives `TIME_ZONE_INVALID`;
  others are `SLOT_UNKNOWN`, `CUSTOMER_UNKNOWN` and `NOT_AN_ID`. Nothing is stored when a call is refused.
- **Notifications:** an app on `platform-notifications` passes the entity's own zone to the notifier (an
  `EntityZones` bean, or `Notifier.Builder.entityZones`): `(tenant, entity) -> zones.zone(UUID.fromString(
  entity.value())).flatMap(EntityTimeZone::ownZone)`. Events of that entity then use its zone; events without an
  entity, and entities without their own zone, use the tenant's zone from the notification settings.
- The runtime user needs nothing new (UPDATE on `idn_customer` and `idn_entity` is already granted).

## 6. Changing the login system safely: drafts and the test sign-in

Your IT Admin changes the login connection in the app's IT Admin screen without risk to the people signing in. The
active connection keeps working the whole time; the change is prepared, tested, switched on, and can be undone.

1. **Draft.** The admin starts a draft: a copy of the active connection (or, the very first time, a new one) and
   edits it, for example a new issuer address or API audience. There is at most one draft per product.
2. **Test sign-in.** The app then checks the draft against your login system:
   - it reads the issuer's discovery document (`<issuer>/.well-known/openid-configuration`) and its signing keys, over
     the same safe outbound rules as the integrations (https only, your proxy and private certificate authority if
     set, no internal or cloud-metadata addresses, no redirects);
   - optionally, it checks a token the admin pastes or gets from a test sign-in: its signature, issuer, the API
     audience the product expects, that it has not expired, and that it carries the groups claim.

   The token is checked and forgotten: never stored, logged or shown. A pass marks the draft **validated**; any later
   edit makes it a draft again. A failure names the exact cause:

   | Code | Means |
   |---|---|
   | `IDN_TEST_ISSUER_UNREACHABLE` | The discovery document could not be fetched (the reason code says why, for example `INT_TLS_FAILED` or `HTTP_404`) |
   | `IDN_TEST_DISCOVERY_MISMATCH` | The document is not readable, or names another issuer: copy the issuer address exactly |
   | `IDN_TEST_KEYS_MISSING` | No key address, the keys could not be fetched, or no usable signing key |
   | `IDN_TEST_TOKEN_UNREADABLE` | The pasted text is not a signed token |
   | `IDN_TEST_TOKEN_SIGNATURE` | The token's signature does not match the login system's keys |
   | `IDN_TEST_TOKEN_ISSUER` | The token comes from another issuer |
   | `IDN_TEST_AUDIENCE` | The token is not for this product's API audience |
   | `IDN_TEST_EXPIRED` | The token has expired (or is not valid yet: check the server clock) |
   | `IDN_TEST_GROUPS_CLAIM_MISSING` | The token carries no groups: set the login system to send them |

3. **Activate.** Only a validated draft can be activated. It becomes the active connection and the old one is
   switched off, in one step.
4. **Rollback.** If people cannot sign in after the switch, the admin goes back: the previous active version is made
   active again (as a new version). There must be one; drafts are left as they are.

Every step is kept in `idn_login_connection_history` with who and when (source `APP`; the setup command writes
`SETUP`). Two admins never overwrite each other: a step made on an out-of-date screen is refused
(`IDN_VERSION_CONFLICT`); reload and try again. Other refusals: `IDN_DRAFT_EXISTS`, `IDN_NOT_VALIDATED`,
`IDN_NOT_A_DRAFT`, `IDN_NO_PREVIOUS_CONNECTION`, `IDN_VALUES_INVALID`, `IDN_VALUES_REQUIRED`,
`IDN_CONNECTION_UNKNOWN`, `IDN_CUSTOMER_UNKNOWN`, `IDN_HTTP_ISSUER_NOT_ALLOWED`.

**Setup and the screen together.** Running setup again never touches a draft. If the admin switched to another
connection, setup's plan shows `conflict` and leaves it, unless you run it with `--replace-connection`
(`docs/install/setup.md` section 4).

**Local test login servers.** The issuer is your login system's `https` address. Only for testing, a login server on
the same machine may use `http` (`localhost`, `127.0.0.1` or `[::1]`), and only while the licence is a demo or test
licence signed by a development key. Otherwise the connection is refused with `IDN_HTTP_ISSUER_NOT_ALLOWED`; a
production release lists no development key, so there it is always refused.

## 7. Grant history

Every change to which group gets which role is kept in `idn_grant_history`: one row per entity and grant, with the
customer, product, entity, the group (and its login system), the role before and after, the change (`ADDED`,
`REMOVED`, or `CHANGED` when a group's role was swapped in one step), the source (`SETUP` or `APP`), who, when, and
for setup the run ID (as in setup's run log). The table only grows: the database refuses to change or delete its
rows, even for the schema owner. The app's audit page reads it per entity and period; the app's everyday database
user is enough.
