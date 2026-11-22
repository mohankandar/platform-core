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
