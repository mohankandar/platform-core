# P012 report — setup command: investigation and proposal

Read-only. No repository was changed apart from these two files in platform-core, and no migration, build or
container was run. The LexaVault working tree is checked out on another branch (`feature/0028-export-followups`) and
has uncommitted changes. So `feature/lexavault-r1b-tenant-identity` (tip `671fdcd`) was read only through git objects
(`git show`, `git grep`, `git ls-tree`), without a checkout.

## Summary

- **No product has a customer level today.** In both apps an entity *is* a tenant, with a UUID key. The licence has
  one tenant per customer (`TEN-…`) and entity slots (`ENT-…`). The apps' word "tenant" therefore means the licence's
  *slot*, not the licence's *tenant*. This is the main conflict, and the proposal below resolves it.
- **Both apps insert rows at start-up**, which the approach forbids:
  - Both have `OidcConnectionBootstrap`, which writes the login connection.
  - LexaVault also has `LocalDemoBootstrap`, which writes the demo tenant, capabilities, rules and scanner connection.
  - SDV seeds tenants through a Liquibase `local` context.
- **Neither app uses platform-core yet.**
  - Both depend on the older `com.documentplatform.identity:enterprise-identity-*`: LexaVault on 0.3.0, SDV on 0.2.0.
  - Neither uses platform-licensing or platform-notifications. LexaVault has its own licensing (changelog 029).
  - Both use **Liquibase**. platform-core's tables use separate **Flyway** runs (`lic_schema_history`, the notifications
    equivalent).
- **The login hub has nothing reproducible.**
  - The everyday Keycloak (`lexa-keycloak`, 26.8.2, realm `document-platform-local`) was set up by hand. Its compose
    file lives outside every repo.
  - The only scripted Keycloak is LexaVault's Phase 1 test environment: realm import plus a few admin REST calls.
  - Organizations and brokering to customer login systems are design only; the hub proof of concept is not done.
- **The first customer (Okta, on-premises) signs in directly, with no hub** (LexaVault design, section 6). The setup
  command must therefore work without Keycloak; loading the hub is a second mode.

## 1. platform-identity (this repo)

**It owns no tables and no migrations, by design** (`SCHEMA_CONTRACT.md`: "The library owns no tables").

It holds types, rules and interfaces that the product implements:

| What | Class | IDs |
|---|---|---|
| Login connection | `OidcConnectionBinding(connectionId, tenantId, providerId, issuer, audience, configurationVersion)` and `OidcConnectionBindingResolver.findActive(providerId, issuer, audience)` | `UUID` |
| Entity | `admission.EntityId(UUID)` | `UUID` |
| Entities with an ACTIVE connection | `ActiveEntityCandidates.find(ConnectionKey)` | — |
| Group-to-role grants | `EntityGroupGrants.activeGrants(key)`, giving entity → group → roles | — |
| Roles | `EntityRoleCatalog` (the product's role names) | — |
| Admission | `EntityMembershipResolver`, `EntitySelection`, `TrustedEntityAdmission`, `MachineEntityAdmission`, `SharedIssuerPreflight` | — |
| Role mapping file | `mapping.RoleMappingFile`, CSV header `entity_id,entity_name,group,role` (entity_id is a UUID), `MappingPlan.between(rows, current)` giving add/remove per entity | `UUID` |
| Actor ID | `StableIdentityId.fromIssuerAndSubject` (name-based UUID from SHA-256) | — |

**Customers:** there is no customer type. The only "tenant" is `OidcConnectionBinding.tenantId`, which is the entity's
UUID.

`SCHEMA_CONTRACT.md` describes the tables a product must provide:

- entities with a status;
- connections, at most one ACTIVE per entity, and no uniqueness of the issuer across entities;
- grants keyed by entity and issuer;
- machine bindings.

Related platform-licensing facts:

- `Licence.tenantId` and `EntitySlot.slotId` are strings (`TEN-`/`ENT-` plus 16 Crockford base32 characters).
- `LicenceService.store` is already idempotent ("storing the same licence again changes nothing").
- `ActivatedSlots.activated(tenantId)` maps slot ID → the app's entity ID, as strings.
- `platform.licensing.tenant-id` is optional today. Owner answer Q2 says it becomes required in this task's build.

## 2. The apps today

### LexaVault: `C:\dev\lexavault\secure-document-platform`, branch `feature/lexavault-r1b-tenant-identity`

**Schema:** Liquibase 4.28, `backend/securedoc-infrastructure/src/main/resources/db/changelog/db.changelog-master.xml`,
default history table `databasechangelog`. There is no separate schema step.

**Tables:**

- `001-initial-schema.xml`: `tenants(id UUID, name, slug unique, status, …)` and `tenant_capabilities`.
- `029-tenant-licensing.xml`: `tenant_license_records` and `tenant_license_assignments`. This is LexaVault's own
  licence per tenant.
- `032`–`034`: `enterprise_identity_connections`:
  - columns `connection_id, tenant_id, provider_id, provider_type, issuer_uri, browser_client_id, api_audience, secret_reference, status DRAFT/VALIDATED/ACTIVE/DISABLED, configuration_version`;
  - one ACTIVE and one staged connection per tenant;
  - a `…_history` table.
- `036-r1b-tenant-oidc-group-grants.xml`: `tenant_oidc_group_grants(tenant_id, issuer_uri, external_group, product_role)`,
  primary key on all four columns. Product code only reads this table; only test SQL writes it.
- `037` drops the uniqueness of the issuer across tenants. It is **not** in the product master; only the Phase 1 test
  changelog applies it.

**Persistence:** JPA `TenantEntity`/`JpaTenantRepositoryAdapter`. The identity tables use JDBC, in
`backend/securedoc-infrastructure/.../infrastructure/identity/`: `JdbcOidcConnectionBindingRepository`,
`JdbcTenantOidcGroupGrantRepository`, `JdbcActiveOidcTenantCandidates`, `JdbcActiveOidcMachineTenantBindings`.

**How rows are created:**

- `backend/securedoc-api/.../api/config/LocalDemoBootstrap.java` is an `ApplicationRunner` in profile `local`. It
  inserts:
  - the demo tenant `00000000-0000-0000-0000-000000000001` (slug `local-demo`);
  - 7 capabilities, identifiers, a redaction policy and a "Local Scanner Bridge" integration.
- `backend/securedoc-api/.../security/oidc/OidcConnectionBootstrap.java` is an `ApplicationRunner` with **no profile**:
  - it runs in OIDC mode, with `persistent-connection-enabled` true by default;
  - it inserts an ACTIVE connection "Bootstrap OIDC connection" when the tenant has none.
- `test-tools/phase1-shared-issuer/db/phase1-seed.sql` (run by `Start-Phase1.ps1`, database `lexavault_phase1` only)
  inserts:
  - 2 tenants, `a1a1a1a1-0000-4000-8000-00000000000a` and `b2b2b2b2-…-00000000000b`;
  - their connections;
  - 6 grants.
- The admin API `/api/v1/admin/enterprise-identity` manages connections only. There is no endpoint that creates tenants
  or writes grants.

**Current tenant per request:** `api/context/RequestContextResolver.java` via `TenantBoundaryInterceptor`.

- **Local mode:** headers `X-Tenant-Id` and `X-Actor-Id`.
- **OIDC, single tenant:** the binding's `tenantId`, then the `tenant-id` setting, then the `tenant_id` claim. Roles
  come from `role-mappings` in config.
- **Shared-issuer mode** (`shared-issuer-tenant-selection-enabled`, off by default):
  - `EntityMembershipResolver` over the grants table;
  - the `X-Selected-Tenant-Id` header, checked by `EntitySelection`;
  - `GET /api/v1/me/tenants` for the chooser.
- `TenantContext(TenantId, slug)`.

### Vero/SDV: `C:\dev\sdv\secure-document-vault`, branch `release/vero-r7`

**Schema:** Liquibase, `backend/sdv-web/src/main/resources/db/changelog/db.changelog-master.yaml`, default history
tables.

- A real separate schema step exists: `backend/sdv-web/.../web/schema/SchemaStep.java` (`status` / `sql --output` /
  `update`, context fixed to `production`). Its wrappers are `Update-SDV-Schema.ps1` and `update-sdv-schema.sh`.
- Liquibase runs at start-up only in the `local` profile.
- `DatabaseSchemaCheck` keeps the app's readiness check failing until the schema matches.

**Tables:**

- `001-foundation-core.sql`:
  - `tenants(tenant_id uuid, tenant_key varchar(80) unique, display_name, status ACTIVE/SUSPENDED, …)`;
  - `ingestion_sources` (machine clients, `credential_reference` is a label).
- `014`–`016`: `enterprise_identity_connections`, the same shape as LexaVault's. `secret_reference` is always written as
  `null`.
- `011`: `tenant_role_capability_overrides` (role → capability per tenant).
- `025`: `role_group_mappings_seen` and `role_mapping_notices`.
- **There is no group-to-role table.** Group mapping is configuration only (`SDV_AUTH_GROUP_*` env vars, read by
  `SdvGroupRoleMapper`).

**How rows are created:**

- `002-local-foundation-seed.yaml` (context `local`) inserts tenants `11111111-1111-1111-1111-111111111111`
  (`local-demo`) and `22222222-…` (`local-isolation`).
- `backend/sdv-web/.../web/security/OidcConnectionBootstrap.java` inserts an ACTIVE connection at start-up, like
  LexaVault's.
- `OidcKnownGoodRecovery` can reactivate a connection.
- `Seed-SDV-Canvas-Demo.ps1` and most `Test-SDV-*.ps1` scripts hard-code tenant `1111…`.
- `Test-SDV-CleanInstall.ps1` asserts that a production install has 0 tenants.

**Current tenant per request:**

- **Local mode:** `LocalHeaderAuthenticationFilter`, headers `X-SDV-Tenant-Id`, `X-SDV-Subject`, `X-SDV-Roles`.
- **Enterprise mode:** `EnterpriseJwtAuthenticationConverter`:
  - machine tokens take the `sdv_tenant_id` claim;
  - people: the binding's `tenantId`, then `sdv.auth.jwt.tenant-id`, then the `sdv_tenant_id` claim.
- SDV has only one tenant per sign-in, with no entity chooser. It uses only `OidcConnectionBindingResolver` and
  `CachingOidcConnectionBindingResolver` from the library.

## 3. The login hub

- **`lexa-keycloak` + `lexa-keycloak-db`** (Keycloak 26.8.2, port 8181, realm `document-platform-local`):
  - It was set up by hand (LexaVault `docs/work/0003-report.md`).
  - Its compose file is inside WSL, not in any repository. There is no realm export and no script.
  - Clients referenced: `lexavault-web` with audience `lexavault-api`, and `sdv-web` with audience `sdv-api`
    (`Start-LexaVault-OIDC.ps1`, `Start-SDV-OIDC.ps1`, `.env.oidc-demo.example` in both UIs).
  - Both repos' `CLAUDE.md` list it as protected development data.
- **LexaVault Phase 1 test environment**, `test-tools/phase1-shared-issuer/` (the same files are in
  `C:\dev\lexavault\sdp-ui`):
  - **`compose.yml`:** Keycloak 26.8.2 `start-dev --import-realm` on 127.0.0.1:18181, plus a throwaway Postgres.
  - **`keycloak/lexavault-phase1-realm.json`:**
    - groups `lv-a-staff`, `lv-a-admin`, …;
    - public clients only (PKCE web client, a password-grant test client, and wrong-audience and no-audience clients);
    - an `oidc-group-membership-mapper` (claim `groups`, `full.path=false`) and an `oidc-audience-mapper`;
    - no identity providers, no organizations and no secrets.
  - **`keycloak/lexavault-phase1-other-realm.json`:** a second realm for the wrong-issuer check.
  - **Scripts:**
    - `Start-Phase1.ps1` takes an admin token from `master`/`admin-cli` and sets the generated test-user passwords
      (`PUT /admin/realms/{realm}/users/{id}/reset-password`).
    - `Phase1-Common.ps1` `Sync-Phase1KeycloakClients` creates missing clients (POST) and updates redirect URIs (PUT).
      It exists because **realm import skips a realm that already exists**.
- **Design** (`docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md` in LexaVault, agreed 2026-11-16):
  - one shared hub realm with **Keycloak Organizations**, one per customer;
  - email-first sign-in, brokering to the customer's OIDC, SAML or LDAP system;
  - a staff space with mandatory multi-factor;
  - a registry owned by the product, mapping each organization to one customer.
  - The hub proof of concept (section 13) is not done, and the shape of the organization claim is unconfirmed.
- **Vault** is used for SDV document encryption only, never for login secrets. No client secret exists anywhere: every
  client is public.

## 4. Gaps and conflicts with the approach

1. **"Tenant" means two things.** The licence has one `TEN-…` per customer, with `ENT-…` slots. The apps have one
   tenant UUID per entity, and the LexaVault design says "each entity is its own tenant ID".
   - Mapping: licence tenant = **customer**; licence slot = app **tenant/entity**.
   - The app's word "tenant" and the platform's `tenantId` differ by one level. This has to be written down once and
     used everywhere.
2. **ID formats differ.** The apps' tenant key is a `UUID` everywhere: FKs on every table, `EntityId(UUID)`,
   `OidcConnectionBinding.tenantId`, the mapping file's `entity_id`, SDV's per-tenant Vault key prefix `sdv-tenant-`.
   The licence's IDs are `ENT-` strings. Changing every app table to strings is a large migration for no gain.
3. **Customer records have nowhere to live.** No customer table exists in either app or in the platform.
4. **The same login tables are defined twice.** `enterprise_identity_connections` and its history exist in both apps
   with the same columns. Grants are a table in LexaVault and configuration in SDV.
5. **Start-up inserts.** `OidcConnectionBootstrap` (both apps), `LocalDemoBootstrap` (LexaVault) and the `local` seed
   changeset (SDV) contradict "no inserts at app startup".
6. **Demo and test data uses fixed made-up UUIDs**: `0000…0001`, `1111…`, `2222…`, `a1a1…`, `b2b2…`. They are spread
   through scripts and tests and come from no licence.
7. **Two licensing systems.** LexaVault's own `tenant_license_*` (one licence per tenant, its own public key) overlaps
   with platform-licensing (one licence per customer).
8. **The LexaVault design is behind the licensing design.**
   - It says "a licence for each entity". The licensing design (2026-11-21) says one licence per customer with slots.
   - It says "Customer administrator creates entities". The approach says "Business Admin activates and names slots".
   - The design doc needs a short update.
9. **Hub versus direct sign-in.** The approach says "load the sign-in config into the login hub", but the first customer
   uses Okta directly with no hub. The hub itself is unproven: no proof of concept, no organizations, no brokering
   anywhere.
10. **The client secret only matters for brokering.** In direct mode the products use a public PKCE browser client and
    verify tokens against an audience. Neither product needs a client secret. A secret is only needed when Keycloak
    acts as a client of the customer's login system.
11. **Schema tools are mixed.** The apps use Liquibase; the platform modules use Flyway, one run per module with its own
    history table. SDV's `SchemaStep` and any LexaVault schema step must also run the platform runs.
12. **The library moves.** Both apps must move from `enterprise-identity` 0.2.0/0.3.0 to `platform-identity-*` before
    they can use anything new.
13. **SDV has no entity chooser and no grants.** A customer with two SDV entities cannot sign in to either today.
14. **The database layout is unknown.** LexaVault (`securedoc`) and SDV (`sdv`) use separate databases. The licence and
    the customer rows would then exist once per product database.

## 5. Proposal

### 5.1 Where the records live

**The platform owns the customer, entity-slot, login-connection and grant tables.** It gets a new schema run,
`db/platform/identity`, with history table `idn_schema_history`, like licensing. It also gets Postgres stores in a new
module. Each app keeps its own `tenants` table, because every app table has a foreign key to it.

| Table (prefix `idn_`) | Holds | Written by |
|---|---|---|
| `idn_customer` | `tenant_id` (`TEN-…`, primary key), name (from the licence), created/updated | setup |
| `idn_entity` | `slot_id` (`ENT-…`, primary key), `entity_uuid` (unique), `tenant_id` FK, `status` (`NOT_ACTIVATED` / `ACTIVE` / `SUSPENDED` / `NOT_IN_LICENCE`), `display_name`, `activated_by/at` | setup (new slots); app (activate, name) |
| `idn_login_connection` (+ `_history`) | the columns of today's `enterprise_identity_connections`, but keyed to the **customer** (all of a customer's entities share one login system), `secret_reference` | setup; the app's existing admin screen (draft/test/activate) |
| `idn_group_grant` | `slot_id`, `issuer`, `external_group`, `product_code`, `role` (primary key on all) | setup; later the IT admin's mapping screen |

- **`entity_uuid` is derived from the slot ID**: a name-based UUID, `StableIdentityId`-style, from
  `"platform-entity|" + slotId`.
  - The app's existing UUID key becomes this value, so no app column type changes.
  - The value is the same in every database and every install, so moving an entity to hosting later keeps its ID.
  - The setup command needs no mapping table to stay idempotent.
- **Grants carry `product_code`**, because roles belong to the product and the two products' role names differ.
- **The app creates its own `tenants` row when the Business Admin activates a slot.** It uses `entity_uuid` as the key,
  so no app row exists for a slot nobody has activated. This is a user action, not a start-up insert. A new entity
  starts empty and locked down, as the design asks.
- **platform-identity provides the store implementations** of `ActiveEntityCandidates`, `EntityGroupGrants`,
  `OidcConnectionBindingResolver` and `MachineEntityBindings` (machine bindings still join to the app's integration
  table, through a small app interface). It also provides `ActivatedSlots` for licensing, directly from `idn_entity`.
- **The library-owns-no-tables rule changes.** Licensing and notifications already broke it, with good results. The
  alternative is each app keeping its own copy of the tables, plus an app interface that the setup command writes
  through. That keeps two copies of the same tables and puts app code inside our command.

### 5.2 Input files

1. **The signed licence** (`platform-licence/1`, as today).
2. **The sign-in config file**, a new format `platform-signin/1` (JSON):
   - `tenantId` (must equal the licence's);
   - `mode`: `direct` or `hub`;
   - `loginSystem`: `type` (`OIDC`), `issuer` (address), `providerId`;
   - for each product code: `browserClientId`, `apiAudience`;
   - `hub` (only in hub mode): organization alias and email domains, the customer system's `clientId`, and
     `clientSecretRef` (a Vault KV reference such as `vault:kv/customers/TEN-…/login#client_secret`; never the
     secret);
   - for each product code: `roleMappingFile`.
   It holds no secrets, no database settings and no prices.
3. **Role mapping files**, one per product, in the existing `RoleMappingFile` CSV format. `entity_id` accepts the
   **slot ID** (`ENT-…`); `entity_name` stays optional, since naming belongs to the Business Admin.
4. **Environment only:**
   - the database address and credentials (entered by the customer's administrator; the everyday DML account is
     enough);
   - the Keycloak admin client credentials (hub mode only);
   - `VAULT_TOKEN`/`VAULT_ADDR` (hub mode only, to read the secret reference).

### 5.3 The command (`platform-setup`, `SetupCommand`)

The shape is `setup plan|apply --licence … --signin … --product PRODUCT_A [--db …]`. The output style and run log
follow the licence command and the issuing command: IDs and codes only.

1. **Preflight (no writes).** Read and verify everything first:
   - The licence is verified with the installed trusted keys.
   - The sign-in file and the licence name the same tenant.
   - `--product` is in the licence.
   - The `lic_` and `idn_` tables exist at the expected versions. Setup never creates tables.
   - Every mapping row names a slot in the licence and a role the product knows (see Q5).
   - Hub mode: Keycloak and Vault are reachable, and the secret reference resolves (its value is never printed).
   - Each refusal has its own `SETUP_*` code.
2. **Plan.** Print every item as `create` / `unchanged` / `update` / `conflict` / `not removed`. `plan` stops here.
3. **Store the licence** with `LicenceService.store` (already idempotent; an early renewal waits, and an older version
   is refused).
4. **Customer:** insert it if missing. Update the name if the licence version changed it.
5. **Entity slots:** insert missing slots as `NOT_ACTIVATED`, with their `entity_uuid`.
   - A slot dropped from a newer licence is marked `NOT_IN_LICENCE`, never deleted.
   - Activated slots and their names are never touched.
6. **Login connection:** insert it as ACTIVE if missing.
   - If it differs, write a new `configuration_version` and a history row (`SETUP`).
   - A connection that someone changed in the app since the last setup is a `conflict`. It is left alone unless
     `--replace-connection` is given.
7. **Grants:** add the missing ones (`MappingPlan`). Grants that are not in the file are listed, not removed (Q4).
8. **Hub (mode `hub` only)**, through the admin REST API; see 5.4.
9. **Tenant setting:** write the tenant ID for `platform.licensing.tenant-id` (Q2 of P010) into the install's answers or
   config fragment, which setup creates or checks.
10. **Run log:** one line per run, with the outcome per item, the licence ID and version, and the codes.

Steps 3–7 run in one database transaction per product database, under `pg_advisory_xact_lock`, so two runs at once
cannot interleave. A failure rolls back the database part. The hub part runs after the commit and is itself safe to
rerun, so a rerun finishes whatever was missing. Exit codes: 0 done or unchanged, 2 refused, 3 conflict needs a flag.

### 5.4 Loading Keycloak: admin REST API, not realm import

- **Realm import is the wrong tool here.** Start-up import (`--import-realm`) skips a realm that exists. Partial import
  either overwrites (which can delete settings someone changed) or skips. Neither can say what changed. Phase 1 already
  had to add REST calls on top of its import for this reason.
- **Use the admin REST API with the JDK `HttpClient`**, as `VaultTransitSigner` does. Each object is looked up by its
  natural key, compared, and then created or updated; nothing is deleted:
  - the organization, by alias = `TEN-…`, with domains;
  - the identity provider, by alias, linked to the organization, with the client ID and the secret read from Vault at
    run time;
  - the IdP group mappers, so the customer's groups reach the hub token;
  - the product clients, by `clientId`, with the `groups` mapper (`full.path=false`) and the audience mapper.
- **Credentials:** a confidential service-account client in the hub realm, with only the needed `realm-management`
  roles, and its secret taken from the environment or Vault.
  - Its token is never logged.
  - Redirects are not followed.
  - The same no-secrets-in-output tests as P011 apply.
- **The realm and the staff space are created once by the hub install runbook, not by the customer setup.**
- **Tests:** Testcontainers `quay.io/keycloak/keycloak:26.8.2`, the version `lexa-keycloak` runs.
- **Group-to-role mapping stays in the product database (`idn_group_grant`), not in Keycloak.** Keycloak only has to
  carry the groups in the token. This matches the design: "the product does not take the hub's word for it".

### 5.5 How the apps adopt it

**For both apps:**

- Move to `platform-bom` and `platform-identity-*`, `platform-licensing(-spring)` and the new identity store module.
- The schema step also runs the `idn_` and `lic_` Flyway runs. Each has its own history table, beside Liquibase.
- Delete `OidcConnectionBootstrap`.
- Add slot activation (activate and name) for the Business Admin. It creates the app's `tenants` row with
  `entity_uuid`.
- Supply `EntityRoleCatalog` and the machine-binding join.
- `platform.licensing.tenant-id` becomes required.
- **Existing rows:** a one-off migration command copies each app's `enterprise_identity_connections` and
  `tenant_oidc_group_grants` into the `idn_` tables for an existing install. Then the app tables are dropped in a later
  release.

**LexaVault:**

- Replace the `tenant_license_*` licensing with platform-licensing.
- Turn `LocalDemoBootstrap` into **demo input files**: a demo licence with fixed demo slot IDs, a sign-in file and a
  mapping file, run through the same setup command (design item A2).
- Demo-only rules and capabilities still need their own app step (Q7).
- Shared-issuer mode becomes the normal path; migration 037's index rule is built into the `idn_` tables.

**SDV:**

- Move from 0.2.0 to the platform library.
- Replace the `SDV_AUTH_GROUP_*` env mapping with `idn_group_grant`.
- Add the entity chooser (the same `EntitySelection` and `/me/…` endpoint as LexaVault).
- Drop `002-local-foundation-seed` in favour of the demo input files.
- `SchemaStep` gains the platform runs.

**Test scripts and tests:** replace the fixed UUIDs (`0000…0001`, `1111…`, `2222…`, `a1a1…`, `b2b2…`) with the UUIDs
derived from the demo slot IDs. That is mechanical, but it touches many scripts.

### 5.6 Order of build tasks

| Task | Where | What |
|---|---|---|
| P013 | platform-core | Identity storage: `idn_` tables and Flyway run, Postgres stores implementing the admission interfaces, `entity_uuid` derivation, `ActivatedSlots` from `idn_entity`, slot activation API, mapping file with slot IDs and product codes, testkit. |
| P014 | platform-core | `platform-setup`: inputs, plan/apply, steps 1–7, 9 and 10 in **direct mode**, run log, `tenant-id` required, idempotency and rerun-after-failure tests, demo input files. |
| P015 | LexaVault repo | Switch-over: platform library, licensing, identity store, schema step, activation screen, bootstrap removal, demo through setup, script UUIDs. |
| P016 | SDV repo | The same, plus grants and the entity chooser. |
| P017 | LexaVault/hub | Hub proof of concept (design section 13): organizations, brokering, claim shape. |
| P018 | platform-core | Setup **hub mode**: the Keycloak admin REST loader, the Vault secret reference, and Testcontainers Keycloak 26.8.2 tests. |

P013 and P014 are enough for the first (Okta, direct) customer once P015 and P016 are done. The hub work waits for its
proof of concept, as the design requires.

## 6. Questions for the owner

1. **Records in shared `idn_` tables owned by platform-core, rather than each app's own tables?** *Recommendation:* yes
   (5.1). The apps keep `tenants`, created at slot activation.
2. **Should the app's entity UUID be derived from the slot ID, instead of switching the apps to `ENT-` strings?**
   *Recommendation:* yes. No change to any app column, and the ID is the same in every install.
3. **One login connection per customer, shared by its entities, instead of one per entity?** *Recommendation:* yes. It
   matches "one login, one contract", and it makes migration 037's special case the rule.
4. **May setup remove anything?** *Recommendation:* no, by default. Grants missing from the file are reported, and are
   removed only with an explicit `--remove-unlisted-grants`. Slots are never deleted. The owner said "never deletes",
   so the flag needs your yes.
5. **Role names:** the setup command is platform code, but roles belong to the product. *Recommendation:* each product
   ships a small role list file (`product-roles.json`) in its distribution, and setup reads it with `--product`.
   Product code is not loaded into the command.
6. **Separate databases per product:** with LexaVault and SDV in separate databases, does setup run once per product
   database with the same input files? *Recommendation:* yes. It is idempotent, so the second run only adds that
   product's grants. One shared platform database can come later, for hosting.
7. **Demo data:** demo licence and slot IDs from a **separate development signing key** that production apps never
   trust? *Recommendation:* yes, combined with the design's "demo mode refuses a real database". Demo-only app records
   (rules, capabilities, the scanner) become a separate app demo step that runs after setup, never at start-up.
8. **Hub mode now or later?** The first customer is direct Okta. *Recommendation:* build direct mode first (P014). Build
   hub mode (P018) only after the hub proof of concept passes.
9. **The broker's client secret:** setup reads it from Vault at run time and writes it into Keycloak's identity provider
   (Keycloak stores it), or Keycloak reads it through its own vault provider? *Recommendation:* setup writes it through
   the admin API. Keycloak 26 has no HashiCorp Vault provider built in, and the file vault would put the secret on
   disk.
10. **LexaVault's own licensing (`tenant_license_*`):** replace it with platform-licensing in P015? *Recommendation:*
    yes. No customer has a LexaVault licence yet, so there is nothing to migrate.
11. **Design doc update:** may I bring `LOGIN_CUSTOMER_ENTITY_DESIGN.md` (in the LexaVault repo) into line? It covers
    one licence per customer with slots, slot activation by the Business Admin, and the meaning of "tenant".
    *Recommendation:* yes, as part of P015 rather than a separate task.
12. **The hand-built `lexa-keycloak`:** recreate it through the hub runbook and setup (P018), so that development uses
    the same steps as customers? *Recommendation:* yes, after P018. Until then, leave it untouched.
