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
3. **Lookup, not custom claims.** Vero finds the entity and source from the verified client ID via `MachineEntityBindings` (one credential per integration per entity). Tokens need only the standard audience and subject; any entity or source claims present must match the binding or the call is refused. **The Vero profile assumes standard tokens only.**
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
| P020 | **Per-entity time zone** (before LexaVault P5) |
| P021 | Integration: **read-only investigation and proposal** — LexaVault intake/export code, 0031 findings, **Vero R7's real ingestion API** (from the SDV repo), integration-standards.md; module layout and task split |
| P022+ | Integration build in the order P021 proposes: core (config model, audit and versioning, sign-in bundle, safety, classified result, health, Test) → outbound API push + packagings + field mapping + **Vero R7 profile and contract tests** → folder export with manifest → intake (API polling, watched folder with trigger file, virus scan hook) → callback and push-API sign-in check |

LexaVault moves intake and export onto it as **part 3 of its switch-over**; the 0031 live run is re-run jointly when both sides are ready.
