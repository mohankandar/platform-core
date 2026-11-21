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
| `idn_schema_version` | The version of the tables |

1. **Run the identity schema step as its own Flyway run,** apart from the app's other tables:
   - location `classpath:db/platform/identity` (files `V1__identity.sql`, …)
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
