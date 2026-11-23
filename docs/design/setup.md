# platform-identity storage and platform-setup — design (agreed 2026-11-21)

Source: the owner's Shared capabilities thread (project doc `Setup-Design.md`), based on `docs/work/P012-report.md`. Coding sessions read this copy. Do not change the rules without the owner.

## 1. Words (one meaning everywhere)

| Word | Means | ID |
|---|---|---|
| **Customer** | The company that buys. One licence per customer. In code: `tenantId` | `TEN-…` |
| **Entity** | A legally separate part of the customer (e.g. after a merger), with its own documents, rules and allowance. In the licence: an entity slot | `ENT-…`, plus the app's `entity_uuid` derived from it |

User-facing text says "customer" and "entity", never "tenant". Today's app tables called `tenants` hold **entities**; they are aligned in each app's switch-over.

## 2. Decisions (owner, 2026-11-21)

1. **Shared `idn_` tables owned by platform-core** (`idn_customer`, `idn_entity`, `idn_login_connection` + history, `idn_group_grant`), own Flyway run `db/platform/identity`, history table `idn_schema_history`. Each app keeps its own entity table, created when the Business Admin activates a slot.
2. **`entity_uuid` derived from the slot ID** (name-based UUID from `"platform-entity|" + slotId`): no app column changes; same ID in every install and on hosting.
3. **One login system per customer**, shared by all its entities. After sign-in the person chooses the entity (see 4).
4. **Entity choice (owner, 2026-11-21):** after sign-in the person **lands on the entity they used last**. Everything shown is for that entity only, never mixed. They change entity with a **switcher in the app header** (app UI work, not this module); every switch is audited. The chooser appears only when there is no usable last entity: first sign-in ever, or the last entity is no longer allowed for them. People with only one entity never see a chooser. Once chosen, the entity stays chosen until the person changes it. The current entity's name is always shown in the header, and the result message after a manual upload or scan names the entity ("3 files uploaded to Acme Mortgage"), with **no extra confirmation step**, because one person may hold roles in both entities and a document can never be moved between entities. Watch folders and API intake are tied to one entity in their setup. The "last entity" is kept per person and is not sensitive.
5. **Separate databases per product by default** (for example a redaction app and a document vault are separate applications with separate users). A customer may instead use one database with a **separate schema per product** (owner, 2026-11-21). Setup runs once per product database or schema with the same input files. The tables still carry `product_code` on grants, so one shared customer/entity record set (e.g. a shared platform schema, or our hosting later) works without redesign: one customer row, one entity row, grants per product.
6. **Setup never deletes customers, entities or slots.** A slot dropped from a newer licence becomes `NOT_IN_LICENCE`. **Group access missing from the mapping file is removed only with the explicit flag `--remove-unlisted-grants`**, and the plan always shows what will be removed first.
7. Each product ships `product-roles.json`; setup reads it with `--product`. No product code inside the command.
8. Demo data uses a **separate development signing key** that production apps never trust; demo-only app records become a separate app demo step after setup, never at startup.
9. **Direct sign-in first** (first customer: Okta, no hub). Hub mode (Keycloak admin REST API, broker secret read from Vault and written through the admin API) only after the hub proof of concept.
10. An earlier app's own licensing tables (`tenant_license_*`) are replaced by platform-licensing in its switch-over (no customer has one yet).
11. `platform.licensing.tenant-id` becomes required; setup writes it.

## 2a. Where the database lives (owner, 2026-11-21)

- **The data model is always the same:** customer → entities → each entity's intake, redaction, documents and audit. Every record carries its entity (and so its customer), whatever the deployment. The same code runs everywhere.
- **On-premises:** the customer's own database, one customer per database (setup refuses a second customer, `SETUP_OTHER_CUSTOMER_INSTALLED`).
- **Hosted, standard:** our shared database, customers kept apart by customer/entity ID, like Salesforce. Safeguards: Postgres row-level security as a database-level lock, and encryption keys per customer.
- **Hosted, dedicated database:** a paid premium option for customers who insist (e.g. banks).
- **Not built now** (first release is on-premises). When hosting is scheduled: setup gains a hosted mode that allows many customers; licensing stores one licence per customer in the same database; row-level security is added (its own design task first); apps confirm every query filters by customer.

## 3. Build order

P013 identity storage (platform-core) → P014 setup command, direct mode (platform-core) → the first app's switch-over → the second app's switch-over (adds grants and the entity chooser) → hub proof of concept → setup hub mode.

Full proposal, table columns, input files and command steps: `docs/work/P012-report.md` sections 5.1–5.6, as amended above.

## 4. Decisions from P013

### Owner answers to the P013 questions (2026-11-21)

1. **Suspend and resume:** platform-core provides the API now (`SlotActivation.suspend` / `resume`, audited by the
   caller); the screen comes with the app.
2. **Activation and the licence state:** allowed while the licence is valid, expiring or in grace; refused after grace
   (the same rule as intake) with its own code, `LICENCE_EXPIRED` (and `LICENCE_NOT_STARTED` before the start date,
   also as intake). Resume follows the same rule; suspend is always allowed.
3. **Hub mode, organization claim in the lookup key:** later, with hub mode.
4. **A SUSPENDED slot dropped from the licence comes back SUSPENDED, never ACTIVE:** column
   `idn_entity.status_before_removal` (added to `V1__identity.sql`, not yet released) remembers the status while the
   slot is `NOT_IN_LICENCE`, and it is restored.

### P013 decisions (from `docs/work/P013-report.md`)

1. The login connection is per customer **and product**: one ACTIVE per (customer, product).
2. The binding resolver returns a binding only when exactly one connection matches and its customer has exactly one
   ACTIVE entity; otherwise the person chooses through `EntityMembershipResolver` (fails closed). A database error
   throws; the app never falls back to a configured entity.
3. Store interfaces and `SlotActivation` live in identity-core (`com.documentplatform.identity.store`), so the testkit
   needs no JDBC and both versions share one set of rules.
4. Activation checks the licence in force (`LicensedSlots`; `LicenceSlots` adapts `LicenceService`). Refusals:
   `NO_LICENCE`, `NOT_IN_LICENCE`, `SLOT_UNKNOWN`, `ALREADY_ACTIVATED`, `NAME_INVALID`, `NAME_TAKEN`. Names are
   trimmed, 1–200 characters, no control or zero-width characters, unique per customer ignoring case; messages name
   the slot ID, never the typed name.
5. A slot that comes back into the licence returns to the status it had (amended by owner answer 4: SUSPENDED stays
   SUSPENDED).
6. `activated()` (for licensing) includes SUSPENDED slots.
7. Mapping files with slot IDs: the slot need not be activated and no active login system is needed (setup loads
   grants before activation). `entity_name` may be empty for slot rows only; if given it must match.
8. The entity UUID uses the same hash as `StableIdentityId`; it equals the actor ID for issuer `"platform-entity"`,
   which is not an issuer address, so the two cannot meet.
9. platform-identity-jdbc depends on platform-licensing (plain Java), for `LicenceSlots` only.
10. Grants can be removed (`GroupGrantStore.remove`); customers and entities have no delete.
11. The Flyway run is tested by running the SQL files as the schema owner; no Flyway dependency.

## 5. Decisions from P014

### Owner answers to the P014 questions (2026-11-21)

1. **Where `trusted-keys.json` and `product-roles.json` come from:** yes, each product ships a `setup` wrapper script
   that passes `--keys` and `--product-roles`.
2. **Suspending a `NOT_IN_LICENCE` slot:** keep it refused.
3. **Exit code 3 when grants are not removed** (no flag): keep it.
4. **Grants under an older issuer:** yes, list them as "not in the file", in the hub-mode task.
5. **One customer per database:** keep for on-premises installs. Hosting later uses a shared database with row-level
   security (section 2a).
6. **Exit code 1 for failures:** keep it.

### Rules setup follows (P014, stated in P039)

- **"Not in the file" (grants).** The role mapping file is the customer's complete grant list for the product and the
  sign-in file's issuer. Every stored grant of the customer for that product and issuer that the file does not list is
  "not in the file", also when the file does not name its slot at all (every row of a slot dropped). Such grants are
  listed as `not removed` (exit code 3) and removed only with `--remove-unlisted-grants`.
- **Grants under another issuer** (for example after the customer moved to a new login system) are left alone: not
  listed, not removed, even with `--remove-unlisted-grants`. Listing them is for the hub-mode task (answer 4 above).
- **Slots of a waiting renewal.** A licence that starts later is stored as the waiting renewal, and its slots are
  stored at once (`NOT_ACTIVATED`), so the mapping and client-binding files may already name them. Activating a slot
  still follows the licence in force.
- **"Changed in the app" (login connection).** Setup compares the customer's connection for the product (the ACTIVE
  one, else the DISABLED one changed last; drafts are the app's) with the sign-in file. Equal values are `unchanged`.
  Different values are an update when the last history row is setup's own; otherwise the connection was changed in
  the app and it is a `conflict` (exit code 3), left as it is until `--replace-connection` is given. A connection the
  app DISABLED is such a change too: setup does not switch it back on without `--replace-connection`.

## 6. Integration client bindings (decision of an app's design thread, 2026-11-21)

Registers which machine client may call which entity, for the apps' own push-API callers (an app finds the entity and source from the verified client ID via `MachineEntityBindings`; see `docs/design/integration-module.md`).

- **Now:** the setup command loads an input file next to the role mapping: **client ID → entity (slot ID) → source**, safe to rerun, shown in the plan like every other item.
- **Rules:**
  1. The file holds client IDs only, never secrets.
  2. Each client ID binds to exactly one entity and one source; a duplicate is refused.
  3. Setup checks that the entity and source exist and stops with a clear message if not.
  4. Removing a line switches the binding off, never deletes it.
  5. Every change is audited.
- **Later:** the IT Admin's integration page (integration module screens) edits the same bindings. No second store.

## Time zones (P020)

The sign-in file may give the customer's time zone (`timeZone`) and a default zone per entity slot
(`entityTimeZones`), as IANA names. Setup stores each into `idn_customer.time_zone` / `idn_entity.time_zone` only
where none is stored yet, in the same transaction as the slots; a zone already stored (by the app or an earlier run)
is kept and shown as `unchanged … kept`. Rerun safe. Refusals: `SETUP_TIME_ZONE_INVALID` (not an IANA name, checked
when the file is read) and `SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE` (checked in the plan). Both are optional fields of
`platform-signin/1`; files without them work as before.

Owner answer to P020 question 3 (recorded in P021): `timeZone` becomes **required** at the next format change of the
sign-in file (`platform-signin/2`). Not now: `platform-signin/1` keeps it optional, and an app falls back to its
notifications tenant zone until identity has one.

### 6.1 Client-binding input file: final format (2026-11-22)

Fixed now because an app builds its own loader to the same format (on Enterprise Identity 0.3.0) before it moves to platform-core.

- **File:** `client-bindings-<product>.csv`, UTF-8, comma-separated, one product per file, named in the sign-in file per product as `"clientBindingsFile"` (optional; no file = no machine callers for that product).
- **Header (exact, required):** `client_id,entity_slot,source,description`
- **Columns:**
  - `client_id`: the verified token's client principal exactly as the customer's login system issues it (e.g. the Okta client ID). 1–255 characters, no spaces, commas, quotes or control characters. Compared as given (case-sensitive).
    **Additive (P028):** a client ID `cert-sha256:<64 lowercase hex>` is the SHA-256 fingerprint (DER) of a caller's client certificate; such a caller needs no token. Two lines with two fingerprints for the same entity and source are a rotation overlap. The reader and its rules are unchanged; setup refuses a `cert-sha256:` value of another form (`CLIENT_ID_INVALID`) and marks these lines `(client certificate)` in the plan.
  - `entity_slot`: the licence slot ID (`ENT-…`) the client may deliver to. Must be a slot of this customer's licence (it need not be activated yet; it is used only once ACTIVE).
  - `source`: the product's sending-system key, `[a-z0-9][a-z0-9._-]{0,63}`. Must be listed in the sign-in file under the product's `"sources"` (optional list of `{ "key": "…", "name": "…" }`; added to `platform-signin/1` as an optional field, so older files still work).
  - `description`: optional free text for people (up to 200 characters); never shown to callers; no secrets.
- **Rules:** no secrets in the file; each `client_id` appears **once per product** (a duplicate line, or a client already bound to another entity or source, is refused); entity slot and source must exist, else setup stops with a clear message naming the line; a line removed from the file switches that binding **off** (never deleted); every change is in setup's plan and run log and in the binding history.
- **Where it lands:** a shared table `idn_machine_binding` (customer, product, client ID, entity UUID derived from the slot, source key, status ACTIVE/INACTIVE, who/when) with history. platform-identity-jdbc gets a built-in `MachineClients` over it, so `PostgresMachineEntityBindings` works without an app-supplied table. The IT Admin's integration page later edits the same table (no second store).
- **The connection:** a binding applies under the customer's ACTIVE login connection for that product (`ConnectionKey`), as `PostgresMachineEntityBindings` already checks.
- **Reader details (P029, binding for that app's own loader too):** both `"` and `'` count as quotes and are refused in a client ID; there are **no comment lines** (a line starting with `#` is a client ID); spreadsheet quoting is accepted in the description; a byte-order mark, CRLF and empty lines are accepted. A value **looks like a secret** (refused, never repeated in the message) when it is a JWT, a PEM block, a word such as `password`/`secret` followed by `=` or `:`, or a run of 40+ key-like characters mixing upper case, lower case and digits. Problems are reported as `line N: CODE: …` (`HEADER_WRONG`, `DUPLICATE_CLIENT_ID`, `SOURCE_UNKNOWN`, `SLOT_UNKNOWN`, `CLIENT_BOUND_ELSEWHERE`, `SECRET_LIKE`, …). The shared reader is `identity.mapping.ClientBindingsFile` (identity-core); the app uses it when its loader moves to platform-core.
- **Decided on the P029 report (2026-11-22):** no `clientBindingsFile` switches every binding of the product off (the plan lists each `switch off` first; nothing deleted). Moving a client to another entity or source takes two runs (remove, run; add, run); a `--move-bound-clients` flag only if customers find it clumsy. The table is added by identity **V2** (`V2__machine_binding.sql`); a database with only V1 is refused with `IDN_VERSION_OLDER` until the schema step runs V2.

**Interface check (2026-11-22):** `MachineEntityBindings`, `EntityId` and `ConnectionKey` are identical between Enterprise Identity 0.3.0 (tag `v0.3.0`) and platform-core (only line endings differ); the Java package `com.documentplatform.identity.admission` is unchanged. Only the Maven coordinates differ (`com.documentplatform.identity:enterprise-identity-core` → `com.documentplatform.platform:platform-identity-core`).

## 7. P031 (2026-11-22): drafts, test sign-in, local test issuers, aliases, grant history, keys guard

Decided by an app's design chat; built in P031 (`docs/work/P031-report.md`).

- **Login-connection drafts** (`LoginConnections`, identity-core): draft (copy of the ACTIVE connection, or the
  first one), test sign-in (`OidcTestSignIn` over an `IssuerFetch`; production: `OutboundIssuerFetch`, since P028 in
  platform-identity-spring on platform-outbound), activate (VALIDATED only; old ACTIVE → DISABLED in one
  transaction), rollback (previous ACTIVE values as a new version). At most one DRAFT or VALIDATED per customer and
  product (index in identity V3). Setup reads only ACTIVE/DISABLED connections; drafts are the app's.
- **Local test issuers:** `http` only on `localhost`, `127.0.0.1`, `[::1]` and only under a licence signed by a key
  marked `"development": true` in `trusted-keys.json` (`platform-licence-keys/1` gains that optional field).
  `SETUP_HTTP_ISSUER_NOT_ALLOWED` / `IDN_HTTP_ISSUER_NOT_ALLOWED`.
- **Role aliases** in `platform-product-roles/1` (`"aliases"`), resolved when the mapping file is read; stored as the
  role.
- **Grant history** `idn_grant_history` (identity V3), insert only (triggers), written by setup (with its run ID) and
  by `GroupGrantStore`; read with `GrantHistory.forEntity`.
- **Customer-keys guard:** `"customerKeys": "required"` in `product-roles.json` refuses `--no-customer-keys`
  (`SETUP_CUSTOMER_KEYS_REQUIRED`).

## 8. P028 (2026-11-22): push-API callers and platform-wide grants

Built in P028 (`docs/work/P028-report.md`).

- **Push-API callers** (`identity.push.PushCaller`, identity-core; Postgres `PostgresPushCallerDirectory`, in-memory
  `InMemoryIdentityStore.pushCallerDirectory()`; Spring bean from `platform.identity.push.*`). An app's push API has
  verified the caller's token or client certificate; the controller asks who the caller is. Token: issuer → the
  customer's ACTIVE connection for the product; audiences must hold its `apiAudience`; client ID from the configured
  claims (default `client_id`, `azp`, `cid`, `sub`); the client's binding under that customer ACTIVE, its entity
  ACTIVE; optional entity and source claims must equal the binding when present. Certificate: the binding
  `cert-sha256:<hex>` (section 6.1, additive), the customer's connection ACTIVE. Both (or `requireTokenAndCertificate`):
  the same customer, entity and source. Refusals `IDN_PUSH_*` (neutral to the caller); answers kept 30 s per caller;
  a database error throws. `admission.MachineSourceBindings` (entity and source) beside `MachineEntityBindings`
  (unchanged).
- **Platform-wide grants.** A role-mapping row with `entity_id` `*` grants one of the product's `platformRoles` to a
  group for the customer and product (`SETUP_ENTITY_ROLE_PLATFORM_WIDE` for an entity role on it,
  `SETUP_PLATFORM_ROLE_ON_ENTITY` for a platform role on an entity row). Stored in `idn_platform_grant` (identity
  **V4**); `idn_grant_history` gains `scope` (ENTITY or PLATFORM; PLATFORM rows have no entity). Setup plans, applies,
  removes (only with `--remove-unlisted-grants`) and records them like entity grants. `PlatformGroupAuthorization`
  gives a signed-in person's platform roles from `PlatformGroupGrants` (customer's ACTIVE connection and product);
  `EntityGroupAuthorization` keeps refusing platform roles through entity grants.
