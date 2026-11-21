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
4. **Entity choice (owner, 2026-11-21):** after sign-in the person **lands on the entity they used last**. Everything shown is for that entity only, never mixed. They change entity with a **switcher in the app header** (app UI work, not this module); every switch is audited. The chooser appears only when there is no usable last entity: first sign-in ever, or the last entity is no longer allowed for them. People with only one entity never see a chooser. The current entity's name is always prominent in the header, and upload/scan confirmations name the entity, so nothing goes into the wrong entity by mistake. The "last entity" is kept per person and is not sensitive.
5. **Separate databases per product by default** (LexaVault and Vero are separate applications with separate users). A customer may instead use one database with a **separate schema per product** (owner, 2026-11-21). Setup runs once per product database or schema with the same input files. The tables still carry `product_code` on grants, so one shared customer/entity record set (e.g. a shared platform schema, or our hosting later) works without redesign: one customer row, one entity row, grants per product.
6. **Setup never deletes customers, entities or slots.** A slot dropped from a newer licence becomes `NOT_IN_LICENCE`. **Group access missing from the mapping file is removed only with the explicit flag `--remove-unlisted-grants`**, and the plan always shows what will be removed first.
7. Each product ships `product-roles.json`; setup reads it with `--product`. No product code inside the command.
8. Demo data uses a **separate development signing key** that production apps never trust; demo-only app records become a separate app demo step after setup, never at startup.
9. **Direct sign-in first** (first customer: Okta, no hub). Hub mode (Keycloak admin REST API, broker secret read from Vault and written through the admin API) only after the hub proof of concept.
10. LexaVault's own `tenant_license_*` licensing is replaced by platform-licensing in its switch-over (no customer has one yet).
11. `platform.licensing.tenant-id` becomes required; setup writes it.

## 3. Build order

P013 identity storage (platform-core) → P014 setup command, direct mode (platform-core) → LexaVault switch-over → Vero switch-over (adds grants and the entity chooser) → hub proof of concept → setup hub mode.

Full proposal, table columns, input files and command steps: `docs/work/P012-report.md` sections 5.1–5.6, as amended above.
