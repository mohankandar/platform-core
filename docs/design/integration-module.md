# platform-integration — shared, configurable integrations (owner-approved 2026-11-21)

Source: the LexaVault thread's request (record: `docs/work/integration-module-decision.md` in secure-document-platform), checked and planned by the Shared capabilities thread. Project doc: `Integration-Module-Design.md`. Builds on `docs/design/integration-standards.md` (requirements 11–16), platform-fields and platform-notifications. Each part gets its detailed design in its task.

## Why

LexaVault's live run against Vero (0031) found its Vero sender doesn't match Vero R7 (addresses, upload headers, sign-in): earlier tests used a fake built from LexaVault's own contract document. Integrations become **one shared, configurable module, inbound and outbound**. An app picks an integration and configures it. Anything outside the building blocks is a coded extension for that customer, never an app change.

## Split of duties

**platform-core (the module):** configured calls (method, address, header and body templates, response mapping); field mapping by platform-fields keys (e.g. `contract_number` → `loanId`); the sign-in bundle; files in and out; common plumbing. It makes **one attempt** and reports a classified result: **sent / already received / refused with code / sign-in failed / can't connect / busy**. Health check per destination. **Vero R7's ingestion API as a ready-made profile.**

**Each app keeps its workflow.** LexaVault: export job and rules (pause when the health check fails, 5 min–12 h retry schedule, one rebuild, "Waiting for you", IT queue, screens, retention per destination — 0027–0030); intake rules; its own push API specification (callers' sign-in checked with the shared bundle); the manifest's app section (case fields, file list with checksums, redaction status, sensitivity levels).

## Catalog

| Area | V1 (build now) | Later (when a customer asks) | Extension (one customer) |
|---|---|---|---|
| Intake | Push API (each app's own; shared sign-in check); polling an API (with paging); watched folder / network share, trigger file last (XML, JSON or .lck, per customer) | SFTP pickup (likely early); S3 / Azure Blob / GCS; SharePoint / OneDrive; email inbox incl. fax-to-email (spam, malware, sender checks) | Message queue (Kafka, IBM MQ, RabbitMQ) |
| Export | API push; folder / network share with a manifest JSON written last (common envelope + app section) | SFTP drop (likely early); cloud storage; DMS profiles (SharePoint, Box; iManage, NetDocuments) | Message queue notice |
| Not offered | Email export | | |
| Sign-in | None (internal network, on-prem only, off by default, warned, audited, on the compliance report, never hosted); shared secret (user+password, or API key in a header, never in the address); fixed bearer token; OAuth2 client credentials / JWT; mutual TLS (certificate uploaded, stored encrypted, shown only as subject/fingerprint/expiry, expiry warnings, replacement without downtime; or a server keystore); HMAC-signed requests for callbacks | Private-key JWT; Windows sign-in (Kerberos/NTLM); SSH keys (SFTP); cloud access keys / managed identity | |
| Packaging | Multipart (documents + JSON); two steps, create then upload (Vero's way); one file per call with details in headers | JSON with base64; ZIP bundle; XML / SOAP incl. MISMO (likely early) | |
| Behaviours (V1) | 202 accepted then status check or callback; paging; 429 / Retry-After; customer's outbound proxy; private certificate authority; request IDs and checksums; size limits and timeouts per integration | | |

## Safety

Fixed building blocks, no free scripting; secrets only in the secret store; no document content or secrets in URLs, templates or logs; protection against cloud-metadata addresses (internal addresses allowed on-premises); every configuration change audited and versioned.

## Testing

A **Test** action with fake data on every integration; **contract tests** against each published API (Vero R7 first) in every build; live-system integration tests marked to run only on request.

**Mock destination server (from `LV-Test-Strategy.md`, owned by platform-core, built with P022/P023):** built from the pinned Vero spec so it can't drift; switches per test or by a small admin call: working, down, slow, busy (429 + Retry-After), unavailable (503), refuses with a code (e.g. `SDV-SEN-006`, `SDV-INT-011`), already received (200 replay, `DUPLICATE_ALREADY_STORED`), sign-in refused (`SDV-AUT-*`), health failing while uploads work and the reverse. Records what it received (headers, fields, checksums; never document text). Also a mock folder/share (can be unwritable or slow) and a mock source API for polling intake. LexaVault uses them from switch-over part 3.

## What platform-core already has (checked 2026-11-21)

| Need | Already there |
|---|---|
| HTTP sending with specific failure codes, 429 / Retry-After, timeouts, idempotency keys | platform-notifications channels and outbox (reusable plumbing; integrations report one attempt, the app keeps retries) |
| OAuth2 client credentials token fetch and cache | platform-notifications `MicrosoftTokens` (to generalise) |
| HMAC-signed, expiring tokens | platform-notifications `ActionTokens` (pattern for callback signing) |
| Secrets by reference only | `SecretResolver` everywhere |
| Field keys, types, delivery format key + type + value, receiving-side verifier | platform-fields (P017) |
| Requirements: virus scan (optional), credentials / mTLS / rotation / expiry warnings, delivery integrity, callback, health and diagnostics, API standard | `integration-standards.md` rounds 1–2 (requirements only, not designed) |

**Missing (to build):** the configured-call engine and templates, response mapping, field mapping to external names, packaging modes, 202 + status/callback, paging, outbound proxy and private CA, cloud-metadata guard, "None" and bearer sign-in, mTLS certificate upload and encrypted storage, per-destination health and Test action, configuration audit and versioning, folder export with manifest last, API polling intake, watched-folder intake with per-customer trigger files, the Vero R7 profile and contract tests.

**Prerequisite found:** encrypted storage (mTLS certificates here, sensitive case-field values in P019) needs the **secrets and encryption module** (capability 6: move Vero's `sdv-crypto` — Vault client, envelope encryption, keyed fingerprints) **first**.

## Vero R7 profile: decisions (2026-11-21, LexaVault and SDV threads, owner)

1. **Pinned contract.** On merging Vero 0040, Vero tags the merge commit `sdv-ingestion-v1.0.0`. Platform-core keeps its own copy of `sdv-ingestion-v1.openapi.json` (source: `secure-document-vault` `docs/api/`, error codes in `docs/api/error-codes.md`), with the version and source commit noted. Contract tests run against that copy, updated deliberately with each Vero release that changes the API.
2. **The claim Vero calls "tenant" means the entity (`ENT-…`)**, not the customer; Vero renames it "entity" at its switch-over.
3. **Lookup, not custom claims.** Vero finds the entity and source from the verified client ID via `MachineEntityBindings` (one credential per integration per entity). Tokens need only the standard audience and subject; any entity or source claims present must match the binding or the call is refused. *(Target state. Updated 2026-11-22: R7 still requires custom claims; the profile supports both until Vero builds the lookup before the first install — see "Decisions on the P022 proposal", item 1.)*
4. Sign-in: OAuth2 client credentials. Health: `/actuator/health/readiness`. Build the profile once Vero 0040 is merged.

## Registering integration client IDs (LexaVault thread decision, 2026-11-21)

Applies to Vero's and LexaVault's own push-API callers.

- **Now (A):** the setup command loads an input file next to the role mapping: client ID → entity → source. Safe to rerun.
- **Rules:**
  1. The file holds client IDs only, never secrets.
  2. Each client ID binds to exactly one entity and one source; a duplicate is refused.
  3. Setup checks that the entity and source exist and stops with a clear message if not.
  4. Removing a line switches the binding off, never deletes it.
  5. Every change is audited.
- **Later (B):** the IT Admin's integration page (with the integration module's screens) edits the same bindings. No second store.
- Details of the file and command: `docs/design/setup.md` section 6.

## Order (platform-core)

| Task | What |
|---|---|
| P018 | **platform-crypto**: move Vero's `sdv-crypto` (read-only source) — Vault client, AES-256-GCM envelope encryption, keyed fingerprint (HMAC) for exact-match search, key-service plug-in point |
| P019 | Case field **values**: encrypted sensitive values, fingerprint search, primary uniqueness |
| P020 | **Per-entity time zone** (before LexaVault P5) — done |
| P021 | **Erase log** (shared) and **customer key destruction** — needed for LexaVault switch-over part 1 (`docs/design/erase-log.md`) |
| P022 | Integration: **read-only investigation and proposal** — LexaVault intake/export code, 0031 findings, **Vero R7's real ingestion API** (from the SDV repo), integration-standards.md; module layout and task split |
| P023+ | Integration build in the order P022 proposes: core (config model, audit and versioning, sign-in bundle, safety, classified result, health, Test) → outbound API push + packagings + field mapping + **Vero R7 profile and contract tests** → folder export with manifest → intake (API polling, watched folder with trigger file, virus scan hook) → callback and push-API sign-in check |

LexaVault moves intake and export onto it as **part 3 of its switch-over**; the 0031 live run is re-run jointly when both sides are ready.

## Decisions on the P022 proposal (LexaVault thread, master, 2026-11-22)

1. **Vero sign-in:** Vero builds the client-ID lookup (`MachineEntityBindings`) **before the first install**. The `sdv-ingestion-v1` profile supports R7's custom claims (`sdv_tenant_id`, `sdv_source_id`) now and the lookup later (configuration switch, no code change).
2. **Busy vs outage:** **busy** = Vero's 503 with its busy code (plus Retry-After when sent): wait, send fewer, **no attempt used**. A 503 **without** that code is an **outage** (health check, then pause or the case's own retries). AUT-004 is a **sign-in fault**, not busy. The generic engine expresses this as profile rules (status + code → result class), not Vero code.
3. **Trigger file:** requirement committed in LexaVault at `docs/work/watched-folder-trigger-file.md` (branch `feature/0037-switchover-1a`, merging soon). Split: **platform-core watches, reads and waits** (folder stability, trigger file last, timeout for incomplete drops); **LexaVault interprets** the trigger file's content.
4. LexaVault's `staged-pipeline-design.md` text about Vero's 409 is corrected (Vero answers 200 for "already received"; 409 = same key, different content).
5. Profiles ship as **data** named after the published API (`sdv-ingestion-v1`); the spec copy is pinned to the current Vero commit until the `sdv-ingestion-v1.0.0` tag, then re-pinned.
6. **Order approved:** P023 core + mock server → P024 packagings, field mapping, integrity, `sdv-ingestion-v1` profile, pinned spec, contract tests → P025 Spring + folder export with manifest → P026 mTLS + callbacks → P027 intake (polling, watched folder, virus scan hook) → P028 push-API sign-in check + client-ID bindings in setup. **LexaVault's part 3 starts after P023 + P024.** Full proposal: `docs/work/P022-report.md` sections B1–B8.

## Decisions on the P023 report (shared-capabilities thread, 2026-11-22)

P023 accepted: 897 tests, 3 TLS tests skipped on the owner's laptop (Norton re-signs loopback TLS). Its decisions 1–10 stand, including: generic default 5xx = outage, 408/429 = busy; 404 without a destination code = `NOT_CONFIGURED` / `INT_ADDRESS_NOT_FOUND`; a timeout after sending = `CANT_CONNECT` / `INT_TIMEOUT`, resent with the same idempotency key; the configuration is re-checked at every attempt.

1. **DNS rebinding:** accepted for V1 (check every resolved address right before each call; not pinned). P025's install guide states it. **Strict pinning is built in P026** on the socket layer it builds for mTLS, and is required before the hosted service goes live.
2. **Skipped TLS tests:** no repo change. They must pass on a machine without TLS interception (CI or another machine) before a release that changes `safety/` or `http/`. A Norton exclusion on the laptop is the owner's choice.
3. **Proxy sign-in:** built in P025 with the Spring properties. The proxy password comes by secret reference; the install guide tells the customer to start the app with `-Djdk.http.auth.tunneling.disabledSchemes=` when their proxy needs Basic. The library never sets JVM properties.
4. **503 default stays outage.** A destination that sends 503 + Retry-After to mean busy gets a profile rule. The configuration screen (P025) offers "503 with Retry-After means busy" as a ready-made rule.
5. **`SecretResolver` from platform-notifications** accepted for now. When a third module needs it, it moves to a small shared module (no task yet).

## Vero busy vs down at the health check (LexaVault thread, master, 2026-11-22)

No counting of failed checks. In the `sdv-ingestion-v1` profile, a readiness answer of **503 with `SDV-SYS-018` or with `Retry-After` = busy**: wait, send fewer at once, no pause, no attempt used. **Can't connect, a timeout, or a 503 without the busy code = down**: pause and probe. A busy spell over 30 minutes raises one IT notice (app side, LexaVault). Vero is asked to put the busy code and Retry-After on its readiness answer. Built in P024 (health rules may map to BUSY generically).

**Update (master, 2026-11-22):** P024 pins Vero's spec at `docs/ingestion-contract` `76307d6` (0040 Part 2), re-pinned at `sdv-ingestion-v1.0.0`. Busy = 503 `SDV-SYS-018` with Retry-After (Vero setting 1–300 s, default 5). `SDV-STO-004` is a 503 without the busy code = **down**. Readiness-busy arrives in a separate Vero commit; the profile takes it when it lands.

## Folder export and its manifest (P025)

Kind `FOLDER_EXPORT`, configured and versioned like API push (`IntegrationConfig.folderExport`, `config.FolderTarget`), run by `folder.FolderExport`. One attempt per call, with the same `AttemptResult` outcomes. It never retries.

**Configuration.** The target folder: an absolute local path (`D:\exports`, `/srv/exports`) or a share path (`\\fileserver\exports\in`), no `.` or `..` parts. On Linux a share is mounted and its mount path is configured. The package folder's name is a template, one path segment, default `{package.id}`. It may use `{package.id}`, `{package.documentCount}`, `{value.<name>}` and `{field:<key>}` (never a sensitive field). Each value is percent-encoded as in a P023 path segment. Each document's file name is either the document's own name made safe for Windows and Unix, or a template that may also use the document placeholders. Two names that differ only in case get ` (2)`, ` (3)` before the extension. The limits are the integration's own. There is no sign-in bundle: the folder's rights are the service account's. Refused on the hosted service (`INT_CONFIG_FOLDER_HOSTED`) and at save for a bad path or name (`INT_CONFIG_FOLDER_INVALID`).

**Writing, so that a reader never sees a package folder without its manifest:**
1. `<folder>/<package>.partial/` is made. A leftover of an earlier failed attempt is removed first.
2. Each document is written to a temporary name (`.tmp-<n>`) and forced to the disk. It is read back, and its size and SHA-256 are checked against the delivery. Then it is renamed to its file name.
3. `manifest.json` is written last, the same way.
4. The folder is renamed from `.partial` to its final name.

On any failure only the `.partial` folder is touched, and removed when possible. Each file must be written within the integration's read timeout (`INT_TIMEOUT`).

**Outcomes:**

| Case | Outcome | Code |
|---|---|---|
| Written | `SENT` | `INT_SENT` (value `export.folderName`) |
| Final folder there, same package's manifest | `ALREADY_RECEIVED` | `INT_ALREADY_RECEIVED` |
| Final folder there, other or no manifest | `REFUSED` | `INT_FOLDER_EXISTS_DIFFERENT` (nothing changed) |
| Target folder missing | `NOT_CONFIGURED` | `INT_FOLDER_NOT_FOUND` |
| No right to write | `NOT_CONFIGURED` | `INT_FOLDER_NO_PERMISSION` |
| Disk or quota full (before or while writing) | `OUTAGE` | `INT_FOLDER_FULL` |
| Share not reachable | `CANT_CONNECT` | `INT_FOLDER_UNREACHABLE` |
| Checksum or size differs after writing | `UNCLEAR` | `INT_INTEGRITY_MISMATCH` |
| Writing too slow | `CANT_CONNECT` | `INT_TIMEOUT` |
| Any other write failure | `OUTAGE` | `INT_FOLDER_WRITE_FAILED` |

"Same package" means the manifests are equal apart from `createdAt`, `correlationId` and `integration.configVersion`, which change between attempts.

**Health:** the target exists and this account can write there. The check writes and removes one probe file (`.platform-health-<random>.tmp`) and nothing else. **Test action:** the fake one-page PDF and a manifest, written exactly like a package into `platform-test-<timestamp>`, then removed. It is audited like API push.

**Manifest `platform-export-manifest/1`** (JSON Schema `schema/platform-export-manifest-1.schema.json`, next to the profile schema):

| Member | What |
|---|---|
| `format` | `platform-export-manifest/1` |
| `packageId`, `correlationId`, `createdAt` | the app's package ID; the attempt's correlation ID; when it was written (UTC, seconds) |
| `product` | the product code |
| `entitySlotId` | the entity's slot ID (`ENT-…`; it must be the integration's entity) |
| `integration` | `id` and `configVersion` of the configuration used |
| `documents` | per document: `fileName` (in the folder), `sourceDocumentId`, `contentType`, `size`, `sha256` |
| `fields` | optional: the case field values in the `platform-field-values/1` wire format (platform-fields). A sensitive field goes only if the configuration allows its key (`allowedSensitiveFields`), as for API push; the others are left out |
| `app` | an object the app fills (`ExportInfo.appSection`). platform-core writes it as given and never reads it |

The app calls `FolderExport.attempt(stored, delivery, new ExportInfo(slotId, appSectionJson))`.

## Proxy sign-in and "omit when missing" (P025)

**Proxy sign-in** ("Decisions on the P023 report", item 3): the proxy's user and its password, by secret reference (read at every call), go as `Proxy-Authorization: Basic …`. That header is sent on the CONNECT of an https call and on plain-http requests through the proxy, never to the destination. The library never sets JVM properties. If the JVM still disables Basic for tunnels (the JDK's default), an https call through a proxy with sign-in fails before connecting with `INT_PROXY_SIGN_IN_DISABLED_BY_JVM`. That code's message names the start option `-Djdk.http.auth.tunneling.disabledSchemes=`. A 407 from the proxy gives `INT_PROXY_SIGN_IN_REFUSED`.

**"Omit when missing"** (P024 question 1): one more fixed building block in JSON body templates. A placeholder written `{name?}` that is the whole value of an object member (`"loan":"{field:loan_number?}"`, `"amount":{field:loan_amount?}`) leaves out that whole member, with its comma, when it has no value. A value that is present but cannot be placed is still refused. Anywhere else it is refused at save (`INT_CONFIG_OMIT_PLACE`). Still no expressions.

**Ready-made rule** (item 4): `Rule.busyWhen503WithRetryAfter()` (and `Rule.healthBusyWhen503WithRetryAfter()` for health rules) is the rule the configuration screen offers as "503 with Retry-After means busy".
