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
| `idn_schema_version` | The version of the tables |

1. **Run the identity schema step as its own Flyway run,** apart from the app's other tables:
   - location `classpath:db/platform/identity` (files `V1__identity.sql`, `V2__machine_binding.sql`; a database
     that ran only V1 gets V2 at the next schema step, and the app refuses to start with `IDN_VERSION_OLDER` until
     then)
   - history table `idn_schema_history`
   - run it as the schema owner, in the same schema the app connects to.
2. **Grant the app's database user** SELECT, INSERT, UPDATE and DELETE on the `idn_` tables, and SELECT on
   `idn_schema_version`. Nothing more is needed (the history table's ID column needs no sequence grant).

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
