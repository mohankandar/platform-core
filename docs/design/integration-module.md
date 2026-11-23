# platform-integration — shared, configurable integrations (owner-approved 2026-11-21)

Source: a request from an earlier app's thread (its record is kept in that app's own repository), checked and planned by the Shared capabilities thread. Project doc: `Integration-Module-Design.md`. Builds on `docs/design/integration-standards.md` (requirements 11–16), platform-fields and platform-notifications. Each part gets its detailed design in its task.

## Why

An earlier app's live run against a document vault's ingestion API found that its sender did not match the vault's released API (addresses, upload headers, sign-in): earlier tests used a fake built from the app's own contract document. Integrations become **one shared, configurable module, inbound and outbound**. An app picks an integration and configures it. Anything outside the building blocks is a coded extension for that customer, never an app change.

## Split of duties

**platform-core (the module):** configured calls (method, address, header and body templates, response mapping); field mapping by platform-fields keys (e.g. `contract_number` → `loanId`); the sign-in bundle; files in and out; common plumbing. It makes **one attempt** and reports a classified result: **sent / already received / refused with code / sign-in failed / can't connect / busy**. Health check per destination. **No product's API ships here** (P030): an API's owner publishes a profile (`platform-integration-profile/1`, `docs/design/integration-profile-format.md`) next to its spec, and the installation gives the file (`profile.ProfileFolder`). platform-core's own tests use made-up profiles only.

**Each app keeps its workflow.** For example, a redaction app: export job and rules (pause when the health check fails, 5 min–12 h retry schedule, one rebuild, "Waiting for you", IT queue, screens, retention per destination); intake rules; its own push API specification (callers' sign-in checked with the shared bundle); the manifest's app section (case fields, file list with checksums, redaction status, sensitivity levels).

## Catalog

| Area | V1 (build now) | Later (when a customer asks) | Extension (one customer) |
|---|---|---|---|
| Intake | Push API (each app's own; shared sign-in check); polling an API (with paging); watched folder / network share, trigger file last (XML, JSON or .lck, per customer) | SFTP pickup (likely early); S3 / Azure Blob / GCS; SharePoint / OneDrive; email inbox incl. fax-to-email (spam, malware, sender checks) | Message queue (Kafka, IBM MQ, RabbitMQ) |
| Export | API push; folder / network share with a manifest JSON written last (common envelope + app section) | SFTP drop (likely early); cloud storage; DMS profiles (SharePoint, Box; iManage, NetDocuments) | Message queue notice |
| Not offered | Email export | | |
| Sign-in | None (internal network, on-prem only, off by default, warned, audited, on the compliance report, never hosted); shared secret (user+password, or API key in a header, never in the address); fixed bearer token; OAuth2 client credentials / JWT; mutual TLS (certificate uploaded, stored encrypted, shown only as subject/fingerprint/expiry, expiry warnings, replacement without downtime; or a server keystore); HMAC-signed requests for callbacks | Private-key JWT; Windows sign-in (Kerberos/NTLM); SSH keys (SFTP); cloud access keys / managed identity | |
| Packaging | Multipart (documents + JSON); two steps, create then upload (as a document vault's API does); one file per call with details in headers | JSON with base64; ZIP bundle; XML / SOAP incl. MISMO (likely early) | |
| Behaviours (V1) | 202 accepted then status check or callback; paging; 429 / Retry-After; customer's outbound proxy; private certificate authority; request IDs and checksums; size limits and timeouts per integration | | |

## Safety

Fixed building blocks, no free scripting; secrets only in the secret store; no document content or secrets in URLs, templates or logs; protection against cloud-metadata addresses (internal addresses allowed on-premises); every configuration change audited and versioned.

## Testing

A **Test** action with fake data on every integration (API push, folder export, callbacks). For API polling and watched folders the Test is `TestMode.CHECK_ONLY` (their default): the configured HTTP health check, when there is one, and the sign-in; nothing is fetched, claimed or handed to the app (`INT_TEST_CHECKED` when both pass). A `FULL` test of these kinds gives `NOT_CONFIGURED` / `INT_KIND_NOT_SUPPORTED`. A watched folder has no sign-in and no HTTP health check, so its Test says nothing about the folder; its health check (`WatchedFolder.health`) does. **Contract tests** (P030): platform-core's build checks the format, the engine and the mocks with **made-up profiles only**; no product's spec or profile is pinned here. The **contract kit** `ProfileContract` (testkit: `checkAgainstSpec`, `checkAgainstServer`) is for an API's owner, who runs it in their own build against their published profile and spec. Live-system integration tests are marked to run only on request.

**Mock destination server (owned by platform-core, built with P022/P023; spec mode since P030):** in spec mode (`MockDestination.specMode(OpenApiSpec, SpecBehaviour)`) it is built from **any** OpenAPI file it is given, so it can't drift from that spec (`ProfileMock` does the same from a profile and its OpenAPI file); switches per test or by a small admin call: working, down, slow, busy (429 + Retry-After), unavailable (503), refuses with a code (the API's own codes), already received (200 replay), sign-in refused, health failing while uploads work and the reverse. Records what it received (headers, JSON field names, checksums; never document text). Also a mock folder/share (`MockFolder`, can be unwritable or slow) and a mock source API for polling intake (`MockSource`). Apps use them in their own tests.

## What platform-core already has (checked 2026-11-21)

| Need | Already there |
|---|---|
| HTTP sending with specific failure codes, 429 / Retry-After, timeouts, idempotency keys | platform-notifications channels and outbox (reusable plumbing; integrations report one attempt, the app keeps retries) |
| OAuth2 client credentials token fetch and cache | platform-notifications `MicrosoftTokens` (to generalise) |
| HMAC-signed, expiring tokens | platform-notifications `ActionTokens` (pattern for callback signing) |
| Secrets by reference only | `SecretResolver` everywhere |
| Field keys, types, delivery format key + type + value, receiving-side verifier | platform-fields (P017) |
| Requirements: virus scan (optional), credentials / mTLS / rotation / expiry warnings, delivery integrity, callback, health and diagnostics, API standard | `integration-standards.md` rounds 1–2 (requirements only, not designed) |

**How the table was used (P022 B decision 2, P028):** integration did **not** reuse the notifications plumbing, so as not to pull in the notification engine. It copied the small shared pieces (Retry-After, no redirects, status mapping) and built its own generic token client (`signin.ClientCredentialsTokens`). Since P028 that HTTP code lives in `platform-outbound` (section "P028" below), which integration and identity both use. Only `SecretResolver` / `SecretRef` and `RetryPolicy` still come from platform-notifications.

**Missing (to build):** the configured-call engine and templates, response mapping, field mapping to external names, packaging modes, 202 + status/callback, paging, outbound proxy and private CA, cloud-metadata guard, "None" and bearer sign-in, mTLS certificate upload and encrypted storage, per-destination health and Test action, configuration audit and versioning, folder export with manifest last, API polling intake, watched-folder intake with per-customer trigger files, the profile format and its contract kit (first planned as one product's profile and contract tests; replaced by P030).

**Prerequisite found:** encrypted storage (mTLS certificates here, sensitive case-field values in P019) needs the **secrets and encryption module** (capability 6: move a document vault's crypto code — Vault client, envelope encryption, keyed fingerprints) **first**.

## A document vault's ingestion profile: decisions (2026-11-21, the app threads, owner; profile parts replaced by P030)

1. **Pinned contract** (replaced by P030). The plan was for platform-core to keep its own copy of the vault's OpenAPI file, pinned at the vault's release tag, with contract tests against that copy. P030 removed the copy, the profile and those tests: no product's spec or profile is kept here. The API's owner publishes the profile next to its spec and runs the contract kit (`ProfileContract`) in its own build.
2. **A claim the vault called "tenant" means the entity (`ENT-…`)**, not the customer; the vault renames it "entity".
3. **Lookup, not custom claims.** A receiving app finds the entity and source from the verified client ID via `MachineEntityBindings` (one credential per integration per entity). Tokens need only the standard audience and subject; any entity or source claims present must match the binding or the call is refused. Since P028 this is identity's `push.PushCaller` (`docs/design/setup.md` section 8). *(Target state. Updated 2026-11-22: the vault's release then still required custom claims; a profile can support both, see "Decisions on the P022 proposal", item 1.)*
4. Sign-in: OAuth 2.0 client credentials; health: the API's readiness address. Since P030 these are data in the owner's published profile, not here.

## Registering integration client IDs (app thread decision, 2026-11-21)

Applies to every app's own push-API callers.

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
| P018 | **platform-crypto**: move a document vault's crypto code (read-only source) — Vault client, AES-256-GCM envelope encryption, keyed fingerprint (HMAC) for exact-match search, key-service plug-in point |
| P019 | Case field **values**: encrypted sensitive values, fingerprint search, primary uniqueness |
| P020 | **Per-entity time zone** — done |
| P021 | **Erase log** (shared) and **customer key destruction** (`docs/design/erase-log.md`) |
| P022 | Integration: **read-only investigation and proposal** — an earlier app's intake/export code, its live-run findings, a document vault's real ingestion API, integration-standards.md; module layout and task split |
| P023+ | Integration build in the order P022 proposes: core (config model, audit and versioning, sign-in bundle, safety, classified result, health, Test) → outbound API push + packagings + field mapping + profile and contract tests (made generic by P030) → folder export with manifest → intake (API polling, watched folder with trigger file, virus scan hook) → callback and push-API sign-in check |

The apps move their intake and export onto it in their own switch-over work.

## Decisions on the P022 proposal (app thread, master, 2026-11-22)

1. **Sign-in of the vault's callers:** the receiving app builds the client-ID lookup (`MachineEntityBindings`) **before the first install**. A profile can support an API's custom entity and source claims now and the lookup later (configuration switch, no code change).
2. **Busy vs outage:** **busy** = a 503 with the API's busy code (plus Retry-After when sent): wait, send fewer, **no attempt used**. A 503 **without** that code is an **outage** (health check, then pause or the case's own retries). A sign-in code is a **sign-in fault**, not busy. The generic engine expresses this as profile rules (status + code → result class), never product code.
3. **Trigger file:** the requirement is the app's. Split: **platform-core watches, reads and waits** (folder stability, trigger file last, timeout for incomplete drops); **the app interprets** the trigger file's content.
4. "Already received" is a 200 replay; a 409 means the same key with different content (an earlier app's design text was corrected).
5. Profiles are **data** named after the published API. *(Replaced by P030: no profile and no spec copy is shipped or pinned in platform-core; the API's owner publishes them.)*
6. **Order approved:** P023 core + mock server → P024 packagings, field mapping, integrity, profile, spec mode, contract tests → P025 Spring + folder export with manifest → P026 mTLS + callbacks → P027 intake (polling, watched folder, virus scan hook) → P028 push-API sign-in check + client-ID bindings in setup. Full proposal: `docs/work/P022-report.md` sections B1–B8.

## Decisions on the P023 report (shared-capabilities thread, 2026-11-22)

P023 accepted: 897 tests, 3 TLS tests skipped on the owner's laptop (Norton re-signs loopback TLS). Its decisions 1–10 stand, including: generic default 5xx = outage, 408/429 = busy; 404 without a destination code = `NOT_CONFIGURED` / `INT_ADDRESS_NOT_FOUND`; a timeout after sending = `CANT_CONNECT` / `INT_TIMEOUT`, resent with the same idempotency key; the configuration is re-checked at every attempt.

1. **DNS rebinding:** accepted for V1 (check every resolved address right before each call; not pinned). P025's install guide states it. **Strict pinning is built in P026** on the socket layer it builds for mTLS, and is required before the hosted service goes live.
2. **Skipped TLS tests:** no repo change. They must pass on a machine without TLS interception (CI or another machine) before a release that changes `safety/` or `http/`. A Norton exclusion on the laptop is the owner's choice.
3. **Proxy sign-in:** built in P025 with the Spring properties. The proxy password comes by secret reference. The library never sets JVM properties. *(Replaced by P026: the JVM start option `-Djdk.http.auth.tunneling.disabledSchemes=` is no longer needed, because Apache HttpClient sends the Basic sign-in on CONNECT itself; see "Client certificates, pinning, callbacks" below.)*
4. **503 default stays outage.** A destination that sends 503 + Retry-After to mean busy gets a profile rule. The configuration screen (P025) offers "503 with Retry-After means busy" as a ready-made rule.
5. **`SecretResolver` from platform-notifications** accepted for now. When a third module needs it, it moves to a small shared module (no task yet).

## Busy vs down at the health check (app thread, master, 2026-11-22)

No counting of failed checks. A profile's health rules say, as data, that a readiness answer of **503 with the API's busy code or with `Retry-After` = busy**: wait, send fewer at once, no pause, no attempt used. **Can't connect, a timeout, or a 503 without the busy code = down**: pause and probe. A busy spell over 30 minutes raises one IT notice (app side). Built in P024: health rules may map to BUSY generically (`HealthResult.busy()`, `Rule.healthBusyWhen503WithRetryAfter()`).

**Update (master, 2026-11-22):** P024 pinned one product's spec at a commit, to be re-pinned at its release tag, with its busy and outage codes in the profile. *(Removed by P030: nothing is pinned here; such codes are rules in the owner's published profile.)*

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

**Proxy sign-in** ("Decisions on the P023 report", item 3): the proxy's user and its password, by secret reference (read at every call), go as `Proxy-Authorization: Basic …`. That header is sent on the CONNECT of an https call and on plain-http requests through the proxy, never to the destination. The library never sets JVM properties. A 407 from the proxy gives `INT_PROXY_SIGN_IN_REFUSED`. *(As built in P025, an https call through a proxy with sign-in failed before connecting with `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` while the JDK disabled Basic for tunnels. Replaced by P026: Apache HttpClient sends the sign-in itself, so the JVM option no longer matters; P027 removed that code.)*

**"Omit when missing"** (P024 question 1): one more fixed building block in JSON body templates. A placeholder written `{name?}` that is the whole value of an object member (`"loan":"{field:loan_number?}"`, `"amount":{field:loan_amount?}`) leaves out that whole member, with its comma, when it has no value. A value that is present but cannot be placed is still refused. Anywhere else it is refused at save (`INT_CONFIG_OMIT_PLACE`). Still no expressions.

**Ready-made rule** (item 4): `Rule.busyWhen503WithRetryAfter()` (and `Rule.healthBusyWhen503WithRetryAfter()` for health rules) is the rule the configuration screen offers as "503 with Retry-After means busy".

## Decisions on the P025 report (shared-capabilities thread, 2026-11-22)

P025 accepted (1048 tests on CI, 0 skipped; the test-count question was a counting error in P031's report, not a double run). Its decisions stand, including: manifest "identical" ignores `createdAt`, `correlationId` and `integration.configVersion`; sensitive fields only when allowed and never in names; folder export refused on the hosted service; proxy sign-in sent preemptively; health cached 60 s.

1. **`doc.attr.*` stays in the app section** of the manifest; the envelope stays stable.
2. **A full disk (`INT_FOLDER_FULL`)** should raise one IT notice at once: app side (passed to the app threads on the board).
3. **The `integrations` health contributor** is revisited in P032 (monitoring): there, each destination is reported as Working / Having problems / Down with its cause, and the app's own health and readiness never go DOWN because another system is down. *(Outcome, P032: with platform-monitoring-spring the entries are monitoring's `dependencies` checks and the `integrations` contributor is not made; see "Decisions carried from the reports", item 7.)*

## Client certificates, pinning, callbacks and "accepted, check later" (P026)

**HTTP client: Apache HttpClient 5 (5.3.1, the version Spring Boot 3.3 manages).** The JDK client cannot take a resolver of our own, so a connection could not be held to the checked address. Apache's client resolves names only through our `DnsResolver`, which returns the address the guard checked for this call (the first one that passed), and refuses any other name; host name verification (`DefaultHostnameVerifier`) and SNI stay on the original name. `Outbound`'s API is unchanged (`Call`, `Answer`, `Failure`, the codes). Proxy sign-in is sent preemptively on CONNECT and on plain-http requests by the client itself, so `jdk.http.auth.tunneling.disabledSchemes` no longer matters: `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` is no longer given (the code stays, so stored results can be read), the startup warning and the separate test JVM are gone. Redirects, retries, cookies and compression are switched off in the client. Its own wire log (`org.apache.hc.client5.http.wire`, `.headers`) must never be at DEBUG: the install guide says so.

**Mutual TLS** is sign-in type `MUTUAL_TLS` (`SignIn.MutualTls(with, certificateForToken)`): alone, or with Basic, API key, bearer or OAuth 2.0, never with NONE. The certificate is not in the configuration but the integration's **credential** (`credential.IntegrationCredentials`), so rotating it is not a configuration version and needs no new Test of the whole configuration. Each certificate has its own HTTP client (connections are never shared between certificates); the key manager always offers the integration's certificate, so a server that does not trust it refuses it with a TLS alert (`INT_CREDENTIAL_REFUSED`) instead of the client sending none.

**Storage.** Uploaded: the chain and the PKCS#8 key as PEM, sealed with `ValueCipher` under the entity's customer (context `integration-credential:<integration>:<credential>`); the upload's password is never kept. Keystore: path, alias and password reference; read for each use, a certificate renewed in the file is recorded (`KEYSTORE_RENEWED`). `int_credential` holds at most one CURRENT and one NEXT per integration (unique partial indexes); a RETIRED row keeps its certificate details but its sealed key is dropped. Every change is numbered per integration in `int_credential_change` (frozen by trigger) and goes to `IntegrationAudit.credentialChanged` (a default method: apps built before P026 compile unchanged). A NEXT that is not valid yet, or expired, cannot be switched to; a timed switch then waits and is logged.

**Expiry warnings.** `CredentialExpiry.check(now)` returns each CURRENT and NEXT certificate at the smallest threshold it has reached (30, 7, 1 days by default) or expired; the app keeps which threshold it already told and raises its own IT notice. An expired CURRENT refuses every call with `INT_CREDENTIAL_EXPIRED` (SIGN_IN_FAILED) before anything is sent.

**Callbacks.** Kind `CALLBACK`: the receiver address (the full address), a sign-in (NONE is allowed for this kind everywhere, the signature proves the sender), limits, rules and `CallbackTarget(signingSecret)`. `Platform-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>` and `Platform-Callback-Id` (the outbox entry's UUID, the same for every attempt). Outbox `int_callback_outbox` as in notifications: claims with `FOR UPDATE SKIP LOCKED` and a lease; back-off with the notifications `RetryPolicy` (1 min doubling to 6 h, given up 24 h after creation or the last resend), a Retry-After replaces the back-off up to the longest delay. SENT and ALREADY_RECEIVED are done; REFUSED gives up at once (the receiver said no); everything else is retried until the window ends. The body must be one JSON object of at most 64 KiB; the platform cannot tell document content from a JSON value, so "never document content" is the app's rule, stated in the API. `CallbackVerifier` checks the signature (constant time, every pair compared), then the time window, then the ID; an ID is recorded only after the first two pass. The Spring `IntegrationWorker` sends due callbacks and makes timed certificate switches.

**Accepted, check later.** `CallStep.Accepted` only on the last step, run once (a later step would need the work done; resuming after a 202 is not built). A 202 classified as SENT on such a step ends the attempt with `Outcome.ACCEPTED` (no attempt used) and a `PendingStatus` the app stores (`toJson`/`fromJson`); a 202 on a step without the declaration is SENT as before. `checkStatus` makes one call: a 202 or a "working" state is ACCEPTED again, "done" is SENT (with the values read), "failed" is REFUSED `INT_STATUS_FAILED`, any other state UNCLEAR `INT_STATUS_UNKNOWN`; a non-2xx status answer is classified by the step's and the integration's rules. A Location reference must stay on the base address's scheme, host and port. A rule can never give ACCEPTED. The profile format gets `steps[].accepted` and the sign-in type `MUTUAL_TLS` (additions only), and `ProfileContract` checks the 202 answer and the status operation.

## Decisions on the P026 report (shared-capabilities thread, 2026-11-22)

P026 accepted (1085 tests on CI, 0 skipped). Its decisions stand, including:
- Apache HttpClient 5 for connection pinning;
- the certificate is the integration's credential, with its own history;
- a retired certificate keeps its details but not its key;
- callbacks: REFUSED gives up at once, everything else is retried;
- "accepted, check later" only on the last step;
- a refused client certificate under TLS 1.3 is identified by one extra TLS 1.2 handshake.

1. **`INT_PROXY_SIGN_IN_DISABLED_BY_JVM` and the `JvmProxySignIn` constructor are removed in P027.** They were never released, and nothing is in production, so no old code lines.
2. **Switching a certificate drops the held token at once** (P027).
3. **Expiry notices are app side:** the wording, and remembering (credential ID, threshold) so each warning is sent once. Passed to the app threads on the board.

## Intake: API polling, watched folder, virus-scan hook (P027)

**Split.** platform-core finds, watches, reads, waits, claims, scans and hands over; the app decides what the content means (levels, identifiers, document types, what a missing or extra file means) and keeps its own intake rules. One pass per call; the app keeps the schedule and, for polling, the cursor. Both kinds hand each item or drop to the app's `intake.IntakeHandler`, which answers `TAKEN`, `ALREADY_TAKEN` (its own idempotency, by the source's ID) or `REFUSED(code)`; an exception is a failure of the app (nothing acknowledged or archived, tried again at a later pass). The files' streams (`intake.IntakeFile`: name, size, SHA-256) check themselves at their end and are valid only during the handler. Nothing new is stored in the database: the configuration is versioned as before, the cursor is the app's, drops and claims live in the folder.

**API polling** (kind `API_POLL`, `config.PollSource`, run by `engine.PollEngine.pass(stored, cursorState, handler)`): a list call, a download call per item and an optional acknowledge call per item, from the same building blocks as outbound (`CallStep`: method, path, header and JSON body templates, rules), through the same `CallEngine` sign-in, certificate, address guard, pinning, proxy, limits and classification. New placeholders `{item.id}` and `{item.<name>}` in the per-item calls only (refused elsewhere, `INT_CONFIG_PLACEHOLDER_PLACE`). Paging NONE, CURSOR (pointer + query parameter), NEXT_LINK (pointer or the `Link` header; same scheme, host and port only) or PAGE_NUMBER, page size and pages per pass; the engine adds the paging parameters to the query string (encoded), never a template. `resume` CONTINUE (the next pass starts where this one ended; at the end, the last page is read again) or FROM_START (a queue whose items leave once acknowledged). Per item, in order: ID and values by pointers (no ID: reported, left alone); the download streamed to a temporary file in the work folder with the size limit and SHA-256 (`Outbound.BodySink`: a 2xx body goes to the sink; stopping early drops the connection instead of draining it); a refused download (REFUSED) or a file over the limit is reported and left alone; the scan; the handler; the acknowledge only after TAKEN or ALREADY_TAKEN. Anything else (sign-in, busy, outage, no connection, unclear, the acknowledge, a scan that failed, the handler) stops the pass with the usual classified outcome, the step (`list`, `download`, `acknowledge`, `scan`, `handler`) and the item; the next state is then the position after the last page fully handled (`intake.PollResult`). Profiles: `poll` in format `/1` (additions only), `MockSource.forProfile`.

**Watched folder** (kind `WATCHED_FOLDER`, `config.WatchSource`, run by `watch.WatchedFolder.pass(stored, handler)`): an absolute local or share root; drops as a subfolder each or as the root's files grouped by base name (up to the first dot); a trigger pattern (`*`, `?`, `<base>`) and type (JSON, XML or MARKER, by default from the extension); stable for N seconds (every file's time at least that old, or the same sizes and times seen by this instance that long ago); incomplete after N minutes (reported once per drop by this instance, nothing moved); claim time; drops per pass; trigger size. A pass releases stuck claims, finds drops, and for each: claim by one atomic rename into `_processing/<drop>-<fence>` (base-name layout: the trigger's move decides; a claim marker holds the fence and time; a rename that fails while the source is gone is a lost claim, which is also how Windows answers the loser), check (no link or folder, limits, one trigger, each file's real path inside the root), SHA-256 and sizes, the trigger through the hardened parser and the fixed mapping (`watch.TriggerValues`: JSON pointers with `*` for every list element; XML paths of element names from the document element, `[n]`, `@attribute` last), the scan, the handler, and the move: `_archive` on TAKEN or ALREADY_TAKEN, `_problem` with `problem.json` (`platform-watch-problem/1`: drop, claim, code, the app's code, time; never content) on REFUSED or a problem, `_quarantine` for an infected file. A handler failure puts the drop back and stops the pass. With a file list in the mapping the app gets the unlisted files and the names without a file. Parsers: UTF-8 only, size and depth limits (JSON 32, XML 64), no DTD (so no entities), no external access, no XInclude. Refused on the hosted service (`INT_CONFIG_FOLDER_HOSTED`).

**Virus scan** (requirement 11, `scan.FileScanner`, `scan.Scanning`): `ClamdScanner` (`zINSTREAM` in 64 KiB chunks) and `IcapScanner` (`RESPMOD` with `Allow: 204`; 204 clean, 200 or a threat header infected), both through the address guard (internal on premises) to the checked address. Off without a scanner. Every incoming file is scanned before the app sees it, a watched drop's trigger too. INFECTED: never handed over, polling does not acknowledge, a drop goes to `_quarantine`, `ScanEvents.infected` tells the app. SCAN_FAILED (`INT_SCAN_FAILED`, `INT_SCAN_UNREACHABLE`): held, the pass stops, scanned again at the next one. Health: clamd `PING`, ICAP `OPTIONS`. The testkit's `MockScanner` speaks both; `Eicar` builds the test file at run time, so no file of the build holds the signature, and tests that write it to disk skip where a desktop scanner removes it (CI forbids skips).

**Spring.** `platform.integration.scanner.*` (type NONE / CLAMD / ICAP, host, port, ICAP service, timeout) and `intake.work-folder`, checked at startup; beans `FileScanner`, `Scanning` (with the app's `ScanEvents`), `PollEngine`, `WatchedFolder` (the app may give `WatchFiles`); the `integrations` health contributor covers sources and has a `scanner` entry. With platform-monitoring-spring that contributor is not made: each integration is a check in monitoring's `dependencies` (P032, decision 7 of the list below).

**P026 follow-ups.** `INT_PROXY_SIGN_IN_DISABLED_BY_JVM`, `safety.JvmProxySignIn` and the `Outbound(..., JvmProxySignIn)` constructor are removed. A held OAuth 2.0 token is bound to the fingerprint of the client certificate in use when it was fetched: after a switch (by the admin, at its set time, or on another instance) it is never used again and a new token is fetched.

## Decisions on the P027 report (shared-capabilities thread, 2026-11-22)

P027 accepted (1141 tests on CI, 0 skipped). Its decisions stand, including:
- a refused download or an item without an ID is reported and the pass goes on, while everything else stops the pass with the cursor kept;
- a failing handler puts the drop back;
- the stability rule has two ways to pass (the files' times, or the same sizes and times seen before);
- the trigger is scanned too;
- ICAP treats "unsure" as infected;
- the token is bound to the certificate in use.

1. **Scanning on the hosted service:** startup on `HOSTED` refuses a missing scanner. This goes in the hosted go-live work, not now.
2. **ICAP over TLS:** built when a customer's scanner needs it.
3. **Incomplete drops** are reported once per app instance; the app de-duplicates by drop ID before an IT notice (passed to the app threads).
4. **Refused downloads** are in `PollResult.problems` for the app to show IT. A source that wants retries gets a rule mapping its status to OUTAGE.

**Note (board, 2026-11-22):** the outbound safety code becomes its own module, `platform-outbound`, in P028, so identity's test sign-in uses the same rules without `platform-integration`. Integration depends on it; its behaviour and result codes don't change.

## P028: the outbound module, push callers (built 2026-11-22, `docs/work/P028-report.md`)

**`platform-outbound`** (plain Java, package `com.documentplatform.outbound`) now holds everything that makes a safe
outbound HTTP call: `Installation`, `AddressGuard` (with the pinning resolver), `TrustBundle`, `ProxySettings`,
`Outbound` (Apache HttpClient 5 set-up, no redirects, timeouts and answer limit per call), `RetryAfter`,
`ClientCertificate` and `CertificateInfo` (mutual TLS needs them in the client). Failures are `OutboundCode`
(`OUT_*`). Integration maps them with `IntegrationCode.of(OutboundCode)` to the `INT_*` code of the same name, so no
integration result changes. Moved packages: `integration.safety.*`, `integration.http.Outbound`,
`integration.http.RetryAfter`, `integration.credential.ClientCertificate` / `CertificateInfo` →
`com.documentplatform.outbound`. `config.Limits` stays in integration: it holds delivery limits (documents,
package); the outbound limits are parameters of each call.

**Dependencies.** `platform-integration` depends on `platform-outbound` and no longer on `platform-identity-core`.
The entity slot rule it used (`EntityUuid`) is a small copy, `folder.SlotIds`, checked against identity by a test.
`OutboundIssuerFetch` moved to `platform-identity-spring` (package `identity.spring`); identity-spring depends on
`platform-outbound-spring`, which brings the HTTP client (the apps have it through Spring Boot anyway), so no
separate identity-outbound module.

**Spring.** `platform-outbound-spring` reads `platform.outbound.*` (installation, internal addresses, sign-in NONE,
private CA file, proxy and its sign-in) whenever any of them is set, and makes the `Installation` and `Outbound`
beans (`OUTBOUND_SETTINGS_INVALID` with every problem listed). Identity's and integration's Spring modules both use
them. The old `platform.integration.installation`, `.allow-internal-addresses`, `.allow-sign-in-none`,
`.private-ca-file` and `.proxy.*` stop the app naming the new property (never released). `allow-sign-in-none` moved
too, because it is a field of `Installation` that the call engine reads through `Outbound.installation()`.

**Codes that moved.** The outbound failures `INT_CANT_CONNECT`, `INT_HOST_UNKNOWN`, `INT_CONNECT_TIMEOUT`,
`INT_TIMEOUT`, `INT_CONNECTION_LOST`, `INT_TLS_FAILED`, `INT_ADDRESS_BLOCKED`, `INT_ADDRESS_NOT_SECURE`,
`INT_PROXY_REFUSED`, `INT_PROXY_SIGN_IN_REFUSED`, `INT_CREDENTIAL_REFUSED`, `INT_SECRET_NOT_FOUND`,
`INT_SECRET_UNAVAILABLE` and `INT_VALUE_NOT_ALLOWED` now start as `OUT_*` in `platform-outbound` and are mapped back
by integration; integration results keep the `INT_*` codes. The identity test sign-in's refusal reason
(`TestReport.reason`) is now the `OUT_*` code.

**Push callers** are identity's (`identity.push.PushCaller`, `docs/design/setup.md` section 8), not integration's
`integration.inbound.PushCaller` as the P022 split had it: the answer comes from identity's tables only.

## Decisions on the P028 report (shared-capabilities thread, 2026-11-23)

P028 accepted (v0.3.0). 1. Platform grants on the IT Admin's page: app side, read-only first. 2. The app's integration page calls `PushCaller.forget()` when it switches a binding off: app side, when that page is built. 3. A certificate-only caller takes entity and source from the binding alone; a product needing more sets `requireTokenAndCertificate`.

## Decisions carried from the reports (P023–P032)

These decisions were made in the build reports and stand. They are recorded here so the design says what the code
does.

1. **No product's profile, spec or contract tests in platform-core (P030).** The independence rule removed the module
   `platform-integration-profiles` with one product's profile, its pinned spec, its mock and its contract tests. What
   was generic moved into platform-integration (the profile format, `ProfileLoader`, `ProfileValidator`,
   `ProfileFolder`, `json.JsonSchema`) and the testkit (`MockDestination.specMode`, `ProfileMock`, the contract kit
   `ProfileContract`). Tests use made-up profiles only. Older sections above that speak of one product's profile or
   a pinned spec are history; this line replaces them.
2. **"Already received" for the whole attempt only when every call was a replay (P023 decision 6).** When some calls
   were new and some replays, the attempt is SENT, and each call's own outcome is in `steps`.
3. **History times are kept to the microsecond (P023 decision 9),** as Postgres keeps them, so the stored history and
   the audit give the same times.
4. **A sensitive case field goes in JSON bodies only (P024 decision 3),** never in a header or a path: addresses and
   headers end up in proxy and server logs. It is checked at save (`INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED`, with
   `allowedSensitiveFields` and `FieldLists`) and again at send (`INT_SENSITIVE_FIELD_NOT_ALLOWED`). Folder and file names never carry one, even
   when allowed (P025).
5. **The mock destination records JSON field names only (P024 decision 6);** values only after `recordValues(true)`.
6. **The required-claims check reads the token for presence only (P024 decision 7):** the JWT payload, not verified
   (that is the receiver's job); it is never kept or logged (`INT_TOKEN_CLAIMS_MISSING`).
7. **`integrations` health and monitoring (P032 decision 5).** With platform-monitoring-spring on the classpath,
   integration-spring's `integrations` contributor is not made; each enabled integration is a check
   `integration-<id>` in monitoring's `dependencies` and never counts for readiness (`docs/design/monitoring.md`
   section 2). Without monitoring the contributor stays.
8. **`ProfileFolder` is in the core module (P025 decision 8),** so apps without Spring have it too; the Spring bean
   only wraps it.
9. **Uploading a certificate while a NEXT exists is refused (P026 decision 6)** with `INT_CREDENTIAL_NEXT_EXISTS`; IT
   discards the NEXT first. This keeps "at most two" simple and visible.
10. **One customer per installation in the Spring wiring (P026 decision 12):** `IntegrationCredentials` uses the
    app's `CustomerId` bean. An installation that serves several customers defines its own `IntegrationCredentials`
    bean with its entity-to-customer function.
11. **A file that changed while the handler ran goes to `_problem` (P027 decision 4)** with `INT_WATCH_FILE_CHANGED`.
    Any other handler failure puts the drop back (so a database outage does not send every drop to `_problem`).
12. **`ClientCertificate.key()` and `chain()` are public (P028 decision 5),** because `Outbound` presents the
    certificate from another module; only `CredentialFiles` uses them, to seal the stored form. They are never logged.
13. **The User-Agent stays `platform-integration` (P028 decision 6)** after the move to platform-outbound, so
    destinations see no change.
14. **Fallback beans in integration-spring (P028 decision 13):** a fallback `Outbound` bean for an app that defines
    only its own `Installation`, and a fallback `Installation` bean that fails with integration's problem list when
    no `platform.outbound.*` setting is given, so the missing `platform.outbound.installation` is named whichever bean
    asks first.

## Intake IDs in log lines (P037)

A watched-folder drop's ID is the customer's own file or folder name, and a polled item's ID is the source's; either
may hold personal data (a person's name, an account number). Log lines therefore never carry them: they carry a
short stable reference instead, `drop=#<hex>` / `item=#<hex>`, where `<hex>` is the first 12 hex characters of the
SHA-256 of the ID (`intake.LogRef.of`). The same ID always gives the same reference, so support can match a log line
to a drop or item from its real name. The real name stays where the customer and the app see it: `problem.json` in the
customer's own `_problem` (or `_quarantine`) folder, `WatchResult` / `PollResult`, `ScanEvents.Infected` and the
handler's `Drop` / `PolledItem`, which the app must not log as they are either.
