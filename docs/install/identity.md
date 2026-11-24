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

All identity codes (`IDN_*`, the test sign-in's `IDN_TEST_*`, push callers' `IDN_PUSH_*`), with their message, cause and
what to do, are in the generated catalogue `docs/errors/platform-error-codes.md`.

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
| `idn_platform_grant` | Platform-wide grants (version 4): which group gets which platform role (for example an operations admin) in every entity, per product. Loaded by the setup command from the role mapping file's `*` rows (`docs/install/setup.md` section 3) |
| `idn_grant_history` | Every grant added, removed or changed (version 3; since version 4 also platform-wide grants, scope `PLATFORM`), by setup or in the app, with who, when and the setup run. Insert only: the database refuses changes and deletes, even by the schema owner |
| `idn_schema_version` | The version of the tables |

1. **Run the identity schema step as its own Flyway run,** apart from the app's other tables:
   - location `classpath:db/platform/identity` (files `V1__identity.sql`, `V2__machine_binding.sql`,
     `V3__grant_history.sql`, `V4__platform_grants.sql`; a database that ran only the earlier files gets the later ones
     at the next schema step,
     and the app refuses to start with `IDN_VERSION_OLDER` until then)
   - history table `idn_schema_history`
   - run it as the schema owner, in the same schema the app connects to.
2. **Grant the app's database user** SELECT, INSERT, UPDATE and DELETE on the `idn_` tables, and SELECT on
   `idn_schema_version`. Nothing more is needed (the history tables' ID columns need no sequence grant). On the
   history tables `idn_grant_history`, `idn_login_connection_history` and `idn_machine_binding_history`, SELECT and
   INSERT are enough; they refuse UPDATE, DELETE and TRUNCATE anyway (the last two since schema version 5, 0.7.0).

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
     the platform's safe outbound rules (`platform.outbound.*`, `docs/install/integration.md` section 1: https only,
     your proxy and private certificate authority if set, no internal or cloud-metadata addresses, no redirects).
     The app needs only the outbound settings for this, not the integrations module;
   - optionally, it checks a token the admin pastes or gets from a test sign-in: its signature, issuer, the API
     audience the product expects, that it has not expired, and that it carries the groups claim.

   The token is checked and forgotten: never stored, logged or shown. A pass marks the draft **validated**; any later
   edit makes it a draft again. A failure names the exact cause:

   | Code | Means |
   |---|---|
   | `IDN_TEST_ISSUER_UNREACHABLE` | The discovery document could not be fetched. The report's reason (not a code of its own) says why: an outbound code such as `OUT_TLS_FAILED`, `HTTP_<status>` such as `HTTP_404`, `FETCH_FAILED` (no answer could be read) or `TOO_LARGE` (over the size limit) |
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
`IDN_CONNECTION_UNKNOWN`, `IDN_CUSTOMER_UNKNOWN`, `IDN_HTTP_ISSUER_NOT_ALLOWED`. A test sign-in that does not pass
is `IDN_TEST_FAILED`: its report names the check that failed (one of the `IDN_TEST_*` codes above), and the draft is
(again) a draft. Correct it and test again.

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

Since version 4 the same table also holds the **platform-wide grants** (section 8): those rows have the scope
`PLATFORM` and no entity. The app's audit page reads them per customer, product and period.

## 8. Platform-wide grants (platform roles)

Some roles work across every entity of your organisation, for example a platform operations admin. The product lists
them as its platform roles. They are never granted per entity: a row of the role mapping file with `*` as its
`entity_id` grants one of them to a group for your whole organisation and this product (`docs/install/setup.md`
section 3). Setup stores those rows in `idn_platform_grant` (schema version 4).

When someone signs in, the app asks which platform roles their groups have under your active login connection for
the product. Nobody gets a platform role through an entity's grants, and an entity role is never platform-wide.

## 9. Push-API callers (other systems that send to the product)

Some of your systems send documents to the product's own API (a push). The product's server checks the caller's token
(signed by your login system) or its client certificate, as usual. Then it asks the identity tables who the caller is:
which entity it sends for, and as which of your systems (its source). The answer comes from the client-binding file
that setup loads (`docs/install/setup.md` section 3a).

**How the caller is found.**

- **With a token:** the token's issuer must be your active login connection for the product, and the token must be
  for the product's API audience. The client ID is read from the token's claims, in this order by default:
  `client_id`, `azp`, `cid`, `sub` (login systems differ; the first one present is used). That client ID must be
  bound (and switched on) to an active entity.
- **With a client certificate:** the client ID in the client-binding file is `cert-sha256:` followed by the
  certificate's SHA-256 fingerprint. No token is needed. Your login connection for the product must still be active.
- A product may require **both**. Then the token and the certificate must point to the same entity and source.

**Settings** (the product's install config):

| Property | Default | |
|---|---|---|
| `platform.identity.push.product-code` | none | the product; without it there is no push-caller check |
| `platform.identity.push.client-id-claims` | `client_id,azp,cid,sub` | the claims the client ID is read from, first present wins |
| `platform.identity.push.entity-claim` | none | optional: a claim that, when the token has it, must name the bound entity (its `ENT-…` slot ID or entity UUID) |
| `platform.identity.push.source-claim` | none | optional: a claim that, when the token has it, must name the bound source |
| `platform.identity.push.require-token-and-certificate` | `false` | both a token and a client certificate |
| `platform.identity.push.cache-for` | `30s` | how long an answer is kept (0 seconds to 10 minutes) |

**Switching a caller off.** Remove its line from the client-binding file and run setup (or switch it off in the IT
Admin's integration page). The change takes effect within the cache time (`cache-for`, 30 seconds by default).

**Refusals.** The calling system only hears that it is not allowed. The product's log, and its IT Admin, get the
exact cause (and a certificate's fingerprint; no token or certificate content):

| Code | Means | What to do |
|---|---|---|
| `IDN_PUSH_NO_CONNECTION` | No active login connection for the product with the token's issuer (or, for a certificate, for your organisation) | Check the issuer the sending system's token comes from, and that the connection is active |
| `IDN_PUSH_AUDIENCE` | The token is not for the product's API audience | Have the login system issue the sending system's tokens for that audience |
| `IDN_PUSH_NO_CLIENT_ID` | The token has none of the client ID claims (or no token where both are required) | Check the claim settings above |
| `IDN_PUSH_NOT_BOUND` | The client ID is not in the client-binding file | Add its line and run setup |
| `IDN_PUSH_BINDING_OFF` | The binding is switched off | Add the line back, if the system should send again |
| `IDN_PUSH_ENTITY_NOT_ACTIVE` | The bound entity is not active (not activated yet, suspended, or no longer in the licence) | Have the Business Admin activate or resume it |
| `IDN_PUSH_CLAIM_MISMATCH` | The token names another entity or source than the binding | Correct the binding, or the sending system's token |
| `IDN_PUSH_CERT_NOT_BOUND` | The certificate's fingerprint is not in the client-binding file (or no certificate where both are required) | Add a `cert-sha256:` line (`docs/install/setup.md` section 3a) |
| `IDN_PUSH_TOKEN_AND_CERT_DIFFER` | The token and the certificate are bound to different entities or sources | Bind both to the same entity and source |

**A certificate forwarded by a proxy.** If a proxy in front of the product ends TLS and passes the client certificate
on in a header, the product reads that header only from its own proxy (the product's install guide says how). The
platform itself never reads such a header.

The app's database user needs nothing new for this: it only reads `idn_login_connection`, `idn_machine_binding` and
`idn_entity`.

## 10. For app developers: what identity does, and apps that keep their own tables

This section is for the app's developers, not for IT. It holds the still-true parts of two older notes (the
integration notes and the schema contract for entity admission), which were removed in P040.

**Where identity stops.** The shared identity modules stop at signing people in and normalising who they are. They
do not know an app's roles beyond the names its `EntityRoleCatalog` gives, nor its business permissions, which source
a person may use, or which intake source may send what. Each app keeps its own authorization and its own release
cadence. The normalised identity (`AuthenticatedIdentity`) holds: provider ID, issuer, subject, user name, e-mail,
display name, groups and the raw claims (which are never logged).

**Apps that keep their own tables.** platform-identity-jdbc implements the admission interfaces over the `idn_` tables
(section 1). An app that still keeps its own tables until its switch-over implements the same four interfaces of
`com.documentplatform.identity.admission` over them. Table and column names are the app's choice; this is what they
must hold. Values are compared exactly as stored: issuer, provider ID, audience, group names and client principals
are not case folded, trimmed of slashes or normalised (see `ConnectionKey`).

| Interface | What the tables must hold | Answers |
|---|---|---|
| `ActiveEntityCandidates` | Entity ID (UUID) and status (only an ACTIVE entity is a candidate); its login connection: entity ID, provider ID, issuer, API audience and status, ACTIVE and matching the key exactly; at most one ACTIVE connection per entity. Several entities may share one login system, so (provider ID, issuer, audience) is **not** unique across entities. | `find`: the IDs of ACTIVE entities with an ACTIVE connection for the key; an empty set for a blank provider ID or audience; `null` only when the store cannot be read. |
| `EntityGroupGrants` | One row per group-to-role grant in one entity: entity ID, issuer, group, role name. Joined to the ACTIVE connection on entity ID **and** issuer, so a grant recorded for another issuer never applies. Group names exactly as the login system sends them (no case folding or path stripping; for Keycloak, turn "full group path" off). Only role names the app's `EntityRoleCatalog` knows; an unknown name refuses the grant. | `activeGrants`: entity → (group → role names); an empty map for a blank provider ID or audience; `null` only when the store cannot be read. |
| `MachineEntityBindings` | Entity ID, an enabled flag and the client principal of each machine integration that signs in with a token, joined to the ACTIVE connection and ACTIVE entity as for `ActiveEntityCandidates`. | `find`: the matching entity IDs; an empty set for a blank provider ID, audience or client principal. The machine is admitted only when exactly one entity is returned, so a client principal must be unique across entities. |
| `EntityRoleCatalog` | No table. The app names its roles: `canonicalEntityRole` maps a configured name to the canonical one (empty for an unknown name), `aliasTarget` resolves aliases, and `isPlatformRole` names the roles that reach platform-wide screens. A platform role is never granted through an entity's groups; since P028 it is granted platform-wide (section 8). | — |
