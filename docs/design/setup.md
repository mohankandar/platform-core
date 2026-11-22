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
5. **Separate databases per product by default** (LexaVault and Vero are separate applications with separate users). A customer may instead use one database with a **separate schema per product** (owner, 2026-11-21). Setup runs once per product database or schema with the same input files. The tables still carry `product_code` on grants, so one shared customer/entity record set (e.g. a shared platform schema, or our hosting later) works without redesign: one customer row, one entity row, grants per product.
6. **Setup never deletes customers, entities or slots.** A slot dropped from a newer licence becomes `NOT_IN_LICENCE`. **Group access missing from the mapping file is removed only with the explicit flag `--remove-unlisted-grants`**, and the plan always shows what will be removed first.
7. Each product ships `product-roles.json`; setup reads it with `--product`. No product code inside the command.
8. Demo data uses a **separate development signing key** that production apps never trust; demo-only app records become a separate app demo step after setup, never at startup.
9. **Direct sign-in first** (first customer: Okta, no hub). Hub mode (Keycloak admin REST API, broker secret read from Vault and written through the admin API) only after the hub proof of concept.
10. LexaVault's own `tenant_license_*` licensing is replaced by platform-licensing in its switch-over (no customer has one yet).
11. `platform.licensing.tenant-id` becomes required; setup writes it.

## 2a. Where the database lives (owner, 2026-11-21)

- **The data model is always the same:** customer → entities → each entity's intake, redaction, documents and audit. Every record carries its entity (and so its customer), whatever the deployment. The same code runs everywhere.
- **On-premises:** the customer's own database, one customer per database (setup refuses a second customer, `SETUP_OTHER_CUSTOMER_INSTALLED`).
- **Hosted, standard:** our shared database, customers kept apart by customer/entity ID, like Salesforce. Safeguards: Postgres row-level security as a database-level lock, and encryption keys per customer.
- **Hosted, dedicated database:** a paid premium option for customers who insist (e.g. banks).
- **Not built now** (first release is on-premises). When hosting is scheduled: setup gains a hosted mode that allows many customers; licensing stores one licence per customer in the same database; row-level security is added (its own design task first); apps confirm every query filters by customer.

## 3. Build order

P013 identity storage (platform-core) → P014 setup command, direct mode (platform-core) → LexaVault switch-over → Vero switch-over (adds grants and the entity chooser) → hub proof of concept → setup hub mode.

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

## 6. Integration client bindings (LexaVault thread decision, 2026-11-21)

Registers which machine client may call which entity, for Vero's and LexaVault's own push-API callers (Vero finds the entity and source from the verified client ID via `MachineEntityBindings`; see `docs/design/integration-module.md`).

- **Now:** the setup command loads an input file next to the role mapping: **client ID → entity (slot ID) → source**, safe to rerun, shown in the plan like every other item.
- **Rules:**
  1. The file holds client IDs only, never secrets.
  2. Each client ID binds to exactly one entity and one source; a duplicate is refused.
  3. Setup checks that the entity and source exist and stops with a clear message if not.
  4. Removing a line switches the binding off, never deletes it.
  5. Every change is audited.
- **Later:** the IT Admin's integration page (integration module screens) edits the same bindings. No second store.
