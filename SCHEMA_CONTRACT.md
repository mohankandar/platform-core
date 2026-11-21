# Schema contract for entity admission (Enterprise Identity 0.3.0)

> **Since P013** platform-core ships these tables itself: module `platform-identity-jdbc` (`idn_` tables, Flyway
> location `db/platform/identity`) implements all four interfaces, with one login connection per customer shared by
> its entities (`docs/design/setup.md`). This page stays as the contract for a product that still keeps its own
> tables until its switch-over.

The library owns no tables. A product that uses `com.documentplatform.identity.admission` keeps its own tables and
migrations and implements four interfaces over them. This page lists what those tables must hold. Names are the
product's choice; LexaVault's names are given as an example.

Values are compared exactly as stored: issuer, provider id, audience, group names and client principals are not case
folded, trimmed of slashes or normalised by the library (see `ConnectionKey`).

## 1. Entities and their login-system connections: `ActiveEntityCandidates`

| Needed | Why | LexaVault |
|---|---|---|
| Entity id (UUID) and entity status | Only an ACTIVE entity is a candidate | `tenants.id`, `tenants.status = 'ACTIVE'` |
| Connection: entity id, provider id, issuer, API audience, status | The connection must be ACTIVE and match the key exactly | `enterprise_identity_connections.tenant_id, provider_id, issuer_uri, api_audience, status = 'ACTIVE'` |
| At most one ACTIVE connection per entity | An entity signs in through one login system | unique index `uq_eic_active_per_tenant` |
| **No** uniqueness of (provider id, issuer, audience) across entities | Several entities share one login system | LexaVault drops `uq_eic_active_binding` in migration 037 (shared-issuer installs only) |

`find` returns the ids of ACTIVE entities with an ACTIVE connection for the key; an empty set for a blank provider id or
audience; `null` only when the store cannot be read.

## 2. Group-to-role grants: `EntityGroupGrants`

| Needed | Why | LexaVault |
|---|---|---|
| Entity id, issuer, external group, role name | One row per group-to-role grant in one entity | `tenant_oidc_group_grants (tenant_id, issuer_uri, external_group, product_role)`, primary key on all four |
| Joined to the ACTIVE connection on entity id **and** issuer | Grants are scoped to the connection; a grant recorded for another issuer never applies | join to `enterprise_identity_connections` on `tenant_id` and `issuer_uri`, plus the conditions of section 1 |
| Group names exactly as the login system sends them | No case folding or path stripping (for Keycloak, turn "full group path" off) | — |
| Role names the product's `EntityRoleCatalog` knows | Unknown names refuse the grant | LexaVault also refuses any unknown name while reading the table |

`activeGrants` returns `entity -> (group -> role names)`; an empty map for a blank provider id or audience; `null` only
when the store cannot be read.

## 3. Machine integrations: `MachineEntityBindings`

| Needed | Why | LexaVault |
|---|---|---|
| Entity id, enabled flag, client principal | The machine's token identity is matched to one enabled integration | `integration_connections.tenant_id, enabled = true, client_principal` |
| Integration kind and authentication type | Only integrations that authenticate with a bearer token can bind a token's identity | `purpose = 'INGESTION_SOURCE'`, `provider_type = 'PUSH_API'`, `authentication_type in ('JWT', 'OAUTH2_CLIENT_CREDENTIALS')` |
| Joined to the ACTIVE connection and ACTIVE entity of section 1 | — | as section 1 |

`find` returns the matching entity ids; an empty set for a blank provider id, audience or client principal. The library
admits the machine only when exactly one entity is returned, so a client principal must be unique across entities.

## 4. Roles: `EntityRoleCatalog`

No table. The product names its roles: `canonicalEntityRole` maps a configured name to the canonical name (or empty for
an unknown one), and `isPlatformRole` names the role that reaches platform-wide screens and can never be granted through
an entity's groups.
