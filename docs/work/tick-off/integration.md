# Tick-off: integration-module, integration-profile-format, integration-standards

Counts (rows marked `n/a` belong to another slice and are not counted):

- integration-module: DONE 138 · UNTESTED 11 · PARTLY 4 · MISSING 3 · DIFFERENT 11 · APP-SIDE 9 · DOC GAP 16 (n/a 5)
- integration-profile-format: DONE 31 · UNTESTED 2 · PARTLY 5 · MISSING 0 · DIFFERENT 0 · APP-SIDE 0 · DOC GAP 0
- integration-standards: DONE 10 · UNTESTED 0 · PARTLY 0 · MISSING 1 · DIFFERENT 0 · APP-SIDE 3 · DOC GAP 0 (n/a 3)

Gaps: A 0 · B 7 · C 26 (some gap rows group several IDs). All 11 DIFFERENT rows follow a later decision (P026, P027, P028
or P030). Each one is a stale-doc gap, not a code fault.

Paths: `pi/` = `platform-integration/src/main/java/com/documentplatform/integration/`, `pit/` =
`platform-integration-testkit/src/test/java/com/documentplatform/integration/`, `pi-t/` =
`platform-integration/src/test/java/com/documentplatform/integration/`, `po/` =
`platform-outbound/src/main/java/com/documentplatform/outbound/`, `po-t/` =
`platform-outbound/src/test/java/com/documentplatform/outbound/OutboundRulesTest.java`, `pis/` =
`platform-integration-spring/src/{main,test}/java/com/documentplatform/integration/spring/`, `pos/` =
`platform-outbound-spring/src/{main,test}/java/com/documentplatform/outbound/spring/`.

The design doc names real products; this file says "a product's published API" instead.

## integration-module — Split of duties

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-1 | One attempt per call, classified result (sent, already received, refused with code, sign-in failed, can't connect, busy; also outage, unclear, not configured, accepted); the engine never retries | DONE | `pi/result/Outcome.java`, `pi/engine/CallEngine.java` `attempt` | `pit/MockSwitchesTest.java` (one test per outcome), `pit/ClassificationRulesTest.java` | |
| integration-module-2 | Configured calls: method, address, header and body templates, response mapping | DONE | `pi/config/CallStep.java`, `pi/config/Template.java` | `pit/CallEngineTest.java` `stepValuesGoIntoLaterPathsEscaped`, `aMissingRequiredValueIsUnclear` | |
| integration-module-3 | Field mapping by platform-fields keys (`{field:<key>}`) to the API's names | DONE | `pi/engine/CaseFields.java` | `pi-t/BuildingBlocksTest.java` `fieldValuesAreWrittenByTheirType` | |
| integration-module-4 | Health check per destination | DONE | `CallEngine.health` | `pit/MockSwitchesTest.java` `healthFailingWhileUploadsWork`, `healthWorkingWhileUploadsFail` | |
| integration-module-5 | A product's ingestion API ships as a ready-made profile in platform-core | DIFFERENT | `pi/profile/ProfileFolder.java` reads the installation's files; no profile in any `src/main` | — | Changed by P030 (module `platform-integration-profiles` removed; CLAUDE.md "none shipped"). Code follows P030. Body not updated: see 150 |
| integration-module-6 | The app keeps its workflow: export job, retry schedule, pause on failed health, rebuild, "Waiting for you", IT queue, screens, retention, intake rules, its push-API spec, the manifest's app section | APP-SIDE | — | — | App: export/intake jobs and screens |
| integration-module-7 | Push-API callers' sign-in checked with the shared bundle (P022 split put it in integration) | DIFFERENT | identity `push.PushCaller` | — | P028 moved it to identity (design P028 section says so). Code follows. Identity slice checks it |

## integration-module — Catalog (V1)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-8 | Intake: polling an API with paging | DONE | `pi/engine/PollEngine.java` | `pit/PollingTest.java` `cursorPagingTakesEveryItemAndAcknowledgesAfterTheApp` | |
| integration-module-9 | Intake: watched folder / share, trigger file last (JSON, XML, marker) | DONE | `pi/watch/WatchedFolder.java` | `pit/WatchedFolderTest.java` `aSubfolderDropWithAJsonTriggerIsTakenAndArchived`, `baseNameDropsWithXmlAndMarkerTriggers` | |
| integration-module-10 | Export: API push | DONE | `CallEngine.attempt` | `pit/MockSwitchesTest.java` `working` | |
| integration-module-11 | Export: folder / share with manifest written last (envelope + app section) | DONE | `pi/folder/FolderExport.java` | `pit/FolderExportTest.java` `writesTheDocumentsThenTheManifestThenRenamesTheFolder` | |
| integration-module-12 | Email export is not offered | DONE | `pi/config/Kind.java` has no such kind | — (nothing to test) | By absence |
| integration-module-13 | Sign-in NONE: on premises only, off by default, warned, audited, never hosted | DONE | `pi/config/ConfigCheck.java`, `pi/store/IntegrationConfigs.java` `done` (warning log), `po/Installation.java` | `pit/ConfigVersioningTest.java` `signInNoneIsWarnedAndRecordedOnPremisesAndRefusedWhenHosted`; `pit/SignInTest.java` `noneWorksOnlyWhereTheInstallationAllowsIt`; `po-t` `installationRules` | |
| integration-module-14 | Sign-in NONE appears on the compliance report | APP-SIDE | `ConfigChange.warnings` holds `INT_CONFIG_SIGN_IN_NONE_WARNING` | `ConfigVersioningTest` (warning stored) | App: compliance report |
| integration-module-15 | Shared secret: user + password, or API key in a header, never in the address | DONE | `pi/signin/SignInHeaders.java` | `pit/SignInTest.java` `basic`, `apiKeyGoesInTheHeaderNeverTheAddress` | |
| integration-module-16 | Fixed bearer token | DONE | `SignIn.BearerFixed` | `pit/SignInTest.java` `fixedBearer` | |
| integration-module-17 | OAuth 2.0 client credentials: secret in form or Basic, certificate assertion (JWT), any token address / scope / audience, cache, ready-made preset | DONE | `pi/signin/ClientCredentialsTokens.java`, `ClientAssertion.java` | `pit/SignInTest.java` `oauth2*` tests, `tokenEndpointFailuresHaveSpecificCodes`, `shortLivedTokensAreRenewed` | |
| integration-module-18 | Mutual TLS: uploaded, stored encrypted, shown only as subject / fingerprint / expiry, replaced without downtime; or a keystore | DONE | `pi/credential/IntegrationCredentials.java`, `CredentialView.java` | `pit/MutualTlsTest.java` `uploadsAreCheckedAndSealed`, `theRightCertificateIsPresented`, `nextIsTestedThenSwitchedWithoutDowntime`, `aKeystoreTheCustomerManages` | |
| integration-module-19 | HMAC-signed callbacks | DONE | `pi/callback/CallbackSignature.java` | `pit/CallbackTest.java` `theSignatureVector` | |
| integration-module-20 | Packaging: multipart, two steps, one file per call with details in headers | DONE | `pi/config/Packaging.java` | `pit/PackagingTest.java` `multipartSendsTheJsonPartAndEveryDocumentInOneCall`, `filePerCallAndTwoStepWithADocumentAttribute`; `pi-t/BuildingBlocksTest.java` `presetsExpandToOrdinaryValidSteps` | |
| integration-module-21 | 202 accepted, then status check or callback | DONE | `CallStep.Accepted`, `CallEngine.checkStatus` | `pit/AcceptedLaterTest.java` | |
| integration-module-22 | 429 / Retry-After honoured and passed through | DONE | `po/RetryAfter.java`, `Classification.DEFAULTS` | `pit/MockSwitchesTest.java` `busyWithRetryAfterUsesNoAttempt`; `po-t` `retryAfterInSecondsOrAsADate` | |
| integration-module-23 | The customer's outbound proxy | DONE | `po/ProxySettings.java`, `po/Outbound.java` | `pit/ProxyTest.java` (4 tests) | |
| integration-module-24 | A private certificate authority | DONE | `po/TrustBundle.java` | `pit/PrivateCaTest.java` (TLS-success tests skip only under TLS interception; CI forbids skips) | |
| integration-module-25 | Request IDs and checksums | DONE | correlation ID in `CallEngine`; `{doc.sha256}` | `pit/CallEngineTest.java` `correlationIdIsSentOnEveryCallWhenConfigured`; `pit/MockSwitchesTest.java` `recordsHeadersFieldsAndChecksumsNeverDocumentText` | |
| integration-module-26 | Size limits and timeouts per integration | DONE | `pi/config/Limits.java` | `pit/CallEngineTest.java` `limitsAreCheckedBeforeSending`, `aTooLargeAnswerIsNotRead`; `MockSwitchesTest` `slowIsATimeout` | |

## integration-module — Safety

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-27 | Fixed building blocks, no free scripting | DONE | `pi/config/Template.java` | `pit/ConfigVersioningTest.java` `templatesFromFixedBuildingBlocksOnly`; `pi-t/TemplateTest.java` `forbiddenAndUnknownPlaceholders` | |
| integration-module-28 | Secrets only in the secret store (references in configuration) | DONE | `pi/config/ConfigJson.java` | `pi-t/JsonAndConfigTest.java` `storedTextHoldsReferencesOnly`; `ConfigVersioningTest` (audit never sees a reference) | |
| integration-module-29 | No document content or secrets in URLs, templates or logs | DONE | `{doc.content}` / `{secret.*}` forbidden, no placeholder in a query, `SignInHeaders` | `ConfigVersioningTest` `templatesFromFixedBuildingBlocksOnly`; `pit/NoSecretsInLogsTest.java` (4 tests) | |
| integration-module-30 | Cloud-metadata addresses always refused; internal addresses only on premises when allowed | DONE | `po/AddressGuard.java` | `po-t` `cloudMetadataIsAlwaysRefused`, `internalAddressesAndPlainHttpOnlyWhereAllowed`; `pit/AddressGuardTest.java` | |
| integration-module-31 | Every configuration change audited and versioned | DONE | `pi/store/IntegrationConfigs.java`, `pi/jdbc/PostgresIntegrationStore.java` | `ConfigVersioningTest` `everyChangeIsANewVersionWithWhoWhenAndWhichFields`; `pi-t/PostgresIntegrationStoreTest.java` `theDatabaseKeepsVersionsFrozenAndTheAppUserCannotChangeTables` | |

## integration-module — Testing

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-32 | A Test action with fake data on every integration | PARTLY | `pi/engine/TestAction.java` `run` | `pit/TestActionTest.java`; `FolderExportTest` `theTestActionWritesTheFakePdfAndAManifestThenRemovesThem`; `CallbackTest` `theTestActionSendsASignedTestCallback` | API push, folder export and callbacks only. For `API_POLL` / `WATCHED_FOLDER`, FULL mode goes to `CallEngine.run` and gives `INT_KIND_NOT_SUPPORTED`; there is only the health check |
| integration-module-33 | Contract tests against each published product API in every build | DIFFERENT | `platform-integration-testkit/.../ProfileContract.java` | `pit/ProfileContractTest.java` (made-up profiles) | P030: made-up profiles only; the kit is for the API's owner. Code follows P030 |
| integration-module-34 | Live-system integration tests, run only on request | MISSING | — | — | No opt-in tag or switch in any integration test. `ProfileContract.checkAgainstServer` is the owner's tool |
| integration-module-35 | The mock destination is built from the pinned product spec so it can't drift | DIFFERENT | `MockDestination.specMode(OpenApiSpec, …)` | `pit/ProfileMockTest.java` `everySwitch` (with spec) | P030: spec mode from any OpenAPI file. Code follows |
| integration-module-36 | Mock switches: working, down, slow, busy (429 + Retry-After), 503, refuse with a code, already received, sign-in refused, health failing while uploads work and the reverse; small admin call | DONE | `MockDestination` | `pit/MockSwitchesTest.java` (one per switch), `switchedByTheAdminCall` | |
| integration-module-37 | The mock records headers, fields and checksums, never document text | DONE | `MockDestination.Received` | `MockSwitchesTest` `recordsHeadersFieldsAndChecksumsNeverDocumentText` | |
| integration-module-38 | Mock folder (unwritable, slow) and mock source API for polling | DONE | `MockFolder`, `MockSource` | `FolderExportTest` `everyFolderFailureHasItsCodeAndNeverLeavesAFinalFolder`, `aSlowShareTimesOutAndALeftoverPartialFolderIsReplaced`; `pit/PollingTest.java` | |

## integration-module — What platform-core already has / prerequisite

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-39 | Reuse the notifications HTTP plumbing (channels, outbox) | DIFFERENT | `po/Outbound.java`; integration takes only `SecretRef` / `SecretResolver` / `RetryPolicy` from notifications | — | P022 B decision 2 (copy, don't depend), then P028 (platform-outbound). Code follows. Table not updated: see 163 |
| integration-module-40 | Encrypted storage needs the crypto module first | DONE | `IntegrationCredentials` seals with `ValueCipher` | `MutualTlsTest` `uploadsAreCheckedAndSealed` | |

## integration-module — The product profile decisions (2026-11-21)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-41 | Pinned copy of the product's OpenAPI file in platform-core; contract tests against it | DIFFERENT | — | — | Removed by P030. Code follows |
| integration-module-42 | The product's "tenant" claim means the entity | APP-SIDE | — | — | The other product's naming |
| integration-module-43 | Lookup through `MachineEntityBindings`, not custom claims | n/a | identity | — | Identity slice |
| integration-module-44 | The product profile: OAuth 2.0 client credentials, readiness health path | DIFFERENT | — | — | Profile data removed by P030 |

## integration-module — Registering integration client IDs

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-45 | Setup loads client ID → entity → source; client IDs only; one binding each; checked; a removed line switches off; audited; later the IT page edits the same store | n/a | platform-setup `ClientBindingsFile` | — | Setup slice (`docs/design/setup.md` section 6) |

## integration-module — Decisions on the P022 proposal

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-46 | (1) Custom claims now, lookup later, by a configuration switch with no code change: generic engine setting `token.requiredClaims` (`INT_TOKEN_CLAIMS_MISSING`) | DONE (P037, edd4edb) | `pi/signin/SignInHeaders.java` `prepare` / `hasClaims`; `IntegrationConfig.REQUIRED_TOKEN_CLAIMS` | only the save rule (`BuildingBlocksTest` "claims without OAuth") and the profile setting (`ProfileValidatorTest`) | No test sends with required claims, missing or present. `MockDestination.tokenClaims` / `requireTokenClaims` exist but are unused. The product tests that covered it went in P030 |
| integration-module-47 | (2) Busy = 503 with the busy code (+ Retry-After), no attempt used; 503 without it = outage; a sign-in fault code is sign-in, not busy; expressed as rules | DONE | `pi/config/Rule.java`, `Classification` | `ClassificationRulesTest` `busyCodeIsBusyAndUsesNoAttempt`, `the503WithoutTheBusyCodeIsAnOutage`, `aut004IsASignInFaultNotBusy` | |
| integration-module-48 | (3) Trigger file: platform-core watches, reads and waits (stability, trigger last, timeout for incomplete drops); the app interprets the content | DONE | `WatchedFolder`, `watch.TriggerValues` (raw values handed over) | `WatchedFolderTest` `aDropIsTakenOnlyOnceItsFilesAreStable`, `aTriggerWrittenBeforeItsFilesWaitsForThem`, `filesWithoutATriggerAreReportedOnceAndLeftAlone` | |
| integration-module-49 | (5) Profiles ship as data named after the API; spec pinned | DIFFERENT | — | — | P030: none shipped. Code follows |

## integration-module — Decisions on the P023 report

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-50 | Generic defaults: 5xx = outage, 408 and 429 = busy | UNTESTED | `Classification.DEFAULTS` | `pi-t/JsonAndConfigTest.java` `classificationDefaults` and `ClassificationRulesTest` `defaultsWithoutRules` cover 5xx and 429 | 408 is never asserted |
| integration-module-51 | 404 without a destination code = `NOT_CONFIGURED` / `INT_ADDRESS_NOT_FOUND` | DONE | `Classification.DEFAULTS` | `ClassificationRulesTest` `defaultsWithoutRules` | |
| integration-module-52 | A timeout after sending = `CANT_CONNECT` / `INT_TIMEOUT` (the app resends with the same idempotency key) | DONE | `CallEngine` | `MockSwitchesTest` `slowIsATimeout` | The resend is app side |
| integration-module-53 | The configuration is checked again at every attempt under the current installation (saved on premises, run hosted → `INT_CONFIG_INVALID`) | DONE | `CallEngine` line ~319, `PollEngine`, `FolderExport`, `WatchedFolder` | `AddressGuardTest` `hostedRefusesTheMockOnLoopback`; `SignInTest` `noneWorksOnlyWhereTheInstallationAllowsIt`; `FolderExportTest` `limitsUnreadableContentAndTheEntitysSlotId`; `WatchedFolderTest` `theRootMissingWithoutRightsOrUnreachable` | |
| integration-module-54 | (1) DNS rebinding: strict pinning (P026), required before the hosted service | DONE | `po/Outbound.java` (`pinnedOnly` resolver) | `pit/PinningTest.java` `theConnectionGoesToTheCheckedAddressNotAFreshLookup`; `po-t` `aNameThatAlsoPointsInsideIsRefusedAndTheConnectionGoesToTheCheckedAddress` | |
| integration-module-55 | (2) The skipped TLS tests must pass on a machine without TLS interception before a release | DONE | `scripts/ci-no-skipped-tests.sh` in `.github/workflows/ci.yml` | CI step "No test skipped" | |
| integration-module-56 | (3) Proxy sign-in: password by secret reference; the guide names a JVM start option; the library sets no JVM property | DIFFERENT | `po/Outbound.java` (preemptive auth cache) | `pit/ProxySignInTest.java` `httpsThroughAProxyWithSignInNeedsNoJvmOption`, `thePasswordComesFromTheSecretStoreAtEveryCall` | P026 decision 2: no JVM option needed. Code follows. Body line 100 still says the option: see 164 |
| integration-module-57 | (4) The 503 default stays outage; ready-made "503 with Retry-After means busy" | DONE | `Rule.busyWhen503WithRetryAfter` | `pi-t/ReadyMadeRuleTest.java`; `ClassificationRulesTest` `defaultsWithoutRules` | |
| integration-module-58 | (5) `SecretResolver` from platform-notifications for now | DONE | `platform-integration/pom.xml` (notifications, angus-mail excluded) | — (build) | |
| integration-module-59 | "Already received" for the whole attempt only when every call was a replay; mixed = SENT (P023 decision 6, also in "Decisions on the P023 report") | UNTESTED | `CallEngine` line ~378 (`allReplays`) | `MockSwitchesTest` `alreadyReceivedThroughARule` covers the all-replay case only | The mixed case is never tested |

## integration-module — Busy vs down at the health check

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-60 | Readiness 503 with the busy code or with Retry-After = busy; can't connect, timeout, or 503 without the code = down; health rules may map to BUSY | DONE | `HealthCheck` rules, `HealthResult.busy()` / `down()` | `pit/ProfileMockTest.java` `healthBusyAndDown`; `MockSwitchesTest` `healthWithItsOwnRules`; `ReadyMadeRuleTest` | |
| integration-module-61 | No counting of failed checks | DONE | `CallEngine.health` (one call, no counter) | `MockSwitchesTest` `healthFailingWhileUploadsWork` | |
| integration-module-62 | A busy spell over 30 minutes raises one IT notice | APP-SIDE | — | — | App: busy-spell notice |
| integration-module-63 | Update: the product spec pinned at a commit, re-pinned at the tag | DIFFERENT | — | — | Removed by P030 |

## integration-module — Folder export and its manifest (P025)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-64 | Kind `FOLDER_EXPORT`, configured and versioned like API push; one attempt; same outcomes; never retries | DONE | `IntegrationConfig.folderExport`, `FolderExport.attempt` | `pi-t/FolderConfigTest.java` `theStoredFormKeepsTheFolderTarget`; `FolderExportTest` | |
| integration-module-65 | Target: absolute local or share path, no `.` or `..` | DONE | `pi/config/FolderTarget.java` `validFolder` | `FolderConfigTest` `localSharedAndUnixFoldersMustBeAbsolute`; `FolderExportTest` `windowsStylePathsWhereTheJdkAllowsThem` | |
| integration-module-66 | Package folder name: a template, one segment, default `{package.id}`; `{package.*}`, `{value.*}`, `{field:*}` allowed; percent-encoded | DONE | `FolderTarget`, `Template.Place.NAME` | `FolderConfigTest` `namesAreOneSafeSegmentAndOwnFileNamesAreMadeSafe`; `FolderExportTest` `namesFromTemplatesAreSafeAndUnique` | |
| integration-module-67 | A sensitive field is never in a name (save and run time) | DONE | `ConfigCheck`, `FolderExport` | `FolderConfigTest` `caseFieldsInNamesMustExistAndNeverBeSensitive`; `FolderExportTest` `namesFromTemplatesAreSafeAndUnique` | |
| integration-module-68 | File names: the document's own name made safe, or a template; names differing only in case get ` (2)`, ` (3)` | DONE | `FolderExport` | `FolderExportTest` `namesFromTemplatesAreSafeAndUnique` | |
| integration-module-69 | No sign-in bundle (the service account's rights) | DONE | `ConfigCheck` | `FolderConfigTest` `theSaveCheckRefusesWhatAFolderExportCannotHave` | |
| integration-module-70 | Refused on the hosted service (`INT_CONFIG_FOLDER_HOSTED`); bad path or name `INT_CONFIG_FOLDER_INVALID` | DONE | `ConfigCheck` | `FolderConfigTest` `localSharedAndUnixFoldersMustBeAbsolute` and related | |
| integration-module-71 | Write order: `.partial` (leftover removed), each document to `.tmp-<n>`, forced, read back (size + SHA-256), renamed; manifest last; folder renamed | DONE | `FolderExport` | `FolderExportTest` `writesTheDocumentsThenTheManifestThenRenamesTheFolder`, `aSlowShareTimesOutAndALeftoverPartialFolderIsReplaced` | |
| integration-module-72 | On failure only `.partial` is touched and removed when possible | DONE | `FolderExport` | `FolderExportTest` `everyFolderFailureHasItsCodeAndNeverLeavesAFinalFolder` | |
| integration-module-73 | Each file within the read timeout (`INT_TIMEOUT`) | DONE | `FolderExport` | `FolderExportTest` `aSlowShareTimesOutAndALeftoverPartialFolderIsReplaced` | |
| integration-module-74 | Outcome table: SENT, ALREADY_RECEIVED, `INT_FOLDER_EXISTS_DIFFERENT`, `_NOT_FOUND`, `_NO_PERMISSION`, `_FULL` (before and while), `_UNREACHABLE`, `INT_INTEGRITY_MISMATCH`, `INT_TIMEOUT` | DONE | `FolderExport` | `FolderExportTest` `everyFolderFailureHasItsCodeAndNeverLeavesAFinalFolder`, `theSamePackageAgainIsAlreadyReceivedAnotherOneIsRefused` | |
| integration-module-75 | Any other write failure = OUTAGE `INT_FOLDER_WRITE_FAILED` | UNTESTED | `FolderExport` | — | Code never asserted |
| integration-module-76 | "Same package" = manifests equal apart from `createdAt`, `correlationId`, `integration.configVersion` | DONE | `FolderExport` | `FolderExportTest` `theSamePackageAgainIsAlreadyReceivedAnotherOneIsRefused` | |
| integration-module-77 | Health: writes and removes one `.platform-health-<random>.tmp` | DONE | `FolderExport.health` | `FolderExportTest` `theHealthCheckWritesAndRemovesOneProbeFile` | |
| integration-module-78 | Test action: fake PDF + manifest into `platform-test-<timestamp>`, removed, audited | DONE | `FolderExport.test` | `FolderExportTest` `theTestActionWritesTheFakePdfAndAManifestThenRemovesThem` | |
| integration-module-79 | Manifest `platform-export-manifest/1` with its members and schema file | DONE | `pi/folder/ExportManifest.java`, `schema/platform-export-manifest-1.schema.json` (`additionalProperties: false`) | `FolderExportTest` `writesTheDocumentsThenTheManifestThenRenamesTheFolder` (schema check, members) | |
| integration-module-80 | `entitySlotId` must be the integration's entity | DONE | `ExportInfo` / `SlotIds` | `FolderExportTest` `limitsUnreadableContentAndTheEntitysSlotId` | |
| integration-module-81 | `fields` in `platform-field-values/1`; a sensitive field only if allowed, others left out | DONE | `ExportManifest` | `FolderExportTest` `writesTheDocumentsThenTheManifestThenRenamesTheFolder` | |
| integration-module-82 | The `app` object is written as given and never read | DONE | `ExportInfo.appSection` | `FolderExportTest` `writesTheDocumentsThenTheManifestThenRenamesTheFolder` | |

## integration-module — Proxy sign-in and "omit when missing" (P025)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-83 | Proxy user + password by secret reference, read at every call; `Proxy-Authorization: Basic` on CONNECT and plain-http requests; never to the destination | DONE | `po/Outbound.java`, `ProxySettings.withSignIn` | `ProxySignInTest` `rightCredentialsGoToTheProxyOnlyAndTheCallsArrive`, `thePasswordComesFromTheSecretStoreAtEveryCall`; `pit/ProxySignInTunnelTest.java` | |
| integration-module-84 | The library never sets JVM properties | DONE | no `System.setProperty` in the modules | `ProxySignInTest` `httpsThroughAProxyWithSignInNeedsNoJvmOption` | |
| integration-module-85 | `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` before connecting when the JVM disables Basic | DIFFERENT | code and `JvmProxySignIn` removed | — | P026 decision 1 / P027: removed. Code follows. Body line 160 still describes it: see 164 |
| integration-module-86 | A 407 from the proxy = `INT_PROXY_SIGN_IN_REFUSED` | DONE | `Outbound` | `ProxySignInTest` `wrongOrMissingCredentialsAreRefusedByTheProxy` | |
| integration-module-87 | `{name?}` only as a whole JSON member value; leaves the member and its comma out; a present value that can't be placed is still refused; elsewhere `INT_CONFIG_OMIT_PLACE` | DONE | `Template` | `pi-t/OmitWhenMissingTest.java` (7 tests) | |
| integration-module-88 | Ready-made rules `busyWhen503WithRetryAfter` / `healthBusyWhen503WithRetryAfter` | DONE | `Rule` | `ReadyMadeRuleTest` | |

## integration-module — Decisions on the P025 report

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-89 | Health results cached 60 s | DONE | `IntegrationProperties.Health.cacheFor` | `pis/IntegrationWiringTest.java` `withTheAppsBeansEverythingIsWiredAndAFolderExportRuns` ("kept for cache-for") | |
| integration-module-90 | (1) `doc.attr.*` stays in the app section; the envelope stays stable | DONE | manifest schema `additionalProperties: false` | `FolderExportTest` schema check | |
| integration-module-91 | (2) A full disk raises one IT notice at once | APP-SIDE | — | — | App: `INT_FOLDER_FULL` notice |
| integration-module-92 | (3) The `integrations` contributor revisited in P032: Working / Having problems / Down; the app's health and readiness never DOWN because another system is | DONE | `pis/IntegrationAutoConfiguration.java` `Health` (`@ConditionalOnMissingClass` monitoring) | platform-monitoring-spring `MonitoringWiringTest` (readiness UP while an integration is DOWN) | Without monitoring-spring the old contributor still goes DOWN |
| integration-module-93 | Busy is UP in the health contributor (P025 decision 7) | UNTESTED | `pis/IntegrationHealthContributor.java` line ~146 | — | |

## integration-module — Client certificates, pinning, callbacks, accepted later (P026)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-94 | Apache HttpClient 5; names resolved only through our `DnsResolver` (the first checked address), other names refused; host name check and SNI on the name | DONE | `po/Outbound.java` `pinnedOnly` | `PinningTest` `theConnectionGoesToTheCheckedAddressNotAFreshLookup`, `aNameOnlyTheSystemResolverKnowsIsNeverLookedUpByTheClient`; `PrivateCaTest` `theHostNameIsAlwaysChecked` | |
| integration-module-95 | Redirects, retries, cookies and compression off | PARTLY | `Outbound` builder (`disableRedirectHandling`, `disableAutomaticRetries`, `disableCookieManagement`, `disableContentCompression`) | `po-t` `redirectsAreNeverFollowedAndLargeAnswersAreNotRead`; `ClassificationRulesTest` `redirectsAreNeverFollowed` | Retries, cookies, compression untested |
| integration-module-96 | The HTTP library's wire log must never be at DEBUG (install guide) | DONE | `docs/install/integration.md` section 1 | — (doc rule) | |
| integration-module-97 | `MUTUAL_TLS` alone or with Basic, API key, bearer or OAuth 2.0, never with NONE | DONE | `SignIn.MutualTls` | `MutualTlsTest` `theConfiguration` | |
| integration-module-98 | The certificate is the integration's credential, not configuration (rotation is no config version) | DONE | `IntegrationCredentials`, `int_credential` | `MutualTlsTest` `nextIsTestedThenSwitchedWithoutDowntime` | |
| integration-module-99 | Own HTTP client per certificate; the key manager always offers it, so an untrusted one is `INT_CREDENTIAL_REFUSED` | DONE | `Outbound` client key, key manager | `MutualTlsTest` `aCertificateTheDestinationDoesNotTrustIsRefused`, `withOAuthTheTokenRequestCanPresentTheCertificate` | |
| integration-module-100 | Uploaded chain + PKCS#8 key as PEM, sealed with `ValueCipher` under the customer; the upload password never kept | DONE | `pi/credential/CredentialFiles.java` | `MutualTlsTest` `uploadsAreCheckedAndSealed`; `pi-t/PostgresCredentialsAndCallbacksTest.java` `certificatesSealedRotatedAndHistoryWithTheDmlOnlyUser` | |
| integration-module-101 | Keystore: path, alias, password reference; read for each use; renewal recorded `KEYSTORE_RENEWED` | DONE | `KeystoreRef`, `IntegrationCredentials` | `MutualTlsTest` `aKeystoreTheCustomerManages` | |
| integration-module-102 | At most one CURRENT and one NEXT (unique partial indexes); RETIRED keeps details, key dropped | DONE | `V2__credentials_callbacks.sql` | `PostgresCredentialsAndCallbacksTest` `certificatesSealedRotatedAndHistoryWithTheDmlOnlyUser`; `MutualTlsTest` `nextIsTestedThenSwitchedWithoutDowntime` | |
| integration-module-103 | Every change numbered in `int_credential_change` (frozen by trigger) and sent to `IntegrationAudit.credentialChanged` (default method) | DONE | `V2` SQL, `IntegrationAudit` | same two tests | |
| integration-module-104 | A NEXT not valid yet, or expired, cannot be switched to; a timed switch waits | DONE | `IntegrationCredentials.switchDue` | `MutualTlsTest` `nextBecomesCurrentAtItsSetTime` | The warning log is not asserted |
| integration-module-105 | `CredentialExpiry.check(now)`: smallest threshold reached (30 / 7 / 1) or expired, CURRENT and NEXT | DONE | `pi/credential/CredentialExpiry.java` | `MutualTlsTest` `expiryWarningsAt30And7And1DaysAndAfter` | The notice is app side (126) |
| integration-module-106 | An expired CURRENT refuses every call `INT_CREDENTIAL_EXPIRED` (SIGN_IN_FAILED) before anything is sent | DONE | `CallEngine` | `MutualTlsTest` `anExpiredCurrentCertificateRefusesTheCall` | |
| integration-module-107 | Kind `CALLBACK`: full receiver address, NONE allowed everywhere (incl. hosted), `CallbackTarget(signingSecret)` | DONE | `ConfigCheck`, `CallbackTarget` | `CallbackTest` `theConfiguration` | |
| integration-module-108 | `Platform-Signature: t=…,v1=HMAC-SHA256(secret, t + "." + body)`; `Platform-Callback-Id` the same on every attempt | DONE | `CallbackSignature` | `CallbackTest` `theSignatureVector`, `retriesWithBackOffAndRetryAfterThenGivesUpAndIsResent` | |
| integration-module-109 | Outbox claims with `FOR UPDATE SKIP LOCKED` and a lease | DONE | `pi/jdbc/PostgresCallbackOutbox.java` | `PostgresCredentialsAndCallbacksTest` `concurrentSendersNeverClaimTheSameCallback`, `theOutboxClaimsWithALeaseAndSurvivesARestart`; `CallbackTest` `aClaimIsALeaseSoOneSenderHasItAtATime` | |
| integration-module-110 | Back-off 1 min doubling to 6 h; given up 24 h after creation or resend; Retry-After replaces the back-off up to the longest delay | DONE | `Callbacks` | `CallbackTest` `retriesWithBackOffAndRetryAfterThenGivesUpAndIsResent` | |
| integration-module-111 | SENT / ALREADY_RECEIVED done; REFUSED gives up at once; everything else retried | DONE | `Callbacks` | `CallbackTest` `aRefusalGivesUpAtOnce`, `aMissingSecretIsNotConfiguredAndRetried` | |
| integration-module-112 | Body: one JSON object, at most 64 KiB | DONE | `Callbacks.MAX_BODY_BYTES` | `CallbackTest` `enqueueChecksTheBodyAndTheKind` | |
| integration-module-113 | "Never document content" in a callback is the app's rule | APP-SIDE | — | — | App: callback body |
| integration-module-114 | `CallbackVerifier`: signature (constant time, every pair), then window (5 min), then ID; ID recorded only after the first two pass | DONE | `pi/callback/CallbackVerifier.java` | `CallbackTest` `theVerifierRefusesWhatIsNotRight`, `duringASecretChangeTheReceiverAcceptsBoth` | |
| integration-module-115 | `IntegrationWorker` sends due callbacks and makes timed certificate switches | PARTLY | `pis/IntegrationWorker.java` `runOnce` | `IntegrationWiringTest` `onTheAppsDataSourceWithTheDmlOnlyUser` (callbacks) | The timed switch through the worker is untested (tests call `switchDue` directly) |
| integration-module-116 | "Accepted, check later" only on the last step, run once | DONE | `ConfigCheck` (`INT_CONFIG_ACCEPTED_INVALID`) | `AcceptedLaterTest` `theConfigurationCheck` | |
| integration-module-117 | 202 on such a step = ACCEPTED (no attempt used) + `PendingStatus` (`toJson`/`fromJson`); 202 elsewhere = SENT | DONE | `CallEngine`, `pi/result/PendingStatus.java` | `AcceptedLaterTest` `acceptedThenStillWorkingThenDone`, `withoutTheDeclarationA202IsSentAsBefore` | |
| integration-module-118 | `checkStatus`: one call; 202 or "working" = ACCEPTED; "done" = SENT with values; "failed" = REFUSED `INT_STATUS_FAILED`; non-2xx by the rules | DONE | `CallEngine.checkStatus` | `AcceptedLaterTest` `acceptedThenStillWorkingThenDone`, `failedIsRefusedWithTheDestinationsCode`, `aRefusedStatusCallAndAnOutageAreTheUsualResults` | |
| integration-module-119 | Any other state = UNCLEAR `INT_STATUS_UNKNOWN` | UNTESTED | `CallEngine.checkStatus` | — | |
| integration-module-120 | A `Location` reference must stay on the base address's scheme, host and port | DONE (P037, cdedb4f) | `CallEngine` line ~832 | — | Off-host check never exercised (the address guard still applies) |
| integration-module-121 | A rule can never give ACCEPTED | DONE | `Rule` constructor | `AcceptedLaterTest` `theConfigurationCheck` | |
| integration-module-122 | Profiles get `steps[].accepted` and `MUTUAL_TLS` (additions only); `ProfileContract` checks the 202 answer and the status operation | DONE | `ProfileValidator`, `ProfileContract` | `AcceptedLaterTest` `aProfileWithAcceptedLaterIsValidAndFitsItsSpec`, `theContractKitCatchesASpecWithoutTheStatus` | |

## integration-module — Decisions on the P026 report

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-123 | A refused client certificate under TLS 1.3 is told by one extra TLS 1.2 handshake | DONE | `Outbound` (`setProtocols(TLSv1.2)`) | `MutualTlsTest` `aCertificateTheDestinationDoesNotTrustIsRefused` (runs on CI) | |
| integration-module-124 | (1) `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` and the `JvmProxySignIn` constructor removed | DONE | not in `IntegrationCode` or any source | — (absence; catalogue check) | |
| integration-module-125 | (2) Switching a certificate drops the held token at once | DONE | `ClientCredentialsTokens` (token bound to the certificate fingerprint) | `SignInTest` `aHeldTokenIsDroppedWhenTheClientCertificateInUseChanges`; `MutualTlsTest` `switchingTheCertificateDropsTheHeldTokenAtOnce` | |
| integration-module-126 | (3) Expiry notice wording and the "already told" record | APP-SIDE | — | — | App: expiry notices |

## integration-module — Intake (P027)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-127 | platform-core finds, watches, reads, waits, claims, scans, hands over; one pass per call; the app keeps the schedule and the cursor | DONE | `PollEngine.pass`, `WatchedFolder.pass` | `PollingTest` `thePageLimitAndTheCursorKeptByTheApp`, `aRestartInTheMiddleOfAPassIsSafe` | |
| integration-module-128 | `IntakeHandler` answers TAKEN / ALREADY_TAKEN / REFUSED(code); an exception is the app's failure (nothing acknowledged or archived, tried again later) | DONE | `pi/intake/IntakeHandler.java`, `IntakeAnswer` | `PollingTest` `aRefusalIsNotAcknowledged`, `aRestartInTheMiddleOfAPassIsSafe`; `WatchedFolderTest` `theAppsAnswerDecidesTheMove`, `aFailingHandlerPutsTheDropBack` | |
| integration-module-129 | `IntakeFile` streams check themselves at their end (size, SHA-256) and are valid only during the handler | UNTESTED | `pi/intake/IntakeFile.java` (`Changed`) | — | `Changed` is never triggered in a test |
| integration-module-130 | Nothing new stored in the database for intake | DONE | no V3 SQL; `IntegrationSchema` at version 2 | `PostgresIntegrationStoreTest` `intakeConfigurationsAreStoredWithTheDmlOnlyUser` | |
| integration-module-131 | Polling uses the same building blocks and the same sign-in, certificate, guard, pinning, proxy, limits and classification | DONE | `PollEngine` through `CallEngine` | `PollingTest` `slowBusyAndDownAreClassified`; `ScannerTest` (sign-in NONE engine) | |
| integration-module-132 | `{item.id}` / `{item.<name>}` only in per-item calls (`INT_CONFIG_PLACEHOLDER_PLACE`) | DONE | `ConfigCheck` | `pi-t/IntakeConfigTest.java` `aPollingConfigurationIsCheckedWhenSaved`; `PollProfileTest` `theFormatChecksForPolling` | |
| integration-module-133 | Paging NONE / CURSOR / NEXT_LINK (pointer or `Link`) / PAGE_NUMBER, page size, pages per pass; parameters added (encoded) by the engine, never a template | DONE | `PollSource.Paging`, `PollEngine` | `PollingTest` `cursorPagingTakesEveryItemAndAcknowledgesAfterTheApp`, `nextLinkInTheAnswerOrTheLinkHeaderAndPageNumbers`, `oneListCallWithoutPaging`, `thePageLimitAndTheCursorKeptByTheApp` | |
| integration-module-134 | NEXT_LINK only on the same scheme, host and port (`INT_POLL_NEXT_PAGE_INVALID`) | DONE (P037, fde3d04) | `PollEngine` | — | Code never asserted |
| integration-module-135 | `resume` CONTINUE (at the end the last page is read again) or FROM_START | DONE | `PollEngine` | `PollingTest` `cursorPagingTakesEveryItemAndAcknowledgesAfterTheApp`, `aQueueReadFromTheStartEveryPass` | |
| integration-module-136 | An item without an ID is reported and left alone | DONE | `PollEngine` | `PollingTest` `anItemWithoutAnIdIsReportedAndLeftAlone` | |
| integration-module-137 | Download streamed to the work folder with the size limit and SHA-256 (`Outbound.BodySink`); stopping early drops the connection | PARTLY | `PollEngine`, `Outbound.BodySink` | `PollingTest` `cursorPagingTakesEveryItemAndAcknowledgesAfterTheApp` (SHA, no leftovers), `aFileOverTheLimitIsLeftAlone` | "Drops instead of draining" and `INT_POLL_STORAGE_FAILED` untested |
| integration-module-138 | A refused download or a file over the limit is reported, left alone, not acknowledged | DONE | `PollResult.problems` | `PollingTest` `aRefusedDownloadIsReportedAndTheOthersAreTaken`, `aFileOverTheLimitIsLeftAlone` | |
| integration-module-139 | Acknowledge only after TAKEN or ALREADY_TAKEN | DONE | `PollEngine` | `PollingTest` `aRefusalIsNotAcknowledged`, `aFailingAcknowledgeStopsThePassAndTheNextPassFinishesIt` | |
| integration-module-140 | Anything else stops the pass with the classified outcome, step and item; next state = after the last page fully handled | DONE | `PollResult` | `PollingTest` `aBrokenPageStopsThePassAtTheLastFullyHandledPage`, `aRestartInTheMiddleOfAPassIsSafe` | |
| integration-module-141 | Profiles: `poll` in `/1` (additions only); `MockSource.forProfile` | DONE | `ProfileValidator`, `MockSource` | `pit/PollProfileTest.java` (2 tests) | |
| integration-module-142 | Watched root absolute local or share; drops as subfolders or by base name (up to the first dot) | DONE | `WatchSource`, `WatchedFolder` | `WatchedFolderTest` `aSubfolderDropWithAJsonTriggerIsTakenAndArchived`, `baseNameDropsWithXmlAndMarkerTriggers` | |
| integration-module-143 | Trigger pattern (`*`, `?`, `<base>`) and type JSON / XML / MARKER, default by extension | DONE | `WatchSource` | `IntakeConfigTest` `triggerPatterns`; `WatchedFolderTest` `baseNameDropsWithXmlAndMarkerTriggers` | |
| integration-module-144 | Stable for N seconds, two ways (file times old enough, or the same sizes and times seen that long ago) | DONE | `WatchedFolder` | `WatchedFolderTest` `aDropIsTakenOnlyOnceItsFilesAreStable`, `aSubfolderDropWithAJsonTriggerIsTakenAndArchived` | |
| integration-module-145 | Incomplete after N minutes: reported once per drop by this instance, nothing moved | DONE | `WatchedFolder` | `WatchedFolderTest` `filesWithoutATriggerAreReportedOnceAndLeftAlone` | |
| integration-module-146 | Claim time, drops per pass, trigger size | DONE | `WatchSource` defaults | `WatchedFolderTest` `aStuckClaimIsReleasedAndTheDropTakenAgainSafely`, `theDropsPerPassLimit`, `limitsTwoTriggersAndALinkMakeADropInvalid` | |
| integration-module-147 | Claim by one atomic rename into `_processing/<drop>-<fence>` with a marker; a failed rename with the source gone = lost claim | DONE | `WatchedFolder` | `WatchedFolderTest` `twoRunnersRacingExactlyOneClaims` | |
| integration-module-148 | Check: no link or folder, limits, one trigger, each real path inside the root | DONE | `WatchedFolder` | `WatchedFolderTest` `limitsTwoTriggersAndALinkMakeADropInvalid` | |
| integration-module-149 | SHA-256 and sizes; trigger through the hardened parser and fixed mapping (JSON pointers with `*`; XML paths with `[n]`, `@attribute`) | DONE | `pi/watch/TriggerValues.java` | `pi-t/TriggerValuesTest.java` `jsonPointersWithEveryListElement`, `xmlPathsWithIndexesAndAttributes` | |
| integration-module-150 | Moves: `_archive` on TAKEN / ALREADY_TAKEN; `_problem` with `problem.json` (`platform-watch-problem/1`, never content); `_quarantine` for infected | DONE | `WatchedFolder` | `WatchedFolderTest` `theAppsAnswerDecidesTheMove`, `unreadableAndAttackTriggersGoToProblemWithACodeNeverContent`, `eicarIsQuarantinedAndNeverReachesTheHandler` | |
| integration-module-151 | A handler failure puts the drop back and stops the pass | DONE | `WatchedFolder` | `WatchedFolderTest` `aFailingHandlerPutsTheDropBack` | |
| integration-module-152 | With a file list the app gets unlisted files and names without a file | DONE | `Drop.unlisted` / `missing` | `WatchedFolderTest` `aSubfolderDropWithAJsonTriggerIsTakenAndArchived`, `namesInTheListWithoutAFileAreReported` | |
| integration-module-153 | Parsers: UTF-8 only, size and depth limits (JSON 32, XML 64), no DTD, no external access, no XInclude | DONE | `TriggerValues` | `TriggerValuesTest` `billionLaughsIsRefused`, `externalEntitiesAndXIncludeAreRefused`, `depthSizeAndEncodingLimits` | |
| integration-module-154 | Watched folder refused on the hosted service (`INT_CONFIG_FOLDER_HOSTED`) | DONE | `ConfigCheck` | `IntakeConfigTest` `aWatchedFolderConfigurationIsCheckedWhenSaved` | |
| integration-module-155 | A file that changed during the handler goes to `_problem` with `INT_WATCH_FILE_CHANGED` (P027 decision 4) | UNTESTED | `WatchedFolder` | — | Code never asserted (ties to 129) |
| integration-module-156 | clamd `zINSTREAM` in 64 KiB chunks; ICAP `RESPMOD` with `Allow: 204` (204 clean); both through the address guard to the checked address | DONE | `pi/scan/ClamdScanner.java`, `IcapScanner.java`, `ScannerAddress` | `pit/ScannerTest.java` `bothAdaptersFindEicarAndPassACleanFile`, `theScannerAddressFollowsTheOutboundRules` | |
| integration-module-157 | ICAP: a 200 without a threat header is infected ("unsure" is never let through) | DONE (P037, 6e38f5a) | `IcapScanner` line ~86 | — | `MockScanner` always sends `X-Infection-Found` |
| integration-module-158 | Scanning off without a scanner | DONE | `Scanning` | `IntegrationWiringTest` `intakeBeansTheScannerAndTheirHealth` | |
| integration-module-159 | Every incoming file scanned before the app sees it, a drop's trigger too | DONE | `Scanning` in `PollEngine` / `WatchedFolder` | `ScannerTest` `eicarFromASourceNeverReachesTheHandlerAndIsNotAcknowledged`; `WatchedFolderTest` `aFailedScanHoldsTheDropForTheNextPass` (file and trigger scanned) | |
| integration-module-160 | INFECTED: never handed over, not acknowledged, `_quarantine`, `ScanEvents.infected` | DONE | `Scanning`, `ScanEvents` | `ScannerTest` `eicarFromASourceNeverReachesTheHandlerAndIsNotAcknowledged`; `WatchedFolderTest` `eicarIsQuarantinedAndNeverReachesTheHandler` | |
| integration-module-161 | SCAN_FAILED (`INT_SCAN_FAILED`, `INT_SCAN_UNREACHABLE`): held, pass stops, scanned again next pass | DONE | `Scanning` | `ScannerTest` `aScanThatFailedHoldsTheItemUntilTheNextPass`; `WatchedFolderTest` `aFailedScanHoldsTheDropForTheNextPass` | |
| integration-module-162 | Health: clamd `PING`, ICAP `OPTIONS` | DONE | both scanners `health` | `ScannerTest` `bothAdaptersFindEicarAndPassACleanFile`, `slowDownAndGarbledAreScanFailures` | |
| integration-module-163a | The EICAR file is built at run time; tests that write it skip where it is removed; CI forbids skips | DONE | testkit `Eicar` | `ScannerTest` `theTestFileIsBuiltAtRunTime`; CI script | |
| integration-module-163b | Spring: `scanner.*` and `intake.work-folder` checked at startup; beans `FileScanner`, `Scanning` (app's `ScanEvents`), `PollEngine`, `WatchedFolder` (app's `WatchFiles`); health covers sources and `scanner` | DONE | `pis/IntegrationAutoConfiguration.java`, `IntegrationSettings` | `IntegrationWiringTest` `intakeBeansTheScannerAndTheirHealth` | |
| integration-module-163c | A held OAuth 2.0 token is bound to the certificate in use; after any switch it is never used again | DONE | `ClientCredentialsTokens` | `SignInTest` `aHeldTokenIsDroppedWhenTheClientCertificateInUseChanges` | |

## integration-module — Decisions on the P027 report

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-163d | (1) Startup on HOSTED refuses a missing scanner | MISSING | — | — | Deferred by the decision to the hosted go-live work |
| integration-module-163e | (2) ICAP over TLS | MISSING | — | — | Deferred by the decision until a customer needs it |
| integration-module-163f | (3) Incomplete drops reported once per instance; the app de-duplicates by drop ID | APP-SIDE | — | — | App: IT notice; the per-instance part is 145 |
| integration-module-163g | (4) Refused downloads in `PollResult.problems`; a source wanting retries gets a rule to OUTAGE | DONE | `PollResult` | `PollingTest` `aRefusedDownloadIsReportedAndTheOthersAreTaken` | |

## integration-module — P028: the outbound module

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-163h | `platform-outbound` holds `Installation`, `AddressGuard` (pinning), `TrustBundle`, `ProxySettings`, `Outbound`, `RetryAfter`, `ClientCertificate`, `CertificateInfo`; failures `OUT_*` | DONE | `po/` | `po-t` (8 tests) | |
| integration-module-163i | `IntegrationCode.of` maps each `OUT_*` to the `INT_*` of the same name; integration results unchanged (14 moved codes) | DONE | `pi/IntegrationCode.java` `of` (exhaustive switch) | `PinningTest` (3 mappings asserted) | The other 11 mappings rely on the switch only |
| integration-module-163j | `config.Limits` stays in integration; outbound limits are per call | DONE | `pi/config/Limits.java`, `Outbound.send` parameters | — (structure) | |
| integration-module-163k | Integration depends on outbound, not identity-core; `folder.SlotIds` copy checked against identity | DONE | `platform-integration/pom.xml` (identity only as testkit, test scope), `pi/folder/SlotIds.java` | `pit/SlotIdsTest.java` `theSameUuidsAndTheSameSlotRule` | |
| integration-module-163l | `OutboundIssuerFetch` in identity-spring; identity-spring depends on outbound-spring | n/a | identity | — | Identity slice |
| integration-module-163m | `platform-outbound-spring` reads `platform.outbound.*` whenever any is set; beans `Installation`, `Outbound`; `OUTBOUND_SETTINGS_INVALID` with every problem | DONE | `pos/OutboundAutoConfiguration.java`, `OutboundSettings` | `pos/OutboundWiringTest.java` (4 tests) | |
| integration-module-163n | The old `platform.integration.*` names stop the app, naming the new one, never repeating a value | DONE | `pis/IntegrationSettings.java` | `IntegrationWiringTest` `badSettingsStopTheAppWithEveryProblemListed` | |
| integration-module-163o | `allow-sign-in-none` moved to `platform.outbound.*` | DONE | `pos/OutboundProperties.java` | `OutboundWiringTest` `theInstallationAndTheOutboundBean` | |
| integration-module-163p | The identity test sign-in's reason is the `OUT_*` code | n/a | identity | — | Identity slice |
| integration-module-163q | Push callers are identity's, not `integration.inbound.PushCaller` | DONE | no `integration.inbound` package | — (absence) | |
| integration-module-163r | P028 decisions 1 (platform grants page) and 2 (`PushCaller.forget()` from the app) | APP-SIDE | — | — | App: IT Admin pages |
| integration-module-163s | P028 decision 3 (certificate-only caller takes entity and source from the binding) | n/a | identity | — | Identity slice |

## integration-module — DOC GAP (decisions in reports P022–P028, or later, not in the design's body)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-module-150d | P030 removed the product profile, the pinned spec and the product contract tests; the body (split of duties, testing, profile decisions) still says they exist | DOC GAP | — | — | Add a "Decisions after P028" line naming P030 |
| integration-module-151d | P023 decision 6: "already received" only when every call was a replay | DOC GAP | | | |
| integration-module-152d | P023 decision 9: history times kept to the microsecond | DOC GAP | | | |
| integration-module-153d | P024 decision 3: a sensitive field goes in JSON bodies only, never a header or path (checked at save and at send) | DOC GAP | | | Stated only for folder names |
| integration-module-154d | P024 decision 6: the mock records JSON field names only; values only with `recordValues(true)` | DOC GAP | | | |
| integration-module-155d | P024 decision 7: the required-claims check reads the token payload for presence only; never kept or logged | DOC GAP | | | |
| integration-module-156d | P025 decision 8: `ProfileFolder` is in the core module (apps without Spring) | DOC GAP | | | |
| integration-module-157d | P026 decision 6: uploading while a NEXT exists is refused (`INT_CREDENTIAL_NEXT_EXISTS`) | DOC GAP | | | |
| integration-module-158d | P026 decision 12: one customer per installation in the Spring wiring (`CustomerId`); multi-customer installations define their own `IntegrationCredentials` | DOC GAP | | | |
| integration-module-159d | P027 decision 4: a file changed during the handler goes to `_problem` (`INT_WATCH_FILE_CHANGED`) | DOC GAP | | | |
| integration-module-160d | P028 decision 5: `ClientCertificate.key()` / `chain()` are public, never logged | DOC GAP | | | Also a public-API candidate to hide |
| integration-module-161d | P028 decision 6: the User-Agent stays `platform-integration` | DOC GAP | | | Also untested |
| integration-module-162d | P028 decision 13: integration-spring's fallback `Outbound` and `Installation` beans | DOC GAP | | | |
| integration-module-163 | P022 B decision 2 / P028: HTTP plumbing copied, then moved to platform-outbound; the "already has" table still says reuse notifications | DOC GAP | | | |
| integration-module-164 | Body lines 100 and 160 still describe the JVM start option and `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` as current; only later sections undo them | DOC GAP | | | Mark them as replaced by P026 |
| integration-module-165 | The P032 outcome for the `integrations` contributor (replaced by monitoring's `dependencies`) is not recorded here | DOC GAP | | | Only in `monitoring.md`; add a cross-reference |

P028 report decisions 7–12 (push-caller matching, `*` rows) belong to `setup.md` / identity and are not checked here.

## integration-profile-format

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-profile-format-1 | platform-core ships no profiles; it reads the file the installation gives | DONE | `pi/profile/ProfileFolder.java`, `ProfileLoader.java`; no profile under any `src/main` | `IntegrationWiringTest` `theProfileFolderAndThePrivateCa` | |
| integration-profile-format-2 | JSON Schema file, `ProfileValidator`, `ProfileLoader.load(Path)` | DONE | `schema/platform-integration-profile-1.schema.json` | `pit/ProfileValidatorTest.java` `bothMadeUpProfilesPass` | |
| integration-profile-format-3 | `format` always `platform-integration-profile/1`; an unknown format refused, never guessed (`/2` is its own format) | DONE | `ProfileValidator` | `ProfileValidatorTest` `oneRefusalPerCode` (`INT_PROFILE_FORMAT_UNKNOWN`) | |
| integration-profile-format-4 | `name` lower case, digits, `.`, `_`, `-`; `version` required | PARTLY | schema pattern | `oneRefusalPerCode` (missing version) | A name breaking the pattern is never tested |
| integration-profile-format-5 | `direction` / `kind`: OUTBOUND + API_PUSH, or INBOUND + API_POLL | DONE | `ProfileValidator` | `PollProfileTest` `theFormatChecksForPolling` | |
| integration-profile-format-6 | API_PUSH: exactly one of `steps` or `packaging`; a preset expands to ordinary steps | PARTLY | `ProfileValidator`, `Packaging` | `oneRefusalPerCode` (both given; preset missing its part); `bothMadeUpProfilesPass` (expansion) | "Neither" is never tested |
| integration-profile-format-7 | `signIn` types incl. `MUTUAL_TLS` (certificate never in a profile), `installationSupplies`, `apiKeyHeader` required with API key | DONE | schema, `ProfileValidator` | `oneRefusalPerCode` (`INT_PROFILE_SIGN_IN`); `AcceptedLaterTest` `aProfileWithAcceptedLaterIsValidAndFitsItsSpec` (API_KEY + MUTUAL_TLS) | |
| integration-profile-format-8 | `health`: GET / HEAD, path, with or without sign-in, its own rules | DONE | `HealthCheck` | `ProfileMockTest` `healthBusyAndDown` | |
| integration-profile-format-9 | `rules`: status, code (exact, prefix, `-`), optional field or Retry-After presence; outcome; platform code; attempt used | DONE | `Rule` | `ClassificationRulesTest` `prefixCodesAndFieldRules`, `aRuleCanSayTheAttemptIsNotUsed`; `BuildingBlocksTest` `stepRulesComeFirstAndRulesCanAskForRetryAfter`; `oneRefusalPerCode` (`INT_PROFILE_RULE`) | |
| integration-profile-format-10 | `errorCodePointer` | DONE | `IntegrationProfile` | `bothMadeUpProfilesPass` (`/error/code`); `ProfileMockTest` `everySwitch` (destination code) | |
| integration-profile-format-11 | `limits` defaults from the profile | UNTESTED | `IntegrationProfile.complete` | — | No profile test reads or asserts them |
| integration-profile-format-12 | `values`: declared, required or not; undeclared `INT_PROFILE_VALUE_UNDECLARED`; required checked at prepare | DONE | `ProfileValidator`, `IntegrationProfile.prepare` | `oneRefusalPerCode`; `ProfileMockTest` `requiredValuesAndAttributesAreChecked` | |
| integration-profile-format-13 | `fields`: one object of a step's JSON body (`"<object>":{}`), expected names, other names allowed or not | DONE | `ProfileValidator`, `complete` | `oneRefusalPerCode` (`INT_PROFILE_FIELDS` ×2); `ProfileMockTest` `everySwitch` | |
| integration-profile-format-14 | `documentAttributes`: allowed values and default | DONE | `ProfileValidator` | `oneRefusalPerCode` (`INT_PROFILE_ATTRIBUTE` ×2); `requiredValuesAndAttributesAreChecked` | |
| integration-profile-format-15 | `settings`: default among its values; values are engine settings; only `token.requiredClaims` exists | DONE | `ProfileValidator` | `oneRefusalPerCode` (`INT_PROFILE_SETTING` ×2) | The engine effect is untested (integration-module-46) |
| integration-profile-format-16 | `notes` for IT | DONE | `IntegrationProfile.notes` | `bothMadeUpProfilesPass` | |
| integration-profile-format-17 | `testMode` FULL or CHECK_ONLY | DONE | `TestAction` | `TestActionTest` `fullRunSendsOnlyTheFakeDocumentAndFakeValues`, `checkOnlyRunsHealthAndSignInAndSendsNoStep` | |
| integration-profile-format-18 | `steps[].accepted`: last step only; reference pointer or `Location`; status method / path with `{status.reference}`; state pointer and values; default wait 30 s; `read` | DONE | `CallStep.Accepted` | `AcceptedLaterTest` (all) | |
| integration-profile-format-19 | `poll` for API_POLL in place of steps / packaging, without fields and attributes; list (only `{correlationId}`), pointers, values, paging, download, acknowledge | DONE | `ProfileValidator`, `PollSource` | `PollProfileTest` `theFormatChecksForPolling`, `aPollingProfileIsCheckedCompletedAndRunsAgainstItsMock` | |
| integration-profile-format-20 | Templates: fixed placeholders only, no expressions or scripts | DONE | `Template` | `oneRefusalPerCode` (`INT_PROFILE_TEMPLATE`, `{secret.key}`) | |
| integration-profile-format-21 | `{#each doc}…{/each}` in JSON only | DONE | `Template` | `BuildingBlocksTest` `theEachBlockWritesOneItemPerDocument` | |
| integration-profile-format-22 | `{name?}` misplaced in a profile reported as `INT_PROFILE_TEMPLATE` | PARTLY | `ProfileValidator` maps `INT_CONFIG_OMIT_PLACE` | `OmitWhenMissingTest` (configuration level only) | The profile-level mapping is untested |
| integration-profile-format-23 | Never an address, secret, client ID, entity, case field key or customer data: `scheme://`, secret-looking text, `{field:}` → `INT_PROFILE_INSTALLATION_DATA` | DONE | `ProfileValidator` | `oneRefusalPerCode` (3 cases); `PollProfileTest` (next link); `/baseAddress` refused by the schema | |
| integration-profile-format-24 | `IntegrationProfile.complete` adds entity, product, address, sign-in, mapping, settings; then the normal save checks | DONE | `IntegrationProfile.complete` | `ProfileMockTest` (save checks), `requiredValuesAndAttributesAreChecked`; `PollProfileTest` | |
| integration-profile-format-25 | Every problem has a code and a JSON pointer; never repeats a value | DONE | `ProfileProblem` | `oneRefusalPerCode`, `noProblemRepeatsAValue` | |
| integration-profile-format-26 | `INT_PROFILE_UNREADABLE`: cannot be read, not UTF-8, or over 2 MiB | PARTLY | `ProfileLoader.MAX_BYTES` | `oneRefusalPerCode` (missing, Latin-1) | Over 2 MiB untested |
| integration-profile-format-27 | Codes `NOT_JSON`, `SCHEMA`, `STEPS`, `TEMPLATE`, `HEADER`, `RULE`, `VALUE_UNDECLARED`, `ATTRIBUTE`, `FIELDS`, `SETTING`, `SIGN_IN`, `INSTALLATION_DATA` | DONE | `ProfileValidator` | `oneRefusalPerCode` (one each) | `INT_PROFILE_HEADER` for an invalid header name is not tested, only a reserved one |
| integration-profile-format-28 | `/1` changes only by additions that old profiles still pass | DONE | schema | `bothMadeUpProfilesPass`, `ProfileContractTest` `theKitAndTheValidatorShareTheFormat` (older profiles still valid) | |
| integration-profile-format-29 | Setup checks that the release tag ends with the profile's version | DONE | platform-setup `SetupInputs` | platform-setup `ProfileSetupTest` `theNameAndTheVersionMustMatchTheEntry` | |
| integration-profile-format-30 | `checkAgainstSpec`: every operation exists with the same method and path | DONE | `ProfileContract` | `ProfileContractTest` `plantedSpecChangesAreCaught` | |
| integration-profile-format-31 | Required headers are sent; no header the spec does not know | PARTLY | `ProfileContract` | `plantedSpecChangesAreCaught` (required header) | An unknown header is never planted |
| integration-profile-format-32 | Request bodies (JSON, multipart JSON part) validate against the schemas | DONE | `ProfileContract` | `plantedSpecChangesAreCaught` (required field, part renamed) | |
| integration-profile-format-33 | Values the profile reads are in the answers | DONE | `ProfileContract` | `plantedSpecChangesAreCaught` (checksum) | |
| integration-profile-format-34 | Every error code in `x-error-codes` is named by a rule | DONE | `ProfileContract` | `plantedSpecChangesAreCaught`; `AcceptedLaterTest` `theContractKitCatchesASpecWithoutTheStatus` | |
| integration-profile-format-35 | `checkAgainstServer`: health healthy or busy, sign-in works, one empty request refused with a code a rule names; no real data | UNTESTED | `ProfileContract` | `ProfileContractTest` `theLiveCheckAgainstAServer` (passes; health down and wrong credential reported) | A server that refuses with an unnamed code, or accepts the empty request, is never tested |
| integration-profile-format-36 | Both checks report every problem | DONE | `ProfileContract.Problem` lists | `plantedSpecChangesAreCaught` | |
| integration-profile-format-37 | `MockSource.forProfile` | DONE | testkit `MockSource` | `PollProfileTest` `aPollingProfileIsCheckedCompletedAndRunsAgainstItsMock` | |
| integration-profile-format-38 | `ProfileMock` of any profile, optionally following its OpenAPI file; switches from the rules (busy, down, refused, already received, sign-in refused, health busy / down) | DONE | testkit `ProfileMock` | `ProfileMockTest` `everySwitch`, `healthBusyAndDown`, `theIntegrityCheck` | |

## integration-standards

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| integration-standards-1 | 11: scanner interface and adapters (ICAP, clamd), quarantine, alert event, specific codes | DONE | `pi/scan/*` | `ScannerTest`; `WatchedFolderTest` `eicarIsQuarantinedAndNeverReachesTheHandler` | |
| integration-standards-2 | 11: on premises used only if the customer provides a scanner; none = off | DONE | `Scanning`, `scanner.type` default NONE | `IntegrationWiringTest` `intakeBeansTheScannerAndTheirHealth` | |
| integration-standards-3 | 11: every incoming file scanned before processing; infected quarantined, never processed | DONE | see integration-module-159 / 160 | same | |
| integration-standards-4 | 11: the IT Admin is alerted | APP-SIDE | `ScanEvents.infected` hook (tested) | — | App: alert |
| integration-standards-5 | 11: always on for the hosted service | MISSING | — | — | Deferred by P027 decision 1 (hosted go-live) |
| integration-standards-6 | 12: callback signed, retried, idempotent (outbox pattern) | DONE | `pi/callback/*` | `CallbackTest` | |
| integration-standards-7 | 12: status API endpoint and its data | APP-SIDE | — | — | App: status API |
| integration-standards-8 | 13: watch folders and file locations use a customer service account (no sign-in on folder kinds) | DONE | `ConfigCheck` | `FolderConfigTest` `theSaveCheckRefusesWhatAFolderExportCannotHave`; `IntakeConfigTest` `aWatchedFolderConfigurationIsCheckedWhenSaved` | |
| integration-standards-9 | 13: APIs accept mutual TLS as well as service-account credentials | DONE | `SignIn.MutualTls` | `MutualTlsTest` | |
| integration-standards-10 | 13: one credential per integration per entity | DONE | `int_credential` per integration (one entity each); unique CURRENT / NEXT | `PostgresCredentialsAndCallbacksTest` `certificatesSealedRotatedAndHistoryWithTheDmlOnlyUser` | |
| integration-standards-11 | 13: rotation with an overlap window, no downtime | DONE | CURRENT / NEXT | `MutualTlsTest` `nextIsTestedThenSwitchedWithoutDowntime`, `nextBecomesCurrentAtItsSetTime` | |
| integration-standards-12 | 13: expiry warning events | DONE | `CredentialExpiry` | `MutualTlsTest` `expiryWarningsAt30And7And1DaysAndAfter` | The notice is app side |
| integration-standards-13 | 14: integrity check on delivery (checksum) | DONE | `CallStep.integrity`; folder read-back | `PackagingTest` `theIntegrityCheckComparesWhatTheDestinationSays`; `FolderExportTest` (corrupt) | |
| integration-standards-14 | 11 app part: call the scan at intake (push API) | APP-SIDE | `Scanning` usable by the app | — | Polling and watched folder call it themselves |
| integration-standards-15 | 15: monitoring hooks, diagnostic bundle | n/a | platform-monitoring | — | Monitoring slice |
| integration-standards-16 | 16: API versioning, error format, request IDs | n/a | platform-errors | — | Error-codes slice |
| integration-standards-17 | 20: per-entity time zone | n/a | identity | — | Identity slice (built P020) |

## Install guide check: docs/install/integration.md

| Item in guide | Kind (property/format/option/code/default) | In code? | Difference |
|---|---|---|---|
| install-integration-1: section 7 `/actuator/health/integrations`, one entry per integration, DOWN when a system is down | property / behaviour | `IntegrationAutoConfiguration.Health` | With platform-monitoring-spring on the classpath the contributor is not created (`@ConditionalOnMissingClass`); the entries are in monitoring's `dependencies` and never affect readiness. The guide does not say so |
| install-integration-2: `INTEGRATION_SETTINGS_INVALID`, `OUTBOUND_SETTINGS_INVALID` | code | message prefixes in `IntegrationConfigException` / `OutboundConfigException` | Not `PlatformCode` values, so not in the catalogue the guide points to (section 14). Known P032 question 2 gap |
| install-integration-3: `INT_WATCH_CLAIM_LOST` "you can see" | code | in `IntegrationCode` | Never given by the code: a lost claim is only counted (`WatchResult.claimsLost`) |
| install-integration-4: `INT_TOKEN_CLAIMS_MISSING` | code | given by `SignInHeaders` | Exists; untested (integration-module-46) |

All other items match: 112 checked. That covers 33 properties with their defaults and ranges, the table rights and the version table `int_schema_version`, the folder / probe / test names, watched-folder defaults (30 s stable, 60 min incomplete, 30 min claim), names starting `_` / `.`, the 32 KB credential file, the 64 KB callback body, the signature example, and every `INT_*` code named in section 14.

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| integration-module-120 | `Location` status reference stays on the base origin | Test | B · **FIXED in P037 (cdedb4f)** | AcceptedLaterTest: 202 with a Location on another host/port → UNCLEAR/refused, no call made | S |
| integration-module-134 | NEXT_LINK same origin only (`INT_POLL_NEXT_PAGE_INVALID`) | Test | B · **FIXED in P037 (fde3d04)** | PollingTest: MockSource next link to another host → pass stops with the code, no call there | S |
| integration-module-157 | ICAP 200 without a threat header = infected | Test | B · **FIXED in P037 (6e38f5a)** | MockScanner switch "200 without header"; ScannerTest asserts INFECTED | S |
| integration-module-129 / 155 | `IntakeFile` self-check; changed file → `_problem` `INT_WATCH_FILE_CHANGED` | Test | B · **FIXED in P037 (7496d86)** | WatchedFolderTest: handler that rewrites a file while reading → `_problem` with the code; unit test of `IntakeFile.Changed` | S |
| integration-module-46 | `token.requiredClaims` → `INT_TOKEN_CLAIMS_MISSING` | Test | B · **FIXED in P037 (edd4edb)** | SignInTest: mock `tokenClaims`; missing claim → SIGN_IN_FAILED with the code, nothing sent; present → SENT | S |
| integration-module-153d (code side) | Sensitive field refused at send time for API push (`INT_SENSITIVE_FIELD_NOT_ALLOWED`, CallEngine ~407) | Test (save-time only tested) | B · **FIXED in P037 (cb60d3a)** | CallEngineTest: delivery marks a mapped field sensitive, not allowed → NOT_CONFIGURED, nothing sent | S |
| integration-module-32 | Test action for API_POLL / WATCHED_FOLDER | Kind not supported in FULL mode | C | Test of intake kinds = health + sign-in (CHECK_ONLY), or document that the Test is health only | S |
| integration-module-34 | Live-system tests on request | None | C | Drop the rule (the contract kit covers it) or add an opt-in tag | S |
| integration-module-50 | 408 = busy | Test | C | Add 408 to `classificationDefaults` | S |
| integration-module-59 | Mixed replays = SENT | Test | C | MockSwitchesTest: replay on one call only → SENT, steps show ALREADY_RECEIVED | S |
| integration-module-75 | `INT_FOLDER_WRITE_FAILED` | Test | C | MockFolder generic IO failure switch → OUTAGE with the code | S |
| integration-module-93 | Busy is UP in the contributor | Test | C | IntegrationWiringTest: busy health → UP with outcome BUSY | S |
| integration-module-95 | Retries, cookies, compression off | Test | C | OutboundRulesTest: server sets a cookie / gzip, answer is not decompressed, cookie not sent back | S |
| integration-module-115 | Worker makes timed certificate switches | Test | C | IntegrationWiringTest: NEXT with a past switch time → `runOnce` switches it | S |
| integration-module-119 | Unknown state = UNCLEAR `INT_STATUS_UNKNOWN` | Test | C | AcceptedLaterTest: mock state "ODD" → UNCLEAR with the code | S |
| integration-module-137 | Early stop drops the connection; `INT_POLL_STORAGE_FAILED` | Test | C | PollingTest: unwritable work folder → the code; oversize download closes without reading the rest | S |
| integration-module-163d | Hosted refuses a missing scanner | Deferred | C | Hosted go-live task | S |
| integration-module-163e | ICAP over TLS | Deferred | C | When a customer needs it | M |
| integration-module-5, 33, 35, 41, 44, 49, 63 | Product profile / pinned spec / product contract tests | Removed by P030; doc still says them | C | Rewrite those design lines to "made-up profiles; owner's contract kit" | S |
| integration-module-7, 39, 56, 85 | Superseded rules (push callers, plumbing reuse, JVM proxy option, `_DISABLED_BY_JVM`) | Code follows the later decision; doc body stale | C | Mark each line as replaced, naming the decision | S |
| integration-module-150d…165 | 16 DOC GAPs (listed above) | Decisions not in the design body | C | Add a short "Decisions carried from the reports" section to integration-module.md | S |
| integration-module-161d | User-Agent `platform-integration` | Test too | C | OutboundRulesTest asserts the User-Agent header | S |
| integration-profile-format-4 | Bad profile name pattern | Test | C | ProfileValidatorTest: name `Bad Name` → `INT_PROFILE_SCHEMA /name` | S |
| integration-profile-format-6 | Neither steps nor packaging | Test | C | ProfileValidatorTest: remove both → `INT_PROFILE_STEPS` | S |
| integration-profile-format-11 | Profile `limits` | Test | C | ProfileMockTest: completed config carries the profile's limits | S |
| integration-profile-format-22 | `{name?}` misplaced in a profile → `INT_PROFILE_TEMPLATE` | Test | C | ProfileValidatorTest case | S |
| integration-profile-format-26 | Over 2 MiB unreadable | Test | C | ProfileValidatorTest: 2 MiB + 1 file → `INT_PROFILE_UNREADABLE` | S |
| integration-profile-format-31 | Unknown header refused by the contract kit | Test | C | ProfileContractTest: plant a header the spec lacks | S |
| integration-profile-format-35 | Server-check refusal path | Test | C | ProfileContractTest: mock refuses with an unnamed code → problem reported | S |
| integration-standards-5 | Scanning always on for hosted | Deferred | C | Hosted go-live task (same as 163d) | S |
| install-integration-1 | Guide section 7 ignores monitoring-spring | Doc | C | Add: "with platform-monitoring-spring the entries are in `dependencies` (docs/install/monitoring.md)" | S |
| install-integration-2 | `*_SETTINGS_INVALID` not codes | Catalogue | B · **FIXED in P037 (2a21e26)** | Cross-check 2 (P032 question 2): make them `PlatformCode`s or say in the guide they are message prefixes | M |
| install-integration-3 | `INT_WATCH_CLAIM_LOST` never given | Doc/code | C | Remove it from the guide table (or give it in `WatchResult`) | S |
