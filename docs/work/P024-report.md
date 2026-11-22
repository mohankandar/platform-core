# P024 report — packagings, field mapping, integrity, the `sdv-ingestion-v1` profile and contract tests

Date: 2026-11-22
Branch: feature/integration-profiles (merged into main, deleted), then main (this report)
Outcome: DONE

## Result (tests per module, merge and push results)

- `mvn clean verify`: **BUILD SUCCESS, 944 tests, 0 failures, 3 skipped.** That is the 911 tests of P029, all
  passing, plus 33 new ones. The 3 skipped are P023's end-to-end TLS tests, which Norton blocks on this laptop. Then
  `mvn install` (with `-DskipTests`, straight after the verify): BUILD SUCCESS.

| Module | Tests |
|---|---|
| platform-identity-core | 90 |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 132 |
| platform-identity-testkit | 7 |
| platform-notifications-spring | 41 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 32 |
| platform-crypto | 20 |
| platform-crypto-testkit | 16 |
| platform-setup | 26 |
| platform-fields | 91 |
| platform-fields-testkit | 18 |
| platform-crypto-spring | 5 |
| platform-erase-log | 16 |
| platform-erase-log-testkit | 9 |
| platform-erase-log-spring | 5 |
| platform-integration | 20 (was 14) |
| platform-integration-testkit | 75 (was 71; 3 skipped) |
| **platform-integration-profiles** (new) | **23** |

- **Start (A):** three files were committed as they were (0e2890f): `docs/design/setup.md` (6.1 reader details and
  P029 decisions), `docs/work/P024.md`, and `docs/design/integration-module.md`. The design file was also
  uncommitted and holds the busy-vs-down decision this task builds on, so it went in the same commit. Then
  `feature/integration-profiles` was branched from main.
- **Merge:** fast-forward, `main` 488a239 → b593c12, pushed.
- **Branch:** deleted locally. It was never pushed, so there was nothing to delete on origin.
- **`feature/case-fields`:** still on origin, so the local branch stays.
- **This report:** the next commit on main, pushed.

## The pinned commit

| | |
|---|---|
| Repository | `C:\dev\sdv\secure-document-vault` (read only: only `git show` was used; its own uncommitted files were not touched) |
| Branch | `docs/ingestion-contract` |
| Commit | **`76307d61e8c8f4029f5fdcf3161425a826c6d5d1`** (`76307d6`, 2026-11-22, "busy answer 503 SDV-SYS-018 with Retry-After setting, upload SDV-STO-004, busy count in Problems (task 0040 part 2)") |
| File | `docs/api/sdv-ingestion-v1.openapi.json`, read from the commit with `git show 76307d6:…`, not from the working tree |
| `info.version` | 1.0.0 |
| SHA-256 | `858b0f07793ddacbbabf2c5d32a89f0e57b6fa425142c8f1e0c219f7cc1feed2` |
| `SDV-SYS-018` listed | Yes: in `x-sdv-answers` (`"retryAfter": true`) and in every operation's 503 `x-sdv-codes` |
| Where it is kept | `platform-integration-profiles/src/main/resources/profiles/sdv-ingestion-v1/` with `PINNED.md`. A new `.gitattributes` keeps `*.openapi.json` there byte for byte (`-text`), so its SHA-256 does not change on Windows checkouts |

## What was built

### B1. Building blocks (generic, `platform-integration`)

**MULTIPART body** (`CallStep.Body.MULTIPART`, `CallStep.Multipart(jsonPart, filePart, fileNameTemplate)`):

- One call with the JSON part and one file part per document.
- The part names come from the configuration. Each file part's name comes from a template (`{doc.*}`); its media
  type is the document's.
- Everything is streamed with a known length. Quotes in a file name are refused.
- Size limits are checked before anything is sent:
  - the largest file and the most documents, as before;
  - new: `Limits.maxPackageBytes` (default 2 GiB), which gives `INT_PACKAGE_TOO_LARGE`.

**Packaging presets** (`config.Packaging`): `twoStep`, `multipart` and `filePerCall`. Each returns ordinary
`CallStep`s; the engine has no special path for them.

**The one list construct:** `{#each doc}…{/each}`, in JSON only. It writes the part between the markers once per
document, separated by commas.

- It cannot be nested, start inside a string, or appear twice.
- `{doc.*}` placeholders are allowed inside it in a step run once.
- There are still no expressions, conditions or loops beyond this.

**Per-document values:** `{doc.attr.<name>}`, from `Delivery.Document.attributes` (for example the sensitivity
level).

**Field mapping by platform-fields key:** `{field:<key>}`.

- **Where the values come from:** `engine.CaseFields`, built from platform-fields' delivery format
  (`platform-field-values/1`) together with the field list. There are two ways to build it:
  - `CaseFields.of(fieldSet, validatedFields)` writes the document;
  - `fromDocument(doc, fieldSet)` checks an existing document with `FieldDelivery.verify`.
- **How a value is written:**
  - In JSON outside a string, by its type:
    - NUMBER, DECIMAL and MONEY as JSON numbers, normalised (MONEY `$250,000` becomes `250000.00`);
    - DATE, DATE_TIME and UUID as normalised text;
    - other text as entered;
    - ADDRESS as an object;
    - a multi-value field as a list.
  - Inside quotes, in a header or in a path: as text.
  - A NUMBER with leading zeros (an account number) is not a JSON number, so in that place it is refused
    (`INT_VALUE_NOT_ALLOWED`). Put the placeholder in quotes to send it as text.
- **Sensitive fields:** sent only when `IntegrationConfig.allowedSensitiveFields` lists the key, and only in a JSON
  body (never a header or a path, which proxies and servers log). This is checked in two places:
  - when saved, against the entity's field list: `IntegrationConfigs` takes a `FieldLists` and refuses with
    `INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED`. An unknown key, or no field list at all, gives
    `INT_CONFIG_FIELD_UNKNOWN`;
  - again when sent, from the delivery's own sensitive flags: `INT_SENSITIVE_FIELD_NOT_ALLOWED`, before anything is
    sent.
- **Never kept anywhere:** values are not in logs or results. The mock now records field **names** only; values only
  when a test asks for them with `recordValues(true)`.

**Integrity check** (`CallStep.withIntegrity(shaValue, sizeValue)`, per-document file steps only):

- The checksum and size the destination returns are compared with what was sent.
- A mismatch gives `UNCLEAR` / `INT_INTEGRITY_MISMATCH`, counts as a used attempt, and reports the step and document
  reached. The documents after it are not sent.

**Per-step rules** (`CallStep.rules`) are tried before the integration's rules.

**Rules can require Retry-After** (`Rule.whenRetryAfter()`, `"retryAfter": true` in the data).

**Health busy vs down:** a health rule may map to BUSY.

- `HealthResult` gets `busy()` and `down()`; it already carried the outcome and Retry-After.
- The new code `INT_DESTINATION_BUSY` is the platform code the profile's health rules give.

**Required token claims:** the setting `token.requiredClaims`.

- The OAuth 2.0 token must be a JWT carrying these claims, checked for presence only. Otherwise the attempt stops
  with `INT_TOKEN_CLAIMS_MISSING` before anything is sent.
- `IntegrationConfig` gets `settings` (profile settings and this one) and `allowedSensitiveFields`.

**Stored configurations:** the stored form (`platform-integration-config/1`) gains the new fields as optional. P023
configurations read unchanged, with the defaults; this is tested.

### B2. `platform-integration-profiles` (new module)

- **Profiles are data:** `profiles/<name>/profile.json`, format `platform-integration-profile/1`, named after the
  published API.
- `Profiles.load(name)` reads one, and `Profile.complete(Completion)` gives an ordinary `IntegrationConfig`. The
  completion adds:
  - entity, product, name, address and sign-in;
  - the field mapping (API name → field key), which fills the profile's `"metadata":{}`;
  - the allowed sensitive fields;
  - the settings.
- `Profile.withDocumentDefaults(delivery)` fills in or checks each document's attributes.
- There is no product code and no product package; `NoProductNamesGuardTest` passes.
- The module is in the root modules, the BOM and CLAUDE.md. The rule asked for is added to CLAUDE.md: "Published
  APIs of our products may ship as integration profiles (data, no product code)".

### B3. Profile `sdv-ingestion-v1`

- **create:** `POST /api/v1/ingestion/submissions`, JSON:
  - `sourceSubmissionId` = `{package.id}`;
  - `expectedDocumentCount`;
  - `expectedDocuments[]`, the complete list through `{#each doc}`, which makes the vault require
    `X-SDV-Source-Document-Id` on each upload;
  - `metadata` from the field mapping;
  - it reads `submissionId` (required).
- **upload,** per document: `POST /api/v1/ingestion/submissions/{step.create.submissionId}/documents`.
  - The raw PDF, with `Content-Type: application/pdf`.
  - `X-SDV-Filename`, `X-SDV-Source-Document-Id` and `Idempotency-Key` (= the document ID; decision 4).
  - `X-SDV-Sensitivity-Level` = `{doc.attr.sensitivityLevel}` (default `STANDARD`; values checked by the profile).
  - It reads `documentId`, `contentSha256`, `contentSizeBytes`, `receiptAcknowledgement` and `currentStatus`, and
    runs the integrity check on the checksum and size.
- **Sign-in:** OAuth 2.0 client credentials only; another type is refused when the profile is completed.
- **Health:** `GET /actuator/health/readiness`, without sign-in.
- **Test action:** CHECK_ONLY (health plus the token, nothing sent).
- **Setting `senderIdentity`, CLAIMS (default) or LOOKUP.**
  - CLAIMS means `token.requiredClaims = sdv_tenant_id,sdv_source_id`.
  - LOOKUP means nothing extra.
  - The requests are the same either way. The install guide `docs/install/integration-profiles.md` says what the
    login server must do in each case.
- **Limits:** 100 documents, 250 MiB per file, 2 GiB per package, as in the vault's defaults.
- **Rules:** one explicit rule per answer code of the spec, plus the replay rules (table below).

### B4. Mock and contract tests

**Spec mode for the generic mock:** `MockDestination.specMode(OpenApiSpec, SpecBehaviour)`.

- Only the document's operations exist; other paths get 404.
- Each request is checked against the spec:
  - required header, path and query parameters, and their schemas (pattern, length, UUID, enum);
  - the media type, refused with 415;
  - the JSON body against its schema.
- A request the spec refuses gets 400 with the API's code (`SDV-SYS-015` here), and the problem is recorded (place
  and code, no values).
- Answers are built from the response schemas, with the behaviour's values (the checksum and size received, the IDs
  sent), and checked against the schema before they are sent. The spec has no 2xx examples.
- The switches, both generic and spec-driven: working; busy with code and Retry-After; outage; refuse; sign-in
  refused; already received (both replay kinds); per-request errors, for example failing one document once.
- **Health:** a configurable path, `healthBusy(code, retryAfter)`, `healthFailing`, `healthSlow`, and `down()`.
- **Token endpoint:** `tokenClaims(...)` adds claims to the issued tokens (unsigned test JWTs), and
  `requireTokenClaims(code, names)` refuses tokens without them (`SDV-AUT-005` in CLAIMS mode).
- **Generic helpers:** `testkit.openapi.OpenApiSpec` (operations, parameters, answers, `x-` extensions) and
  `testkit.openapi.Schemas` (JSON Schema check and samples). The API's own behaviour (codes, error body, answer
  values) is in the profile module's test `IngestionApiMock`.

## Tests asked for → where

| Asked for | Where |
|---|---|
| MULTIPART, presets, lists, attributes | core `BuildingBlocksTest` (6): each-block, field typing, presets valid when saved, the new save checks, step rules and Retry-After rules, stored-form round trip and P023 compatibility. testkit `PackagingTest` (4): multipart on the wire (parts, file names, checksums), file-per-call and two-step with an attribute, the generic integrity check, per-step rules |
| Field mapping, sensitive refused unless allowed | `BuildingBlocksTest.fieldValuesAreWrittenByTheirType`, `theSaveCheckCoversTheNewBlocks`; profiles `EndToEndTest.sensitiveFieldsOnlyWhenAllowed`: refused at save; refused at send with nothing sent; allowed → sent, and the value is not in the mock's records, the result or any log line; an unknown field is refused |
| Every operation exists with the same method, path, required headers and fields | `ContractTest.everyStepIsAnOperationWithItsRequiredHeaders` |
| Requests validate against the request schemas | `ContractTest.theRequestBodiesFitTheRequestSchemas`; in every end-to-end run the mock checks each request against the spec (`specProblems()` empty) |
| Every answer code in the spec maps to a rule (derived table) | `ContractTest.everyAnswerCodeHasItsOwnRule`: for each of the 44 `x-sdv-answers` entries there must be an exact rule, and its outcome must equal the table derived from the spec's `x-sdv-retry` and the owner's 503 decisions. Every `x-sdv-codes` code is in `x-sdv-answers`, and no rule names a code the spec lacks. **A new code in the spec without a rule fails the build** |
| The mock's answers validate against the response schemas | `ContractTest.theMockFollowsTheSpecAndItsAnswersFitTheResponseSchemas`; `theMockRefusesWhatTheSpecRefuses` (a missing `X-SDV-Filename` gets 400 `SDV-SYS-015`) |
| Pinned copy | `PinnedSpecTest`: the SHA-256 matches `PINNED.md`, the commit is named, the version is 1.0.0, `SDV-SYS-018` has `retryAfter`, `SDV-STO-004` is present |
| End to end, every switch | `EndToEndTest` (16): two-step send with typed metadata and integrity; busy `SDV-SYS-018` with Retry-After 1, 5 and 300 → BUSY, no attempt used; a bare 503 → OUTAGE; `SDV-STO-004` → OUTAGE with the code kept; `SDV-AUT-003` / 403 `SDV-INT-005` / 503 `SDV-AUT-004` → SIGN_IN_FAILED; already received, both kinds; 409 / 400 / 413 → REFUSED |
| Health | `EndToEndTest.healthBusyVersusDown`: readiness 503 + `SDV-SYS-018` → BUSY (`INT_DESTINATION_BUSY`, Retry-After passed through); 503 + Retry-After only → BUSY; bare 503 → down; timeout → down (`INT_TIMEOUT`); unreachable → down (CANT_CONNECT) |
| Resume after a failure mid-upload | `EndToEndTest.resumesAfterAFailureMidUpload`: doc-2 busy once; the resume sends doc-2 and doc-3 only, not the create or doc-1 again |
| Integrity mismatch | `EndToEndTest.anIntegrityMismatchIsUnclearAndStops` |
| CLAIMS / LOOKUP | `EndToEndTest.claimsModeChecksTheTokenLookupModeDoesNot`: in CLAIMS mode a token without the claims stops before sending (`INT_TOKEN_CLAIMS_MISSING`); in LOOKUP mode it is sent; a destination that wants the claims answers `SDV-AUT-005`. Also `theProfileSavesAndChecksItsSettings`, `theTestActionChecksHealthAndTheToken`, `sizeLimitsBeforeSending` |

## How an app loads `sdv-ingestion-v1` and sends one package (minimal example)

```java
Profile profile = Profiles.load("sdv-ingestion-v1");
IntegrationConfig config = profile.complete(new Profile.Completion(entityUuid, "DEMO", "Records vault",
        Endpoint.of("https://vault.customer.local"),
        SignIn.OAuth2ClientCredentials.secret(URI.create("https://login.customer.local/oauth2/token"),
                "records-sender", SecretRef.of("integrations/vault/client-secret"), "ingest"),
        Map.of("contractNumber", "contract_number", "loanAmount", "loan_amount"),   // vault name -> field key
        Set.of(),                                                                    // no sensitive fields
        Map.of("senderIdentity", "CLAIMS")));
StoredIntegration saved = new IntegrationConfigs(store, appAudit, installation, Clock.systemUTC(),
        (entity, product) -> Optional.of(fieldSetOf(entity, product))).create(config, "it-admin");

Delivery delivery = profile.withDocumentDefaults(Delivery.of(packageId,
                List.of(Delivery.Document.of("doc-1", "statement.pdf", "application/pdf", pdfBytes)
                        .withAttribute("sensitivityLevel", "CONFIDENTIAL")), Map.of())
        .withFields(CaseFields.of(fieldSet, validatedCaseValues)));

HealthResult health = engine.health(saved);
if (health.busy()) { waitAndSendFewer(health.retryAfter()); }      // no pause, no attempt used
else if (health.down()) { pauseAndProbe(); }
else {
    AttemptResult r = engine.attempt(saved, delivery);              // one attempt; the app keeps the schedule
    // SENT / ALREADY_RECEIVED: done.  BUSY: wait r.retryAfter(), r.attemptUsed() == false.
    // UNCLEAR + INT_INTEGRITY_MISMATCH: the vault's checksum differs.  Next time:
    //   engine.attempt(saved, delivery.resumingFrom(Delivery.Resume.from(r)));
}
```

**What the app needs from the results for its 30-minute "busy spell" IT notice:** `HealthResult.busy()` and
`AttemptResult.outcome() == BUSY` (with `retryAfter()` and `checkedAt()`) on each check or attempt. When a busy spell
started, and whether it has lasted 30 minutes, is the app's to track: the module keeps no state between calls. The
vault's own code (`destinationCode()`, for example `SDV-SYS-018` or `SDV-STO-004`) tells IT why.

## The full rule table (`sdv-ingestion-v1`, `profile.json`)

The rules are tried in order (step rules first, then these, then the generic defaults). The "When" column is the
spec's own summary from `x-sdv-answers`.

| # | Status | Destination code | When | Outcome | Attempt used |
|---|---|---|---|---|---|
| 1 | 200 | any | /receiptAcknowledgement = DUPLICATE_ALREADY_STORED | ALREADY_RECEIVED | yes |
| 2 | 200 | any | /idempotentReplay = true | ALREADY_RECEIVED | yes |
| 3 | 401 | `SDV-AUT-001` | No token. | SIGN_IN_FAILED | yes |
| 4 | 401 | `SDV-AUT-003` | Token expired, not yet valid, badly signed, for another issuer or audience, too long-lived, or not a JWT. | SIGN_IN_FAILED | yes |
| 5 | 401 | `SDV-AUT-005` | A claim Vero needs is missing or not usable (sub, tenant ID, source ID), or the token has both the source-ID and the groups claim. | SIGN_IN_FAILED | yes |
| 6 | 401 | `SDV-AUT-006` | The token has no source-ID claim, so it was read as a person's, and Vero has no active sign-in connection for its issuer. | SIGN_IN_FAILED | yes |
| 7 | 401 | `SDV-AUT-007` | The token has no source-ID claim, so it was read as a person's, and it gives no Vero role. | SIGN_IN_FAILED | yes |
| 8 | 401 | `SDV-SYS-006` | Sign-in is switched off in this Vero. | SIGN_IN_FAILED | yes |
| 9 | 403 | `SDV-AUT-002` | A person's sign-in on a sending system's address. | SIGN_IN_FAILED | yes |
| 10 | 403 | `SDV-AUT-009` | The tenant doesn't exist in Vero or is suspended. | SIGN_IN_FAILED | yes |
| 11 | 403 | `SDV-INT-005` | The sending system isn't one of the tenant's, or Tenant IT paused it. | SIGN_IN_FAILED | yes |
| 12 | 400 | `SDV-INT-006` | Submission incomplete: no body, expectedDocumentCount below 1, sourceSubmissionId missing or longer than 200. | REFUSED | yes |
| 13 | 400 | `SDV-INT-007` | Document list not valid: over the limit, more entries than the count, an entry missing, too long or twice. | REFUSED | yes |
| 14 | 400 | `SDV-INT-008` | A metadata field unknown, missing, blank, null, or not a single value. | REFUSED | yes |
| 15 | 400 | `SDV-INT-009` | A metadata value of the wrong type or breaking its field's rule. | REFUSED | yes |
| 16 | 400 | `SDV-SEN-002` | recordSensitivityLevel is not exactly one of the three names. | REFUSED | yes |
| 17 | 400 | `SDV-SEN-006` | sensitivityLevel on the submission; it belongs on each upload. | REFUSED | yes |
| 18 | 400 | `SDV-SYS-015` | The request couldn't be read: JSON that doesn't parse, an ID that isn't a UUID, a required header or parameter missing. | REFUSED | yes |
| 19 | 400 | `SDV-SEN-001` | X-SDV-Sensitivity-Level is not exactly one of the three names. | REFUSED | yes |
| 20 | 400 | `SDV-SEN-005` | X-SDV-Record-Sensitivity-Level on an upload. | REFUSED | yes |
| 21 | 400 | `SDV-INT-012` | The upload has no identity, or its X-SDV-Source-Document-Id isn't in the submission's complete list. | REFUSED | yes |
| 22 | 400 | `SDV-INT-013` | An upload header isn't valid: file name blank, too long or a path; an ID longer than 200. | REFUSED | yes |
| 23 | 400 | `SDV-INT-015` | The body is empty, cut short, or not a PDF. | REFUSED | yes |
| 24 | 400 | `SDV-INT-016` | The document would take the submission over its total size limit. Send it on a new submission. | REFUSED | yes |
| 25 | 404 | `SDV-INT-004` | No submission of this sending system has this ID. | REFUSED | yes |
| 26 | 404 | `SDV-INT-018` | No document of this submission has this ID. | REFUSED | yes |
| 27 | 406 | `SDV-SYS-015` | The Accept header asks for a format Vero can't answer in. The work may already be done; send again with Accept application/json. | REFUSED | yes |
| 28 | 409 | `SDV-INT-011` | A submission with this sourceSubmissionId exists with a different body. | REFUSED | yes |
| 29 | 409 | `SDV-SEN-004` | A submission with this sourceSubmissionId exists with another recordSensitivityLevel. | REFUSED | yes |
| 30 | 409 | `SDV-INT-016` | The submission already has all its documents. Send it on a new submission. | REFUSED | yes |
| 31 | 409 | `SDV-INT-017` | A document with this identity is already stored with other content or file name. | REFUSED | yes |
| 32 | 409 | `SDV-SEN-003` | A document with this identity is already stored with another level. | REFUSED | yes |
| 33 | 409 | `SDV-INT-018` | The document can't be finished again in its state. | REFUSED | yes |
| 34 | 413 | `SDV-INT-014` | The PDF is over the size limit. | REFUSED | yes |
| 35 | 415 | `SDV-SYS-015` | The body's Content-Type isn't the one this operation takes. | REFUSED | yes |
| 36 | 500 | `SDV-SYS-013` | Unexpected failure inside Vero. | OUTAGE | yes |
| 37 | 500 | `SDV-SYS-016` | An internal check failed; retrying usually gives the same answer. | OUTAGE | yes |
| 38 | 500 | `SDV-SYS-017` | The audit couldn't be written, so nothing was done. | OUTAGE | yes |
| 39 | 500 | `SDV-INT-010` | Tenant IT's rule for a metadata field is broken. | OUTAGE | yes |
| 40 | 503 | `SDV-SYS-001` | Vero's database is older than Vero (upgrade under way). | OUTAGE | yes |
| 41 | 503 | `SDV-SYS-002` | Vero's database is newer than Vero. | OUTAGE | yes |
| 42 | 503 | `SDV-SYS-014` | Vero's database can't be reached. | OUTAGE | yes |
| 43 | 503 | `SDV-SYS-018` | Vero is busy: no database connection is free for the request. Wait the seconds in the Retry-After header and send the same request again. | BUSY | no |
| 44 | 503 | `SDV-AUT-004` | The sign-in server's keys can't be fetched. | SIGN_IN_FAILED | yes |
| 45 | 503 | `SDV-STO-001` | No storage can take the document (storage full). | OUTAGE | yes |
| 46 | 503 | `SDV-STO-004` | Storage for this tenant isn't available: the storage volume Vero picked names a storage provider this install doesn't have. The installer must fix it. | OUTAGE | yes |
| 47 | 429 | any | Sent by a gateway, no code | BUSY | no |
| 48 | 503 | any | Any other 503 | OUTAGE | yes |

Health:
| H1 | 503 | `SDV-SYS-018` |  | BUSY (INT_DESTINATION_BUSY) | no |
| H2 | 503 | any | Retry-After present | BUSY (INT_DESTINATION_BUSY) | no |
| H3 | 503 | any |  | OUTAGE | - |

**How the outcomes were derived** (`ContractTest.expected`, asserted for every entry):

| The spec's `x-sdv-retry` | Outcome |
|---|---|
| `no` | REFUSED |
| `new-token` | SIGN_IN_FAILED |
| `after-fix`, 401/403 | SIGN_IN_FAILED |
| `after-fix`, 5xx (`SDV-SYS-016`, `SDV-INT-010`, `SDV-STO-004`) | OUTAGE, with the code kept |
| `yes` | OUTAGE, except: `SDV-SYS-018` → BUSY (no attempt used, Retry-After passed through) and `SDV-AUT-004` → SIGN_IN_FAILED (owner decision 2) |

Answers with no code fall to the last rules and the generic defaults:

- 429 from a gateway → BUSY;
- any other 503 → OUTAGE;
- an unknown 4xx code → REFUSED;
- 404 without a code → NOT_CONFIGURED (wrong address).

These are tested too.

## Files changed, commits

- 0e2890f `docs: P024 task; setup 6.1 reader details and P029 decisions; Vero busy vs down at the health check`
- b593c12 `feat(integration): packagings, field mapping, integrity, the sdv-ingestion-v1 profile and contract tests (P024)`:
  - `platform-integration`:
    - `config/`: Template (each-block, `field:`, `doc.attr.`, `fill(Values)`), CallStep (MULTIPART, multipart,
      integrity, step rules), Packaging (new), Rule (retryAfter), Classification (step rules, Retry-After),
      ConfigCheck (the new checks, FieldLists), FieldLists (new), IntegrationConfig (allowed sensitive fields,
      settings), Limits (package limit), ConfigJson (new fields, `readSteps`);
    - `engine/`: CallEngine (field values, attributes, multipart, integrity, step rules, sensitive check, package
      limit, token claims), CaseFields (new), Delivery (fields, attributes);
    - `result/HealthResult` (busy/down), `signin/SignInHeaders` (required claims), `store/IntegrationConfigs`
      (FieldLists), `IntegrationCode` (10 new codes);
    - pom: platform-fields; test `BuildingBlocksTest`.
  - `platform-integration-testkit`: MockDestination (spec mode, parts, field names only, health switches, token
    claims), `openapi/OpenApiSpec`, `openapi/Schemas`; tests `PackagingTest` (new) and `recordValues(true)` in three
    P023 tests (decision 6).
  - `platform-integration-profiles` (new): pom; `Profile`, `Profiles`; resources
    `profiles/sdv-ingestion-v1/{profile.json, sdv-ingestion-v1.openapi.json, PINNED.md}`; tests `IngestionApiMock`,
    `ProfileFx`, `PinnedSpecTest`, `ContractTest`, `EndToEndTest`.
  - `pom.xml`, `platform-bom/pom.xml` (new module); `.gitattributes` (new); `CLAUDE.md` (module rows, the profiles
    rule); `docs/install/integration-profiles.md` (new).
- This report: the next commit on main.

## Decisions I had to make

1. **`docs/design/integration-module.md` was committed in step A** with the two files the task names. It was also
   uncommitted, and it holds the master's busy-vs-down decision this task implements.
2. **Field values are taken with the field list.** The delivery document carries values as they were entered
   (MONEY `$250,000`), so typed JSON needs platform-fields' normalised forms. `CaseFields` is therefore built from the
   field list plus the accepted values (`of`), or a document checked against the field list (`fromDocument`). The
   format on the wire is still `platform-field-values/1`.
3. **Sensitive fields go in JSON bodies only,** never headers or paths: addresses and headers end up in proxy and
   server logs. A NUMBER with leading zeros is not a JSON number; put the placeholder in quotes to send it as text.
4. **`Idempotency-Key` on uploads is the document ID,** not `<package>:<document>`. The spec allows 200 characters,
   both IDs may each be 200, the key names the upload within its submission, and `X-SDV-Source-Document-Id` (always
   sent) is the identity Vero uses first.
5. **The mock builds its answers from the response schemas,** because the spec has no 2xx examples. It checks its
   own answers against the schemas, so it cannot drift from the spec.
6. **The mock now records JSON field names only by default.** The task says the mock's records hold field names
   only. Three P023 tests that read non-sensitive values switch values on with `recordValues(true)`, a one-line change
   each.
7. **The CLAIMS check reads the token's claims for presence only** (the JWT payload, not verified; that is the
   vault's job). The payload is never kept or logged.
8. **The profile's Test action is CHECK_ONLY** (health plus the token). The vault has no test entity in its spec, so
   a full fake delivery would land as a real submission.
9. **`recordSensitivityLevel` is not sent.** It is optional in the spec, and templates cannot leave out a member when
   there is no value. See question 1.
10. **Where readiness puts its busy code.** I assumed the vault will put it in the same place as its other errors
    (`/code`, the Error shape). The health rules read it there. See question 2.

## Not done, and why

Nothing in the task is left. Re-pinning to the tag `sdv-ingestion-v1.0.0` is a later, separate step, as the task
says. The 30-minute busy-spell IT notice is the app's (see "What the app needs" above).

## Questions (numbered, each with a recommendation)

1. **`recordSensitivityLevel` on the submission.** LexaVault's own sender set it. Templates have no way to leave a
   JSON member out when its value is missing. *Recommendation:* in P025 add one more fixed building block: an
   "omit when missing" mark on a JSON member, still no expressions. The profile then sends
   `recordSensitivityLevel` from `{value.recordSensitivityLevel}` when the app gives one. Confirm that LexaVault needs
   it for switch-over part 3.
2. **Readiness busy body.** *Recommendation:* ask the SDV thread to put the busy code on readiness 503 as
   `{"code": "SDV-SYS-018", …}` (the Error shape) together with `Retry-After`. The profile reads `/code`. If it is
   Retry-After only, the profile already treats that as busy.
3. **403 `SDV-INT-005`** (the sending system isn't the tenant's, or Tenant IT paused it) is classed SIGN_IN_FAILED,
   because the spec says "after-fix" and it is a set-up fault. *Recommendation:* keep it; LexaVault's job sends it to
   IT like other sign-in faults.
