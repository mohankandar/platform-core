# Tick-off: setup.md (platform-setup and the identity modules), install guides setup.md and identity.md

Counts (setup.md, 134 rules): DONE 93 · UNTESTED 5 · PARTLY 0 · MISSING 3 · DIFFERENT 1 · APP-SIDE 9 · DOC GAP 23
Install guides: docs/install/setup.md 4 differences (one is setup-111); docs/install/identity.md 1 difference.

Read-only check against the code on branch `docs/p035-tick-off` (2026-11-23). Modules: platform-setup,
platform-identity-core, platform-identity-jdbc, platform-identity-spring, platform-identity-testkit. The three MISSING
rows are work the design itself defers to the hub-mode task; nothing is due for the integration release.

Path shorthand (main code / tests):
- `S/` = `platform-setup/src/main/java/com/documentplatform/setup/`, `ST/` = `platform-setup/src/test/java/com/documentplatform/setup/`
- `IC/` = `platform-identity-core/src/main/java/com/documentplatform/identity/`, `ICT/` = `platform-identity-core/src/test/java/com/documentplatform/identity/`
- `IJ/` = `platform-identity-jdbc/src/main/java/com/documentplatform/identity/jdbc/`, `IJT/` = `platform-identity-jdbc/src/test/java/com/documentplatform/identity/jdbc/`
- `SQL/` = `platform-identity-jdbc/src/main/resources/db/platform/identity/`
- `ISP/` = `platform-identity-spring/src/main/java/com/documentplatform/identity/spring/`, `ISPT/` = `platform-identity-spring/src/test/java/com/documentplatform/identity/spring/`
- `ITK/` = `platform-identity-testkit/src/main/java/com/documentplatform/identity/`, `ITKT/` = `platform-identity-testkit/src/test/java/com/documentplatform/identity/inmemory/`

## setup.md — section 1 Words

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-1 | User-facing text says "customer" and "entity", never "tenant" | UNTESTED | `S/SetupRefusal.java`, plan lines in `S/SetupRun.java`; `IC/store/*Outcome.java` messages | — | Code mostly follows it. A few texts still say tenant: `SETUP_CONFIG_OTHER_TENANT.cause` ("another customer's tenant ID") and the config fragment comment in `S/ConfigFragment.java` "customer (tenant) ID". No test looks for the word |
| setup-2 | The apps' `tenants` tables (which hold entities) are aligned in each app's switch-over | APP-SIDE | — | — | App switch-over tasks |

## setup.md — section 2 Decisions (owner, 2026-11-21)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-3 | Shared `idn_` tables owned by platform-core, own Flyway run `db/platform/identity`, history table `idn_schema_history` | DONE | `SQL/V1__identity.sql`…`V4__platform_grants.sql`; `IJ/IdentitySchema.java` `verify` | `IJT/IdentitySchemaTest.java` `theTablesComeOnlyFromTheSchemaRun`, `startupRefusalCodes` | `idn_schema_history` is named in the SQL header and the guide (Flyway's table, not checked by code) |
| setup-4 | Each app keeps its own entity table, created when the Business Admin activates a slot | APP-SIDE | `IC/store/SlotActivation.java` returns `EntityRecord.entityUuid()` | `IJT/SlotActivationTest.java` `theAppActivatesAndCreatesItsEntityRowInOneTransaction` | The app's activation screen |
| setup-5 | `entity_uuid` = name-based UUID of `"platform-entity|" + slotId`; the same in every install and on hosting | DONE | `IC/EntityUuid.java` `fromSlotId` (`NAME_PREFIX`) | `ICT/EntityUuidTest.java` `theUuidIsPinnedAndTheSameEveryTime`; `IJT/PostgresIdentityStoresTest.java` `slotsGetTheirDerivedUuidAndTheSameUuidInEveryDatabase`; `ST/SetupCommandTest.java` `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing` | |
| setup-6 | One login system per customer, shared by all its entities | DONE | `IJ/PostgresIdentityLookup.java` `find`; one-active index in `SQL/V1__identity.sql` | `IJT/PostgresIdentityStoresTest.java` `oneLoginConnectionIsSharedByTheCustomersEntities` | Per customer and product (setup-30) |
| setup-7 | Land on the last entity used; chooser only with no usable last entity; header switcher; every switch audited; entity name in the header and in upload results; no extra confirmation; last entity kept per person | APP-SIDE | — | — | App UI work ("not this module"); nothing in identity stores a last entity |
| setup-8 | People with only one entity never see a chooser; a choice is a request, never proof | DONE | `IC/admission/EntitySelection.java` `select` | `ICT/admission/EntitySelectionTest.java` `singleAuthorizedTenantDoesNotRequireSelection`, `fabricatedOrMalformedTenantNeverGrantsAccess`, `multipleMembershipsRequireExplicitChoice` | |
| setup-9 | Everything is for the chosen entity only, never mixed (roles scoped to the entity) | DONE | `IC/admission/EntityGroupAuthorization.java`, `EntityMembershipResolver.java` | `ICT/admission/EntityGroupAuthorizationTest.java` `rolesAreScopedToSelectedTenant`; `ICT/admission/EntityMembershipResolverTest.java` `isolatesRolesEvenWhenIdpGroupsContainMultipleTenants`, `authenticatedGroupForAnotherTenantNeverElevatesSelectedTenant` | What the screens show is app-side |
| setup-10 | Watch folders and API intake are tied to one entity in their setup | APP-SIDE | push callers: one binding → one entity (setup-53) | — | Integration configuration per entity is the app's / integration module's |
| setup-11 | Separate database per product by default, or one schema per product; setup runs once per product database or schema with the same files | DONE | `S/SetupCommand.java` (unqualified table names, `--product`) | `ST/SetupCommandTest.java` `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing` (two schemas in one database, `ST/Pg.java`) | |
| setup-12 | Grants carry `product_code`, so one shared record set works without redesign | DONE | `SQL/V1__identity.sql` `idn_group_grant.product_code`; `IJ/PostgresGroupGrantStore.java` | `IJT/PostgresIdentityStoresTest.java` `grantsAreSeparatedByProductCode`; `ICT/mapping/MappingPlanPerProductTest.java` `theSameEntityInTwoProductsGivesTwoChanges` | |
| setup-13 | Setup never deletes customers, entities or slots; a slot dropped from a newer licence becomes `NOT_IN_LICENCE` | DONE | `IJ/PostgresEntityStore.java` `syncSlots` (no delete in `EntityStore`); `S/SetupRun.java` `planSlots` | `IJT/PostgresIdentityStoresTest.java` `customerAndSlotSyncAreIdempotentAndNeverDelete`; `ST/SetupCommandTest.java` `aSlotDroppedFromANewerLicenceIsKeptAsNotInLicence` | |
| setup-14 | Grants missing from the mapping file are removed only with `--remove-unlisted-grants`; the plan shows the removal before it happens | DONE | `S/SetupRun.java` `planGrants`, `apply` | `ST/SetupCommandTest.java` `thePlanShowsRemovalsFirstAndGrantsAreRemovedOnlyWithTheFlag` | |
| setup-15 | Each product ships `product-roles.json`, read with `--product`; no product code in the command | DONE | `S/ProductRoles.java` `parse` (productCode must equal `--product`) | `ST/SetupCommandTest.java` `wrongOptionsAndHubModeAreRefused` (SETUP_PRODUCT_ROLES_INVALID); `ICT/NoProductNamesGuardTest.java` `noProductNameInCodeDataOrInstallGuides` | |
| setup-16 | Demo data uses a separate development signing key that production never trusts | DONE | `platform-setup/demo/development-trusted-keys.json` (`"development": true`), `licence-demo.json` | `ST/SetupCommandTest.java` `theDevelopmentKeyLicenceIsRefusedByProductionTrustedKeys` | |
| setup-17 | Demo-only app records come from a separate app demo step after setup, never at startup | APP-SIDE | — | — | The apps' demo step |
| setup-18 | Direct sign-in first; hub mode is not supported now | DONE | `S/SigninFile.java` `parse` → SETUP_MODE_NOT_SUPPORTED | `ST/SetupCommandTest.java` `wrongOptionsAndHubModeAreRefused` | |
| setup-19 | Hub mode (Keycloak admin REST API, broker secret from Vault) after the hub proof of concept | MISSING | — | — | Deferred by the decision itself; hub-mode task |
| setup-20 | The app's own licensing is replaced by platform-licensing in its switch-over | APP-SIDE | — | — | App switch-over |
| setup-21 | `platform.licensing.tenant-id` becomes required; setup writes it | DONE | `S/ConfigFragment.java` `check` / `write`; licensing-spring settings | `ST/SetupCommandTest.java` `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing`, `aDatabaseOfAnotherCustomerOrAConfigOfAnotherCustomerIsRefused`; `platform-licensing-spring/.../LicensingWiringTest.java` (tenant-id is missing) | |

## setup.md — section 2a Where the database lives

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-22 | One data model everywhere; every record carries its entity (and customer) | APP-SIDE | identity tables carry `tenant_id` / `slot_id` / `entity_uuid` | — | App records |
| setup-23 | On premises: one customer per database; setup refuses a second (`SETUP_OTHER_CUSTOMER_INSTALLED`) | DONE | `S/SetupRun.java` `requireOnlyThisCustomer` (`idn_customer` and `lic_licence`) | `ST/SetupCommandTest.java` `aDatabaseOfAnotherCustomerOrAConfigOfAnotherCustomerIsRefused` | |
| setup-24 | Hosted modes (shared database with row-level security, dedicated database, setup hosted mode) are not built now | DONE | No hosted mode in `S/SetupCommand.java`; the refusal of setup-23 holds | `ST/SetupCommandTest.java` `aDatabaseOfAnotherCustomerOrAConfigOfAnotherCustomerIsRefused` | Nothing due now; row-level security gets its own design task |

Section 3 (build order) holds no checkable rule.

## setup.md — section 4 Decisions from P013

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-25 | Suspend and resume API now (`SlotActivation.suspend` / `resume`), audited by the caller | DONE | `IC/store/SlotActivation.java` `suspend`, `resume` | `IJT/SlotActivationTest.java` `suspendAndResume` | Auditing is app-side |
| setup-26 | Activation allowed while the licence is valid, expiring or in grace; `LICENCE_EXPIRED` after grace, `LICENCE_NOT_STARTED` before the start | DONE | `IC/store/SlotActivation.java` `licenceRefusal` | `IJT/SlotActivationTest.java` `activationIsAllowedInGraceAndRefusedAfterGrace` | |
| setup-27 | Resume follows the same rule; suspend is always allowed | DONE | `IC/store/SlotActivation.java` `resume`, `suspend` | `IJT/SlotActivationTest.java` `suspendAndResume` (expired / no licence: resume refused, suspend works) | |
| setup-28 | Hub mode: organization claim in the lookup key | MISSING | — | — | Deferred "later, with hub mode" |
| setup-29 | A SUSPENDED slot dropped from the licence comes back SUSPENDED (`idn_entity.status_before_removal`) | DONE | `SQL/V1__identity.sql` `status_before_removal`; `IJ/PostgresEntityStore.java` `syncSlots`; `ITK/inmemory/InMemoryIdentityStore.java` | `IJT/PostgresIdentityStoresTest.java` `aSuspendedSlotDroppedFromTheLicenceComesBackSuspendedNeverActive`; `ITKT/InMemoryIdentityStoreTest.java` `suspendedSlotComesBackSuspended` | |
| setup-30 | One ACTIVE login connection per (customer, product) | DONE | `SQL/V1__identity.sql` `idn_login_connection_one_active` | `IJT/PostgresIdentityStoresTest.java` `connectionsKeepHistoryAndOneActivePerCustomerAndProduct` | |
| setup-31 | The binding resolver returns a binding only for exactly one connection whose customer has exactly one ACTIVE entity; else the person chooses (fails closed) | DONE | `IJ/PostgresIdentityLookup.java` `findActive` | `IJT/PostgresIdentityStoresTest.java` `oneLoginConnectionIsSharedByTheCustomersEntities` | |
| setup-32 | A database error throws; the app never falls back to a configured entity | DONE | `IJ/PostgresIdentityLookup.java` (`IdentityStoreException`) | `IJT/PostgresIdentityStoresTest.java` `anUnreadableStoreRefusesSignIn` | |
| setup-33 | Store interfaces and `SlotActivation` live in identity-core; the testkit needs no JDBC | DONE | `IC/store/*`; `platform-identity-testkit/pom.xml` depends on identity-core only | `ITKT/InMemoryIdentityStoreTest.java` `activationRules` (same rules on the in-memory store) | |
| setup-34 | Activation checks the licence in force (`LicensedSlots`; `LicenceSlots` adapts `LicenceService`) | DONE | `IC/store/LicensedSlots.java`, `IJ/LicenceSlots.java` | `IJT/SlotActivationTest.java` `withoutAValidLicenceNothingIsActivated` | |
| setup-35 | Refusals `NO_LICENCE`, `NOT_IN_LICENCE`, `SLOT_UNKNOWN`, `ALREADY_ACTIVATED`, `NAME_INVALID`, `NAME_TAKEN` | DONE | `IC/store/ActivationOutcome.java` `Result` | `IJT/SlotActivationTest.java` `slotsNotInTheLicenceAreRefused`, `aLicensedSlotIsActivatedOnceAndNamed`, `namesAreCheckedAndUniquePerCustomer` | Also `NOT_A_SLOT_ID` and `NOT_ACTIVATED`, not in the design list |
| setup-36 | Names trimmed, 1–200 characters, no control or zero-width characters, unique per customer ignoring case | DONE | `IC/store/SlotActivation.java` `cleanName`; index `idn_entity_name_per_customer` | `IJT/SlotActivationTest.java` `namesAreCheckedAndUniquePerCustomer`, `renameRules`, `twoAdminsAtOnceNeverGetTheSameName` | |
| setup-37 | Messages name the slot ID, never the typed name | DONE | `IC/store/ActivationOutcome.java` `message` | `IJT/SlotActivationTest.java` `namesAreCheckedAndUniquePerCustomer` ("never repeat what was typed"), `aLicensedSlotIsActivatedOnceAndNamed` | |
| setup-38 | A slot back in the licence returns to the status it had | DONE | `IJ/PostgresEntityStore.java` `syncSlots` | `IJT/PostgresIdentityStoresTest.java` `aSlotDroppedFromTheLicenceBecomesNotInLicenceAndComesBackAsItWas` | Follows the owner's later answer (setup-29) |
| setup-39 | `activated()` (for licensing) includes SUSPENDED slots | DONE | `IJ/PostgresEntityStore.java` `activated` | `IJT/PostgresIdentityStoresTest.java` `activatedSlotsFeedLicensingIncludingSuspendedOnes` | |
| setup-40 | Mapping files with slot IDs: no activation or login system needed; `entity_name` may be empty for slot rows only, must match if given | DONE | `IC/mapping/RoleMappingFile.java` `forSlots` | `ICT/mapping/SlotMappingFileTest.java` `slotIdsAreAcceptedBeforeActivationAndTheNameMayBeEmpty`, `slotsMustBeInTheLicenceAndANameMustMatch` | |
| setup-41 | The entity UUID uses the `StableIdentityId` hash; it equals the actor ID for issuer `"platform-entity"` (not an address) | DONE | `IC/EntityUuid.java`, `IC/StableIdentityId.java` | `ICT/EntityUuidTest.java` `itIsANameBasedUuidAndDiffersPerSlot` | |
| setup-42 | identity-jdbc depends on platform-licensing (plain Java) for `LicenceSlots` only | DONE | `platform-identity-jdbc/pom.xml` | `IJT/PostgresIdentityStoresTest.java` `activatedSlotsFeedLicensingIncludingSuspendedOnes` | |
| setup-43 | Grants can be removed (`GroupGrantStore.remove`); customers and entities have no delete | DONE | `IC/store/GroupGrantStore.java` `remove`; `EntityStore` has no delete | `IJT/GrantHistoryTest.java` `everyGrantChangeWritesItsRowsAndTheAppReadsThemPerEntity`; `IJT/PostgresIdentityStoresTest.java` `customerAndSlotSyncAreIdempotentAndNeverDelete` | |
| setup-44 | The Flyway run is tested by running the SQL files as the schema owner; no Flyway dependency | DONE | `IJT/Pg.java`, `ST/Pg.java` `migrated`; no flyway in the poms | `IJT/IdentitySchemaTest.java` `theTablesComeOnlyFromTheSchemaRun`, `theDmlOnlyUserRunsEverythingButCannotChangeTables` | |

## setup.md — section 5 Decisions from P014

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-45 | Each product ships a `setup` wrapper script that passes `--keys` and `--product-roles` | APP-SIDE | — | — | Product distributions |
| setup-46 | Suspending a `NOT_IN_LICENCE` slot stays refused | DONE | `IJ/PostgresEntityStore.java` `suspend` | `IJT/SlotActivationTest.java` `suspendAndResume` (`suspend(DROPPED)` → NOT_IN_LICENCE) | |
| setup-47 | Exit code 3 when grants are not removed (no flag) | DONE | `S/Plan.java` `needsFlag`; `S/SetupCommand.java` `EXIT_NEEDS_FLAG` | `ST/SetupCommandTest.java` `thePlanShowsRemovalsFirstAndGrantsAreRemovedOnlyWithTheFlag` | |
| setup-48 | Grants under an older issuer are listed as "not in the file" | MISSING | `S/SetupRun.java` `planGrants` reads only the sign-in file's issuer | — | Owner: "in the hub-mode task". Today they are neither listed nor touched |
| setup-49 | One customer per database for on-premises installs | DONE | as setup-23 | `ST/SetupCommandTest.java` `aDatabaseOfAnotherCustomerOrAConfigOfAnotherCustomerIsRefused` | |
| setup-50 | Exit code 1 for failures | DONE | `S/SetupRefusal.java` `SETUP_FAILED`, `SETUP_CONFIG_NOT_WRITTEN` | `ST/SetupCommandTest.java` `aRerunAfterAMidRunFailureFinishesWhatWasMissing` | |

## setup.md — section 6 Integration client bindings

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-51 | Setup loads client ID → entity slot → source next to the role mapping; safe to rerun; shown in the plan | DONE | `S/SetupRun.java` `planClientBindings` | `ST/ClientBindingsSetupTest.java` `loadsTheDemoFileAndARerunChangesNothing` | |
| setup-52 | The file holds client IDs only, never secrets | DONE | `IC/mapping/ClientBindingsFile.java` `looksSecret` | `ICT/mapping/ClientBindingsFileTest.java` `secretsAreRefusedAndNeverRepeated` | |
| setup-53 | Each client ID binds to exactly one entity and one source; a duplicate is refused | DONE | `IC/mapping/ClientBindingsFile.java` (DUPLICATE_CLIENT_ID); `S/SetupRun.java` (CLIENT_BOUND_ELSEWHERE); unique index `idn_machine_binding_client` | `ICT/mapping/ClientBindingsFileTest.java` `everyProblemHasItsLineAndCode`; `ST/ClientBindingsSetupTest.java` `aClientBoundToAnotherEntityOrSourceIsRefused` | |
| setup-54 | Setup checks that the entity and source exist and stops with a clear message | DONE | `ClientBindingsFile.read` (SOURCE_UNKNOWN); `SetupRun.planClientBindings` (SLOT_UNKNOWN) | `ST/ClientBindingsSetupTest.java` `fileProblemsRefuseTheRunWithLineNumbersAndWriteNothing` | |
| setup-55 | Removing a line switches the binding off, never deletes it | DONE | `S/SetupRun.java` `planClientBindings` (SWITCH_OFF) | `ST/ClientBindingsSetupTest.java` `aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn` | |
| setup-56 | Every change is audited | DONE | `IJ/PostgresMachineBindingStore.java` (`idn_machine_binding_history`) | `ST/ClientBindingsSetupTest.java` `loadsTheDemoFileAndARerunChangesNothing`, `aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn`; `IJT/MachineBindingsTest.java` `storeVersionsAndHistoryWithTheDmlOnlyUser` | |
| setup-57 | Later the IT Admin's integration page edits the same bindings; no second store | APP-SIDE | `IC/store/MachineBindingStore.java` takes `ChangeSource.APP` | — | Integration screens in the apps |

## setup.md — Time zones (P020)

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-58 | `timeZone` / `entityTimeZones` (IANA) stored only where none is stored, in the same transaction as the slots; an existing zone is kept and shown `unchanged … kept`; rerun safe | DONE | `S/SetupRun.java` `planTimeZones`, `apply` | `ST/SetupCommandTest.java` `timeZonesFromTheSignInFileAreDefaultsAndARerunKeepsWhatTheAppSet`, `aRerunAfterAMidRunFailureFinishesWhatWasMissing` (one transaction) | |
| setup-59 | `SETUP_TIME_ZONE_INVALID` when the file is read; `SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE` in the plan | DONE | `S/SigninFile.java` `zone`; `S/SetupRun.java` `planTimeZones` | `ST/SetupCommandTest.java` `anInvalidTimeZoneOrOneForASlotOutsideTheLicenceIsRefused` | |
| setup-60 | Both are optional fields of `platform-signin/1`; files without them work | DONE | `S/SigninFile.java` `parse` | `ST/SetupCommandTest.java` `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing` (demo file has no zones) | |
| setup-61 | `timeZone` becomes required only at `platform-signin/2`; not now | DONE | `S/SigninFile.java` (`FORMAT = platform-signin/1`, optional) | as setup-60 | The `/2` change is future work |

## setup.md — section 6.1 Client-binding input file: final format

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-62 | `client-bindings-<product>.csv`, UTF-8, one product per file, named per product as `clientBindingsFile` (optional) | DONE | `S/SigninFile.java`, `S/SetupInputs.java` `load`; `ClientBindingsFile.read` (NOT_UTF8) | `ST/ClientBindingsSetupTest.java` `loadsTheDemoFileAndARerunChangesNothing`; `ICT/mapping/ClientBindingsFileTest.java` `everyProblemHasItsLineAndCode` | |
| setup-63 | Header exactly `client_id,entity_slot,source,description` | DONE | `ClientBindingsFile.HEADER` | `ICT/mapping/ClientBindingsFileTest.java` `theHeaderIsExact` | |
| setup-64 | `client_id` 1–255 characters, no spaces, commas, quotes or control characters | DONE | `IC/store/MachineBinding.java` `CLIENT_ID`; check in `SQL/V2__machine_binding.sql` | `ICT/mapping/ClientBindingsFileTest.java` `everyProblemHasItsLineAndCode` | |
| setup-65 | `client_id` is compared as given (case-sensitive) | UNTESTED | `ClientBindingsFile.read` (exact map keys); `IJ/PostgresPushCallerDirectory.java` (exact SQL match) | — | No test gives two client IDs that differ only in case |
| setup-66 | `cert-sha256:<64 lowercase hex>` is a certificate fingerprint client ID; no token needed; two lines = rotation overlap; another form → CLIENT_ID_INVALID; plan marks `(client certificate)` | DONE | `S/SetupRun.java` `planClientBindings`; `IC/push/PushCaller.java` `isFingerprint`, `fingerprint` | `ST/ClientBindingsSetupTest.java` `certificateFingerprintsAreClientIdsAndTwoGiveARotationOverlap`; `IJT/PushCallerTest.java` `certificatesBoundNotBoundAndInARotationOverlap` | |
| setup-67 | `entity_slot` must be a slot of the customer's licence; need not be activated; used only once ACTIVE | DONE | `S/SetupRun.java` `planClientBindings` (SLOT_UNKNOWN); `IJ/PostgresMachineClients.java` | `ST/ClientBindingsSetupTest.java` `fileProblemsRefuseTheRunWithLineNumbersAndWriteNothing`, `theLoadedBindingAdmitsTheClientOnlyOnceTheEntityIsActive` | |
| setup-68 | `source` matches `[a-z0-9][a-z0-9._-]{0,63}` and is listed under the product's optional `sources`; older files still work | DONE | `MachineBinding.SOURCE_KEY`; `S/SigninFile.java` `sources` | `ICT/mapping/ClientBindingsFileTest.java` `everyProblemHasItsLineAndCode`; `ST/ClientBindingsSetupTest.java` `anOlderSignInFileWithoutTheNewFieldsStillWorks` | |
| setup-69 | `description` optional, up to 200 characters, never shown to callers, no secrets | DONE | `ClientBindingsFile.read` (DESCRIPTION_INVALID, SECRET_LIKE on description); `IC/push/PushCallerIdentity.java` has no description | `ICT/mapping/ClientBindingsFileTest.java` `everyProblemHasItsLineAndCode`, `secretsAreRefusedAndNeverRepeated` | "Never shown" is held by the type (no description field), not by a test |
| setup-70 | Each `client_id` once per product; a client already bound elsewhere is refused, naming the line | DONE | as setup-53 | `ST/ClientBindingsSetupTest.java` `aClientBoundToAnotherEntityOrSourceIsRefused` | |
| setup-71 | Every change is in the plan, the run log and the binding history | DONE | `S/SetupCommand.java` `writeRunLog` (`switch_off=`) | `ST/ClientBindingsSetupTest.java` `aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn` | |
| setup-72 | Lands in `idn_machine_binding` (customer, product, client ID, entity UUID, source, ACTIVE/INACTIVE, who/when) with history; built-in `MachineClients` so `PostgresMachineEntityBindings` works without an app table | DONE | `SQL/V2__machine_binding.sql`; `IJ/PostgresMachineClients.java`, `PostgresMachineEntityBindings.java` | `IJT/MachineBindingsTest.java` `storeVersionsAndHistoryWithTheDmlOnlyUser`, `theBindingCountsOnlyWhileBindingEntityAndConnectionAreActive` | |
| setup-73 | A binding applies under the customer's ACTIVE login connection for the product | DONE | `IJ/PostgresMachineEntityBindings.java` | `IJT/MachineBindingsTest.java` `theBindingCountsOnlyWhileBindingEntityAndConnectionAreActive` | |
| setup-74 | Reader: `"` and `'` both refused in a client ID; no comment lines; spreadsheet quoting in the description; BOM, CRLF, empty lines accepted | DONE | `IC/mapping/ClientBindingsFile.java` `read`, `fields` | `ICT/mapping/ClientBindingsFileTest.java` `readsTheFormatAsSpreadsheetsSaveIt`, `everyProblemHasItsLineAndCode` | |
| setup-75 | "Looks like a secret": JWT, PEM block, `password`/`secret` word + `=`/`:`, 40+ mixed key-like characters; refused, never repeated | DONE | `ClientBindingsFile.looksSecret` | `ICT/mapping/ClientBindingsFileTest.java` `secretsAreRefusedAndNeverRepeated` | |
| setup-76 | Problems reported as `line N: CODE: …` with the listed codes | DONE | `ClientBindingsFile.Problem.display`; `S/SetupInputs.java` `bindingsInvalid` | `ICT/mapping/ClientBindingsFileTest.java` `everyProblemHasItsLineAndCode`; `ST/ClientBindingsSetupTest.java` `fileProblemsRefuseTheRunWithLineNumbersAndWriteNothing` | |
| setup-77 | The shared reader is `identity.mapping.ClientBindingsFile` in identity-core | DONE | `IC/mapping/ClientBindingsFile.java`, used by `S/SetupInputs.java` | `ICT/mapping/ClientBindingsFileTest.java` (all) | |
| setup-78 | No `clientBindingsFile` switches every binding of the product off; the plan lists each `switch off`; nothing deleted | DONE | `S/SetupRun.java` `planClientBindings` | `ST/ClientBindingsSetupTest.java` `aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn` (sign-in file without the file) | |
| setup-79 | Moving a client takes two runs (refused while ACTIVE elsewhere; an INACTIVE one may come back elsewhere) | DONE | `S/SetupRun.java` `planClientBindings` | `ST/ClientBindingsSetupTest.java` `aClientBoundToAnotherEntityOrSourceIsRefused`, `aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn` | |
| setup-80 | Table added by identity V2; a V1-only database is refused with `IDN_VERSION_OLDER` | DONE | `SQL/V2__machine_binding.sql`; `IJ/IdentitySchema.java` | `IJT/MachineBindingsTest.java` `aV1DatabaseIsRefusedUntilV2IsRun` | |
| setup-81 | `MachineEntityBindings`, `EntityId`, `ConnectionKey` identical to the earlier identity library; package `com.documentplatform.identity.admission` unchanged | UNTESTED | `IC/admission/` | — | Package confirmed by reading; no API-compatibility test |

## setup.md — section 7 P031

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-82 | Drafts: copy of the ACTIVE connection or the first one; test sign-in over an `IssuerFetch`; production `OutboundIssuerFetch` in identity-spring on platform-outbound | DONE | `IC/store/LoginConnections.java`; `IC/signin/OidcTestSignIn.java`; `ISP/OutboundIssuerFetch.java` | `IJT/LoginConnectionDraftsTest.java` `theWholeFlowOnPostgresWithHistory`; `ITKT/InMemoryLoginConnectionsTest.java` `theFirstDraftNeedsValuesAndAStoredCustomer`; `ISPT/LoginConnectionsWiringTest.java` `identityAloneTestsTheSignInThroughTheSharedOutboundRules` | |
| setup-83 | Activate only VALIDATED; the old ACTIVE becomes DISABLED in one transaction | DONE | `LoginConnections.activate`; `IJ/PostgresLoginConnectionStore.java` | `IJT/LoginConnectionDraftsTest.java` `theWholeFlowOnPostgresWithHistory` | |
| setup-84 | Rollback: the previous ACTIVE values as a new version | DONE | `LoginConnections.rollback` | `IJT/LoginConnectionDraftsTest.java` `theWholeFlowOnPostgresWithHistory`; `ITKT/InMemoryLoginConnectionsTest.java` `draftTestActivateThenAChangeAndRollback` | |
| setup-85 | At most one DRAFT or VALIDATED per customer and product (identity V3 index) | DONE | `SQL/V3__grant_history.sql` `idn_login_connection_one_pending` | `IJT/LoginConnectionDraftsTest.java` `theDatabaseAllowsOneDraftPerCustomerAndProduct`; `ITKT/InMemoryLoginConnectionsTest.java` `atMostOneDraftAndVersionsAreChecked` | |
| setup-86 | Setup reads only ACTIVE / DISABLED connections; drafts are the app's | DONE | `S/SetupRun.java` `latestConnection` | `ST/SetupIdentityChangesTest.java` `setupLeavesTheAppsDraftsAloneAndStillSeesAConnectionChangedInTheApp` | |
| setup-87 | `http` issuer only on `localhost`, `127.0.0.1`, `[::1]` and only under a development-key licence; `SETUP_HTTP_ISSUER_NOT_ALLOWED` / `IDN_HTTP_ISSUER_NOT_ALLOWED` | DONE | `IC/signin/LocalTestIssuers.java` `check`; `S/SetupInputs.java` `load`; `LoginConnections` | `ICT/signin/LocalTestIssuersTest.java` `httpOnLoopbackIsAllowedOnlyWithADevelopmentLicence`, `httpElsewhereIsNeverAllowed`; `ST/SetupIdentityChangesTest.java` `anHttpIssuerOnThisMachineIsAcceptedWithTheDevelopmentKeyLicence`, `anHttpIssuerIsRefusedWithoutADevelopmentKeyOrOffThisMachine`; `IJT/LoginConnectionDraftsTest.java` `anHttpIssuerFollowsTheLicencesSigningKey` | |
| setup-88 | `platform-licence-keys/1` gains optional `"development": true` | DONE | licensing `TrustedKeys.isDevelopmentKey`; `IJ/LicenceSigningKey.java` | `ST/SetupIdentityChangesTest.java` `anHttpIssuerOnThisMachineIsAcceptedWithTheDevelopmentKeyLicence` | |
| setup-89 | Role aliases (`"aliases"`) resolved when the mapping file is read; stored as the role | DONE | `S/ProductRoles.java` `aliases`, `aliasTarget`; `IC/mapping/RoleMappingFile.java` | `ST/SetupIdentityChangesTest.java` `aMappingFileMayUseAnAliasWhichIsStoredAsItsRole`, `badAliasesOrABadCustomerKeysValueRefuseTheProductRolesFile`; `ICT/mapping/RoleAliasMappingTest.java` `anAliasIsResolvedToItsRoleAndRemembered` | |
| setup-90 | Grant history `idn_grant_history` (V3), insert only by triggers, written by setup (with its run ID) and by `GroupGrantStore`; read with `GrantHistory.forEntity` | DONE | `SQL/V3__grant_history.sql`; `IJ/PostgresGroupGrantStore.java` `change`; `IJ/PostgresGrantHistory.java` | `IJT/GrantHistoryTest.java` `theHistoryIsInsertOnlyEvenForTheOwner`, `everyGrantChangeWritesItsRowsAndTheAppReadsThemPerEntity`; `ST/SetupIdentityChangesTest.java` `setupWritesTheGrantHistoryWithItsRunAndARerunWritesNone` | |
| setup-91 | `"customerKeys": "required"` refuses `--no-customer-keys` (`SETUP_CUSTOMER_KEYS_REQUIRED`) | DONE | `S/SetupCommand.java` `run`; `S/ProductRoles.java` | `ST/SetupIdentityChangesTest.java` `requiredCustomerKeysRefuseNoCustomerKeys` | |

## setup.md — section 8 P028

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-92 | Token: issuer → the customer's ACTIVE connection for the product (`IDN_PUSH_NO_CONNECTION`) | DONE | `IC/push/PushCaller.java` | `IJT/PushCallerTest.java` `everyTokenRefusal` | |
| setup-93 | The audiences must hold the connection's `apiAudience` (`IDN_PUSH_AUDIENCE`) | DONE | `PushCaller` | `IJT/PushCallerTest.java` `everyTokenRefusal` | |
| setup-94 | Client ID from the configured claims, default `client_id`, `azp`, `cid`, `sub` | DONE | `IC/push/PushCallerSettings.java` `DEFAULT_CLIENT_ID_CLAIMS` | `IJT/PushCallerTest.java` `aTokenFromEachClaimOrderFindsTheEntityAndSource` | |
| setup-95 | The client's binding ACTIVE under that customer; its entity ACTIVE | DONE | `PushCaller.choose` | `IJT/PushCallerTest.java` `everyTokenRefusal` (BINDING_OFF, ENTITY_NOT_ACTIVE) | |
| setup-96 | Optional entity and source claims must equal the binding when present | DONE | `PushCaller` (entity claim: slot ID or entity UUID) | `IJT/PushCallerTest.java` `configuredEntityAndSourceClaimsMustMatchWhenPresent` | |
| setup-97 | Certificate: the binding `cert-sha256:<hex>` | DONE | `PushCaller.byCertificate` | `IJT/PushCallerTest.java` `certificatesBoundNotBoundAndInARotationOverlap` | |
| setup-98 | Certificate: the customer's connection must be ACTIVE | UNTESTED | `PushCaller.byCertificate` (`hasActiveConnection` → IDN_PUSH_NO_CONNECTION) | — | Only the token case of a DISABLED connection is tested (`everyTokenRefusal`); no certificate caller is tried with a DISABLED connection |
| setup-99 | Both (or `requireTokenAndCertificate`): same customer, entity and source | DONE | `PushCaller.decide` | `IJT/PushCallerTest.java` `tokenAndCertificateBothRequiredTheSameOrDifferent` | |
| setup-100 | Refusals `IDN_PUSH_*` are neutral to the caller | UNTESTED | `IC/push/PushCallerResult.java` `NEUTRAL`; `IC/push/PushRefusal.java` | — | The app must send `NEUTRAL`; no test checks the constant or that no code text reaches it |
| setup-101 | Answers kept 30 s per caller | DONE | `PushCallerSettings.DEFAULT_CACHE_FOR` | `IJT/PushCallerTest.java` `aBindingSwitchedOffTakesEffectWithinTheCacheTime` | |
| setup-102 | A database error throws | DONE | `IJ/PostgresPushCallerDirectory.java` | `IJT/PushCallerTest.java` `aDatabaseErrorThrowsAndNeverFallsBack` | |
| setup-103 | `admission.MachineSourceBindings` (entity and source) beside the unchanged `MachineEntityBindings` | DONE | `IC/admission/MachineSourceBindings.java`; `IC/push/DirectoryMachineSourceBindings.java` | `IJT/PushCallerTest.java` `theSourceLookupBesideMachineEntityBindings` | |
| setup-104 | Spring bean from `platform.identity.push.*` | DONE | `ISP/IdentityPushCallerAutoConfiguration.java`, `PushCallerProperties.java` | `ISPT/PushCallerWiringTest.java` `theBeanFromTheSettingsAndTheHelperForATokenAndACertificate`, `withoutAProductCodeThereIsNoBeanAndBadSettingsStopTheApp` | |
| setup-105 | Postgres `PostgresPushCallerDirectory` and in-memory `pushCallerDirectory()` | DONE | `IJ/PostgresPushCallerDirectory.java`; `ITK/inmemory/InMemoryIdentityStore.java` | `IJT/PushCallerTest.java` (parameterized `memory` / `postgres`) | |
| setup-106 | A `*` row grants one of the product's `platformRoles`; `SETUP_ENTITY_ROLE_PLATFORM_WIDE` / `SETUP_PLATFORM_ROLE_ON_ENTITY` | DONE | `IC/mapping/RoleMappingFile.java`; `S/SetupRun.java` `readMapping` | `ST/SetupIdentityChangesTest.java` `starRowsLoadAreRefusedForTheWrongRoleKindAndAreRemovedOnlyWithTheFlag`; `ICT/mapping/PlatformWideRowsTest.java` `theWrongRoleKindIsRefusedOnEitherKindOfRow`; `ST/SetupCommandTest.java` `anUnknownRoleRefusesTheWholeFileAndNothingIsWritten` | |
| setup-107 | Stored in `idn_platform_grant` (V4); `idn_grant_history.scope` ENTITY / PLATFORM; PLATFORM rows have no entity | DONE | `SQL/V4__platform_grants.sql` (constraint `idn_grant_history_scope_entity`) | `IJT/PlatformGrantsTest.java` `storeAndHistoryWithScopePlatform`; `IJT/GrantHistoryTest.java` `aVersion2DatabaseIsRefusedUntilV3AndV4AreRunAndKeepsItsData` | |
| setup-108 | Setup plans, applies, removes (only with the flag) and records platform grants like entity grants | DONE | `S/SetupRun.java` `planPlatformGrants`, `apply` (`changePlatform`) | `ST/SetupIdentityChangesTest.java` `starRowsLoadAreRefusedForTheWrongRoleKindAndAreRemovedOnlyWithTheFlag` | |
| setup-109 | `PlatformGroupAuthorization` gives platform roles from `PlatformGroupGrants` (ACTIVE connection, product) | DONE | `IC/admission/PlatformGroupAuthorization.java`; `IJ/PostgresIdentityLookup.java` | `IJT/PlatformGrantsTest.java` `platformRolesFromNoGrantOneGrantAndAConnectionThatIsNotActive` | |
| setup-110 | `EntityGroupAuthorization` keeps refusing platform roles through entity grants | DONE | `IC/admission/EntityGroupAuthorization.java` | `IJT/PlatformGrantsTest.java` `entityGrantsStillRefusePlatformRoles`; `ICT/admission/EntityGroupAuthorizationTest.java` `tenantGroupCannotGrantPlatformOperations` | |

## setup.md — decisions in the reports (DOC GAP) and a later decision the code does not follow

Report decisions checked: P013 (all in setup.md §4), P014, P019 (decision 7), P020, P028, P029, P030 (decision 3),
P031. P001–P012 and P015–P017 have no setup or identity decisions (P012 is the proposal that setup.md §3 points to;
its sections 5.1–5.6 were not ticked rule by rule). For each DOC GAP the Note says whether code and a test follow it.

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| setup-111 | P019 decision 7: the flag is `--no-customer-keys`, not `--no-keys` | DONE (P037, 77e6652) | `S/SetupRefusal.java` `SETUP_KEY_SERVICE_SETTINGS_MISSING` action text, its `.fix` in `SetupRefusal.codes.properties`, and the catalogue all say "run with `--no-keys`"; `--no-keys` is refused as SETUP_BAD_OPTION | `ST/SetupCommandTest.java` `theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted` checks only the code | Someone who follows the "What to do" line gets a second refusal |
| setup-112 | P014-1: `--keys` and `--product-roles` are required options | DOC GAP | `S/SetupCommand.java` `REQUIRED` | no test for a missing `--keys` | Not in setup.md (only the wrapper answer in §5) |
| setup-113 | P014-2: database settings only from `PLATFORM_SETUP_DB_*`; no `--db` option; database errors by SQL state only | DOC GAP | `S/SetupCommand.java` `dataSource`, `database` | `ST/SetupCommandTest.java` `noSecretInAnyOutput`, `wrongOptionsAndHubModeAreRefused`, `missingDatabaseSettingsAreNamed` | Code and tests follow it |
| setup-114 | P014-4: items that need a flag do not block the rest; `apply` writes the rest and exits 3 | DOC GAP | `S/SetupCommand.java` `database` | `ST/SetupCommandTest.java` `thePlanShowsRemovalsFirstAndGrantsAreRemovedOnlyWithTheFlag` ("Setup done except 1 item") | Code and tests follow it |
| setup-115 | P014-5: "not in the file" = every grant of the customer for the product and the sign-in file's issuer, including slots the file does not name; grants under an older issuer untouched | DOC GAP | `S/SetupRun.java` `planGrants` (`grantsOfCustomer`) | — | Code follows it; no test removes a grant of a slot the file does not name, none for an older issuer |
| setup-116 | P014-6: slots stored = licence in force plus a waiting renewal; activation still only for the licence in force | DOC GAP | `S/SetupRun.java` `planLicence` (returns current + renewal) | — | Code follows it; no test stores an early renewal |
| setup-117 | P014-7: "changed in the app" = differs and the last history row is not SETUP; equal values are `unchanged`; a connection the app DISABLED is a conflict too | DOC GAP | `S/SetupRun.java` `planConnection`, `latestConnection` | `ST/SetupCommandTest.java` `aConnectionChangedInTheAppIsAConflictUntilReplaceConnectionIsGiven`; `ST/SetupIdentityChangesTest.java` `setupLeavesTheAppsDraftsAloneAndStillSeesAConnectionChangedInTheApp` | The DISABLED-by-the-app case has no test |
| setup-118 | P014-9: the licensing stores join setup's transaction; a failure rolls the licence back too | DOC GAP | `S/Connections.java` `joined` | `ST/SetupCommandTest.java` `aRerunAfterAMidRunFailureFinishesWhatWasMissing` | Code and test follow it |
| setup-119 | P014-10: config fragment written in one step after the commit; another customer's file refused before any write; a file without the key gets the line added | DOC GAP | `S/ConfigFragment.java` | `ST/SetupCommandTest.java` `aDatabaseOfAnotherCustomerOrAConfigOfAnotherCustomerIsRefused`, `aRerunAfterAMidRunFailureFinishesWhatWasMissing` | The "add the key to an existing file" (`NO_KEY`) path has no test |
| setup-120 | P014-11: format names `platform-role-mapping/1`, `platform-product-roles/1`; a role in the wrong case refused with its spelling | DOC GAP | `S/SetupRun.java` `MAPPING_FORMAT`; `S/ProductRoles.java` `FORMAT` | `ICT/mapping/RoleMappingFileTest.java` `anotherFormatIsRefused`, `rolesMustBeKnownCanonicalAndNeverThePlatformRole` | Code and tests follow it |
| setup-121 | P014-12: sign-in issuer https without query or user info; any unknown field refuses the file, naming the field, never the value | DOC GAP | `S/SigninFile.java` `parse`, `issuer` | `ST/SetupCommandTest.java` `noSecretInAnyOutput` (`[clientSecret]`) | http exception since P031 (setup-87) |
| setup-122 | P014-15: the demo licence is perpetual and NON_PRODUCTION; the development private key is kept nowhere | DOC GAP | `platform-setup/demo/licence-demo.json`; `ST/DemoFilesGenerator.java` | — (generator runs only when asked) | Files follow it |
| setup-123 | P014-16: no run log when the first word or the options cannot be parsed | DOC GAP | `S/SetupCommand.java` `run` (`runLog` stays null) | — | Code follows it; no test |
| setup-124 | P019-7: customer keys are a separate step, not a plan item ("Customer keys:" output, `keys=` in the run log, skipped with `--no-customer-keys`); keys created just before the database commit; a key-service problem refuses before any database change | DOC GAP | `S/SetupKeys.java`; `S/SetupCommand.java` `database`, `keysLine` | `ST/SetupCommandTest.java` `applyCreatesTheCustomersKeysAndARerunLeavesThem`, `aKeyServiceProblemRefusesTheRunBeforeTheDatabaseChanges`; `ST/SetupIdentityChangesTest.java` `aCustomerStoredWithoutKeysGetsBothFromTheNextRunAndNothingElseChanges` | setup.md describes only the guard (§7), not the step |
| setup-125 | P020-3: two customer zones (identity `idn_customer.time_zone`, notifications' tenant zone); the app passes the entity's own zone | DOC GAP | `IC/store/EntityTimeZone.java` `ownZone` | `IJT/EntityTimeZonesTest.java` `anEntityWithoutItsOwnZoneFollowsTheCustomers` | Only in the identity install guide §5 |
| setup-126 | P020-5: valid zone = a zone Java knows (`ZoneId.getAvailableZoneIds()`), offsets refused; a stored unknown name reads as none | DOC GAP | `IC/store/EntityTimeZones.java` `isZoneName`, `parse` | `IJT/EntityTimeZonesTest.java` `invalidZoneNamesAreRefusedWithTheirCode` | "Stored unknown name reads as none" not tested |
| setup-127 | P028-7: shared issuer across customers: a client bound under two matching customers is `IDN_PUSH_NOT_BOUND`, never guessed | DOC GAP | `IC/push/PushCaller.java` `choose` | — | Code follows it; no test (single-customer worlds only) |
| setup-128 | P028-11: platform grants from more than one matching customer → refused (null), never merged | DOC GAP | `IJ/PostgresIdentityLookup.java` (platform grants, `byTenant.size() > 1`) | — | Code follows it; no test |
| setup-129 | P028-12: `*` rows only in setup files (product + slot reader); other readers see `*` as no entity ID | DOC GAP | `IC/mapping/RoleMappingFile.java` | `ICT/mapping/PlatformWideRowsTest.java` `filesReadWithoutAProductOrSlotsDoNotTakeStarRows` | Code and test follow it |
| setup-130 | P029-7: which binding checks run when (preflight: format, shapes, sources, duplicates, secrets; plan: slots against the licences, clients bound elsewhere), same code and line numbers | DOC GAP | `S/SetupInputs.java` `load`; `S/SetupRun.java` `planClientBindings` | `ST/ClientBindingsSetupTest.java` `fileProblemsRefuseTheRunWithLineNumbersAndWriteNothing`, `aClientBoundToAnotherEntityOrSourceIsRefused` | Code and tests follow it |
| setup-131 | P030-3: integration profiles in the sign-in file (`integrationProfiles` name / file / tag; tag present and ending with the profile's version; name equal; `SETUP_PROFILE_TAG_MISSING` / `SETUP_PROFILE_INVALID`; plan `checked`; stored nowhere) | DOC GAP | `S/SigninFile.java` `profiles`; `S/SetupInputs.java` `checkProfile` | `ST/ProfileSetupTest.java` `aValidProfileIsCheckedAndStoredNowhere`, `refusalsNameTheFileAndThePlace`, `theNameAndTheVersionMustMatchTheEntry` | setup.md never mentions profiles |
| setup-132 | P031-3: `discardDraft`: the draft becomes DISABLED, stays in the history, never a rollback target | DOC GAP | `IC/store/LoginConnections.java` `discardDraft` | `ITKT/InMemoryLoginConnectionsTest.java` `atMostOneDraftAndVersionsAreChecked` | In-memory only; no Postgres test of discard |
| setup-133 | P031-5: rollback target = newest ACTIVE history row whose values differ from the current ACTIVE's; two rollbacks swap | DOC GAP | `LoginConnections.rollback` | `ITKT/InMemoryLoginConnectionsTest.java` `draftTestActivateThenAChangeAndRollback` | Code and test follow it |
| setup-134 | P031-7: run-log field order: `run=` after `code=`, before `keys=` | DOC GAP | `S/SetupCommand.java` `writeRunLog` | — | Code follows it; no test pins the order |

## Install guide check: docs/install/setup.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-setup-1: §5 list of refusal codes | code | yes | The §5 list leaves out `SETUP_TIME_ZONE_INVALID` and `SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE` (named only in §2) |
| install-setup-2: `--no-customer-keys` for an installation without sensitive values (§1) | option | yes | The guide is right, but the code's "What to do" line for `SETUP_KEY_SERVICE_SETTINGS_MISSING` says `--no-keys` (setup-111) |
| install-setup-3: §2 "Any field not shown above refuses the file" | format | yes | The §2 example does not show `clientBindingsFile`, `sources`, `integrationProfiles`, which are accepted (and described further down) |
| install-setup-4: §3b "Setup checks each file before anything else" | format | partly | Profiles are checked after the licence, sign-in, product-roles, mapping and client-binding files (`S/SetupInputs.java` `load`), still before the database |
| xc-codes-setup-1: catalogue texts of `SETUP_DB_SETTINGS_MISSING` and `SETUP_KEY_SERVICE_SETTINGS_MISSING` | code | broken | `SetupRefusal.codes.properties` (and so `docs/errors/platform-error-codes.md`) read "Set , and in the environment …" and "Set and one login (, , or with ) …": the variable names are missing. The enum's own action texts have them |

All other items match: about 110 checked (the database and key-service variables and their defaults `approle`,
`transit`, `customer-`; every option and the defaults `platform-tenant.properties` and `platform-setup-runs.log`; the
sign-in fields; mapping-file comments and header; the client-binding header and its 13 codes; the 8 plan actions and
their output lines; the `Customer keys:` line and `vault-transit`; key names `customer-<hex>` / `-fp`; exit codes
0–3; the other refusal codes; the run-log fields and `keys=` values; the advisory lock; the 30 s cache; the demo
files and `demo/README.md`).

## Install guide check: docs/install/identity.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-identity-1: §6 list of connection refusals | code | yes | `ConnectionOutcome.Result.IDN_TEST_FAILED` (returned when a test sign-in fails) is not in the list; the `IDN_TEST_*` causes are |

All other items match: 64 checked (the 10 tables and the V1–V4 file names, history table, the runtime rights, the
7 `IDN_*` schema codes, the entity status values, the activation codes and the 30-day grace rule, `EntityTimeZones`
methods and codes, `EntityTimeZone::ownZone`, `Notifier.Builder.entityZones`, the 9 `IDN_TEST_*` codes and the
`HTTP_<status>` reason, the 10 connection codes listed, the local test issuer rule, the 6 `platform.identity.push.*`
properties with their defaults and the 0–10 minute range, the 9 `IDN_PUSH_*` codes, the entity claim accepting slot ID
or UUID, no header read by the platform).

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| setup-98 | A certificate caller needs the customer's ACTIVE connection | No test | B | Add a certificate case with a DISABLED connection to `PushCallerTest` (memory and Postgres) | S |
| setup-111 | Flag is `--no-customer-keys` | The fix text says `--no-keys`, which is refused | B · **FIXED in P037 (77e6652)** | Correct the `SETUP_KEY_SERVICE_SETTINGS_MISSING` action and `.fix`, regenerate the catalogue, assert the text in `theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted` | S |
| xc-codes-setup-1 | Catalogue texts of two setup codes | Variable names missing in `.fix` | B · **FIXED in P037 (77e6652)** | Write the variable names into `SetupRefusal.codes.properties`, regenerate, and have the catalogue check refuse empty `()` / `, ,` gaps | S |
| setup-115 | Grants of slots the file does not name count as "not in the file"; older issuer untouched | Not in setup.md; no test | B | State it in setup.md §5; add a setup test that drops every row of one slot and one for a grant under another issuer | S |
| setup-116 | Slots of a waiting renewal are stored at once | Not in setup.md; no test | B | State it in setup.md §5; add a setup test with a renewal that starts later | S |
| setup-117 | An app-DISABLED connection is a conflict | Not in setup.md; no test for the DISABLED case | B | State the "changed in the app" rule in setup.md §5; add the DISABLED case to `SetupIdentityChangesTest` | S |
| setup-1 | No "tenant" in user-facing text | No test; two texts say tenant | C | Reword the two texts; add the word to a text check over the setup and identity code files | S |
| setup-19 | Hub mode | Deferred by design | C | None now; hub-mode task after the proof of concept | L |
| setup-28 | Organization claim in the lookup key | Deferred by design | C | None now; hub-mode task | M |
| setup-48 | Grants under an older issuer listed as "not in the file" | Deferred by owner answer | C | None now; hub-mode task | S |
| setup-65 | Client IDs compared case-sensitive | No test | C | Add a two-client-IDs-differing-in-case line to `ClientBindingsFileTest` and a `PushCallerTest` lookup | S |
| setup-81 | Admission interfaces equal to the earlier library | No compatibility test | C | None needed beyond the release notes; optionally a signature test of the three types | S |
| setup-100 | Push refusals neutral to the caller | No test | C | Test `PushCallerResult.NEUTRAL` holds no code or cause; say in setup.md §8 that the app sends it | S |
| setup-112 | `--keys`, `--product-roles` required | Not in setup.md; no test | C | State in setup.md §5; add a missing-option case to `wrongOptionsAndHubModeAreRefused` | S |
| setup-113 | DB settings from the environment only, SQL state only | Not in setup.md | C | State in setup.md §5 | S |
| setup-114 | Flagged items do not block the rest | Not in setup.md | C | State in setup.md §5 | S |
| setup-118 | Licensing stores join setup's transaction | Not in setup.md | C | State in setup.md §5 | S |
| setup-119 | Config fragment rules | Not in setup.md; `NO_KEY` path untested | C | State in setup.md §5; add a test with an existing fragment without the key | S |
| setup-120 | Format names and canonical role spelling | Not in setup.md | C | State in setup.md §5 | S |
| setup-121 | Sign-in issuer and unknown-field rule | Not in setup.md | C | State in setup.md §5 | S |
| setup-122 | Demo licence perpetual NON_PRODUCTION, key kept nowhere | Not in setup.md | C | State in setup.md §2 item 8 | S |
| setup-123 | No run log for unparseable options | Not in setup.md; no test | C | State in setup.md §5; assert no log file in `wrongOptionsAndHubModeAreRefused` | S |
| setup-124 | The customer-keys step | Not in setup.md | C | Add a "customer keys" paragraph to setup.md (step, output line, `keys=`, order before commit, refusal before writes) | S |
| setup-125 | Two customer zones | Not in setup.md | C | Add one line to setup.md "Time zones" | S |
| setup-126 | Valid zone names; unknown stored name reads as none | Not in setup.md; last part untested | C | State in setup.md "Time zones"; add a read test with an unknown stored name | S |
| setup-127 | Client bound under two matching customers refused | Not in setup.md; no test | C | State in setup.md §8; add a two-customer case to `PushCallerTest` | S |
| setup-128 | Platform grants from two customers refused | Not in setup.md; no test | C | State in setup.md §8; add a two-customer case to `PlatformGrantsTest` | S |
| setup-129 | `*` rows only in setup files | Not in setup.md | C | State in setup.md §8 | S |
| setup-130 | When the binding checks run | Not in setup.md | C | State in setup.md 6.1 | S |
| setup-131 | Integration profiles in the sign-in file | Not in setup.md | C | Add a "profiles" paragraph to setup.md (fields, tag rule, codes, stored nowhere) | S |
| setup-132 | `discardDraft` | Not in setup.md; no Postgres test | C | State in setup.md §7; add discard to `LoginConnectionDraftsTest` | S |
| setup-133 | Rollback target rule | Not in setup.md | C | State in setup.md §7 | S |
| setup-134 | Run-log field order | Not in setup.md; no test | C | State in setup.md; assert the order in one setup test | S |
| install-setup-1 | Refusal code list | Two time-zone codes missing from §5 | C | Add them to the §5 list | S |
| install-setup-3 | Sign-in fields | §2 says unshown fields refuse, but three optional fields are accepted | C | Show the optional fields in the §2 example or reword the sentence | S |
| install-setup-4 | Profile check order | "before anything else" is not exact | C | Reword to "before the database is touched" | S |
| install-identity-1 | Connection refusals | `IDN_TEST_FAILED` not listed | C | Add it to the §6 list | S |
| xc-javadoc-setup-1 | `ProductRoles` class comment | Says platform roles "are never granted through a mapping file"; since P028 they are, on `*` rows | C | Correct the comment | S |

Gap totals: A 0 · B 6 · C 32 (38 rows).
