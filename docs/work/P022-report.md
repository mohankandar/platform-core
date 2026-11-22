# P022 report — P021 follow-ups, then the integration module investigation (read-only proposal)

Date: 2026-11-22
Branch: feature/p022-followups (part A; merged into main), then main (this report)
Outcome: DONE. Part A is built, tested, merged and pushed. Part B is the proposal below; nothing was changed in
LexaVault or Vero.

## Result (tests per module, merge and push results)

- `mvn clean verify`, then `mvn install`: **812 tests, 0 failures**: the 806 existing tests, all passing, plus 6
  new ones.

| Module | Tests |
|---|---|
| platform-identity-core | 86 |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 132 |
| platform-identity-testkit | 6 |
| platform-notifications-spring | 41 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 29 |
| platform-crypto | 20 (was 18) |
| platform-crypto-testkit | 16 (was 13) |
| platform-setup | 20 |
| platform-fields | 91 |
| platform-fields-testkit | 18 |
| platform-crypto-spring | 5 |
| platform-erase-log | 16 |
| platform-erase-log-testkit | 9 (was 8) |
| platform-erase-log-spring | 5 |

- **Merge:** fast-forward, `main` 22519d4 → 38803e8, pushed.
- **Branch:** `feature/p022-followups` was deleted locally. It was never pushed, so there was nothing to delete on
  origin.
- **`feature/case-fields`:** still on origin (cde6be6), so the local branch stays, as the task says.
- **This report:** the next commit on main, pushed.

# Part A — P021 follow-ups

## What was built

### A1. Erase only through the log

- `FieldValues.erase(ref)` now checks its caller with `StackWalker`. The only caller it accepts is the erase log's
  built-in handler, `com.documentplatform.eraselog.fields.CaseFieldsEraseHandler` (the constant
  `FieldValues.ERASE_LOG_HANDLER`).
- Any other caller gets the new code `VALUES_ERASE_OUTSIDE_LOG`, before anything is read or erased. The message says
  to use `Eraser`, kind `case-fields`.
- Package-private was not possible: the handler is in another module and another package
  (`com.documentplatform.eraselog.fields`), and the repo has no Java modules (`module-info`). So this is the
  "equivalent guard" the task allows. It stops mistakes, not code that deliberately copies the handler's class name.
- The erase itself moved to the package-private `eraseRecord`, unchanged.
- platform-fields' own tests reach it through a test-only helper, `com.documentplatform.fields.values.TestErase`.
  It is in the test sources of `platform-fields` and `platform-fields-testkit`, never in a jar.
- Docs: `docs/design/case-fields.md` and `docs/design/erase-log.md` ("Owner answers to P021", "Built-in handler");
  the CLAUDE.md row for platform-fields.

### A2. Separate offboarding policy

- **`platform-crypto-setup`** now has `create`, `update` and `read` on `transit/keys/customer-*`, and no longer
  `delete`.
- **New `platform-crypto-offboard`:** `delete` only, on the same paths. It is given only to the offboarding run.
- **The offboarding login** has both policies. Destroying a key needs `read` (the versions), `update`
  (`config deletion_allowed`) and `delete`. That is why the offboard policy is "delete only" and goes *with* the
  set-up policy.
- **`VaultKeyService.destroyCustomerKeys` asks first.** Before it changes a key, it asks Vault what this login may do
  on that key (`sys/capabilities-self`, which Vault's default policy allows).
  - A login without `delete` is refused with `CRYPTO_PERMISSION_DENIED`, and the message names the offboard policy.
    That covers the set-up policy alone and the runtime policy.
  - Nothing is changed: the key is not even marked `deletion_allowed`.
  - Without this check, a set-up-only login would have set `deletion_allowed` and only then been refused the
    `DELETE`.
- `VaultJson.write` now also writes lists of strings, which the check needed.
- **Docs:**
  - `docs/design/crypto.md` sections 7 and 11 (the policy texts and "Who may").
  - `docs/install/crypto.md` sections 1 and 5 (both policies as HCL, and the change an older installation makes).
  - `docs/install/setup.md` (never give setup the offboard policy).
  - The test policy files `crypto-setup.hcl` and `crypto-offboard.hcl` (new).
- The command's environment variables are unchanged (`PLATFORM_SETUP_VAULT_*`). Only the login put into them for the
  offboarding run differs.

### A3. A failing logged entry blocks later erasures

Kept and recorded in `docs/design/erase-log.md`, "Owner answers to P021", item 3:

- Erasures are applied in order.
- A logged entry that keeps failing stops later erasures with its code.
- The app's admin screen shows the pending entry (number, kind, record type and ID) and its handler code, from the
  `eraseLog` health details or `EraseGuard`'s state. No value is shown.

No code change.

### A4. Key-service backups and the two dates

- **`docs/install/crypto.md` section 6 (new):** Vault snapshot retention must be at most the backup retention (35
  days by default). If snapshots must be kept longer, give that number to the command.
- **`crypto destroy-customer-keys --backup-retention-days N`:** 30 to 90, default 35, the same range and default as
  the erase log. Outside the range it is refused with `CRYPTO_COMMAND_USAGE` before the key service is called.
- **The certificate data (`platform-key-destruction/1`) gains three fields:**
  - `liveDataDestroyedOn`: the UTC day of `destroyedAt`.
  - `backupRetentionDays`.
  - `unreadableEverywhereFrom`: `liveDataDestroyedOn` + the retention.
- The command also prints both dates.
- The fields were added before `operator`; nothing was removed or renamed.

## Tests asked for → where

| Asked | Where |
|---|---|
| A1 nothing can erase outside the log | `CaseFieldsEraseHandlerTest.caseFieldsAreErasedOnlyThroughTheEraseLog`: a direct call and a call through an app-side method reference are both refused with `VALUES_ERASE_OUTSIDE_LOG` and nothing is erased; the same record through `Eraser` is erased and logged. The existing erase tests (`PostgresValueStoreTest`, `FieldValuesInMemoryTest`) still pass through `TestErase`. The replay tests (`RestoreAndReplayTest`, `EraseLogInMemoryTest`) pass through the handler. |
| A2 the setup login can't destroy | `KeyDestructionVaultTest.theSetupLoginAloneCannotDestroyAndChangesNothing` (real Vault: `CRYPTO_PERMISSION_DENIED` naming the offboard policy; the value still opens; `deletion_allowed` still false); `.theCommandWithTheSetupLoginAloneFailsAndDestroysNothing` (exit 1, no result file, keys still there) |
| A2 the offboard login can | `KeyDestructionVaultTest.destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable` and `.theCommandNeedsTheTenantIdTypedBackAndWritesAResultWithoutKeyMaterial`, now with the set-up + offboard login; the end of `.theSetupLoginAloneCannotDestroyAndChangesNothing` |
| A2 runtime still can't | `.theRuntimePolicyCannotDestroyKeys`, `.theCommandWithTheRuntimeLoginFailsAndDestroysNothing` (unchanged) |
| A4 the second date | `KeyDestructionInMemoryTest.theSecondDateIsTheDestructionDayPlusTheBackupRetention`: just before midnight UTC; 30, 35 and 90 days; a leap year. `.theCertificateDataCarriesBothDatesWithTheDefaultOrTheGivenRetention`: in the file and printed, default and 60. `.aBackupRetentionOutsideThirtyToNinetyDaysIsRefusedBeforeTheKeyServiceIsCalled`: 29, 91, 0, -35, 35.5, abc. |
| 806 existing still pass | 812 run, 0 failures. No existing assertion was changed (see decisions 2 and 5). |

## Files changed, commits

- 3cc9b91 docs: P022 task (committed as it was).
- 38803e8 feat(fields,crypto), part A:
  - `FieldValues`, `FieldValuesError`, `TestErase` (test sources, two modules), and the erase tests in
    platform-fields.
  - `CaseFieldsEraseHandlerTest`.
  - `CryptoCommand`, `VaultKeyService`, `VaultJson`, `VaultDev`, the policies `crypto-setup.hcl` and
    `crypto-offboard.hcl` (new), `KeyDestructionVaultTest`, `KeyDestructionInMemoryTest`.
  - Docs: `docs/design/crypto.md`, `erase-log.md`, `case-fields.md`; `docs/install/crypto.md`, `setup.md`.
  - The CLAUDE.md rows for platform-fields and platform-crypto.
- This report (on main).

## Decisions I had to make (part A)

1. **A1 guard by caller class, not package-private.** Explained above. The other options were worse:
   - a split package in production jars, which any app can copy;
   - moving the handler into platform-fields, which renames packages (CLAUDE.md: renames are their own task) and
     turns the dependency around.
2. **Existing erase tests in platform-fields** call the erase through the test-only `TestErase` (`values.erase(…)`
   became `TestErase.erase(values, …)`, 13 lines). No assertion changed.
3. **A2 "delete only" goes with the set-up policy.** A login with `delete` alone cannot read the key's versions or set
   `deletion_allowed`. So the offboarding run gets both policies, and the set-up run never gets the offboard one.
4. **The pre-check on `sys/capabilities-self`.** If Vault cannot answer it (for example a login without the default
   policy), the old steps run, and Vault itself refuses the step the login may not do.
5. **A2 existing Vault tests:** the two tests that destroy keys now use the set-up + offboard login (a test set-up
   change; their assertions are the same). `VaultDev` loads the third policy.
6. **A4 dates in UTC**, the date of `destroyedAt`. Retention comes from the command option, not from the erase log
   settings, because the command has no app configuration. The format name stays `/1`, since fields were only added.

# Part B — Integration module investigation (proposal)

Read-only. Nothing was changed in LexaVault (`C:\dev\lexavault\secure-document-platform`, branch
`feature/0036-export-p1-fixes`, HEAD 17b178a, with someone else's uncommitted work left alone) or in Vero
(`C:\dev\sdv\secure-document-vault`, checked out on `docs/ingestion-contract`).

- `docs/ingestion-contract` = `release/vero-r7` (f4c5c6e) plus d881d93, which adds only `docs/work/0040-task.md`.
- So everything under `docs/api` and in the backend is R7 as released.
- Vero's uncommitted work (trusted-proxy CIDRs, a limit on unidentified attempts) touches no ingestion endpoint.

Paths below:

- LexaVault: `INF` = `backend/securedoc-infrastructure/src/main/java/com/securedoc/platform/infrastructure`, `APP` =
  `backend/securedoc-application/.../application`, `API` = `backend/securedoc-api/.../api`, `DOM` =
  `backend/securedoc-domain/.../domain`.
- Vero: paths are under `backend/` unless they start with `docs/`.

## B1. What LexaVault and Vero do today

### LexaVault intake

| Channel | Today |
|---|---|
| **Push API** | `POST /api/v1/external-intakes`, then multipart `POST /{packageId}/documents/{externalSourceDocumentId}` with `Idempotency-Key` and `X-Content-SHA256` (201, or 200 for a duplicate), and `GET /{packageId}` (`API/intake/external/ExternalIntakeController.java:32-64`). Sign-in: the verified JWT gives a username; shared-issuer mode finds the tenant through `machineTenantBindings.findEnabledPushTenants(provider, issuer, audience, username)` (`API/context/RequestContextResolver.java:196-216`; `INF/identity/JdbcActiveOidcMachineTenantBindings.java:23`), then `IntegrationConnectionService.resolveEnabledPushSource` requires an enabled `PUSH_API` connection whose `client_principal` is that username, else `LV-AUT-008` (`APP/integration/IntegrationConnectionService.java:151-158`). Spec: `docs/api/EXTERNAL_INGESTION_V1.md`. |
| **Polling** | A fixed contract that LexaVault defines, `/lexavault/v1/pickups` (`INF/intake/polling/HttpPollingSourceAdapter.java:28`): `GET ?limit=N` (at most 1000, :45), then per document `GET …/{pkg}/revisions/{rev}/documents/{doc}` with a size cap (:60-82), then `POST …/acknowledgement` (:99-106). **No paging**: one `{items:[…]}` read (:50-51, 176). Sign-in: none, Basic, `X-API-Key` or a static bearer (:128-130); OAuth2 and mTLS are refused (`DOM/integration/IntegrationConnection.java:266-269`). Schedule: interval or daily, with a batch limit (`API/integration/PollingScheduler.java`). No test of the HTTP adapter itself. |
| **Watched folder** | One deployment-wide root and one tenant (`securedoc.intake.watched-folder.*`; `API/platform/WatchedFolderScheduler.java:77-79, 229`). One subfolder per drop. A fixed JSON trigger `manifest.json` (`INF/intake/WatchedFolderProperties.java:9`) with `businessIdentifiers`, `metadata` and optional `files[]` (`INF/intake/LocalWatchedFolderSourceAdapter.java:166-182`). Refusals `MISSING_FILE` / `NO_DOCUMENTS` / `UNSUPPORTED_FILE` / `MALFORMED_TRIGGER` (:186-221; `LV-INT-030..033`). A claim file with a fencing token; `_processing` / `_archive` / `_problem` folders. **No per-customer trigger format** (XML, `.lck`): deferred in `docs/work/0021-task.md:30-33`. The trigger-file requirement (`Watched-Folder-Trigger-File.md`) is referenced but is not in the repo. |
| **Scan Bridge** | A separate service. JSON create on `/api/v1/scanner-intakes`, then a PDF upload with `Idempotency-Key <submissionId>:<sourceDocumentId>` and `X-Content-SHA256` (`backend/securedoc-scan-bridge/.../LexaVaultScannerDeliveryClient.java:88-111`). Sign-in: a static bearer token from the environment (:217-227). The server matches a `SCANNER` connection's client principal (`IntegrationConnectionService.java:160`). |
| **Virus scan** | None. Intake checks only that a PDF is readable and has no password (`INF/intake/PdfBoxIntakeValidationAdapter.java:36-39`). |

### LexaVault export (tasks 0027–0030)

- **Tables:**
  - `integration_connections` (changelog 014, polling columns in 016).
  - `delivery_submissions`, `delivery_submission_documents` and `delivery_attempts`, with the
    `connection_configuration_version` stored per attempt (017).
  - `routing_rules` and `derived_deliveries` (018); `pipeline_jobs` (043).
  - `export_destination_states`, `export_reachability_alerts` and `export_retention_periods` (044).
- **Connection fields** (`DOM/integration/IntegrationConnection.java:18-52`):
  - purpose, direction and provider type (PUSH_API, HTTP_API, FILE_SHARE, WATCHED_FOLDER, SCANNER);
  - authentication type (NONE, BASIC, API_KEY, JWT, OAUTH2_CLIENT_CREDENTIALS, MTLS), client principal and
    `secretReference`;
  - endpoint, file share root, timeout and `retryMaxAttempts`;
  - limits (documents, size, pages), schedule, `configurationVersion` and health fields.
- **Destinations:** one master destination per deployment (`securedoc.delivery.type` `sdv` or `local`), plus split
  destinations from routing rules (HTTP_API or FILE_SHARE).
- **Packaging today:**
  - Vero: two steps.
  - Split HTTP: one file per call, with the details in headers (`INF/routing/DerivedDestinationDeliveryAdapter.java:46-61`).
  - File share: one file per document.
  - Local folder: staged, then the package folder is moved into place, with **no manifest**
    (`INF/delivery/LocalFolderDeliveryAdapter.java:27-31, 96-112`).
- **No field mapping or templates:** `businessIdentifiers` is passed through as it is (`INF/delivery/SdvDeliveryAdapter.java:145`).
- **The job's own rules** (stay in the app):
  - retries `5m,15m,1h,4h,12h`, with one rebuild before the 3rd retry (`application.yml:160-170`);
  - pause the destination when its health check fails;
  - a 429 uses no attempt and lowers that destination's workers.
- **Classification:** `APP/pipeline/ExportJobService.java` `classify` (:653-670 in the working copy, :642 at HEAD):
  - DLV-001 and 002: temporary;
  - 009: done;
  - 003 and 004: "Waiting for you";
  - 005: credential;
  - 007 and 008: answer unclear;
  - anything else: "Needs IT".

### LexaVault's Vero sender (`INF/delivery/SdvDeliveryAdapter.java`)

1. **Health:** `GET {endpoint}/api/v1/health`. Any status other than 2xx fails (:86).
2. **Create:** `POST /api/v1/submissions` (:36, 153-157).
   - Headers: `Content-Type: application/json`, `Idempotency-Key: <sourceSubmissionId>`.
   - Body: `sourceSubmissionId`, `sourceRevision`, `businessIdentifiers`, `expectedDocumentCount`,
     `documents[{sourceDocumentId, fileName, contentType, sizeBytes, sha256}]`, `recordSensitivityLevel` (:133-150).
   - It reads `submissionId` from the answer; if that is missing, DLV-007 (:162-164).
3. **Upload, one per document:** `POST /api/v1/submissions/{id}/documents` with the raw file (:191-201).
   - Headers: `Content-Type: <item MIME>`, `Idempotency-Key: <sourceSubmissionId>:<sourceDocumentId>`,
     `X-Source-Document-Id`, `X-Original-File-Name`, `X-SDV-Sensitivity-Level`, `X-Content-SHA256`.
   - "Already received" is true when `idempotentReplay`, `duplicate` or `receiptAcknowledgement=DUPLICATE_ALREADY_STORED`
     is set (:177-185).
4. **Status:** `GET /api/v1/submissions/{id}`. Anything other than `COMPLETE` is DLV-008 (:119-122, 223).
5. **Sign-in:** Basic, `X-API-Key`, or a static `Bearer <secret>` (:244-257). OAuth2 client credentials and mTLS
   throw DLV-005 and are refused for SDV by the domain (`DOM/integration/IntegrationConnection.java:287-291`).
   There is no token fetch.
6. **Answers:**
   - a 4xx whose code starts with `SDV-SEN-` → DLV-003;
   - 401 or 403 → DLV-005;
   - any other 4xx → DLV-004;
   - 429 or 5xx → DLV-002, with Retry-After (seconds or an HTTP date, capped at 1 h; `RetryAfter.java:22-43`);
   - an I/O error → DLV-001 (:291);
   - no connection or a bad address → DLV-006 (:314-363).
   - The destination's own `code` is kept if it matches `[A-Za-z0-9_-]{1,40}`.
7. **Retries:** `inline` mode retries inside the adapter (1–3 s sleeps); `job` mode makes one try (:294-307).

### Vero R7 ingestion API (from `docs/api/sdv-ingestion-v1.openapi.json` 1.0.0 and the controllers)

- **Base path `/api/v1/ingestion`.** Two steps: create the submission (JSON), then upload each PDF on its own.
- **`POST /submissions`** (spec :14-43; `sdv-web/.../web/ingestion/SubmissionController.java:34-45`).
  - Body: `sourceSubmissionId` (1–200), `expectedDocumentCount` (≥ 1), optional `expectedDocuments[{sourceDocumentId}]`,
    `metadata` (map of scalar values, keys set up per source), optional `recordSensitivityLevel` (spec :173-183).
  - `sensitivityLevel` in the body → SEN-006 (controller :77-82); a value that is not scalar → INT-008 (:94-97).
  - Answers: 201 created; 200 exact replay with `idempotentReplay: true`.
- **`GET /submissions?sourceSubmissionId=`** and **`GET /submissions/{submissionId}`**: 200, or 404 INT-004.
- **`POST /submissions/{submissionId}/documents`** (`DocumentIntakeController.java:29-59`).
  - The body is raw `application/pdf`, **not multipart**.
  - Headers:
    - `X-SDV-Filename`: required, at most 500 characters, no paths.
    - `X-SDV-Source-Document-Id`: optional.
    - `Idempotency-Key`: optional.
    - `X-SDV-Sensitivity-Level`: optional, STANDARD, CONFIDENTIAL or HIGHLY_CONFIDENTIAL.
    - `X-SDV-Record-Sensitivity-Level`: always refused, SEN-005.
    - `X-Correlation-Id`: optional on every call, ≤ 128 characters of `[A-Za-z0-9._:-]`.
  - Answers: 201 stored; 200 replay or exact duplicate. The `DocumentReceipt` carries `receiptAcknowledgement`
    (RECEIVED or `DUPLICATE_ALREADY_STORED`), `contentSha256`, `contentSizeBytes`, `idempotentReplay` and `duplicate`.
- **Also:** `GET …/documents`, and `POST …/documents/{documentId}/retry-finalization` (404 or 409 INT-018).
- **Idempotency:**
  - **Submission:** keyed on `sourceSubmissionId` per tenant and source. A different body → 409 INT-011; only the
    record level different → 409 SEN-004 (`application/.../submission/SubmissionService.java:116-121, 147`).
  - **Document:** keyed on `X-SDV-Source-Document-Id`, else `Idempotency-Key`; one of the two is required (400
    INT-012). A different file or file name → 409 INT-017; a different level → 409 SEN-003
    (`DocumentIntakeService.java:154-188`).
  - **The same SHA-256** in a new delivery for the same record → 200 `DUPLICATE_ALREADY_STORED` (`docs/api/README.md:47-66`).
- **Errors:** the body is `{code, message, reference, timestamp}`, and `reference` = the `X-Correlation-Id` (spec
  :256-260). Codes are listed in `docs/api/error-codes.md`:
  - AUT-001/003/005/006/007 401; AUT-002 403; **AUT-004 503** (the login server's keys can't be reached); AUT-009 403;
  - INT-005 403, INT-014 413, INT-016 409/400;
  - SYS-001/002/014 and STO-001 503; SYS-015 400/404/405/406/415.
  - **There is no 429, no rate limit and no `Retry-After`** anywhere in R7. "Busy" is the set of 503s.
- **Limits:**
  - PDF size: the smaller of the tenant's and the source's limit (default 250 MiB).
  - Per submission: 100 documents and 2 GiB (`db/changelog/001-foundation-core.sql:9-12`). The declared
    `Content-Length` is checked first.
- **Readiness:** `GET /actuator/health/readiness` needs no sign-in (`sdv-security/.../config/SecurityConfiguration.java:48`).
  It answers 200 or 503, with no details. It turns 503 while the login server's keys can't be fetched.
- **Sign-in in R7:** a JWT bearer token (`SDV_AUTH_MODE=enterprise-jwt`).
  - Checked: issuer, time and a required audience; keys from a JWK set over https
    (`sdv-security/.../enterprise/EnterpriseJwtConfiguration.java:37-56, 163-180`).
  - **A sending system is recognised by custom claims `sdv_source_id` and `sdv_tenant_id` (UUIDs)**, together with
    `sub` (`EnterpriseJwtAuthenticationConverter.java:117-131, 187-191`). There is no client-ID lookup and no
    `MachineEntityBindings` in Vero.
  - The "tenant" is a Vero tenant UUID, not `ENT-…`. A person's token on ingestion → 403 AUT-002.
  - 0040 keeps these claims and calls the lookup a "Planned change" (`docs/work/0040-task.md:22`).
- **Contract status:**
  - The tag **`sdv-ingestion-v1.0.0` does not exist yet** (`git tag -l` has no ingestion tag).
  - 0040 is not merged; only its task file is committed.
  - There is no contract test, sender's guide or changelog in Vero yet.
- **Where the spec and the code differ:**
  - 403 is missing from the spec on every operation except create.
  - The spec says `additionalProperties:false`, but Jackson probably ignores unknown fields.
  - `metadata` is required in the spec; the code accepts null.
  - The claims are not described in the security scheme.

### Where LexaVault's sender differs from Vero R7

| # | LexaVault (file:line) | Vero R7 expects | Effect |
|---|---|---|---|
| 1 | Paths `/api/v1/submissions…` (`INF/delivery/SdvDeliveryAdapter.java:36, 191, 223`) | `/api/v1/ingestion/submissions…` | 404 `SDV-SYS-015` (seen live in 0031), which becomes DLV-004 "Waiting for you": a wrong address looks like a refusal |
| 2 | Health `GET /api/v1/health` (`:86`) | `GET /actuator/health/readiness` (no sign-in) | The health check fails, so the destination is paused |
| 3 | Basic, API key or a static bearer only; OAuth2 refused (`:244-257`; `DOM/integration/IntegrationConnection.java:287-291`) | A JWT from OAuth2 client credentials, with the **custom claims `sdv_tenant_id` + `sdv_source_id`** (R7) | 401 `SDV-AUT-001` (seen live in 0031) |
| 4 | `X-Original-File-Name` (`:198`) | `X-SDV-Filename`, required | 400 SYS-015 |
| 5 | `X-Source-Document-Id` (`:197`) | `X-SDV-Source-Document-Id` | Vero falls back to `Idempotency-Key` (LexaVault sends `<sub>:<doc>`), so replays still work, but the document's source ID is not recorded |
| 6 | Create body: `businessIdentifiers`, `documents[]`, `sourceRevision` (`:133-150`) | `metadata` (scalars), `expectedDocuments[{sourceDocumentId}]`, `additionalProperties:false` | `metadata` is missing; the extra fields are probably ignored (Jackson default), but the spec allows a 400 |
| 7 | `Idempotency-Key` on create (`:156`) | Idempotency comes from `sourceSubmissionId`; there is no header on create | Harmless; not needed |
| 8 | `X-Content-SHA256` sent (`:200`); `contentSha256` in the receipt is never compared | Not in the spec; the receipt has `contentSha256` | No integrity check on delivery (requirement 14) |
| 9 | `Content-Type: <item MIME>` (`:195`) | `application/pdf` only | 415 for anything else |
| 10 | No `X-Correlation-Id` | Optional; echoed as `reference` | Support can't match LexaVault's attempts to Vero's errors |
| 11 | 429/5xx → DLV-002 with Retry-After (`:314-363`) | R7 never sends 429 or Retry-After; busy = 503 (AUT-004, SYS-014, STO-001, SYS-001/002) | It works, but AUT-004 (503) is a Vero-side sign-in fault, not "busy" |
| 12 | Status `GET …/{id}`, `COMPLETE` only (`:119-122, 223`) | Status OPEN / RECEIVING / COMPLETE / INCOMPLETE | Correct idea, but a wrong path (row 1) |
| 13 | The local profile fixes `delivery.type: local` (`application-local.yml:75-76`) | — | A local run never meets Vero |
| 14 | `staged-pipeline-design.md:58` "409 with duplicate code → DLV-009" | Vero's 409 means **same key, different content** (INT-011, INT-017, SEN-003/004); "already received" is a **200** | The code is right (`:170-176`); the design text is wrong |

## B2. Module layout (proposal)

| Module | Package | What |
|---|---|---|
| `platform-integration` | `com.documentplatform.integration` | Plain Java, no Spring. The core: configuration model and versioning, the call engine, sign-in bundle, safety, the one-attempt result, health and Test, packagings, field mapping, folder export, intake (polling, watched folder), virus scan hook, callback signing. Subpackages: `config`, `http`, `signin`, `safety`, `result`, `packaging`, `mapping`, `export.folder`, `intake.polling`, `intake.folder`, `scan`, `callback`, `jdbc` (config store, history, SQL `db/platform/integration`, history table `int_schema_history`, startup check `IntegrationSchema.verify`). |
| `platform-integration-testkit` | `com.documentplatform.integration.testkit` | The **mock destination server** (JDK `HttpServer`, switchable per test or by a small admin call), the mock folder or share (unwritable, slow, full), the mock source API (paging), and in-memory stores. Test scope only. |
| `platform-integration-spring` | `com.documentplatform.integration.spring` | `platform.integration.*` properties (proxy, private CA, address guard mode), beans, the startup schema check, a health indicator per destination. |
| `platform-integration-profiles` | `com.documentplatform.integration.profile` | Ready-made profiles as **data** (JSON in resources), not code: first `sdv-ingestion-v1` (Vero R7), with the **pinned spec copy** (`sdv-ingestion-v1.openapi.json` + `error-codes.md`, version and source commit noted) and the contract tests. A profile is a configuration the app loads and can override; a customer extension is the same shape. |

**Reused from platform-core:**

- **HTTP plumbing patterns** from `platform-notifications` `webhook/WebhookClient.java`:
  - redirects never followed (:59), and the Retry-After parser for seconds or an HTTP date (:148-165);
  - status → code mapping (:131-145) and the address kept out of results and logs.
  - These are copied into `integration.http` (with their own codes). Notifications is not depended on.
- **`SecretResolver` / `SecretRef`** (`com.documentplatform.notifications.secret`). Integration needs them without
  the notifications engine. See question 3.
- **OAuth2 client credentials:** generalise `MicrosoftTokens` (274 lines, token cache, client secret or certificate
  assertion, its failure codes) into a generic token client (`integration.signin.ClientCredentialsTokens`, any token
  address, scope or audience). Microsoft stays a preset of it. Notifications can move onto it later.
- **`ActionTokens`** (HMAC, expiring): the pattern for signing callbacks and checking signed inbound calls.
- **platform-fields:** field keys and types for mapping, and `delivery.FieldDelivery` (`platform-field-values/1`) for
  the case-field part of a package.
- **platform-crypto:** `ValueCipher` / `RecordCipher` to store uploaded mTLS client certificates and private keys
  encrypted, bound to customer and context; shown only as subject, fingerprint and expiry.
- **platform-identity:** `MachineEntityBindings` / `MachineClients` for the shared push-API sign-in check, and
  `platform-setup` for loading client-ID bindings (`docs/design/setup.md` section 6).
- **platform-notifications as an optional dependency** for events: certificate expiry, destination down, quarantined
  file.

**New:** everything in the "Missing" list of `docs/design/integration-module.md`.

## B3. The configuration model

- **Integration** (one row per configured integration):
  - id, entity (UUID) and product, direction (OUTBOUND or INBOUND), kind (API_PUSH, FOLDER_EXPORT, API_POLL,
    WATCHED_FOLDER);
  - profile (`sdv-ingestion-v1` or none) with overrides;
  - enabled, `configVersion`, and created / changed by whom and when.
- **Destination or source:** base address (https; http only on-prem when the "internal" switch is set), the sign-in
  bundle, limits and timeouts, proxy use, trust (system, or the private CA by reference).
- **Call steps** (outbound API): an ordered list from fixed building blocks, for example `create → upload-each →
  status`. Each step has:
  - a method and a path template;
  - header templates;
  - a body kind: JSON template, raw file, multipart or none;
  - a response mapping: JSON pointers to named values such as `submissionId`, the "already received" rule, and the
    path to the error code.
  - Steps pass values on by name, for example `{submissionId}` in the next path.
- **Templates:** placeholders only (`{field:contract_number}`, `{doc.sourceDocumentId}`, `{doc.fileName}`,
  `{doc.sha256}`, `{package.id}`, `{step.create.submissionId}`, `{correlationId}`). No expressions, loops or scripts.
  - Values are escaped by place: path segment, header or JSON.
  - A placeholder for a secret or document content is refused when the configuration is saved, and so is any
    placeholder in the query string or the address's host.
- **Field mapping:** a platform-fields key → an external name and type (`contract_number` → `metadata.loanId`).
  - It is checked when saved against the entity's field definitions: the key exists and the type fits.
  - Sensitive fields are sent only when the mapping marks them as allowed (written to the audit).
  - Missing required values → refused before sending, `INT_MAPPING_VALUE_MISSING`.
- **Packaging modes (V1):** `TWO_STEP` (create, then upload: Vero), `MULTIPART` (documents + JSON), `FILE_PER_CALL`
  (details in headers), and `FOLDER_MANIFEST` (files, then `manifest.json` last).
- **Sign-in bundle:** `NONE` (on-prem only, off by default, warned, audited, on the compliance report), `BASIC`,
  `API_KEY` (header name configurable, never in the query string), `BEARER_FIXED`, `OAUTH2_CLIENT_CREDENTIALS` (token
  address, client ID, secret ref or certificate ref, scope / audience), and `MTLS` (certificate ref; together with any
  of the others). HMAC for callbacks. Secrets are always `SecretRef` and resolved for each attempt.
- **Uploaded certificates:** stored encrypted with platform-crypto (`RecordCipher`, context
  `integration:<id>:cert:<n>`) in `int_credential`.
  - Shown only as subject, fingerprint and expiry.
  - Two may be active at once to allow overlap during rotation.
  - Expiry warnings at 30, 14 and 7 days (notification event).
  - Or a reference to a keystore on the server.
- **Limits and timeouts per integration:** connect and read timeouts, maximum file and package size, maximum
  documents, and maximum response size read (1 MiB for JSON).
- **Audit and versioning:**
  - Every change writes `int_integration_history` (version n, who, when, what changed by field name, secrets shown
    only as "reference changed") and raises `configVersion`.
  - Each attempt result carries the `configVersion` it used. LexaVault already stores this per attempt.
  - The app's audit gets one entry per change through an `IntegrationAudit` interface that the app supplies.

## B4. The one-attempt result

`AttemptResult`: an outcome, the platform code, the destination's own code (safe characters, ≤ 40), the HTTP status,
`retryAfter`, the correlation ID, the step reached, the values read (for example `submissionId`, `documentId`,
`contentSha256`) and `configVersion`. No content and no secret.

| Outcome | When | LexaVault code today → job behaviour |
|---|---|---|
| `SENT` | 2xx and the response mapping is satisfied; for Vero also `contentSha256` = ours (integrity, req. 14) | done |
| `ALREADY_RECEIVED` | the profile's rule: Vero 200 with `idempotentReplay` or `DUPLICATE_ALREADY_STORED` | DLV-009 → done |
| `REFUSED` (with code) | 4xx other than sign-in (400, 404 *on a step*, 409, 413, 415, 422); for Vero, codes `SDV-SEN-*` → **sensitivity** sub-kind | DLV-003 (SEN) / DLV-004 → "Waiting for you" |
| `SIGN_IN_FAILED` | 401, 403; the token address refused the credential; the certificate expired | DLV-005 → credential |
| `CANT_CONNECT` | DNS, refused connection, TLS failure, timeout, blocked by the address guard (its own code), proxy refused | DLV-001 → temporary (guard and TLS go to "Needs IT", not temporary) |
| `BUSY` | 429, 503, 502/504, with `retryAfter` if given | DLV-002 → temporary; a 429 uses no attempt |
| `UNCLEAR` | 2xx with an answer that doesn't map (no `submissionId`, a different `documentId`), status not complete | DLV-007/008 → answer unclear |
| `NOT_CONFIGURED` | the integration is off or invalid, the secret is missing | DLV-006 → "Needs IT" |

- The engine makes one try of the whole step sequence, or resumes from the step reached, using the values read. It
  never sleeps or retries.
- LexaVault keeps its schedule (5 min – 12 h, one rebuild, pausing on health). Its `classify` becomes a mapping from
  `Outcome` to its DLV codes.
- **Vero specifics in the profile:**
  - a 404 on the *address* (path unknown, SYS-015) is `NOT_CONFIGURED`, not a refusal;
  - a 404 INT-004 on a stored submission ID means "create again";
  - AUT-004 (503) is `BUSY` but flagged as a sign-in-side fault.

## B5. Safety

- **Address guard, checked on every attempt after DNS:**
  - Refused: cloud metadata addresses (169.254.169.254, fd00:ec2::254, metadata.google.internal, Azure 168.63.129.16,
    Alibaba 100.100.100.200), link-local, and `0.0.0.0` / `::`.
  - Allowed on-prem only (setting `platform.integration.allow-internal-addresses`, default false when hosted): loopback
    and private ranges.
  - The connection goes to the address that was checked, which closes the DNS-rebinding gap of LexaVault's
    `APP/integration/OutboundAddressGuard.java` (checked once, at draft test only).
  - Redirects are never followed.
- **Outbound proxy:** per installation (`platform.integration.proxy.*`: host, port, optional sign-in by secret ref,
  hosts that bypass it), through `ProxySelector`. A proxy refusal is `CANT_CONNECT` with its own code.
- **Private CA:** a trust bundle (PEM) per installation, or per integration by reference, added to the system trust,
  never replacing it. Host names are always checked. There is no "trust all" switch.
- **No secrets or content in URLs, templates or logs:**
  - Templates are checked when saved (B3).
  - Sign-in goes only in headers.
  - Logs and results hold integration ID, step, status and codes.
  - The destination's error message is never stored, only its code (as LexaVault 0030 does).
  - A test scans the logs for planted secrets, as in crypto.

## B6. Testing

- **Test action:** each integration runs its steps with fake data (a one-page PDF made in memory and fake field
  values from the mapping's types), against the destination's test entity, or as far as the profile allows (Vero: the
  readiness check plus the token fetch). The result is the same `AttemptResult`, and it is audited.
- **Contract tests:**
  - The Vero profile's requests are checked against the pinned `sdv-ingestion-v1.openapi.json`: paths, methods,
    required headers and their patterns, body schema, content type. The profile's response mapping is checked against
    the spec's response schemas and examples.
  - A small OpenAPI checker is written in the test sources, so no new runtime dependency is needed.
  - **The tag `sdv-ingestion-v1.0.0` does not exist yet** (Vero 0040 is not merged). Until then the copy is pinned to
    `release/vero-r7` f4c5c6e and noted so, then re-pinned to the tag.
- **Mock destination server** (testkit), built from the pinned spec: it refuses any request the spec doesn't allow,
  so it can't drift. Switches:
  - working, down, slow;
  - busy (429 + Retry-After; R7 itself never sends it, but other destinations do);
  - unavailable (503 SYS-014 / AUT-004);
  - refuses with a code (`SDV-SEN-006`, `SDV-INT-011`, …);
  - already received (200 replay, `DUPLICATE_ALREADY_STORED`);
  - sign-in refused (`SDV-AUT-*`);
  - health failing while uploads work, and the reverse.

  It records headers, fields and checksums, never document bytes. It also has a token endpoint for client credentials.
- **Mock folder or share:** a temp directory that can be made unwritable, slow or full.
- **Mock source API:** paging (cursor and page number), a slow page, a page that changes between calls, sign-in
  refused.
- **Live tests:** JUnit tag `live`, skipped unless `-Dlive=true` with the target's environment variables; they never
  run in `mvn verify`.

## B7. Inbound

- **API polling:**
  - Configured calls like outbound: a list step with paging by cursor, next link or page number; a download step; an
    optional acknowledge step.
  - Page size and maximum pages per run are limits.
  - Each item is handed to the app with a stable source ID; the app says "taken" or "already taken" (its idempotency),
    then the module acknowledges.
  - LexaVault's fixed `/lexavault/v1/pickups` contract becomes one profile.
- **Watched folder / share:**
  - One configuration per customer and entity: root, how a drop is found (subfolder or file pattern), and the trigger
    file.
  - The trigger is **last**: JSON, XML (fixed building blocks mapping elements to field keys), or `.lck` / `.done` with
    the same base name. Files are taken only once their trigger exists and sizes are stable.
  - Claim with a fencing token, then `_processing` / `_archive` / `_problem` (LexaVault's proven pattern, moved here).
  - Per-customer trigger formats are new. The requirement text (`Watched-Folder-Trigger-File.md`) is not in any repo
    and is needed for the design (question 6).
- **Virus scan hook (optional):**
  - An interface `FileScanner` with `CLEAN` / `INFECTED(name)` / `SCAN_FAILED`, adapters for ICAP and clamd, and off
    when no scanner is configured.
  - When on, every incoming file is scanned before the app sees it: infected → quarantine folder or store, never
    processed, and an alert event; scan failed → held and retried, never let through.
- **Push-API sign-in check (shared):** `integration.inbound.PushCaller`.
  - It takes the verified token (issuer, audience, `sub` / client ID) and finds exactly one entity and source through
    `MachineEntityBindings` (identity), as `docs/design/setup.md` section 6.
  - Entity or source claims in the token must match the binding, or the call is refused.
  - Each app's controller calls it.
  - The bindings come from the setup command's client-ID file (setup task, below).

## B8. Task split and order

| Task | What | Needs |
|---|---|---|
| **P023** | `platform-integration` core + `-testkit`: configuration model, store, history and audit; the call engine (steps, templates, response mapping) for API push; sign-in NONE / BASIC / API_KEY / BEARER_FIXED / OAUTH2_CLIENT_CREDENTIALS (generic token client); address guard, proxy, private CA, limits; `AttemptResult`; health per destination; Test action; the generic **mock destination server** and its token endpoint | — |
| **P024** | Packagings TWO_STEP / MULTIPART / FILE_PER_CALL, field mapping by platform-fields key, the integrity check; `platform-integration-profiles` with **`sdv-ingestion-v1`**, the pinned spec copy, contract tests, and the mock server driven by the spec with its Vero switches | P023; Vero 0040 tag (or pin f4c5c6e first); answer to question 1 |
| **P025** | `platform-integration-spring` (properties, beans, health); folder / share export with `manifest.json` last (common envelope + app section) and the mock folder | P023 |
| **P026** | mTLS: certificate upload, encrypted storage (platform-crypto), subject / fingerprint / expiry, rotation overlap, expiry events; HMAC callback signing (requirement 12) | P023 |
| **P027** | Intake: API polling with paging + mock source API; watched folder / share with per-customer trigger files; virus scan hook (ICAP, clamd) | P023; the trigger-file requirement (question 6) |
| **P028** | Push-API sign-in check (`PushCaller`) + client-ID bindings file in the setup command (`docs/design/setup.md` section 6) | identity, setup; answer to question 1 |

**LexaVault's switch-over part 3 needs P023 + P024 first:** the mock destination and the Vero profile, so its L5 tests
move onto the mock (`docs/work/test-strategy.md:29-37, 59` in LexaVault). Then P025, because LexaVault's local folder
destination gains the manifest. Intake (P027, P028) can follow part 3. The 0031 live re-run comes after Vero 0040 and
P024.

## Decisions I had to make (part B)

1. **The four modules above, with profiles as data.** The design names Vero R7 as a ready-made profile. CLAUDE.md
   forbids app-specific code. A profile named after Vero's *published API* (`sdv-ingestion-v1`), held as data and
   used by any sender, is the closest fit: no product package, and `NoProductNamesGuardTest` passes. See question 2.
2. **Copy, don't depend on, the notifications HTTP plumbing.** Integration must not pull in the notification engine.
   The shared pieces are small (Retry-After, no redirects, status mapping).
3. **The engine never retries.** That keeps the split the design sets: the app owns the schedule.

## Not done, and why

- **`feature/case-fields`:** it is still on origin, so the local branch stays (the task says the owner deletes it on
  GitHub).
- Nothing in part B is built: it is a proposal, as asked.

## Questions (numbered, each with a recommendation)

1. **Vero R7 does not use standard tokens.** It identifies the sender by the custom claims `sdv_tenant_id` and
   `sdv_source_id` (Vero tenant UUIDs) and has no client-ID lookup. That contradicts decision 3 of
   `docs/design/integration-module.md`. Vero 0040 keeps the claims and calls the lookup "planned".
   *Recommendation:* the `sdv-ingestion-v1` profile supports R7 as it is: the login server adds the two claims for the
   client, documented as a set-up step, and the profile sends standard client credentials. Vero moves to the
   `MachineEntityBindings` lookup in a named later task, and the profile then drops the claim requirement. Tell the SDV
   thread.
2. **A Vero-named profile in platform-core.** *Recommendation:* allow it as data in `platform-integration-profiles`,
   named after the API (`sdv-ingestion-v1`), and add one line to CLAUDE.md's rules: "published APIs of our products
   may ship as integration profiles (data, no product code)".
3. **`SecretResolver` lives in `com.documentplatform.notifications.secret`.** *Recommendation:* a later small task
   moves it to a tiny shared module (`platform-secrets`, package `com.documentplatform.secrets`), with the old
   interface kept as a subtype for one release. Until then integration depends on platform-notifications for that
   interface only.
4. **R7 sends no 429 / Retry-After.** *Recommendation:* the profile treats the 503s as busy (AUT-004 flagged as a
   sign-in-side fault), and the SDV thread decides in 0040 whether to document 429. The mock keeps the 429 switch for
   other destinations.
5. **Pinning before the tag.** *Recommendation:* P024 pins the spec to `release/vero-r7` f4c5c6e (noted in the copy)
   and re-pins to `sdv-ingestion-v1.0.0` when Vero 0040 merges, rather than waiting.
6. **The per-customer trigger-file requirement** (`Watched-Folder-Trigger-File.md`) is in no repo. *Recommendation:*
   commit it to LexaVault's or platform-core's `docs/design` before P027.
7. **LexaVault design text** `staged-pipeline-design.md:58` says a Vero 409 means "already received". It doesn't:
   Vero answers 200 for that. *Recommendation:* the LexaVault thread corrects the text; the code is already right.
