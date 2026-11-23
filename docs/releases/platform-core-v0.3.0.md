# platform-core 0.3.0

Integrations ready for the apps: Spring wiring and folder export (P025), mutual TLS with rotation, connection
pinning, signed callbacks and "accepted, check later" (P026), intake by API polling, watched folders and a virus-scan
hook (P027); and push-API callers, the outbound rules as their own module and platform-wide grants (P028). Every
module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.3.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Modules

All modules of 0.2.0, plus two new ones. What is new:

| Module | New in 0.3.0 |
|---|---|
| `platform-outbound` (**new**, P028) | The safe outbound HTTP rules, moved out of platform-integration: `Installation`, `AddressGuard` with connection pinning, `TrustBundle`, `ProxySettings` (with Basic sign-in), `Outbound` (Apache HttpClient 5, no redirects, timeouts and answer limits, client certificates), `ClientCertificate`, `CertificateInfo`, `RetryAfter`; failures `OutboundCode` (`OUT_*`). Package `com.documentplatform.outbound` |
| `platform-outbound-spring` (**new**, P028) | `platform.outbound.*` (installation, internal addresses, sign-in NONE, private CA file, proxy and its sign-in), beans `Installation` and `Outbound`, shared by identity's and integration's Spring modules |
| `platform-integration` | P025: folder export (`folder.FolderExport`, `config.FolderTarget`, `folder.ExportManifest` `platform-export-manifest/1`), proxy sign-in, JSON "omit when missing", `Rule.busyWhen503WithRetryAfter`. P026: connection pinning, mutual TLS with rotation (`credential.IntegrationCredentials`, `CredentialExpiry`), signed callbacks (`callback.Callbacks`, `CallbackSignature`, `CallbackVerifier`), accepted-check-later (`CallStep.Accepted`, `CallEngine.checkStatus`), schema V2. P027: intake (`intake.IntakeHandler`, `engine.PollEngine` with `config.PollSource`, `watch.WatchedFolder` with `config.WatchSource` and trigger files, `scan.FileScanner` with clamd and ICAP, `scan.Scanning`). P028: outbound rules from `platform-outbound`; no longer depends on `platform-identity-core` |
| `platform-integration-testkit` | `MockFolder` (export and watched folder), `MockProxy` sign-in, `TestCertificates`, `MockDestination.startMutualTls` and `acceptLater`, `InMemoryCredentialStore`, `InMemoryCallbackOutbox`, `MockSource`, `MockScanner`, `Eicar` |
| `platform-integration-spring` | P025: properties, beans and health contributor. P026: credentials, callbacks, `IntegrationWorker`. P027: `scanner.*`, `intake.work-folder`, `PollEngine`, `WatchedFolder`, health for sources and the scanner. P028: the installation and `Outbound` come from `platform.outbound.*` |
| `platform-identity-core` | P028: push-API callers (`push.PushCaller`, `PushCallerInput`, `PushCallerSettings`, `PushCallerIdentity`, `PushCallerResult`, `PushRefusal`, `PushCallerDirectory`, `DirectoryMachineSourceBindings`), `admission.MachineSourceBindings`; platform-wide grants (`store.PlatformGrantStore`, `PlatformGrantChange`, `GrantHistory.forPlatform`, `admission.PlatformGroupGrants`, `PlatformGroupAuthorization`, role-mapping rows with `entity_id` `*`) |
| `platform-identity-jdbc` | Identity schema **V4**; `PostgresPushCallerDirectory`; platform grants in `PostgresGroupGrantStore`, `PostgresIdentityLookup` and `PostgresGrantHistory` |
| `platform-identity-testkit` | The same in memory (`InMemoryIdentityStore.pushCallerDirectory()`, platform grants) |
| `platform-identity-spring` | `PushCaller` bean from `platform.identity.push.*` and the helper `PushCallerRequests`; `OutboundIssuerFetch` (moved here) on `platform-outbound-spring`: the test sign-in works without platform-integration |
| `platform-setup` | `*` rows in the role mapping file (platform roles for the customer and product); certificate client IDs `cert-sha256:<hex>` in the client-binding file |

## Design docs implemented

`docs/design/integration-module.md` (decisions on P025 to P028), `docs/design/integration-profile-format.md`
(`poll`, `accepted`, `MUTUAL_TLS`), `docs/design/setup.md` sections 6.1 (certificate client IDs, additive) and 8
(P028), on top of everything in 0.2.0.

## New codes

- **P025** folder export: `INT_FOLDER_NOT_FOUND`, `INT_FOLDER_NO_PERMISSION`, `INT_FOLDER_FULL`,
  `INT_FOLDER_UNREACHABLE`, `INT_FOLDER_EXISTS_DIFFERENT`, `INT_FOLDER_WRITE_FAILED`; proxy:
  `INT_PROXY_SIGN_IN_REFUSED`; configuration: `INT_CONFIG_OMIT_PLACE`, `INT_CONFIG_FOLDER_INVALID`,
  `INT_CONFIG_FOLDER_HOSTED`; Spring: `INTEGRATION_SETTINGS_INVALID`.
- **P026** client certificates: `INT_CREDENTIAL_FILE_INVALID`, `_PASSWORD_WRONG`, `_KEY_MISMATCH`, `_EXPIRED`,
  `_NOT_YET_VALID`, `_MISSING`, `_KEY_SERVICE_FAILED`, `_REFUSED`, `_KEYSTORE_UNREADABLE`, `_ALIAS_NOT_FOUND`,
  `_NEXT_EXISTS`, `_NO_NEXT`, `_CONFLICT`, `INT_CONFIG_MUTUAL_TLS_INVALID`; callbacks: `INT_CALLBACK_SIGNATURE`,
  `_EXPIRED`, `_REPLAYED`, `_BODY_INVALID`, `_NOT_FOUND`, `INT_CONFIG_CALLBACK_INVALID`; accepted-check-later:
  `INT_ACCEPTED`, `INT_STATUS_FAILED`, `INT_STATUS_REFERENCE_MISSING`, `INT_STATUS_UNKNOWN`,
  `INT_CONFIG_ACCEPTED_INVALID`; outcome `ACCEPTED`.
- **P027** polling: `INT_POLL_ITEMS_MISSING`, `INT_POLL_ITEM_ID_MISSING`, `INT_POLL_NEXT_PAGE_INVALID`,
  `INT_POLL_STORAGE_FAILED`, `INT_POLL_HANDLER_FAILED`; watched folder: `INT_WATCH_ROOT_NOT_FOUND`,
  `INT_WATCH_NO_PERMISSION`, `INT_WATCH_SHARE_UNREACHABLE`, `INT_WATCH_TRIGGER_UNREADABLE`, `INT_WATCH_CLAIM_LOST`,
  `INT_WATCH_MOVE_FAILED`, `INT_WATCH_FILE_CHANGED`, `INT_WATCH_DROP_INVALID`, `INT_WATCH_INCOMPLETE`,
  `INT_WATCH_REFUSED`, `INT_WATCH_HANDLER_FAILED`; scanner: `INT_SCAN_INFECTED`, `INT_SCAN_FAILED`,
  `INT_SCAN_UNREACHABLE`; configuration: `INT_CONFIG_POLL_INVALID`, `INT_CONFIG_WATCH_INVALID`.
- **P028** push callers (`PushRefusal`): `IDN_PUSH_NO_CONNECTION`, `IDN_PUSH_AUDIENCE`, `IDN_PUSH_NO_CLIENT_ID`,
  `IDN_PUSH_NOT_BOUND`, `IDN_PUSH_BINDING_OFF`, `IDN_PUSH_ENTITY_NOT_ACTIVE`, `IDN_PUSH_CLAIM_MISMATCH`,
  `IDN_PUSH_CERT_NOT_BOUND`, `IDN_PUSH_TOKEN_AND_CERT_DIFFER`; Spring: `IDENTITY_PUSH_SETTINGS_INVALID`. Outbound
  (`OutboundCode`): `OUT_CANT_CONNECT`, `OUT_HOST_UNKNOWN`, `OUT_CONNECT_TIMEOUT`, `OUT_TIMEOUT`,
  `OUT_CONNECTION_LOST`, `OUT_TLS_FAILED`, `OUT_ADDRESS_BLOCKED`, `OUT_ADDRESS_NOT_SECURE`, `OUT_PROXY_REFUSED`,
  `OUT_PROXY_SIGN_IN_REFUSED`, `OUT_CREDENTIAL_REFUSED`, `OUT_SECRET_NOT_FOUND`, `OUT_SECRET_UNAVAILABLE`,
  `OUT_VALUE_NOT_ALLOWED`; Spring: `OUTBOUND_SETTINGS_INVALID`. Platform grants: role mapping
  `PLATFORM_ROLE_ON_ENTITY`, `ENTITY_ROLE_PLATFORM_WIDE`; setup `SETUP_PLATFORM_ROLE_ON_ENTITY`,
  `SETUP_ENTITY_ROLE_PLATFORM_WIDE`; admission `ENTITY_ROLE_IN_PLATFORM_GRANT`.

## Breaking changes

No app uses platform-integration yet, so nothing outside platform-core breaks. Within platform-core:

- **Moved packages** (P028): `com.documentplatform.integration.safety.Installation`, `.AddressGuard`,
  `.ProxySettings`, `.TrustBundle`, `com.documentplatform.integration.http.Outbound`, `.RetryAfter` and
  `com.documentplatform.integration.credential.ClientCertificate`, `.CertificateInfo` are now in
  `com.documentplatform.outbound` (module `platform-outbound`). `Outbound.Failure.code()` and
  `AddressGuard.check` return `OutboundCode`; integration maps them with `IntegrationCode.of`, and every integration
  result keeps its `INT_*` code. `com.documentplatform.integration.http.OutboundIssuerFetch` is now
  `com.documentplatform.identity.spring.OutboundIssuerFetch` (module `platform-identity-spring`), and the test
  sign-in's refusal reason is the `OUT_*` code (was `INT_*`).
- **Settings** (P028): `platform.integration.installation`, `.allow-internal-addresses`, `.allow-sign-in-none`,
  `.private-ca-file` and `.proxy.*` are now `platform.outbound.*`; the old names stop the app naming the new one.
- **Dependencies**: platform-integration no longer brings platform-identity-core; platform-identity-spring now
  depends on platform-outbound-spring (and so on Apache HttpClient 5) instead of an optional platform-integration.
- **Role mapping files read for setup** (P028): a platform role on an entity row now gives
  `PLATFORM_ROLE_ON_ENTITY` (was `PLATFORM_ROLE`); files read without a product or slot IDs keep `PLATFORM_ROLE`.
- **Identity schema V4**: run the identity schema step (`V4__platform_grants.sql`) before starting an app on 0.3.0;
  until then it stops with `IDN_VERSION_OLDER`. New interface methods (`GrantHistory.forPlatform`) are default
  methods.
- The P025-only proxy code is gone (`INT_PROXY_SIGN_IN_DISABLED_BY_JVM`, `safety.JvmProxySignIn`); it was never
  released.

## Database schemas

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | **4** (`V4__platform_grants.sql`: `idn_platform_grant`; `idn_grant_history.scope` ENTITY or PLATFORM) |
| platform-integration | `db/platform/integration` | `int_schema_history` | **2** (`V2__credentials_callbacks.sql`: `int_credential`, `int_credential_change`, `int_callback_outbox`, kind CALLBACK) |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | the app's (location added to its own run) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
