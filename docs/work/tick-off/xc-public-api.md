# Tick-off: cross-check B2.6, public API surface

Scope: every non-testkit, non-catalogue module (22 modules). This is a read-only review: nothing was changed.

Method: I listed every top-level `public class|final class|record|interface|enum` under `src/main/java` (550 types).
For each type I found every reference by fully qualified name (imports and inline FQNs, javadoc links included) in all
modules' main and test code. I then compared that with the intended surface: the CLAUDE.md module table,
`docs/install/*.md` and `docs/design/*.md`. The usage columns behind this file mean:
- "other pkg": the type is used from another package of the same module;
- "x-module": main code of another non-testkit module uses it;
- "testkit": a testkit module's main code uses it.

**Findings for every module.**
- No module has a `module-info.java`, so every public type is exported API.
- None has an `internal` package, an `@Internal` marker or a guard test on visibility.
- Package-private is already used for 42 top-level helper types. Examples:
  - `com.documentplatform.crypto.vault.VaultJson`
  - `com.documentplatform.eraselog.{Applying, InStep}` and `com.documentplatform.eraselog.file.Lines`
  - `com.documentplatform.fields.jdbc.Sql` and `com.documentplatform.fields.values.ValueCodec`
  - `com.documentplatform.identity.mapping.MappingCsv` and `com.documentplatform.identity.signin.MiniJson`
  - `com.documentplatform.identity.jdbc.{Db, Sql}`
  - `com.documentplatform.integration.signin.ClientAssertion`
  - `com.documentplatform.licensing.command.DriverManagerDataSource`, `com.documentplatform.licensing.issue.VaultJson` and `com.documentplatform.licensing.jdbc.Sql`
  - `com.documentplatform.licensing.spring.LicenceStartupCheck` and `com.documentplatform.licensing.spring.notifications.{LicensingNoticeWorker, LicensingNotificationsConfig}`
  - `com.documentplatform.modelgov.{PackPdf, Values}`
  - `com.documentplatform.monitoring.spring.{BuiltInChecks, DependencyHealth, IntegrationSource}`
  - `com.documentplatform.notifications.{ConditionTracker, Keys, MessageBuilder, Occurrence, Rules, SummaryBuckets, Text}`
  - `com.documentplatform.notifications.approval.Ids` and `com.documentplatform.notifications.email.{ClientAssertion, TokenJson}`
  - `com.documentplatform.notifications.spring.{NotificationWorkers, ObservedChannel}`
  - `com.documentplatform.outbound.spring.OnOutboundSettings`
  - `com.documentplatform.setup.{ConfigFragment, Connections, SetupInputs, SetupRun}`
- Members are package-private too in places, for example the test hooks `FailureLog.clear()`, `ReferenceId.useClock(Clock)` and `FieldValues.eraseRecord`.
- No public non-final fields were found in any module.

**What the labels mean.**
- `yes`: the module table, an install guide or a design doc names the type, or an app has to implement, build or receive it.
- `cross-module only`: another platform module needs the type, but apps should not use it. It has to stay public until there is a module system or an `internal` package convention.
- `internal candidate`: only the type's own package (or its tests and testkit) uses it, and no doc names it for apps.

**Records and code enums.** Records always have a public canonical constructor. Code enums are `PlatformCode` and stay
public. `CatalogueTest` finds them by reflection, and apps compare against them.

Counts per module (yes / cross-module only / internal candidate):
- platform-errors 6 / 1 / 1
- platform-monitoring 19 / 0 / 0
- platform-monitoring-spring 6 / 0 / 2
- platform-model-governance 28 / 0 / 0
- platform-model-governance-spring 3 / 0 / 2
- platform-identity-core 59 / 15 / 2
- platform-identity-jdbc 13 / 2 / 0
- platform-identity-spring 10 / 0 / 0
- platform-notifications 76 / 4 / 8
- platform-notifications-spring 2 / 0 / 7
- platform-licensing 41 / 9 / 10
- platform-licensing-spring 5 / 2 / 4
- platform-setup 1 / 0 / 6
- platform-fields 38 / 0 / 6
- platform-crypto 13 / 2 / 0
- platform-crypto-spring 2 / 0 / 4
- platform-erase-log 21 / 0 / 0
- platform-erase-log-spring 4 / 0 / 3
- platform-outbound 8 / 0 / 1
- platform-outbound-spring 2 / 1 / 2
- platform-integration 79 / 2 / 11
- platform-integration-spring 3 / 0 / 4

Gaps: A 0 · B 2 · C 16

---

## platform-errors (`com.documentplatform.errors`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.errors.PlatformCode` | yes | Every code enum implements it; used by 12 modules. |
| `com.documentplatform.errors.HasPlatformCode` | yes | Implemented by every module's exceptions. |
| `com.documentplatform.errors.PlatformError` | yes | API error body (error-codes guide). |
| `com.documentplatform.errors.ReferenceId` | yes | `current()` / `setCurrent` per request (RequestReferenceFilter, apps' non-HTTP threads). Its test hook `useClock` is already package-private. |
| `com.documentplatform.errors.FailureLog` | yes | Read by the diagnostic bundle; its test hook `clear()` is already package-private. |
| `com.documentplatform.errors.SafeText` | yes | Apps clean details with it (error-codes guide). |
| `com.documentplatform.errors.Catalogue` | cross-module only | Only the `platform-error-catalogue` build check uses it (`com.documentplatform.errors.catalogue.CatalogueTest`, another module and package), so it must stay public. Apps have no use for it. |
| `com.documentplatform.errors.CodeTexts` | internal candidate | Used only by `PlatformCode`'s default methods, in the same package; no doc names it. `of`, `family` and `resourceName` are public. It can be package-private (xc-public-api-11). |

## platform-monitoring (`com.documentplatform.monitoring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.monitoring.{DependencyCheck, Verdict, CheckResult, CheckState, CheckRunner, StartupChecks, DependencyStatus, MonitoringEvent, MonitoringCode, DiagnosticBundle}` | yes | Module table and monitoring design/install guides; apps write their own `DependencyCheck` beans. |
| `com.documentplatform.monitoring.checks.{DatabaseCheck, KeyServiceCheck, SecretStoreCheck, LoginSystemCheck, LicenceCheck, ChannelCheck, FileLocationCheck, HttpServiceCheck, IntegrationChecks}` | yes | Built-in checks; apps without Spring build them, and monitoring-spring builds them from another package. |

There are no internal candidates. The module is small and every type is part of the documented model.

## platform-monitoring-spring (`com.documentplatform.monitoring.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.monitoring.spring.MonitoringAutoConfiguration` | yes | Framework: named in `AutoConfiguration.imports`; integration-spring orders after it. |
| `com.documentplatform.monitoring.spring.MonitoringProperties` | yes | Bound by Spring for `platform.monitoring.*`. |
| `com.documentplatform.monitoring.spring.MonitoringListener` | yes | App bean (module table). |
| `com.documentplatform.monitoring.spring.AppVersions` | yes | Optional app bean for the bundle. |
| `com.documentplatform.monitoring.spring.DiagnosticBundles` | yes | Bean apps call to build a bundle. |
| `com.documentplatform.monitoring.spring.RequestReferenceFilter` | yes | Module table (`X-Request-Id`, MDC `ref`). |
| `com.documentplatform.monitoring.spring.MonitoringFailureAnalyzer` | internal candidate | Registered in `spring.factories`; apps never touch it. Low value (Spring can instantiate non-public classes). |
| `com.documentplatform.monitoring.spring.MonitoringMetrics` | internal candidate | Only the metric-name constants (`STATE`, `DURATION`, `ATTEMPTS`) are useful to apps. The class itself is wiring. Keep the constants public and hide the rest. |

## platform-model-governance (`com.documentplatform.modelgov`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.modelgov.{ModelGovernance, ModelGate, LiveModel, ModelStamp, ModelMeasures, ModelReviews, ModelPack, PackTexts, ModelGovAudit, ModelEvents, ModelEvent, ModelGovSettings, ModelGovStore, ModelGovCode, ModelGovException}` | yes | Module table P033/P034; services, app ports and the code. |
| `com.documentplatform.modelgov.{Model, ModelDigest, ManifestDigests, ModelUse, ConfigFingerprint, Approver, Threshold, ValidationRecord, Decisions, Monitoring}` | yes | Data the app builds or reads. `Decisions` and `Monitoring` are holders of nested public records (`Decisions.Approval / Acceptance / Live / LiveChange`, `Monitoring.MonitorThreshold / MeasurePeriod / Reading / Drift / DriftAction`) that store implementers need. |
| `com.documentplatform.modelgov.jdbc.{PostgresModelGovStore, ModelGovSchema, ModelGovSchemaException}` | yes | Apps without Spring build the store; `ModelGovSchema.verify` is the startup check. |

There are no internal candidates. `PackPdf` and `Values` are already package-private.

## platform-model-governance-spring (`com.documentplatform.modelgov.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.modelgov.spring.ModelGovAutoConfiguration` | yes | Framework (auto-configuration). |
| `com.documentplatform.modelgov.spring.ModelGovProperties` | yes | Bound `platform.model-governance.*`. |
| `com.documentplatform.modelgov.spring.ModelGovWorker` | yes | Module table; the "also for tests" one-run method is acceptable as an app-callable action. |
| `com.documentplatform.modelgov.spring.ModelGovConfig` | internal candidate | The checked settings record. `ModelGovConfig.Invalid` is the startup failure, so keep the exception public if this type is hidden. |
| `com.documentplatform.modelgov.spring.ModelGovCheck` | internal candidate | Registered as a `DependencyCheck` bean by the auto-configuration; apps get it by type `DependencyCheck`. |

## platform-identity-core (`com.documentplatform.identity`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.identity.{AuthenticatedIdentity, IdentityClaimConfig, IdentityClaims, StableIdentityId, OidcConnectionBinding, OidcConnectionBindingResolver, IdentityConnectionStatus, IdentityProviderType, EntityUuid}` | yes | Claims and bindings model (module table: claims, OIDC connection bindings). |
| `com.documentplatform.identity.admission.{EntityMembershipResolver, EntitySelection, TrustedEntityAdmission, SharedIssuerPreflight, AdmissionSettings, IdentityAdmissionException, EntityGroupAuthorization, PlatformGroupAuthorization, MachineEntityAdmission, EntityRoleCatalog, EntityId, ConnectionKey}` | yes | Entity admission, role mapping, platform group authorization (module table). |
| `com.documentplatform.identity.admission.{ActiveEntityCandidates, EntityGroupGrants, PlatformGroupGrants, MachineEntityBindings, MachineSourceBindings}` | yes | Ports the app (or identity-jdbc) implements. |
| `com.documentplatform.identity.signin.{OidcTestSignIn, TestSignIn, TestReport, IssuerFetch, LocalTestIssuers, DevelopmentLicence}` | yes | Test sign-in (module table); `LocalTestIssuers` is also used by setup and identity-spring. |
| `com.documentplatform.identity.push.{PushCaller, PushCallerInput, PushCallerResult, PushCallerIdentity, PushCallerSettings, PushCallerDirectory, PushRefusal, DirectoryMachineSourceBindings}` | yes | Push-API callers (P028). |
| `com.documentplatform.identity.store.{LoginConnections, LoginConnection, LoginConnectionStore, ConnectionOutcome, ConnectionValues, ActivationOutcome, ChangeSource, ConnectionChange, SlotActivation, EntityTimeZones, EntityTimeZone, TimeZoneOutcome, GrantHistory, GrantChange, GrantAuthor, PlatformGrantChange, PlatformGrantStore, GroupGrantStore, GrantStep, EntityStore, EntityRecord, EntityStatus, LicensedSlots, MachineBinding, MachineBindingChange, MachineBindingStatus, MachineBindingStore, SlotSync}` | yes | Store interfaces and their records ("store interfaces live in identity-core"); identity-jdbc and the testkit implement them. |
| `com.documentplatform.identity.mapping.Grant` | yes | Grant model, shared by setup and jdbc. |
| `com.documentplatform.identity.mapping.RoleMappingFile` | cross-module only | The parser of the role mapping CSV; only platform-setup uses it. |
| `com.documentplatform.identity.mapping.MappingPlan` | cross-module only | Setup's plan of the mapping. |
| `com.documentplatform.identity.mapping.MappingRow` | cross-module only | Setup only. |
| `com.documentplatform.identity.mapping.MappingError` | cross-module only | Setup only. |
| `com.documentplatform.identity.mapping.SlotTargets` | cross-module only | Setup's port. |
| `com.documentplatform.identity.mapping.ClientBindingsFile` | cross-module only | The client-binding file parser; only setup uses it (the module table puts it in identity-core for setup). |
| `com.documentplatform.identity.mapping.MappingTargets` | internal candidate | No reference outside its own package in any module, and no doc names it. Its javadoc says "implemented by the product". Either it is dead API or the docs are missing it: check before the release. |
| `com.documentplatform.identity.admission.*`, `com.documentplatform.identity.store.*` used by identity-jdbc only (`ActiveEntityCandidates`, `EntityGroupGrants`, `GrantStep`, `SlotSync`, `ConnectionValues`, `ActivationOutcome`, `MachineBindingChange`, `GroupGrantStore`) | cross-module only | Store SPI between identity-core and identity-jdbc and the testkit; apps that bring their own store implement them. Keep them public, but document them as the store SPI, not as app calls. |

## platform-identity-jdbc (`com.documentplatform.identity.jdbc`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.identity.jdbc.{IdentitySchema, IdentitySchemaException, PostgresIdentityLookup, PostgresEntityStore, PostgresGroupGrantStore, PostgresGrantHistory, PostgresLoginConnectionStore, PostgresMachineEntityBindings, PostgresMachineClients, MachineClients, PostgresPushCallerDirectory, LicenceSlots, LicenceSigningKey}` | yes | Module table: the Postgres stores the app builds (identity has no Spring wiring for most of them), the startup check, and the `MachineClients` port. |
| `com.documentplatform.identity.jdbc.PostgresMachineBindingStore` | cross-module only | Written by setup only (`idn_machine_binding`); apps read through `PostgresMachineClients`. |
| `com.documentplatform.identity.jdbc.IdentityStoreException` | cross-module only | Thrown to setup; apps see it through the stores. |

## platform-identity-spring (`com.documentplatform.identity.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.identity.spring.{OidcJwtIdentityNormalizer, RequiredAudienceValidator, CachingOidcConnectionBindingResolver}` | yes | "Spring Security adapters" (module table); apps wire them into their security chain. |
| `com.documentplatform.identity.spring.{PushCallerRequests, OutboundIssuerFetch}` | yes | Module table. |
| `com.documentplatform.identity.spring.PushCallerProperties` | yes | Bound `platform.identity.push.*`. |
| `com.documentplatform.identity.spring.{IdentityIssuerFetchAutoConfiguration, IdentityLoginConnectionStoreAutoConfiguration, IdentityLoginConnectionsAutoConfiguration, IdentityPushCallerAutoConfiguration}` | yes | Framework (auto-configuration; monitoring-spring orders after two of them). |

There are no internal candidates.

## platform-notifications (`com.documentplatform.notifications`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.notifications.{Notifier, NotificationEvent, NotificationSettings, NotificationException, EventCatalogue, EventType, EventTypeId, EventKind, EventDefaults, EventSettings, FieldSpec, Frequency, Schedule, NumericThreshold, ConditionKey, ConditionTiming, RecordIdentifier, AppLink, ActionLink, ActionLinks, ActionAudience, TenantId, EntityId, RoleId, Recipient, RecipientResolver, TenantSettings, DestinationId, ChannelKind, CustomerZones, EntityZones, RetryPolicy, Message, MessageKind, DeliveryReport, DeliveryStatus, FailureCode}` | yes | The catalogue, settings, routing and message model (design `notifications.md`). |
| `com.documentplatform.notifications.{Channel, OutgoingMessage, SendResult, OutboxStore, OutboxEntry, OutboxState, SettingsStore, SummaryStore, SummaryItem, SummaryBucket, ConditionStore, ConditionState}` | yes | Sender and store SPI that the testkit and the Postgres stores implement and an app may replace. |
| `com.documentplatform.notifications.approval.{Approvals, ApprovalType, ApprovalAudit, ApproverCheck, ApprovalRequest, NewApprovalRequest, ApprovalResult, ApprovalAction, ApprovalRefusal, Decision, SignedInPerson, ApprovalStore, ApprovalLinks, ActionTokens, ApprovalKeyException}` | yes | Approval links (module table). |
| `com.documentplatform.notifications.email.{EmailChannel, MailServer, MailServerDirectory, MailSecurity, MailAuth, MicrosoftOAuth, MicrosoftTokens}` | yes | SMTP sender and Microsoft 365 OAuth (module table). |
| `com.documentplatform.notifications.teams.TeamsChannel` | yes | Sender. |
| `com.documentplatform.notifications.slack.SlackChannel` | yes | Sender. |
| `com.documentplatform.notifications.webhook.WebhookDirectory` | yes | App port (webhook addresses as secret references). |
| `com.documentplatform.notifications.webhook.WebhookOptions` | yes, but see gap | Timeouts are configuration. Two parts are test switches on the production type: the public `allowingPlainHttpForTests()` and the `allowPlainHttp` record component (xc-public-api-3). |
| `com.documentplatform.notifications.jdbc.{PostgresOutboxStore, PostgresSettingsStore, PostgresSummaryStore, PostgresConditionStore, PostgresApprovalStore, NotificationSchema, NotificationSchemaException, NotificationStoreException}` | yes | Postgres stores and the startup check (built by notifications-spring from another package, and by apps without Spring). |
| `com.documentplatform.notifications.secret.{SecretRef, SecretResolver, Secret}` | cross-module only (de facto platform-wide) | The secret port of the whole platform: outbound, integration, monitoring and their Spring modules use it, and apps implement `SecretResolver`. Apps do use it, but it lives in the notifications package (xc-public-api-18). |
| `com.documentplatform.notifications.webhook.WebhookClient` | internal candidate | Shared by `teams` and `slack` (another package), so package-private would need a move. Its public `post(OutgoingMessage, String, Function<URI, Optional<FailureCode>> addressCheck)` lets a caller choose the address check. |
| `com.documentplatform.notifications.webhook.Json` | internal candidate | A minimal JSON writer for payloads; used by `teams` and `slack` only. |
| `com.documentplatform.notifications.teams.TeamsChannel.payload(Message)` and `retiredConnector(URI)` (public static members) | internal candidate | Helpers that are public for tests. |
| `com.documentplatform.notifications.SummaryBucket` | internal candidate | Built only by the package-private `SummaryBuckets`; the store SPI does not take it (only tests use it). |
| `com.documentplatform.notifications.ConditionState` | internal candidate | Used by the testkit store only through `ConditionStore`. Keep it if `ConditionStore` stays SPI; it is listed here so the store SPI gets one decision. |
| `com.documentplatform.notifications.OutboxEntry` / `OutboxState` | internal candidate | Same as above: SPI rows of `OutboxStore`. |

Notifications already has 13 package-private helpers (rules, builder, buckets, tracker, texts), the most of any module.

## platform-notifications-spring (`com.documentplatform.notifications.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.notifications.spring.NotificationsAutoConfiguration` | yes | Framework (licensing-spring and monitoring-spring order after it). |
| `com.documentplatform.notifications.spring.NotificationsProperties` | yes | Bound `platform.notifications.*`. |
| `com.documentplatform.notifications.spring.NotificationsConfig` | internal candidate | The checked config; no other module uses it. |
| `com.documentplatform.notifications.spring.NotificationsConfigException` | internal candidate | Startup failure. Keep it public if apps assert on it in tests; otherwise hide it. |
| `com.documentplatform.notifications.spring.NotificationsFailureAnalyzer` | internal candidate | Registered in `spring.factories`. |
| `com.documentplatform.notifications.spring.NotificationsHealthIndicator` | internal candidate | Actuator bean. |
| `com.documentplatform.notifications.spring.DestinationHealth` | internal candidate | Internal state of the health indicator. |
| `com.documentplatform.notifications.spring.NotificationChannels` | internal candidate | Wiring helper. |
| `com.documentplatform.notifications.spring.SenderSignal` | internal candidate | Wakes the sender; wiring only. |

## platform-licensing (`com.documentplatform.licensing`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.licensing.{LicenceService, LicenceStatus, LicenceState, Licence, LicenceKind, LicenceType, Deployment, Allowance, AllowanceType, EntitySlot, LicenceException, LicenceRefusal, LicenceVerifier, TrustedKeys, LastSeenReset, ResetException, ResetRefusal}` | yes | `LicenceService` and its model (design `licensing.md`). |
| `com.documentplatform.licensing.{LicenceStore, LastSeenStore, NoticeLog}` | yes | Store ports (Postgres stores and testkit). |
| `com.documentplatform.licensing.usage.{UsageRecorder, UsageLedger, UsageEntry, UsageRecord, UsageLevel, UsageAssessment, UsageError, UsageException, UsageExport, UsageExportVerifier, ExportAnchor, ExportCheck, ChainCheck, ChainHead, UsageChain}` | yes | Usage levels, ledger, export and verifier (module table). `UsageChain` is needed by anyone writing a ledger store. |
| `com.documentplatform.licensing.jdbc.{PostgresLicenceStore, PostgresLastSeenStore, PostgresUsageLedger, PostgresNoticeLog, LicensingSchema, LicensingSchemaException}` | yes | Postgres stores and the startup check. |
| `com.documentplatform.licensing.command.LicenceCommand` | yes | Customer-side licence command. |
| `com.documentplatform.licensing.jdbc.LicensingStoreException` | cross-module only | Seen by setup. |
| `com.documentplatform.licensing.ClockGuard` | cross-module only | The effective-time clock rule; used by licensing-spring and setup. Apps should go through `LicenceService`. |
| `com.documentplatform.licensing.ClockReading` | cross-module only | Licensing-spring only. |
| `com.documentplatform.licensing.json.Json` | cross-module only | The strict JSON of the licence files; setup uses it to read its input files (xc-public-api-7). |
| `com.documentplatform.licensing.json.Fields` | cross-module only | As above. |
| `com.documentplatform.licensing.json.JsonException` | cross-module only | As above. |
| `com.documentplatform.licensing.issue.IssueLicenceCommand` | cross-module only (our side) | The issuing command ("our side only"). It is shipped in the customer-side jar that the BOM lists (xc-public-api-5). |
| `com.documentplatform.licensing.issue.VaultTransitSigner` | cross-module only (our side) | As above. |
| `com.documentplatform.licensing.issue.Signer` | cross-module only (our side) | As above; the testkit implements it. |
| `com.documentplatform.licensing.issue.IdGenerator` | internal candidate | Used only by `IssueLicenceCommand` (same package). |
| `com.documentplatform.licensing.issue.IssuedRecords` | internal candidate | Same. |
| `com.documentplatform.licensing.issue.IssueError` / `IssueException` | internal candidate (keep the code enum public) | Only the issuing side uses them; the testkit references them. |
| `com.documentplatform.licensing.LicenceSigner` | internal candidate | Writes a signed licence from a `PrivateKey`. Customers never need it; it is there for the issuing side and the testkit. |
| `com.documentplatform.licensing.LicenceFormat` | internal candidate | Only the signer and verifier in the same package use it. |
| `com.documentplatform.licensing.SignedDocument` | internal candidate | The signed envelope, used inside the package. `SignedDocument.MessageSigner` appears in the public `LicenceSigner.sign` signature. |
| `com.documentplatform.licensing.Ed25519` | internal candidate (partly) | `verify` and `decodePublic` are used by licensing-spring (key files). `generate`, `encodePrivate` and `decodePrivate` are documented "for tests and key ceremonies only" (xc-public-api-15). |
| `com.documentplatform.licensing.usage.UsageTotals` | internal candidate | Used only in the `usage` package. |
| `com.documentplatform.licensing.usage.UsageRecord.withAmount(long)` (method) | internal candidate | Its javadoc says "For tests". It builds a record whose hash does not match its contents (xc-public-api-15). |

## platform-licensing-spring (`com.documentplatform.licensing.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.licensing.spring.LicensingAutoConfiguration` | yes | Framework (identity-spring and monitoring-spring order after it). |
| `com.documentplatform.licensing.spring.LicensingProperties` | yes | Bound `platform.licensing.*`. |
| `com.documentplatform.licensing.spring.LicensingClock` | yes | Apps (and tests) may define the bean. |
| `com.documentplatform.licensing.spring.notifications.ActivatedSlots` | yes | App bean (module table). |
| `com.documentplatform.licensing.spring.notifications.LicensingEvents` | yes | Apps add these to their catalogue (module table). |
| `com.documentplatform.licensing.spring.LicensingConfig` | cross-module only | identity-spring reads the tenant from it. |
| `com.documentplatform.licensing.spring.notifications.LicensingNotificationsAutoConfiguration` | cross-module only | Framework; it is listed in imports, so it stays public. |
| `com.documentplatform.licensing.spring.LicensingConfigException` | internal candidate | Startup failure. |
| `com.documentplatform.licensing.spring.LicensingFailureAnalyzer` | internal candidate | `spring.factories`. |
| `com.documentplatform.licensing.spring.LicensingHealthIndicator` | internal candidate | Actuator bean. |
| `com.documentplatform.licensing.spring.notifications.LicensingNotices` | internal candidate | Run by the package-private `LicensingNoticeWorker`. |

## platform-setup (`com.documentplatform.setup`)

This module is a command line tool, and its only entry is `SetupCommand.main`.

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.setup.SetupCommand` | yes | The setup command (`setup plan|apply`) and its exit codes and environment names. Its constructor `SetupCommand(Map, Clock, SetupKeys.Connector)` is a test seam and could be package-private (tests are in the same package). |
| `com.documentplatform.setup.SetupRefusal` | internal candidate (keep the code enum public) | `SETUP_*` codes. They must stay public for the catalogue and for apps that read the run log. The rest of setup does not need to be public. |
| `com.documentplatform.setup.Plan` | internal candidate | Nothing outside the package uses it. |
| `com.documentplatform.setup.ProductRoles` | internal candidate | The `product-roles.json` reader; same package only. |
| `com.documentplatform.setup.SigninFile` | internal candidate | The sign-in file reader; same package only. |
| `com.documentplatform.setup.SetupKeys` | internal candidate | Its nested `Connector` is in the public `SetupCommand` constructor. Hide both together. |
| `com.documentplatform.setup.SetupException` | internal candidate | Caught inside `SetupCommand`. |

## platform-fields (`com.documentplatform.fields`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.fields.{FieldSpec, FieldKey, FieldScope, FieldType, SearchMode, Normalisation, FieldDefinition, FieldDefinitions, FieldDefinitionException, FieldStore, FieldValuesCheck, FieldValidator, FieldSet, FieldValue, ValidatedFields, ValidValue, ValueError, ValueProblem}` | yes | Definitions and validation (design `case-fields.md`); `FieldKey`, `FieldType`, `FieldSet` and others are also used by integration. |
| `com.documentplatform.fields.file.{FieldListFile, FieldImport, ImportReport, FieldFileException}` | yes | Field list file and import. |
| `com.documentplatform.fields.delivery.{FieldDelivery, DeliveryResult, DeliveryProblem}` | yes | Delivery format and verifier; also used by integration. |
| `com.documentplatform.fields.values.{FieldValues, RecordRef, RecordValues, SearchResult, LegalHolds, FieldValuesError, FieldValuesException}` | yes | Value storage (module table). |
| `com.documentplatform.fields.values.{ValueStore, StoredRecord, StoredValue, SearchMatch}` | yes (store SPI) | Implemented by Postgres and the testkit and passed to `FieldValues`. `ValueStore.delete` is public on the interface (xc-public-api-2). |
| `com.documentplatform.fields.jdbc.{PostgresFieldStore, FieldSchema, FieldSchemaException, FieldStoreException}` | yes | There is no fields-spring module, so the app builds these itself. |
| `com.documentplatform.fields.jdbc.PostgresValueStore` | yes, but see gap | The app builds it itself. Its public `delete(StoredRecord)` deletes a record and its envelope without the erase log. That bypasses the `VALUES_ERASE_OUTSIDE_LOG` rule that `FieldValues.erase` enforces (xc-public-api-2). |
| `com.documentplatform.fields.json.Json` | internal candidate | The strict JSON subset; used only by `file` and `delivery` in this module. Package-private would need a move, or one shared JSON helper (xc-public-api-7). |
| `com.documentplatform.fields.json.JsonFields` | internal candidate | Same. |
| `com.documentplatform.fields.json.JsonException` | internal candidate | Same. It appears in the throws of public readers, so map it to `FieldFileException` first. |

## platform-crypto (`com.documentplatform.crypto`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.crypto.{KeyService, CustomerId, CryptoError, CryptoException, KeyDestruction}` | yes | Module table and `crypto.md`. |
| `com.documentplatform.crypto.envelope.{ValueCipher, RecordCipher, StreamCipher, EnvelopeInfo}` | yes | Ciphers. |
| `com.documentplatform.crypto.fingerprint.Fingerprinter` | yes | Exact-match search. |
| `com.documentplatform.crypto.vault.{VaultKeyService, VaultAuth}` | yes | Apps without Spring build them. |
| `com.documentplatform.crypto.command.CryptoCommand` | yes | `crypto destroy-customer-keys`. |
| `com.documentplatform.crypto.vault.VaultClient` | cross-module only | crypto-spring builds it. Its public `readSecret` / `writeSecret` give KV v2 read and write to anyone holding the client. The Vault policy still applies, so a doc note is enough (xc-public-api-12). |
| `com.documentplatform.crypto.vault.VaultEnvironment` | cross-module only | `PLATFORM_SETUP_VAULT_*`; setup and the crypto command use it. |

`VaultJson` is already package-private.

## platform-crypto-spring (`com.documentplatform.crypto.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.crypto.spring.CryptoAutoConfiguration` | yes | Framework (monitoring-spring orders after it). |
| `com.documentplatform.crypto.spring.CryptoProperties` | yes | Bound `platform.crypto.*`. |
| `com.documentplatform.crypto.spring.CryptoConfig` | internal candidate | The checked config. |
| `com.documentplatform.crypto.spring.CryptoConfigException` | internal candidate | Startup failure. |
| `com.documentplatform.crypto.spring.CryptoFailureAnalyzer` | internal candidate | `spring.factories`. |
| `com.documentplatform.crypto.spring.KeyServiceHealthIndicator` | internal candidate | Actuator bean. |

## platform-erase-log (`com.documentplatform.eraselog`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.eraselog.{Eraser, EraseRequest, ErasedBy, EraseEntry, ChainPoint, EraseLog, EraseGuard, EraseReplay, EraseRetention, EraseHandler, EraseHandlers, EraseHandlerException, EraseLogError, EraseLogException}` | yes | Design `erase-log.md` and the module table. |
| `com.documentplatform.eraselog.{EraseLogStore, ErasePosition}` | yes (store SPI) | Implemented by the file store, Postgres and the testkit. `ErasePosition.Locked.applyAndAdvance` and `verified` are reachable by any holder: an app could advance the position or lift the restore guard without erasing. Only `Eraser` and `EraseReplay` should call them (xc-public-api-13). |
| `com.documentplatform.eraselog.file.FileEraseLogStore` | yes | The built store. |
| `com.documentplatform.eraselog.jdbc.{PostgresErasePosition, EraseLogSchema, EraseLogSchemaException}` | yes | Position store and the startup check. |
| `com.documentplatform.eraselog.fields.CaseFieldsEraseHandler` | yes | The built-in handler that apps register. |

`Applying`, `InStep` and `file.Lines` are already package-private.

## platform-erase-log-spring (`com.documentplatform.eraselog.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.eraselog.spring.EraseLogAutoConfiguration` | yes | Framework. |
| `com.documentplatform.eraselog.spring.EraseLogProperties` | yes | Bound `platform.erase-log.*`. |
| `com.documentplatform.eraselog.spring.EraseLogWorker` | yes | Module table. |
| `com.documentplatform.eraselog.spring.EraseReplayRunner` | yes | `--erase-replay` (module table). |
| `com.documentplatform.eraselog.spring.EraseLogConfig` | internal candidate | The checked config. |
| `com.documentplatform.eraselog.spring.EraseLogConfigException` | internal candidate | Startup failure. |
| `com.documentplatform.eraselog.spring.EraseLogHealthIndicator` | internal candidate | Actuator bean. |

## platform-outbound (`com.documentplatform.outbound`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.outbound.{Outbound, Installation, ProxySettings, ClientCertificate, CertificateInfo, OutboundCode, RetryAfter}` | yes | Module table; used by identity-spring, integration and outbound-spring. |
| `com.documentplatform.outbound.AddressGuard` | yes | Used by integration (the scanners, `FolderExport`). The constructor `AddressGuard(boolean, Resolver)` and the nested `Resolver` are documented "Replaceable for tests". Every resolved address is still checked, so this is polish only (xc-public-api-15). |
| `com.documentplatform.outbound.TrustBundle` | internal candidate | Only `Outbound` (same package) and tests use it. |

## platform-outbound-spring (`com.documentplatform.outbound.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.outbound.spring.OutboundAutoConfiguration` | yes | Framework (identity-spring and integration-spring order after it). |
| `com.documentplatform.outbound.spring.OutboundProperties` | yes | Bound `platform.outbound.*`. |
| `com.documentplatform.outbound.spring.OutboundSettings` | cross-module only | Builds the `Installation`; integration-spring checks the old property names with it. |
| `com.documentplatform.outbound.spring.OutboundConfigException` | internal candidate | Startup failure. |
| `com.documentplatform.outbound.spring.OutboundFailureAnalyzer` | internal candidate | `spring.factories`. |

`OnOutboundSettings` is already package-private.

## platform-integration (`com.documentplatform.integration`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.integration.IntegrationCode` | yes | `INT_*` codes. |
| `com.documentplatform.integration.config.{IntegrationConfig, Kind, Direction, Endpoint, CallStep, HttpMethod, Template, Packaging, SignIn, Rule, Limits, HealthCheck, FolderTarget, PollSource, WatchSource, CallbackTarget, FieldLists, ConfigCheck, ConfigProblem, ConfigJson}` | yes | The configuration model; the app builds it, checks it at save and stores it (module table). |
| `com.documentplatform.integration.store.{IntegrationConfigs, IntegrationStore, StoredIntegration, ConfigChange, IntegrationAudit}` | yes | Versioning and the app's audit port. |
| `com.documentplatform.integration.engine.{CallEngine, Delivery, CaseFields, TestAction, PollEngine}` | yes | Engine, the app's delivery, Test action and polling. |
| `com.documentplatform.integration.result.{AttemptResult, Outcome, HealthResult, PendingStatus, StepResult}` | yes | Results (`StepResult` is a component of `AttemptResult`). |
| `com.documentplatform.integration.profile.{ProfileValidator, ProfileLoader, IntegrationProfile, ProfileProblem, ProfileFolder}` | yes | Profiles (setup also uses the validator and loader). |
| `com.documentplatform.integration.json.JsonSchema` | yes | Named in the module table (schema check and samples); used by the testkit. |
| `com.documentplatform.integration.folder.{FolderExport, ExportFiles, LocalFiles, ExportInfo}` | yes | Folder export. |
| `com.documentplatform.integration.credential.{IntegrationCredentials, CredentialExpiry, CredentialStore, StoredCredential, CredentialChange, CredentialState, CredentialSource, CredentialView, KeystoreRef, CertificateSource}` | yes | Mutual TLS credentials (P026). |
| `com.documentplatform.integration.callback.{Callbacks, CallbackOutbox, CallbackEntry, CallbackState, CallbackVerifier}` | yes | Signed callbacks. |
| `com.documentplatform.integration.callback.CallbackSignature` | yes | The header constants and `v1` / `header` are the published signature scheme. A receiving app may need them, and `CallbackVerifier` is the safe entry. |
| `com.documentplatform.integration.intake.{IntakeHandler, IntakeAnswer, IntakeFile, PolledItem, PollResult, CursorState}` | yes | Intake (P027). |
| `com.documentplatform.integration.watch.{WatchedFolder, WatchFiles, Drop, WatchResult}` | yes | Watched folder. |
| `com.documentplatform.integration.scan.{FileScanner, ClamdScanner, IcapScanner, ScannerAddress, Scanning, ScanEvents, ScanResult}` | yes | Virus scan. |
| `com.documentplatform.integration.jdbc.{PostgresIntegrationStore, PostgresCredentialStore, PostgresCallbackOutbox, IntegrationSchema, IntegrationSchemaException}` | yes | Postgres stores and the startup check. |
| `com.documentplatform.integration.signin.ClientCredentialsTokens` | cross-module only | It appears in the public `CallEngine(Outbound, SecretResolver, ClientCredentialsTokens, ...)` constructors and in `ClientCredentialsTokens.Status` for health. It has to stay public while those constructors exist. |
| `com.documentplatform.integration.json.Json` | cross-module only | The strict JSON reader and writer, used in 8 packages of this module and by the testkit. |
| `com.documentplatform.integration.signin.SignInHeaders` | internal candidate | Used only by `engine`. Its public `prepare(...)` returns `Prepared.headers()`, a `List<Map.Entry<String,String>>` that holds the resolved secret values (Basic, API key, bearer token) as plain strings. Hide it, or move it into `engine` as package-private (xc-public-api-4). |
| `com.documentplatform.integration.signin.SignInHeaders.outcomeOf(IntegrationCode)` | internal candidate | A static helper. |
| `com.documentplatform.integration.credential.CredentialFiles` | internal candidate | The PKCS#12/PEM/keystore reader; only `IntegrationCredentials` (same package) uses it. Its public `read` / `keystore` return a `ClientCertificate` that holds the private key. |
| `com.documentplatform.integration.config.Classification` | internal candidate | The rule matcher (`DEFAULTS`); only `engine` uses it. The module table names `Classification.DEFAULTS` as documentation, so keep `DEFAULTS` readable through `Rule` if this is hidden. |
| `com.documentplatform.integration.folder.SlotIds` | internal candidate | A copy of the slot rule for the folder package only (module table: "the slot rule copied in"). |
| `com.documentplatform.integration.folder.ExportManifest` | internal candidate | Written by `FolderExport` (same package); the format is published as a schema file, not as Java API. |
| `com.documentplatform.integration.watch.TriggerValues` | internal candidate | The hardened JSON/XML trigger reader; only `WatchedFolder` uses it. |

## platform-integration-spring (`com.documentplatform.integration.spring`)

| Type | Intended for apps? | Reason |
|---|---|---|
| `com.documentplatform.integration.spring.IntegrationAutoConfiguration` | yes | Framework (monitoring-spring orders after it). |
| `com.documentplatform.integration.spring.IntegrationProperties` | yes | Bound `platform.integration.*`. |
| `com.documentplatform.integration.spring.IntegrationWorker` | yes | Module table; its one-run method ("also for tests") is acceptable. |
| `com.documentplatform.integration.spring.IntegrationSettings` | internal candidate | The checked settings. |
| `com.documentplatform.integration.spring.IntegrationConfigException` | internal candidate | Startup failure. |
| `com.documentplatform.integration.spring.IntegrationFailureAnalyzer` | internal candidate | `spring.factories`. |
| `com.documentplatform.integration.spring.IntegrationHealthContributor` | internal candidate | Actuator bean (monitoring-spring replaces it). |

---

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| xc-public-api-1 | B2.6: public API is a deliberate surface | No module has `module-info.java`, an `internal` package convention, an `@Internal` marker or a guard test. All 550 public top-level types are equally "API" to the apps, and so are the public members of the about 60 internal candidates listed above. | C | Decide one policy before the integration release: either `module-info.java` with `exports` per module, or `*.internal` packages plus a guard test (like `NoProductNamesGuardTest`) that fails on new public types outside the documented list. | M |
| xc-public-api-2 | case-fields: values are erased only through the erase log (`VALUES_ERASE_OUTSIDE_LOG`) | `FieldValues.erase` refuses any caller except `CaseFieldsEraseHandler` (`platform-fields/src/main/java/com/documentplatform/fields/values/FieldValues.java` `erase`). But `com.documentplatform.fields.jdbc.PostgresValueStore`, which the app builds itself because there is no fields-spring, has a public `delete(StoredRecord)`. The `ValueStore.delete` interface method is also public. That deletes the record and its envelope with no erase-log entry, so a restored backup would bring the values back unnoticed. Also, the caller check compares a class name, which on the classpath (no JPMS) any jar can define. | B | Make deletion need a capability only `FieldValues` can create (for example a package-private-constructible `EraseTicket` parameter on `ValueStore.delete`). Document `ValueStore` as SPI that apps never call. | M |
| xc-public-api-3 | notifications: webhook addresses carry a token and are https only | `com.documentplatform.notifications.webhook.WebhookOptions.allowingPlainHttpForTests()` is public, and so is the `allowPlainHttp` record component. Any app code can turn on plain-http webhook posting, and the token-bearing URL then travels unencrypted. Unlike identity's http issuer, nothing ties this to a development-key licence or localhost. Spring never sets it, which is correct. | B | Move the switch to the testkit (or limit it to loopback hosts like `LocalTestIssuers`) and drop the boolean from the public record. | S |
| xc-public-api-4 | Never expose secrets outside the call | `com.documentplatform.integration.signin.SignInHeaders` is public. `prepare(...)` returns `Prepared.headers()` with resolved secret header values as plain `String`s. It is used only by `engine`. | C | Make `SignInHeaders` package-private inside `engine` (move it), or at least make `Prepared` non-public. | S |
| xc-public-api-5 | licensing: the issuing command is "our side only" | `com.documentplatform.licensing.issue.*` (`IssueLicenceCommand`, `VaultTransitSigner`, `Signer`, `IdGenerator`, `IssuedRecords`), `LicenceSigner` and `Ed25519.generate/encodePrivate/decodePrivate` ship in `platform-licensing`, which the BOM gives to every app. No private key ships with them, so nothing is exposed, but the customer-side jar carries the issuing tool. | C | Split the issuing side into a separate module that is not in the BOM (for example `platform-licensing-issue`). | M |
| xc-public-api-6 | Only intended types public (setup) | `com.documentplatform.setup.{Plan, ProductRoles, SigninFile, SetupKeys, SetupException}` are public, but only their own package uses them. The constructor `SetupCommand(Map, Clock, SetupKeys.Connector)` is a test seam. | C | Make them package-private; keep `SetupCommand.main`, the exit codes and the `SetupRefusal` codes public. | S |
| xc-public-api-7 | One strict JSON, not public per module | There are several JSON helpers. Public: `com.documentplatform.licensing.json.{Json, Fields, JsonException}` (setup uses them), `com.documentplatform.fields.json.{Json, JsonFields, JsonException}`, `com.documentplatform.integration.json.Json` and `com.documentplatform.notifications.webhook.Json`. Package-private: `identity.signin.MiniJson`, `crypto.vault.VaultJson`, `licensing.issue.VaultJson` and `notifications.email.TokenJson`. Apps can depend on any of the public ones, and their parse rules differ. | C | Hide `fields.json` and `notifications.webhook.Json` now (map `JsonException` to the module's own exception); later one shared internal JSON (its own task). | M |
| xc-public-api-8 | Only intended types public (integration) | Same-package helpers are public: `com.documentplatform.integration.credential.CredentialFiles` (returns a `ClientCertificate` with the private key), `config.Classification`, `folder.SlotIds`, `folder.ExportManifest` and `watch.TriggerValues`. | C | Make them package-private; for `Classification`, keep `DEFAULTS` reachable for documentation. | S |
| xc-public-api-9 | Only intended types public (notifications) | `com.documentplatform.notifications.webhook.WebhookClient` (its public `post` takes a caller-chosen address check), `webhook.Json`, `SummaryBucket`, and the public static helpers `TeamsChannel.payload` / `retiredConnector`. | C | Make them package-private, moving `WebhookClient` beside the channels or behind a package-private factory. | S |
| xc-public-api-10 | Only intended types public (licensing) | `com.documentplatform.licensing.{LicenceFormat, SignedDocument, LicenceSigner}`, `usage.UsageTotals` and `issue.{IdGenerator, IssuedRecords}` are used only inside their packages. `ClockGuard` and `ClockReading` are cross-module only (licensing-spring, setup). | C | Package-private where same-package. For `ClockGuard`, mark it as not for apps in the javadoc. | S |
| xc-public-api-11 | Only intended types public (errors) | `com.documentplatform.errors.CodeTexts` (`of`, `family`, `resourceName`) is used only by `PlatformCode` in the same package. | C | Make it package-private. | S |
| xc-public-api-12 | crypto: apps use `KeyService`, not raw Vault | `com.documentplatform.crypto.vault.VaultClient.readSecret/writeSecret` give public KV v2 access. `KeyService.destroyCustomerKeys` is callable on the app's `KeyService` bean. Only the Vault policy guards both (the app's policy should not allow deletion). | C | State in `docs/install/crypto.md` that the app's Vault policy must not allow key deletion or KV writes. Optionally make `VaultClient`'s KV methods package-private with a narrow accessor for crypto-spring and setup. | S |
| xc-public-api-13 | erase-log: only `Eraser` / `EraseReplay` move the position or lift the guard | `ErasePosition.Locked.applyAndAdvance` (with any supplier, a no-op included) and `verified(Instant)` are public interface methods, and `PostgresErasePosition` is public. An app could advance past an entry without erasing, or mark a replay verified. | C | Document `ErasePosition` and `EraseLogStore` as store SPI that apps never call. Optionally add a guard test that no app-facing class calls them. | S |
| xc-public-api-14 | Only intended types public (Spring modules) | Wiring types in every Spring module are public: `*Config` / `*Settings` records, `*ConfigException`, `*FailureAnalyzer`, `*HealthIndicator`, `NotificationChannels`, `DestinationHealth`, `SenderSignal`, `ModelGovCheck`, `IntegrationHealthContributor`, `LicensingNotices` and the `MonitoringMetrics` class. Spring can create non-public classes. | C | Make them package-private, keeping auto-configurations, `*Properties`, app-facing beans and the metric-name constants public. | M |
| xc-public-api-15 | No test hooks on production API | These test hooks are public: `UsageRecord.withAmount(long)` ("For tests"; builds a record whose hash does not match), `Ed25519.generate / encodePrivate / decodePrivate` ("tests and key ceremonies"), `AddressGuard(boolean, Resolver)` ("Replaceable for tests") and `WebhookOptions.allowingPlainHttpForTests()` (see gap 3). Errors already does this right: `FailureLog.clear` and `ReferenceId.useClock` are package-private. | C | Move each into the module's testkit, or make it package-private like errors does. | S |
| xc-public-api-16 | identity-core exposes setup's parsers | `com.documentplatform.identity.mapping.{RoleMappingFile, MappingPlan, MappingRow, MappingError, SlotTargets, ClientBindingsFile}` are needed only by platform-setup. `com.documentplatform.identity.mapping.MappingTargets` has no user anywhere and no doc. | C | Mark the mapping package as "for the setup command" in its javadoc. Check whether `MappingTargets` is dead API and remove or document it. | S |
| xc-public-api-17 | Store SPI vs app API not separated | Store interfaces and their row records are public and used by apps, testkits and jdbc modules alike, with no marking of which are "implement" and which are "call". Examples: `identity.store.*`, `notifications.{OutboxStore, OutboxEntry, SummaryStore, SummaryItem, ConditionStore, ConditionState}`, `fields.values.ValueStore`, `eraselog.{EraseLogStore, ErasePosition}`, `integration.store.IntegrationStore`, `licensing.usage.UsageLedger`. | C | Add one line to each store interface ("SPI: implemented by stores; apps call `<Service>`") and list the SPI types in each install guide. | S |
| xc-public-api-18 | Secret port is in the notifications package | `com.documentplatform.notifications.secret.{SecretRef, SecretResolver, Secret}` are the platform-wide secret port: outbound, integration and monitoring depend on platform-notifications for them, and apps implement `SecretResolver`. | C | Record it as a known wart. Moving them is a package rename and therefore its own task (CLAUDE.md "Keep Java package names stable"). | L |
