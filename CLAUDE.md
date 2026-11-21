# platform-core

Shared code for LexaVault and Vero (SDV). Each product pulls these modules in as **libraries** (jars on its
classpath). platform-core is not a server, has no `main`, and is never deployed on its own.

## Modules

| Module | What it is |
|---|---|
| `platform-bom` | Bill of materials. Lists every platform module at the platform version. Products import it in `dependencyManagement`. |
| `platform-identity-core` | Identity: claims, OIDC connection bindings, entity admission, role mapping. No framework dependencies. |
| `platform-identity-spring` | Spring Security adapters for identity (JWT normalizer, audience validator, caching binding resolver). |
| `platform-identity-jdbc` | Identity storage (plain Java, no Spring): `idn_` tables and SQL (`db/platform/identity`, history table `idn_schema_history`, run only by the app's schema step) with startup check `IdentitySchema.verify`; Postgres stores for customers, entity slots (`entity_uuid` derived from the slot ID by `EntityUuid`), login connections per customer and product with history, group grants per product; `PostgresIdentityLookup` (candidates, grants, binding resolver), `PostgresMachineEntityBindings` (app supplies `MachineClients`), `LicenceSlots` for `SlotActivation` (activate, rename, suspend, resume; activation and resume follow the intake rule; a slot back in the licence gets its earlier status from `status_before_removal`). Store interfaces and `SlotActivation` live in identity-core (`com.documentplatform.identity.store`). Install guide: `docs/install/identity.md`. |
| `platform-identity-testkit` | Test support for identity storage: `InMemoryIdentityStore` (all stores and lookups in memory). Test scope only. |
| `platform-notifications` | Notifications (plain Java, no Spring): event catalogue, admin settings, routing, summaries, continuous-condition alerts, message content rule; outbox with retries; SMTP (password login or relay, or Microsoft 365 with OAuth 2.0 `AUTH XOAUTH2`: `MicrosoftOAuth`, `MicrosoftTokens`, client certificate or secret via `SecretResolver`), Teams (Workflows webhook) and Slack senders; approval links (`approval.Approvals`: Approve / Decline links in messages, signed single-recipient expiring `ActionTokens` (HMAC, key via `SecretResolver`), first decision wins in `notif_approval_request`, outcome to the requester, optional "expired" event to the requester (`ApprovalType.whenExpired`, `Approvals.tellExpired`); the app builds the page); Postgres stores and SQL migrations (`db/platform/notifications`). Design: `docs/design/notifications.md`. |
| `platform-notifications-testkit` | Test support for notifications: in-memory channel, recipient resolver, secret resolver and stores (approval store included). Test scope only; never in production code. |
| `platform-notifications-spring` | Spring Boot wiring for notifications: auto-configuration, `platform.notifications.*` properties (validated at startup; `mail.auth` PASSWORD or OAUTH2_MICROSOFT with `mail.oauth.*`), startup check of the `notif_` tables (never creates them), scheduled tick and background sender, health indicator per destination and the mail sign-in mode and token state, approval links when `approvals.link-key-secret` is set (app supplies `ApprovalType` beans and an `ApprovalAudit`; the tick tells requesters of expired requests and purges requests expired longer than `retention` ago daily). Install guide: `docs/install/notifications.md`. |
| `platform-licensing` | Licensing (plain Java, no Spring, no runtime dependencies): Ed25519-signed licence file and verifier with refusal codes, licence states with 30-day grace, effective-time clock rule and signed last-seen reset, `LicenceService` (current + waiting licence), usage levels (never blocks), hash-chained usage ledger with chain head, usage file export and verifier, Postgres stores (plain JDBC) and SQL (`db/platform/licensing`), licence-issuing command (`IssueLicenceCommand`, our side only; signs in Vault Transit with `VaultTransitSigner`, the private key never leaves Vault; set-up: `docs/install/licence-signing.md`), notice log of published licensing notifications, licence command (`LicenceCommand`, customer side). Design: `docs/design/licensing.md`; formats: `docs/design/licensing-file-format.md`. |
| `platform-licensing-testkit` | Test support for licensing: in-memory licence store, usage ledger, last-seen store, notice log, signer, throwaway test keys and licences. Test scope only. |
| `platform-licensing-spring` | Spring Boot wiring for licensing: auto-configuration of `LicenceService`, `platform.licensing.*` properties (validated at startup; `tenant-id` required; keys from `trusted-keys-file` and/or inline `trusted-keys`), startup check of the `lic_` tables (never creates them) and of the stored licence, health indicator, licensing notifications (`LicensingEvents` for the app's catalogue, hourly check per activated entity slot, including "Licence does not cover this product") when the app has `platform-notifications` (optional dependency). Install guide: `docs/install/licensing.md`. |
| `platform-setup` | The setup command (plain Java, no Spring), direct sign-in mode: `SetupCommand` (`setup plan|apply`) reads the signed licence, the sign-in file (`platform-signin/1`), the role mapping CSV (slot IDs) and the product's `product-roles.json`; preflight with `SETUP_*` codes, plan, then one transaction per product database under `pg_advisory_xact_lock` (licence, customer, slots, login connection, grants), tenant-id config fragment, run log. DB settings from the environment only. Demo input files (development key) in `platform-setup/demo/`. Install guide: `docs/install/setup.md`; design: `docs/design/setup.md`. |
| `platform-fields` | Case field definitions (plain Java, no Spring, no runtime dependencies; package `com.documentplatform.fields`): `FieldSpec` per entity and product (`FieldScope`: entity UUID + product code) with types TEXT, NUMBER, MONEY (one currency), DATE, EMAIL, UUID, DROPDOWN (≤100), ADDRESS (US parts, country ready), CUSTOM_PATTERN, rules, flags (primary, searchable + `SearchMode`, sensitive (names and addresses by default; EXACT search only), multi-value, displayable), display order, active; `FieldValidator` / `FieldSet` (stable `ValueError` codes, messages never hold a value); `FieldDefinitions` (key never changes, type locked once the app's `FieldValuesCheck` says values exist, removing = switching off, history, app-only settings by key); field list file `file.FieldListFile` (`platform-fields/1`) with import by key (`ImportReport`: CREATE / UNCHANGED / CONFLICT); delivery `delivery.FieldDelivery` (`platform-field-values/1`: key + type + value(s)) and its verifier (`DELIVERY_*` codes); Postgres `jdbc.PostgresFieldStore`, SQL `db/platform/fields` (history table `fld_schema_history`, run only by the schema step), startup check `FieldSchema.verify`. No value storage or search yet. Design: `docs/design/case-fields.md`; install guide: `docs/install/fields.md`. |
| `platform-fields-testkit` | Test support for case fields: `InMemoryFieldStore` (same rules as Postgres), `TestValuesCheck`. Test scope only. |

New capability = new module, added to the root `<modules>` list **and** to `platform-bom`.

## One version

Every module inherits its version from the root `pom.xml`. Never set a `<version>` in a module POM other than
in its `<parent>` block. Inter-module dependencies use `${project.version}`. A release bumps all modules together.

## Build

```
mvn verify      # compile + all tests
mvn install     # also puts the artifacts in the local Maven repo for LexaVault / Vero builds
```

Java 21, Maven 3.9+.

On the owner's Windows laptop, Norton 360 intercepts TLS; Maven trusts it via the user environment variable
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`. Never add machine-specific settings to the repo.

## Rules

- **No app-specific code here.** Nothing that belongs to only LexaVault or only Vero: no product names, product
  packages, product roles, screens or endpoints. `NoProductNamesGuardTest` fails the build on product package names.
  Products plug their specifics in through interfaces (for example `EntityRoleCatalog`).
- **Never log secrets, document content or redacted values.** That includes tokens, client secrets, keys,
  passwords, raw claims, file contents, and anything that was redacted. Log ids and neutral reasons only.
- Keep Java package names stable; renames are their own task.
- **Coordinates and packages.** Every module uses groupId `com.documentplatform.platform`. A new module puts its
  code in `com.documentplatform.<capability>` (for example `com.documentplatform.notifications`). The identity
  modules keep their existing `com.documentplatform.identity...` packages.

## Work tracking

- Tasks live in `docs/work/PNNN.md` (P001, P002, ...).
- The report for each task goes in `docs/work/PNNN-report.md`.
