# P013 report — identity storage

Branch `feature/identity-storage` (from `main`), pushed, not merged. Built per `docs/design/setup.md` decisions 1–6.

## Result

- `mvn verify`: **542 tests, 0 failures** (the 504 existing ones, all still passing, plus 38 new). `mvn install`
  ran with the same result.
- The new tests by module: identity-core +13, identity-jdbc 21 (Postgres 16 in Testcontainers), identity-testkit 4.
- No existing test was changed.

## What was built

**New module `platform-identity-jdbc`** (package `com.documentplatform.identity.jdbc`), added to the root
`<modules>` and to `platform-bom`. Plain Java, no Spring.

- **SQL:** `db/platform/identity/V1__identity.sql`, run only by the app's schema step as its own Flyway run (history
  table `idn_schema_history`).
- **Startup check:** `IdentitySchema.verify(DataSource)` only reads. It checks the tables, the version and the DML
  rights. Each refusal has its own code: `IDN_TABLES_MISSING`, `IDN_VERSION_UNKNOWN`, `IDN_VERSION_OLDER`,
  `IDN_VERSION_NEWER`, `IDN_TABLES_INCOMPLETE`, `IDN_PRIVILEGES_MISSING` and `IDN_CHECK_FAILED`. Each message says
  what to do. This matches licensing's check.
- **`PostgresEntityStore`:** customers and slots.
  - `saveCustomer`
  - `syncSlots`: adds new slots as `NOT_ACTIVATED`, marks dropped ones `NOT_IN_LICENCE`, and restores a slot that comes
    back. It never deletes.
  - `activate` / `rename`: lock the customer row, so two Business Admins working at once cannot pick the same name.
  - `activated(tenantId)`: has the same shape as licensing's `ActivatedSlots`, so the app wires `store::activated`.
  - `onConnection(Connection)`: the app can activate a slot and insert its own entity row in one transaction.
- **`PostgresLoginConnectionStore`:** one ACTIVE connection per customer and product.
  - Each insert or update writes a history row (`SETUP` / `APP` / `MIGRATION`).
  - Updates check the expected version (optimistic), so a stale write changes nothing.
- **`PostgresGroupGrantStore`:** grants are keyed by slot, product code, issuer, group and role.
- **`PostgresIdentityLookup(ds, productCode)`:** implements `ActiveEntityCandidates`, `EntityGroupGrants` and
  `OidcConnectionBindingResolver`. The customer's one connection is shared by all its ACTIVE entities.
- **`PostgresMachineEntityBindings`:** uses the app-supplied interface `MachineClients` (client principal → entity
  UUIDs, read from the app's own integration table). It keeps only entities that are ACTIVE and have the connection.
- **`LicenceSlots.of(LicenceService)`:** gives the slots of the licence in force, for activation.

**In identity-core** (no new dependencies):

- `EntityUuid`: derives the UUID from the slot ID, and holds the `TEN-` / `ENT-` ID rules.
- Package `com.documentplatform.identity.store`:
  - the store interfaces and records;
  - `SlotActivation`, the activation API.
- `RoleMappingFile`:
  - accepts slot IDs and a product code;
  - `RoleMappingFile.forSlots(...)` is the reader for setup;
  - a `# Product:` comment must match the product the file is read for.
- `MappingPlan.perProduct(...)`: gives add/remove per entity and product.
- New error codes: `SLOT_ID_REQUIRED`, `SLOT_NOT_IN_LICENCE` and `WRONG_PRODUCT`.
- The old constructors and behaviour are unchanged.

**New module `platform-identity-testkit`:** `InMemoryIdentityStore` holds all the stores and lookups in memory, with
the same rules.

**Docs:**

- new: `docs/install/identity.md`;
- updated: the CLAUDE.md module table, and a note in `SCHEMA_CONTRACT.md`;
- `docs/design/setup.md` was untracked on `main` and is now committed.

## Tables

| Table | Key | Holds |
|---|---|---|
| `idn_schema_version` | version | Version of the files run (1) |
| `idn_customer` | `tenant_id` (`TEN-…`) | Name from the licence |
| `idn_entity` | `slot_id` (`ENT-…`) | `entity_uuid` (unique), `tenant_id`, status `NOT_ACTIVATED`/`ACTIVE`/`SUSPENDED`/`NOT_IN_LICENCE`, `display_name` (unique per customer, ignoring case), `activated_by/at` |
| `idn_login_connection` | `connection_id` | `tenant_id`, `product_code`, provider ID/type, issuer, browser client ID, API audience, `secret_reference` (a reference only; the check refuses a bare value), status, `configuration_version`. One ACTIVE per customer and product |
| `idn_login_connection_history` | identity ID | Every version, with `change_source` and `changed_by` |
| `idn_group_grant` | all five columns | `slot_id`, `product_code`, `issuer_uri`, `external_group`, `role` |

## Tests asked for → where

| Asked for | Test |
|---|---|
| Tables only via the schema run | `IdentitySchemaTest.theTablesComeOnlyFromTheSchemaRun` (the check on an empty schema creates nothing) |
| DML-only user runs everything | `IdentitySchemaTest.theDmlOnlyUserRunsEverythingButCannotChangeTables`. All store tests also run as that user |
| UUID derivation stable | `EntityUuidTest` (pinned values computed outside Java), `slotsGetTheirDerivedUuidAndTheSameUuidInEveryDatabase` |
| One connection shared by two entities | `oneLoginConnectionIsSharedByTheCustomersEntities` (through `EntityMembershipResolver`) |
| Grants separated by product code | `grantsAreSeparatedByProductCode`, `MappingPlanPerProductTest` |
| Activation rules | `SlotActivationTest` (7 tests, with a real `LicenceService`, including two admins at once and the app's own transaction) |
| `NOT_IN_LICENCE` status | `aSlotDroppedFromTheLicenceBecomesNotInLicenceAndComesBackAsItWas`, `slotsNotInTheLicenceAreRefused` |
| Startup refusal codes | `IdentitySchemaTest.startupRefusalCodes` |

The tests found one real bug before commit:

- Postgres refuses regex repetition counts above 255, so the first `secret_reference` check (`\S{1,500}`) failed.
- It is now `\S+` plus a length check.

## Decisions I had to make

1. **The login connection is per customer *and product*.** Each product has its own audience and client ID, and the
   tables may be shared by both products (decision 5). The "one ACTIVE" rule is therefore per (customer, product).
2. **The binding resolver returns a binding only when exactly one connection matches and its customer has exactly one
   ACTIVE entity.**
   - Otherwise it returns empty: the person chooses an entity through `EntityMembershipResolver`, which fails closed.
   - A database error makes it throw, not return empty, so the app never falls back to a configured entity.
3. **Store interfaces and `SlotActivation` are in identity-core (`...identity.store`), not in the jdbc module.** That
   way the testkit does not depend on JDBC, and both versions share one set of rules.
4. **Activation checks the licence in force** (through `LicensedSlots`; `LicenceSlots` adapts `LicenceService`).
   - Refusals: `NO_LICENCE`, `NOT_IN_LICENCE` (also when the stored slot is `NOT_IN_LICENCE`), `SLOT_UNKNOWN` (licensed
     but setup has not stored it yet), `ALREADY_ACTIVATED`, `NAME_INVALID`, `NAME_TAKEN`.
   - Names are trimmed, 1–200 characters, with no control or zero-width characters, and unique per customer ignoring
     case.
   - Outcome messages name the slot ID, never the name that was typed.
5. **A slot that comes back into the licence returns to `ACTIVE` if it had been activated, otherwise to
   `NOT_ACTIVATED`.** A slot that was `SUSPENDED` before it was dropped also comes back as `ACTIVE` (there is no
   column for the earlier status).
6. **`activated()` (for licensing) includes `SUSPENDED` slots.** They were activated, and licence notices still
   concern them.
7. **Mapping files with slot IDs:**
   - The slot does not have to be activated yet, and no active login system is needed. Setup loads grants before the
     Business Admin activates anything, in the same run that stores the connection.
   - `entity_name` may be empty for slot rows only. If given, it must match. UUID rows keep every old rule.
8. **The entity UUID uses the same hash as `StableIdentityId`** ("StableIdentityId-style"). It equals the actor ID for
   issuer `"platform-entity"`. That is not an issuer address, so the two cannot meet; a test documents this.
9. **The jdbc module depends on `platform-licensing`** (plain Java, no runtime dependencies), for `LicenceSlots` only.
   `platform-licensing-spring` is a test-scope dependency only: it proves that `store::activated` fits
   `ActivatedSlots`.
10. **Grants can be removed** (`GroupGrantStore.remove`): the mapping screen and setup's `--remove-unlisted-grants`
    need it. Customers and entities have no delete at all.
11. **The Flyway run is tested by running the SQL files as the schema owner,** as licensing does. No Flyway dependency
    was added.

## Questions

1. **Suspend and resume:** should platform-core provide `suspend` / `resume` for an entity? Only `SUSPENDED` exists
   today, set by hand in tests. *Recommendation:* yes, in P015 with the app's screen, audited like activation.
2. **Expired licence:** may a slot be activated while the licence is expired (in grace or after it)? Today any verified
   licence counts. *Recommendation:* allow it during grace and refuse it after grace, as intake does.
3. **Hub mode and the shared issuer:** all customers then share one issuer and audience, so candidates come from every
   customer, and the grants must not mix groups across customers. *Recommendation:* add the organization claim (the
   customer) to the lookup key in P018, once the hub proof of concept fixes the claim's shape.
4. **A slot `SUSPENDED` before it left the licence comes back `ACTIVE`.** *Recommendation:* accept this for now. Add a
   `status_before_removal` column only if suspension becomes common.
