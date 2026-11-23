# platform-core 0.6.0

The strength-check release (tasks P037 to P041, after the patch 0.5.1 of P036):
- every failure carries a `PlatformCode`;
- the never-leak, safety and known-answer tests are complete;
- schema rights are tightened;
- a change of where a model runs goes through the gate;
- the design docs and install guides match the code;
- a written public-API rule hides internals;
- the licence-issuing side is its own module.

Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.6.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.** The name guard now reads every text file outside `docs/work/` and
`docs/design/`, and `docs/design/` names no product any more.

## Modules

All modules of 0.5.1, plus:

| Module | What it is |
|---|---|
| `platform-licensing-issue` (**new**, not in the BOM) | Our own licence issuing: the issuing command `issue.IssueLicenceCommand`, the Vault Transit signer, and the signing half of the formats (`issue.LicenceSigner`, `issue.ResetSigner`, `issue.DocumentSigner`, `issue.SigningKeys`). It is never on a customer's classpath. `platform-licensing` now only verifies. The licensing testkit depends on it (test scope for apps). |

## Design docs implemented

- `docs/design/public-api.md` (**new**): the public API rule.
- Every design doc's body now holds the decisions that were only in task reports:
  - erase log, case fields, crypto;
  - monitoring, model governance, integration;
  - licensing and its formats;
  - notifications, setup.
- Model governance, "Decision and fix (P039)": a change of where a model runs goes through the gate for every
  materiality.
- Model governance, "Fix (P037)": the purge guard belongs to the schema owner.
- Notifications B4: run its SQL files as their own Flyway run.

## Public API (P041)

What apps may use is what each module's install guide, design doc or line in `CLAUDE.md` names, and what an app must
call or implement. Everything else is package-private, or lives in a package whose name contains `.internal`.
**`internal` packages may change in any release and are not supported for apps.** `PublicApiTest` checks the rule
in the build.

## Breaking changes

No app depended on these yet except through identity, and **identity's documented API is unchanged**.

### Types moved

| Before | After |
|---|---|
| `licensing.issue.*` (`IssueLicenceCommand`, `VaultTransitSigner`, `Signer`, `IdGenerator`, `IssuedRecords`, `IssueError`, `IssueException`) | same names, now in module `platform-licensing-issue` |
| `licensing.LicenceSigner` | `licensing.issue.LicenceSigner` (`platform-licensing-issue`) |
| `licensing.SignedDocument.sign(...)`, `SignedDocument.MessageSigner` | `licensing.issue.DocumentSigner.sign(...)`, `DocumentSigner.MessageSigner` |
| `licensing.LastSeenReset.sign(...)` | `licensing.issue.ResetSigner.sign(reset, …)` |
| `licensing.Ed25519.generate / sign / encodePrivate / decodePrivate` | `licensing.issue.SigningKeys` (`Ed25519` keeps `verify`, `encodePublic`, `decodePublic`, `sha256Hex`) |
| `integration.config.Classification` | `integration.config.internal.Classification`; its defaults are `integration.config.Rule.DEFAULTS` |
| `integration.watch.TriggerValues` | `integration.watch.internal.TriggerValues` |
| `integration.json.Json` | `integration.json.internal.Json` (`json.JsonSchema` stays public) |
| `integration.signin.SignInHeaders` | `integration.engine.SignInHeaders`, package-private |
| `notifications.webhook.WebhookClient`, `notifications.webhook.Json` | `notifications.webhook.internal.*` |
| `notifications.spring.NotificationsConfig` | `notifications.spring.internal.NotificationsConfig` |
| `licensing.spring.LicensingConfig`, `LicensingConfigException` | `licensing.spring.internal.*` |
| `fields.json.{Json, JsonFields, JsonException}` | `fields.json.internal.*` |

### Made package-private

- **setup:** `Plan`, `ProductRoles`, `SigninFile`, `SetupKeys`, `SetupException`, and the constructor
  `SetupCommand(Map, Clock, SetupKeys.Connector)`.
- **identity:** `mapping.MappingTargets`, and the UUID-form constructors of `RoleMappingFile`. The mapping package is
  now documented as being for the setup command.
- **integration:**
  - `credential.CredentialFiles`, `folder.SlotIds`, `folder.ExportManifest`;
  - integration-spring's `IntegrationSettings`, `IntegrationConfigException`, `IntegrationFailureAnalyzer`,
    `IntegrationHealthContributor`.
- **licensing:**
  - `usage.UsageTotals`;
  - licensing-spring's `LicensingFailureAnalyzer`, `LicensingHealthIndicator`, `notifications.LicensingNotices`.
- **crypto-spring:** `CryptoConfig`, `CryptoConfigException`, `CryptoFailureAnalyzer`, `KeyServiceHealthIndicator`.
- **erase-log-spring:** `EraseLogConfig`, `EraseLogConfigException`, `EraseLogHealthIndicator`.
- **identity-spring:** `IdentityFailureAnalyzer`, `PushCallerConfigException`.
- **monitoring-spring:** `MonitoringFailureAnalyzer`.
- **model-governance-spring:** `ModelGovCheck`.
- **notifications-spring:** `NotificationsFailureAnalyzer`, `NotificationsHealthIndicator`, `DestinationHealth`,
  `NotificationChannels`, `SenderSignal`.
- **outbound-spring:** `OutboundFailureAnalyzer`, `OutboundSettings`.
- **errors:** `CodeTexts`.
- **notifications:** `TeamsChannel.payload` / `retiredConnector`, `SlackChannel.payload`.
- **crypto:** `VaultClient.writeSecret`.

### Test hooks removed from production classes

- `UsageRecord.withAmount(long)` → the testkit's `InMemoryUsageLedger.editAmountForTest`.
- `WebhookOptions.allowingPlainHttpForTests()` → package-private. `WebhookOptions` is now a final class with a public
  `WebhookOptions(Duration, Duration)` constructor; the three-argument record constructor is gone. Since P037, plain
  http goes only to this machine.

### Other changes

- **`ValueStore.delete(StoredRecord, EraseTicket)`.** A store deletes only with a ticket that `FieldValues` makes during
  an erasure through the erase log (P037).
- **Messages of start-up refusals and store failures now start with their code**, for example
  `LIC_SETTINGS_INVALID: …` and `IDN_STORE_FAILED: …`. Read `platformCode()` instead of the text.
- **`ValidationRecord` has a last component, `runsWhere`.** The old constructor is kept.
- **The runtime user of model governance** needs EXECUTE on `mg_purge_measures` and no longer DELETE on `mg_measure`
  (schema V3).
- **`platform.model-governance.forbidden-roles` is required.**
- **`platform.monitoring.every`** now sets the interval of checks that don't set their own.
- **monitoring-spring's mail check** reads notifications-spring's checked settings, as an optional dependency.

## New codes (21; no code renamed or removed)

| Group | Codes |
|---|---|
| Start-up settings, missing beans | `OUTBOUND_SETTINGS_INVALID`, `INTEGRATION_SETTINGS_INVALID`, `IDENTITY_PUSH_SETTINGS_INVALID`, `LIC_SETTINGS_INVALID`, `NOTIF_SETTINGS_INVALID`, `MOD_SETTINGS_INVALID`, `MON_SETTINGS_INVALID` |
| Store failures | `FLD_STORE_FAILED`, `IDN_STORE_FAILED`, `LIC_STORE_FAILED`, `NOTIF_STORE_FAILED`, `MG_STORE_FAILED`, `INT_CONFIG_UNREADABLE` |
| Model governance | `MOD_LOCATION_CHANGED` |
| Monitoring | `MON_DB_USER_TOO_POWERFUL` (HAVING_PROBLEMS) |
| Case fields | `FIELD_VALUES_CHECK_FAILED`; `FLD_PARTIAL_SEARCH_UNAVAILABLE` (INFO) |
| Erase log | `ERASE_HANDLER_ERROR` |
| Field-list import conflicts | `TYPE_DIFFERS`, `CURRENCY_DIFFERS`, `KEY_CASE_DIFFERS` |

The last six groups' codes were string codes or enum values before; they are now catalogued `PlatformCode`s with
their names kept. `docs/errors/code-names.txt` lists every code name ever released, and the build fails if one
disappears. The catalogue (`docs/errors/platform-error-codes.md` / `.json`) has 669 codes. The texts of
`ISSUE_VAULT_SEALED`, `ISSUE_VAULT_ERROR`, `SETUP_CONFIG_OTHER_TENANT`, `CRYPTO_KEY_MISSING` and
`MG_PRIVILEGES_MISSING` are corrected.

## Tests

The new tests (P037–P040):
- never-leak tests for every listed place;
- integration safety tests;
- **known-answer tests** of the value, record envelope, item and fingerprint formats, of what platform-fields seals,
  and of the licence's signed bytes, the ledger hash and the export chain. Every format matches the design;
- locks between instances and processes;
- DDL refused for every runtime user;
- every schema check's outcomes;
- a guard pinning every released SQL file.

1414 tests in total.

## Database schemas

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 4 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 2 |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | its own run, any history table (for example `notif_schema_history`) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |
| platform-model-governance | `db/platform/modelgov` | `mg_schema_history` | **4** (V3: the purge as the schema owner with a 30-day floor; V4: where the model ran, recorded with each validation) |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
