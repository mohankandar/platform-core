# Switching an app over to platform-core 1.0

**Needs:** Spring Boot 4.2 and Java 21 in the app (`docs/design/platform-baseline.md`).

For the app's developers, with what its IT has to do. It says what to add, in which order, and where each step is described.
`examples/sample-app` in the platform-core repository is a small app that does all of it, and
`examples/sample-app/install/install-proof.sh` installs it step by step on Linux and Windows.

From 1.0.0 on, versions follow strict semantic versioning: a breaking change comes only with a new major version.

## 1. Prerequisites

- [ ] Spring Boot 4.2.x and Java 21. Do this move first, on its own: checklist in `docs/releases/platform-core-v0.8.0.md`.
- [ ] Read access to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core` (README.md).
- [ ] PostgreSQL, with a schema owner and a runtime user that owns nothing. Vault for encryption, the keyed erase log and
      client certificates (`docs/install/crypto.md`).
- [ ] The app's licence public keys in its own build (`docs/install/licensing.md` section 2): `licence-keys.json` and
      `licence-build.properties`, each once on the class path.

## 2. Dependencies

Import `platform-bom` at one version; name no other platform version. Add only what the app uses:

| Capability | Dependency | Guide |
|---|---|---|
| Codes, references, safe texts | `platform-errors` | `docs/design/error-codes.md` |
| Sign-in, entities, grants | `platform-identity-jdbc`, `platform-identity-spring` | `docs/install/identity.md` |
| Licence | `platform-licensing-spring` | `docs/install/licensing.md` |
| Keys and encryption | `platform-crypto-spring` | `docs/install/crypto.md` |
| Case fields | `platform-fields` (no Spring module: the app builds the objects) | `docs/install/fields.md` section 5 |
| Erase log | `platform-erase-log-spring` | `docs/install/erase-log.md` |
| Outbound calls | `platform-outbound-spring` | `docs/install/integration.md` section 1 |
| Integrations | `platform-integration-spring` | `docs/install/integration.md` |
| Notifications | `platform-notifications-spring` | `docs/install/notifications.md` |
| Monitoring | `platform-monitoring-spring` | `docs/install/monitoring.md` |
| Model governance | `platform-model-governance-spring` | `docs/install/model-governance.md` |
| Setup command | `platform-setup`, in the app's setup wrapper | `docs/install/setup.md` |

Testkits (`…-testkit`) in test scope only. `platform-licensing-issue` is never on an app's class path.

## 3. Order of the modules

Each step works on its own and can be released on its own. Later ones use earlier ones.

1. `platform-errors`, then `platform-monitoring-spring`: the app's own checks and codes first.
2. Licensing, then identity, then the setup command (setup needs both).
3. Crypto (the customer's keys come from setup). Many data keys at once: `KeyService.unwrapAll` (1.1.0).
4. Case fields, then the erase log (the field erase handler needs fields; the keyed log needs crypto).
   A results page with sensitive values: `FieldValues.readSensitive(List)`, one key service call per page (1.1.0).
5. Outbound, then integrations.
6. Notifications (the licensing events and time zones join when licensing and identity are there).
7. Model governance, when the app runs models.

**New in 1.2.0, per module:**

- `platform-fields`: `FieldValues.read(List)`, one query per page; `conflictingKeys()`; `FieldDefinitions.change(list)`;
  `FieldValues.withoutKeyService`.
- `platform-identity-*`: schema version 6; the test sign-in with a token before a connection is replaced; draft calls with the
  customer and product. **Check the app's screen offers the token** before upgrading.
- `platform-setup`: `identity rollback-login-connection`.
- `platform-notifications*`: `mail-servers.*`; `Approvals.decidedElsewhere` and `ApprovalAudit.recordElsewhere`.
- `platform-licensing*`: the admin's warning days; `LicenceBuild.isDevelopment()`.

**New in 1.3.0, per module (additive; every new setting is off):**

- `platform-fields`: the stores in the app's own transaction, `values.in(connection)` and `definitions.in(connection)`
  (`docs/install/fields.md` section 6); dropdown values with display names (section 7); **schema version 3**.
- `platform-notifications*`: the Microsoft 365 sign-in and the Teams and Slack posts through `platform.outbound.*`, with
  `mail.via-outbound` and `webhook.via-outbound` (`docs/install/notifications.md`, "Through the outbound settings").
- `platform-app-check`: a Spring `@Transactional` test of the field stores (build check only).

## 4. Schema runs

Each module's files run as their **own Flyway run with their own history table**, as the schema owner, before the app starts.
The platform never creates tables at start-up. The runs do not depend on each other; this is the order the sample app uses:

| # | Location | History table | Version in 1.0 |
|---|---|---|---|
| 1 | `db/platform/licensing` | `lic_schema_history` | 2 |
| 2 | `db/platform/identity` | `idn_schema_history` | 5 (6 from 1.2.0) |
| 3 | `db/platform/notifications` | your choice, for example `notif_schema_history` | 1 |
| 4 | `db/platform/fields` | `fld_schema_history` | 2 (3 from 1.3.0) |
| 5 | `db/platform/erase-log` | `erl_schema_history` | 3 |
| 6 | `db/platform/integration` | `int_schema_history` | 3 |
| 7 | `db/platform/modelgov` | `mg_schema_history` | 5 |

When the schema already holds tables, Flyway needs `baselineOnMigrate=true` with `baselineVersion=0` for each run, so that V1
runs as well (`SchemaStep` in the sample app). Then grant the runtime user what each guide lists; the sample app's
`install/grants.sql` has all of it in one file. Back up the whole database, not one schema (`docs/install/fields.md` section 4).

## 5. Setup inputs

Licence, sign-in file, role mapping, and where used client bindings and integration profiles, plus the product's own
`product-roles.json` in its release: `docs/install/setup.md` sections 1 to 3b. Run `plan`, read it, then `apply`, with the
runtime database user and a short-lived Vault login that has only the set-up policy.

## 6. Settings

Every module checks its settings at start-up and lists every problem with the property's name. The sample app's
`application.yml` (what the app fixes) and the `sample-install.properties` of the install proof (what the installation gives)
show a complete set. Secrets come from the environment or files, never from a file under version control.

## 7. What the app still owns

- **Audit:** it implements `IntegrationAudit`, `ModelGovAudit` and `ApprovalAudit` and writes them to its own audit.
- **Secrets:** a `SecretResolver` over its secret store; integration secrets under `int/<entity>/<name>`.
- **Screens** for IT Admin, Business Admin, support (with the diagnostic bundle's download) and model approval.
- **Schedules:** when a poll or a watched folder runs, when retention erases.
- **Notification events:** its `EventCatalogue` (register `LicensingEvents` in it), recipients, links.
- **Erase handlers** for its own data, and its legal holds.
- **Roles:** its `EntityRoleCatalog` and `product-roles.json`.
- **Test sets, pass marks and texts** for its model uses; **integration profiles** it receives.
- **Enforcing the licence:** the app asks `LicenceService.intakeAllowed()` where new work starts. Until it does, licensing only
  shows its state (read-only).
- **The security filter chain,** and who may see health details (`docs/install/monitoring.md` section 5).

## 8. Apps that use only identity, from an earlier version

1. Move to Spring Boot 4.2 and the 1.0.0 BOM.
2. Run the identity schema step to this release's version and adjust the grants (`docs/install/identity.md` section 1).
3. Check group names and push callers (`docs/install/identity.md`, "Upgrading to 0.7.0").
4. The `platform.outbound.*` settings are needed for the test sign-in; the integrations module is not.
5. An app that keeps its own tables until later implements the four interfaces (`docs/install/identity.md` section 10).

## 9. Check that it works

Start with the runtime user: readiness UP and every check in `/actuator/health/dependencies` WORKING. Then go through the smoke
steps of the install proof with the app's own screens: test sign-in, an entity activation, a field encrypted and found, an
erasure with a restore and replay, an export, a model refusal and approval, a notification, and a diagnostic bundle without
secrets.
