# platform-core

Shared code for LexaVault and Vero (SDV). Each product pulls these modules in as **libraries** (jars on its
classpath). platform-core is not a server, has no `main`, and is never deployed on its own.

## Modules

| Module | What it is |
|---|---|
| `platform-bom` | Bill of materials. Lists every platform module at the platform version. Products import it in `dependencyManagement`. |
| `platform-identity-core` | Identity: claims, OIDC connection bindings, entity admission, role mapping. No framework dependencies. |
| `platform-identity-spring` | Spring Security adapters for identity (JWT normalizer, audience validator, caching binding resolver). |
| `platform-identity-jdbc` | Identity storage (plain Java, no Spring): `idn_` tables and SQL (`db/platform/identity`, history table `idn_schema_history`, run only by the app's schema step) with startup check `IdentitySchema.verify`; Postgres stores for customers, entity slots (`entity_uuid` derived from the slot ID by `EntityUuid`), login connections per customer and product with history, group grants per product; `PostgresIdentityLookup` (candidates, grants, binding resolver), `PostgresMachineEntityBindings` (app supplies `MachineClients`), `LicenceSlots` for `SlotActivation`. Store interfaces and `SlotActivation` live in identity-core (`com.documentplatform.identity.store`). Install guide: `docs/install/identity.md`. |
| `platform-identity-testkit` | Test support for identity storage: `InMemoryIdentityStore` (all stores and lookups in memory). Test scope only. |
| `platform-notifications` | Notifications (plain Java, no Spring): event catalogue, admin settings, routing, summaries, continuous-condition alerts, message content rule; outbox with retries; SMTP, Teams (Workflows webhook) and Slack senders; Postgres stores and SQL migrations (`db/platform/notifications`). Design: `docs/design/notifications.md`. |
| `platform-notifications-testkit` | Test support for notifications: in-memory channel, recipient resolver, secret resolver and stores. Test scope only; never in production code. |
| `platform-notifications-spring` | Spring Boot wiring for notifications: auto-configuration, `platform.notifications.*` properties (validated at startup), startup check of the `notif_` tables (never creates them), scheduled tick and background sender, health indicator per destination. Install guide: `docs/install/notifications.md`. |
| `platform-licensing` | Licensing (plain Java, no Spring, no runtime dependencies): Ed25519-signed licence file and verifier with refusal codes, licence states with 30-day grace, effective-time clock rule and signed last-seen reset, `LicenceService` (current + waiting licence), usage levels (never blocks), hash-chained usage ledger with chain head, usage file export and verifier, Postgres stores (plain JDBC) and SQL (`db/platform/licensing`), licence-issuing command (`IssueLicenceCommand`, our side only; signs in Vault Transit with `VaultTransitSigner`, the private key never leaves Vault; set-up: `docs/install/licence-signing.md`), notice log of published licensing notifications, licence command (`LicenceCommand`, customer side). Design: `docs/design/licensing.md`; formats: `docs/design/licensing-file-format.md`. |
| `platform-licensing-testkit` | Test support for licensing: in-memory licence store, usage ledger, last-seen store, notice log, signer, throwaway test keys and licences. Test scope only. |
| `platform-licensing-spring` | Spring Boot wiring for licensing: auto-configuration of `LicenceService`, `platform.licensing.*` properties (validated at startup), startup check of the `lic_` tables (never creates them) and of the stored licence, health indicator, licensing notifications (`LicensingEvents` for the app's catalogue, hourly check per activated entity slot) when the app has `platform-notifications` (optional dependency). Install guide: `docs/install/licensing.md`. |

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
