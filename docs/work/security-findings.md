# Security findings — independent review (P042)

Date: 2026-11-23. Code: `main` at `7a54068` (0.7.0-SNAPSHOT; released up to `platform-core-v0.6.0`).
Reviewer: an independent security review (Claude Code), read-only; no product code changed.

How to read this:
- **Severity**, in plain words:
  - **Critical**: exploitable now, with serious harm.
  - **High**: exploitable by a realistic attacker or mistake, with serious harm.
  - **Medium**: real harm, but it needs a precondition (write access to a share or the database, or a set-up mistake), or it breaks a stated safety promise.
  - **Low**: limited harm, or an unlikely precondition.
  - **Info**: hardening, not a flaw.
- **Proof** says how each finding was shown:
  - *ran*: executed on this machine;
  - *read*: confirmed by reading the code, with the steps for a test;
  - *read ×2*: found independently by two reviewers.
- **Size**:
  - **S**: under a day.
  - **M**: a few days.
  - **L**: a week or more.
- Paths are shortened with `…`, which stands for `src/main/java/com/documentplatform/<capability>`.

Counts: **Critical 0 · High 1 · Medium 14 · Low 26 · Info 18** (59 findings).

## Findings

### High

| ID | Area | Title | Severity | Where | What an attacker or a mistake can do | Proof | Proposed fix | Size |
|---|---|---|---|---|---|---|---|---|
| SEC-1 | 8 Integration (folder export, watched folder) | **FIXED in P043 (e4317b1)** `LocalFiles.delete` follows Windows junctions and deletes what they point to | High | `platform-integration/…/integration/folder/LocalFiles.java:111-131`; called from `FolderExport.java:320,332,446` and `WatchedFolder.java:145,454,695,709,714` | Anyone who can write to an export share or a watched folder can delete any file or folder the service account can delete: app data, the erase-log folder, configuration. Two ways in:<br>• plant a junction named `<package>.partial`, a predictable name, which is deleted before every attempt;<br>• swap `.platform-claim` in `_processing/…` for a junction.<br>On Windows a junction passes `isDirectory(NOFOLLOW_LINKS)`, and `walkFileTree` enters it. | *ran*, on this Windows machine, with an exact copy of `delete`:<br>1. Make `victim\keep.txt`.<br>2. `mklink /J export\P1.partial victim`.<br>3. Call `delete(export\P1.partial)`.<br>Result: `keep.txt` is gone (`isDirectory(NOFOLLOW)` is `true` on the junction). | Read attributes with NOFOLLOW. For `isSymbolicLink() \|\| isOther()`, delete only the entry itself. In the visitor, add `preVisitDirectory`: on `attrs.isOther()`, delete the entry and return `SKIP_SUBTREE`. Add a Windows test with `mklink /J`. | S |

### Medium

| ID | Area | Title | Severity | Where | What an attacker or a mistake can do | Proof | Proposed fix | Size |
|---|---|---|---|---|---|---|---|---|
| SEC-2 | 4 Crypto, 2 Setup | **FIXED in P044 (3dcfeaa)** The set-up Vault policy can destroy key versions without `delete` | Medium | `platform-crypto/src/test/resources/vault/crypto-setup.hcl:3-5`; `docs/install/crypto.md:16-29` | `update` on `transit/keys/customer-*` is a prefix match, so it also covers `…/config` and `…/trim`. A set-up login can:<br>1. rotate the key;<br>2. set `min_decryption_version` / `min_available_version` to the latest version;<br>3. `trim`.<br>The older versions are then gone for good, and the data sealed under them is unreadable (crypto-shredding). That breaks the promise "the set-up policy alone cannot delete". The same login can also set `exportable` or `allow_plaintext_backup` on customer keys; neither can ever be switched back. | *read ×2*. Test against the Vault dev server (`VaultDev`), with a set-up-policy login: create, then rotate, then `POST …/config {"min_decryption_version":2,"min_available_version":2}`, then `POST …/trim {"min_available_version":2}`. All three succeed, and decrypting a v1 value fails. | Set-up policy:<br>• `create` and `read` on `transit/keys/customer-+` (exact segment);<br>• `update` only on `transit/keys/customer-+/rotate`;<br>• explicit `deny` on `…/trim` and `…/config` (the offboard policy gets `config` for `deletion_allowed`).<br>Add negative tests. Optionally `KeyServiceCheck` refuses an exportable customer key. | S |
| SEC-3 | 5 Erase log | **FIXED in P044 (0805b0c)** The erase-log chain has no key: someone who can write the log folder can forge erasures, or hide them together with a database restore | Medium | `platform-erase-log/…/eraselog/EraseEntry.java:64-73` (plain SHA-256); `file/FileEraseLogStore.java:102-147` (checkpoint not authenticated); `Eraser.java:79-81` (pending entries applied without `hashMatches()`) | The log lives on a share, outside the database.<br>• **Forge:** someone who can write `erase-log.jsonl` appends a line for any record, and the next `Eraser.erase` or a replay erases that record.<br>• **Hide:** with write access to both stores, restore the database and cut the log tail or the checkpoint back to the same point. The erasures made after the backup are then lost without a trace. | *read*. In `FileEraseLogStoreTest`: after `eraser.erase(a)`, append `Lines.entry(EraseEntry.link(logId, head, now, victim))` by hand, then call `eraser.erase(b)`. `RecordingEraseHandler` has applied `victim`. | Make the chain an HMAC with a key held in the key service (Transit `hmac`, like the fingerprints), or sign the head. Check `hashMatches()` on every pending entry in `Eraser` and `Applying`. Optionally keep a copy of the signed head in a second place. | M |
| SEC-4 | 5 Erase log | **FIXED in P044 (0805b0c)** A database restored while the app keeps running is never flagged as a restore | Medium | `platform-erase-log/…/eraselog/EraseGuard.java:146` (only `startup` records a restore); `refresh()` / `fullCheck()` at `:163-187`; `EraseLogWorker` | If the database is restored under running instances (an in-place restore, or a managed restore behind the same name), "applied < head" is read as "erase in progress". `mayTakeWork()` stays true, and erased records can be read and worked on until a restart or the next erase. | *read*. In a `RestoreAndReplayTest` style test with `InMemoryErasePosition`:<br>1. Erase 3 entries.<br>2. Snapshot at 1 and restore it.<br>3. Call `guard.refresh()` and `guard.fullCheck()`.<br>`mayTakeWork()` is still `true`. | In `refresh()` and `fullCheck()`, take the position lock with try-lock. If the database is behind while no erase holds the lock, and no recorded failure explains it, call `markRestoreDetected`. Alternatively, have the worker apply pending entries. | S–M |
| SEC-5 | 5 Erase log, 13 Public API | **FIXED in P044 (0805b0c)** Field values can be erased outside the erase log through public types | Medium | `platform-erase-log/…/eraselog/fields/CaseFieldsEraseHandler.java:34,47,79` (public constructor; `apply` ignores the context); `platform-fields/…/fields/values/FieldValues.java:250-257` (caller check by class name); `StoredRecord.java:37` (`with` is public); `PostgresValueStore.java:106-140` | Values can be wiped without a log entry in two ways:<br>• `new CaseFieldsEraseHandler(values, store).apply(EraseEntry.link(randomUUID, ChainPoint.START, now, req), null)`;<br>• `store.replace(r.with(null, null, now, List.of()))`.<br>After a backup restore the values come back, and `EraseGuard` cannot notice, because nothing was logged. This defeats the erase-log rule. (`EraseTicket` itself cannot be forged.) | *read ×2*. Call the handler directly as above. It returns `ERASED`, and `InMemoryEraseLogStore` is still empty. | `apply` accepts only a `Context` that `Eraser` / `EraseReplay` make (package-private), and checks the entry against the log. `ValueStore.replace` refuses to remove a record's last value or its envelope; make `with` package-private. | S–M |
| SEC-6 | 10 Model governance | The gate trusts `mg_live` without re-checking approval, or that the use and entity match | Medium | `platform-model-governance/…/modelgov/ModelGate.java:59-83`; `ModelGovernance.java:42` (`store()` is public); `ModelGovStore.switchLive` / `insertApproval` are public; the runtime user has UPDATE on `mg_live` (`docs/install/model-governance.md:24`); `V1__modelgov.sql:161-170` (single-column FK) | App code, or a direct database write, can point `mg_live` at a failed or unapproved validation, even one belonging to another use or entity. `requireLive` then passes and stamps results. `store.insertApproval(...)` also skips `checkApprover` and the forbidden roles. Separation of duties then depends on the app's good behaviour alone. | *read* (confirmed in `requireLive`: digest, configuration and location are checked; approval is not). `InMemoryModelGovStore` + `ModelGovFixtures`:<br>1. Record a failing validation.<br>2. Call `store.switchLive(...)`.<br>3. Call `gate.requireLive(...)`: it returns a `LiveModel`. | In `requireLive`, re-check the approval (and the acceptance for a failed customer model) and that `v.useId()` / `v.entity()` match. In SQL, add a composite FK `(validation_id, use_id, entity_uuid)` and a trigger that requires an `mg_approval` row. Narrow or remove `ModelGovernance.store()`. | M |
| SEC-7 | 1 Identity (push callers) | `PushCaller` admits a person's token as a machine source | Medium | `platform-identity-core/…/identity/push/PushCaller.java:164-218`; `PushCallerSettings.java:27` (`client_id, azp, cid, sub`) | A person's access token carries `azp` / `cid` naming the app they signed in through. If the bound client also allows user sign-in (common), or a browser client ID is bound by mistake, then anyone who can sign in through it is admitted as that entity's machine source. Nothing tells a client-credentials token apart from a person's. | *read*. `InMemoryIdentityStore` with an ACTIVE connection and a binding for `app-1`. Then `PushCaller.check(PushCallerInput.token(iss, [aud], {azp: app-1, sub: user-123, groups: [staff]}))` gives `allowed()`. | A machine-token rule, on by default: `sub` must equal the client ID, or a configured claim/value such as `idtyp=app`, and no groups claim. Drop `sub` from the default client-ID claims. Document it in `docs/install/identity.md`. | M |
| SEC-8 | 8 Integration (callbacks) | **FIXED in P044 (e8b531c)** The callback signature does not cover `Platform-Callback-Id`, so the replay check can be bypassed | Medium | `platform-integration/…/integration/callback/CallbackSignature.java:26-36` (`t + "." + body` only); `CallbackVerifier` (the `firstTime(id)` check) | Someone who sees one signed callback (a TLS-terminating proxy, a WAF log, the receiver's logs) can resend it within the 5-minute window under a fresh ID. The signature, window and replay checks all pass, so the receiver acts again on each one. | *read* (confirmed in `v1`). `h = header(s, t, body)`: `verify(h, id=A)` is ok, and `verify(h, id=B)` is also ok; it should be `INT_CALLBACK_REPLAYED`. | Add a `v2` HMAC over `t "." id "." body`, accept `v1` only during a stated changeover, and update the receiver steps in `docs/install/integration.md`. | S–M |
| SEC-9 | 8 Integration (watched folder, scan) | **FIXED in P043 (e4317b1)** The virus scan reads different bytes from the ones hashed and handed over | Medium | `platform-integration/…/integration/watch/WatchedFolder.java:557-576` | The claim folder is still writable by the sender. The sender can:<br>1. let the hash be taken of malware M;<br>2. swap in a clean file for the scan;<br>3. swap M back.<br>The app's self-checking stream then matches M's hash, and malware passes the "INFECTED is never handed over" rule. API polling is not affected. | *read*. A `WatchFiles` wrapper changes the content between the hash read and the scan read, and back afterwards; use `MockScanner` (EICAR is infected). The handler receives the EICAR bytes. | Scan through the same size- and SHA-checked stream (mismatch → `INT_WATCH_FILE_CHANGED`, drop to `_problem`), or copy the drop into the private work folder before hashing. | S |
| SEC-10 | 8 Integration (sign-in) | **FIXED in P044 (600d3da)** Secret references are not tied to the integration's customer or entity, and the token cache bypasses the resolver | Medium (High on a hosted service where admins type references) | `platform-integration/…/integration/engine/SignInHeaders.java:158`; `signin/ClientCredentialsTokens.java:82,113-115,158`; `CallEngine.java:876`; `ConfigCheck` accepts any well-formed reference | An admin of entity B can save an integration whose sign-in references entity A's secret and send it to a host B controls; the platform resolves A's secret. A cached token is also returned for an identical configuration without calling the resolver. | *read*. A resolver holding `int/A/secret`, plus a config for entity B whose token address is `MockDestination` and whose `credential` is `int/A/secret`. The mock records A's client secret. | Pass the entity and integration to the resolver (or have the app supply a `SecretScope`), check it in `ConfigCheck` and again at resolve time, and key the token cache by (entity, integration, client). | M |
| SEC-11 | 3 Secrets, 9 Monitoring | The diagnostic bundle copies secret property sources whole | Medium | `platform-monitoring-spring/…/monitoring/spring/DiagnosticBundles.java:56-72` (only `systemProperties` / `systemEnvironment` are skipped) | A config tree (`spring.config.import=configtree:/etc/secrets/`, the usual Kubernetes secret mount) or a Vault/Config property source goes into the bundle. A secret whose name is not secret-like, or whose value is JSON, is written in clear, and IT then sends the bundle to support. | *ran* (by the reviewer, against the built classes). A config tree with `dbcreds=S3cr3t…` and `vendor-json={"password":"hunter2"}`: both appear unchanged in `DiagnosticBundle.scrubbed()`. | Use an allow-list of sources: application files, `commandLineArgs`, `spring.application.json`. Never include a `ConfigTreePropertySource` or vault/secret sources. Better still, list only `platform.*`, `spring.application.*`, `server.*`, `management.*` and prefixes the app declares. Add a test with a config tree. | S–M |
| SEC-12 | 6 Licensing | The customer controls the licence trust root | Medium (commercial; no data exposure) | `platform-licensing-spring/…/licensing/spring/internal/LicensingConfig.java:31-72`; `docs/design/licensing.md:10` ("apps ship the public keys") | Trusted keys come only from installation config (`trusted-keys-file` or inline `trusted-keys`). An on-premises customer can generate a key pair, add the public key, and sign their own licence with any allowance. The same file can mark the key `"development": true`. Nothing stops a production release from trusting a development key. | *read* (confirmed). `SigningKeys.generate()`, then `LicenceSigner` signs a licence, then `LicensingConfig.from(props with only trustedKeys={id: pub})`, then `verify`: VALID. | Ship the production key set inside the release (a resource in the jar, or the SHA-256 of the file compiled in). Config may only narrow it. Refuse inline and development keys unless a build-time development switch is on. Say plainly in the design that on-premises licensing is a deterrent, not tamper-proof. | M |
| SEC-13 | 6 Licensing | The runtime database user can undo the clock-rollback rule and bring back an older licence | Medium (commercial) | `platform-licensing/…/licensing/jdbc/PostgresLastSeenStore.java:22-28`; `ClockGuard.java:48-52`; `V1__licensing.sql:14-50`; `LicenceService.java:73-78` | With DELETE/UPDATE on `lic_last_seen`, delete row 1 and turn the clock back, and an expired licence is VALID again. Deleting `lic_clock_reset` rows lets a reset be replayed. Writing an older signed licence back into CURRENT passes `verify`: there is no version floor on read. | *read*. `InMemoryLastSeenStore` at T+40 days, licence expired at T+10: the status is EXPIRED_GRACE_OVER. Clear the store and set the clock to T: VALID. | Revoke UPDATE/DELETE from the runtime user and use `SECURITY DEFINER` functions (as P037 did for `mg_purge_measures`). Use `effective = max(now, lastSeen, licence.issuedAt, newest usage row)`. Refuse a licence version lower than the highest ever stored. | M |
| SEC-14 | 12 Dependencies | **PARTLY FIXED in P043 (617b4e8): httpclient5, httpcore5, Nimbus updated; the Spring line waits for its own decision** Spring Boot 3.3.2, Spring Security 6.3.1 and Spring Framework 6.1.9/6.1.11 are past open-source support, with open advisories; httpcore5 5.2.4 and nimbus-jose-jwt 9.37.3 are behind | Medium | root `pom.xml:50-61` (`spring.security.version`, `spring.boot.version`, `httpclient5.version`) | Compile-scope dependencies carry advisories, among them:<br>• CVE-2025-22235: Boot `EndpointRequest.to()` matches the wrong endpoints, High;<br>• CVE-2025-41249: Framework annotation detection can lead to wrong authorisation, High;<br>• CVE-2026-54399: httpcore5 header parsing exhausts memory, High; this affects the setup command and apps not on Boot's dependency management;<br>• CVE-2025-53864: nimbus deeply nested JSON, DoS;<br>• several Spring Security 6.3 items.<br>The 6.1 / 3.3 / 6.3 lines get no open-source fixes any more. A Boot app's own Spring version wins over these, but the platform's own pins, the setup command and anything not on Boot's dependency management keep the old versions. | *ran*. The GitHub advisory database was queried through `gh api graphql` for all 81 resolved artifacts on 2026-11-23. Matching advisories are listed in `P042-report.md`. | Move the compile baseline to Spring Boot 3.5.x (latest) with its Spring Security and Framework; httpclient5 ≥ 5.6.3 / httpcore5 ≥ 5.4.3; nimbus ≥ 9.37.4 (through Security). Turn on Dependabot alerts and version updates for Maven and Actions. | S–M |
| SEC-15 | 12 Build / release | **FIXED in P043 (617b4e8)** The release workflow publishes any `platform-core-v*` tag, including a tag on a commit that never went through a PR | Medium | `.github/workflows/release.yml:3-6` (no check that the commit is on main); no tag ruleset (`gh api …/rulesets` returns `[]`) | Anyone with push rights can tag a local or branch commit and push the tag. The workflow verifies and publishes it to GitHub Packages, where apps pick it up as a release. That bypasses the "merges only through PRs with green CI" rule. | *read* (and `gh api`: no rulesets; branch protection covers `main` only). | A first step `git merge-base --is-ancestor "$GITHUB_SHA" origin/main` (with `fetch-depth: 0`), failing otherwise. Add a tag ruleset limiting the creation of `platform-core-v*` and blocking its update and deletion. Optionally run the publish inside a protected `release` environment. | S |

### Low

| ID | Area | Title | Severity | Where | What an attacker or a mistake can do | Proof | Proposed fix | Size |
|---|---|---|---|---|---|---|---|---|
| SEC-16 | 1 Identity | A token can claim a certificate binding by name | Low | `platform-identity-core/…/identity/push/PushCaller.java:174`; `DirectoryMachineSourceBindings.find`; `PostgresMachineClients` | A token whose client-ID claim is literally `cert-sha256:<hex>` matches a certificate-only binding without holding the key. It needs a client with that ID registered in the customer's own login system. | *read*. Bind `cert-sha256:<64 hex>`, then `check(token({client_id: same}))`: allowed, `Via.TOKEN`. | Refuse a client ID for which `isFingerprint(clientId)` is true on token paths (`IDN_PUSH_NOT_BOUND`). | S |
| SEC-17 | 1 Identity, 2 Setup | Grants under an earlier login system come back on rollback | Low | `platform-setup/…/setup/SetupRun.java` (`planGrants` / `planPlatformGrants` touch only the current issuer); `PostgresLoginConnectionStore.candidate()` `:189-212`; `LoginConnections.rollback` | After a move from login system A to B, A's grants (platform `*` grants included) stay stored. A rollback to A makes them live again with no plan or warning, including grants that were meant to be removed. | *read*. Setup with issuer A and grant G; setup with issuer B, `--remove-unlisted-grants` and no G; rollback: G is active. | When the issuer changes, setup lists or removes the old issuer's grants, or rollback warns about or refuses grants that were not reviewed. | M |
| SEC-18 | 2 Setup | The "looks like a secret" check misses common client-secret shapes | Low | `platform-identity-core/…/identity/mapping/ClientBindingsFile.java` (`looksSecret`, `LONG_RUN`); `SigninFile.text` has no check | These pass the check:<br>• secrets with `~` or `.` (a common cloud-directory format);<br>• 32-character alphanumeric secrets;<br>• `GOCSPX-…`.<br>They are then stored as a client ID and printed in the plan. | *read*. `ClientBindingsFile.read("Abc8Q~dEfGhIjKlMnOpQrStUvWxYz0123456.7_8,…")` is valid. | Count `~` and `.` as run characters, add known prefixes, lower the threshold to 32 for high-entropy text, and apply the check to the sign-in file's client and audience values. | S |
| SEC-19 | 3 Secrets | `SafeText` can be beaten in many common forms | Low (backstop only, except for bundle values, see SEC-11) | `platform-errors/…/errors/SafeText.java:18-34,66-74` | These pass unchanged:<br>• JSON-quoted names (`{"password": "…"}`);<br>• `passphrase=`, `credential=`, `private_key=`, `secret_id=`;<br>• URL-encoded `password%3D…`;<br>• values with spaces (only the first word is removed);<br>• `Authorization: Token|Digest`;<br>• userinfo containing `/` or a space;<br>• lowercase hex keys;<br>• camelCase `…Key` names (`signingKey`, `masterKey`).<br>The cause of the last one: matching runs after lower-casing. | *ran* (`SafeText.scrub` / `secretName`, by the reviewer). | Match names before lower-casing and split camelCase. Widen the name list. Accept JSON/YAML quoting around `=`/`:` and take a quoted value whole. Treat any `Authorization:` value as secret. Take userinfo up to the last `@`. URL-decode once before matching. | M |
| SEC-20 | 9 Errors | Log injection through the failure line | Low | `platform-errors/…/errors/PlatformError.java:105` (escapes `"` only); `platform-monitoring/…/monitoring/DependencyStatus.java:170-171` | A detail containing CR/LF, such as a file or drop name, forges extra `code=… ref=…` lines that log tools parse as real failures. | *ran*. The detail `a.pdf" \ncode=SEC_FAKE ref=R-…` gives two lines. | Replace control characters in details (`PlatformError` constructor and `fill`, `CheckResult.of`) and cap their length. | S |
| SEC-21 | 9 Monitoring | A caller-chosen `X-Request-Id` becomes the failure reference | Low | `platform-monitoring-spring/…/monitoring/spring/RequestReferenceFilter.java:25,33` | A client that is not signed in chooses the reference stored in logs, FailureLog, the bundle and the user message, and can copy another person's `R-…`, which confuses support tickets. Header reflection itself is safe (strict character set). | *read*. | Always mint `ReferenceId.next()`; keep the incoming ID as a separate MDC `requestId`, or accept it only from a configured proxy. | S |
| SEC-22 | 9 Errors | References in the same tenth of a second are predictable | Low | `platform-errors/…/errors/ReferenceId.java:38-44` | A follow-on reference is `last + 1 + nextInt(1024)`, so only 10 bits are new. The docs promise a reference "cannot be guessed from another one". Nothing in the platform authorises by reference. | *ran*. Three consecutive references differ only in their last 2–3 characters. | Draw the full random part each time and keep ordering by the time prefix only; or correct the docs. | S |
| SEC-23 | 9 Monitoring | Health details have no guidance on limiting who sees them | Low | `DependencyHealth.java:48-60`; `EraseLogHealthIndicator.java:51,63-72` (location, erased record ID); `KeyServiceHealthIndicator`; `docs/install/monitoring.md` §5 | The guide presents `dependencies` as a dashboard, which needs `show-details=always`. Hosts, paths, secret reference names, issuer URLs and erased record IDs are then shown without sign-in. | *read*. | Guide: `show-details=when-authorized` with roles; probes need no details. Drop `cause`/`fix` from `readinessState` and `recordId` from erase-log health. | S |
| SEC-24 | 7 Notifications | Teams and Slack webhooks do not go through platform-outbound | Low (Medium if tenant admins ever enter webhook addresses) | `platform-notifications/…/notifications/webhook/internal/WebhookClient.java:61-66,87-120`; `SlackChannel.java:49`; `TeamsChannel.java:59`; `MicrosoftTokens.java:101-104,191` | Any `https` host, internal ones included, is posted to, with no address guard, no pinning, the JVM proxy and an unbounded token answer. The result codes reveal reachability, which works as an internal port scan. Today only IT sets the address. | *read* (confirmed: the JDK `HttpClient` with `ProxySelector.getDefault()`). | Send through `Outbound` with the app's `Installation` (address guard, answer limit, no redirects). Optionally allow-list the webhook host suffixes. | M |
| SEC-25 | 7 Notifications | Password login is allowed over plain SMTP (`security: NONE`) | Low | `platform-notifications-spring/…/NotificationsConfig.java:178-181,223-239`; `MailServer.java:51-54` | A mistaken setting sends the mail password in clear. | *read*. `security=NONE` with a username and `password-secret` gives no problem. | Refuse NONE whenever a username is set. | S |
| SEC-26 | 6 Licensing | An unpaired surrogate and `?` give the same signed bytes | Low | `platform-licensing/…/licensing/Json.java:144-148,307`; `SignedDocument.java:54` | In free-text signed fields such as the customer name, `?` can be replaced with `\uD800` and the signature still verifies, although the value differs. This breaks "exactly one canonical form". | *read*. | Refuse unpaired surrogates in `Parser.string`; encode with a REPORT encoder. | S |
| SEC-27 | 6 Licensing | The "tamper-evident" usage chain and export use unkeyed SHA-256 | Low (the claim is overstated) | `platform-licensing/…/licensing/usage/UsageChain.java:44-46`; `UsageExport.java:132-134`; `UsageExportVerifier.java:80-106`; `docs/design/licensing.md:41` | The database owner can rewrite usage and recompute every hash, and the chain and export still verify. It detects accidents and naive edits only. | *read*. Edit `records[0].amount` in an export and recompute the chain: `verify(...).ok()`. | Reword the design to "detects accidental edits and gaps". On our side, keep the anchors of received files and compare them with other evidence. | S |
| SEC-28 | 4 Crypto | `StreamCipher` is not bound to a document | Low | `platform-crypto/…/crypto/envelope/StreamCipher.java:255-258` | With write access to both the document store and the database row that holds the envelope, document A's stream can be swapped into document B of the same customer, and it opens without error. | *read*. | Add a context (document or record ID) to the associated data in `platform-stream/2`. | M |
| SEC-29 | 4 Crypto | `RecordCipher` items are not bound to a record version or an item count | Low | `platform-crypto/…/crypto/envelope/RecordCipher.java:188-191` | With database write access, an older sealed item can be put back (same envelope, field and position), or an item dropped, without detection. Moving an item to another field is detected. | *read*. | Document this as a database-integrity limit, or bind a record version or an item manifest. | M |
| SEC-30 | 4 Crypto | **FIXED in P044 (3dcfeaa)** Plain `http://` Vault addresses are accepted | Low | `platform-crypto/…/crypto/vault/VaultClient.java:357`; `platform-crypto-spring/…/CryptoConfig.java:46` | On a misconfigured install the token, the AppRole `secret_id` and the unwrapped data keys cross the network in clear. | *read*. | Refuse `http` unless the host is loopback. | S |
| SEC-31 | 10 Model governance | No four-eyes check by person | Low | `platform-model-governance/…/modelgov/ModelGovernance.java:235-257,343-353` | The person who ran the validation can also approve it, as long as they hold an approver role. | *read*. `recordValidation(ranBy="alice")`, then `approve(v, Approver("alice", {APPROVER}))` succeeds. | Refuse when the approver ran the validation (a new `MOD_*` code with message, cause and fix). | S |
| SEC-32 | 10 Model governance | Separation of duties fails open on role spelling; the core allows an empty forbidden list | Low | `ModelGovSettings.java:25-31`; `Approver.java:10`; `ModelGovernance.java:345-351` | If `business-admin` is forbidden but identity delivers `Business-Admin`, the conflict check misses it while the approver check passes. A non-Spring app with an empty forbidden list gets no separation at all. | *read*. | In `ModelGovSettings`, require at least one forbidden role and refuse blanks. Normalise role names on both sides, or refuse roles that differ only in case. | S |
| SEC-33 | 11 Database | Register and connection history tables can be rewritten by the runtime user | Low | `V1__modelgov.sql:41,82` (`mg_model_history`, `mg_model_use_history`: no triggers); identity `idn_login_connection_history`, `idn_machine_binding_history`; `docs/install/model-governance.md:24`, `docs/install/identity.md:44` | Deleting a `runsWhere` history row silences `MOD_LOCATION_CHANGED` for validations recorded before V4 and rewrites the audit trail. | *read*. | Insert-only triggers (including TRUNCATE); grant SELECT, INSERT only; the schema checks report extra rights. | M |
| SEC-34 | 11 Database | The `mg_measure_guard` trigger can be fooled with a temp table | Low | `V3__modelgov_purge_guard.sql:13-29` (no pinned `search_path`; `pg_class` / `pg_roles` not qualified) | If the runtime user still has the DELETE granted before 0.6.0 (the guide only says it "may be revoked"), a temp table named `pg_roles` makes the guard think the caller is the owner. Readings younger than 30 days can then be deleted. | *read* (it relies on PostgreSQL searching `pg_temp` first for relations). | Qualify with `pg_catalog.`; set `search_path = pg_catalog, pg_temp` on the trigger and `<schema>, pg_temp` on the purge function; have `ModelGovSchema.verify` warn about DELETE on `mg_measure`. | S |
| SEC-35 | 13 Public API, 8 Outbound | `new Outbound(Installation, AddressGuard)` gets around the hosted rule | Low | `platform-outbound/…/outbound/Outbound.java:173-186` | `new Outbound(Installation.hostedService(), new AddressGuard(true))` reaches internal addresses on the hosted service. Metadata addresses are still refused. | *read*. | Refuse a guard that allows internal addresses when the installation is hosted, or take only a `Resolver`. | S |
| SEC-36 | 8 Outbound | `AddressGuard` does not unwrap IPv4 addresses embedded in IPv6 | Low | `platform-outbound/…/outbound/AddressGuard.java:135-171` | NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, Teredo, and IPv4-compatible `::a.b.c.d` (in `internal()`) are not unwrapped. On a NAT64 network an AAAA record reaches 10.x addresses. | *read*. `new AddressGuard(false).check("[64:ff9b::a00:5]")` is allowed. | Extract the embedded IPv4 for these prefixes and check it. Also block 240.0.0.0/4 and 192.0.0.0/24. | S |
| SEC-37 | 8 Outbound | Through a proxy there is no pinning, and names the local resolver doesn't know are allowed | Low | `AddressGuard.java:96-101`; `Outbound.java:237,273,555` | CONNECT sends the name and the proxy resolves it again, so DNS rebinding and internal-only names reach the proxy's network. | *read*. | Install guide: the proxy must refuse private and metadata ranges. Optionally refuse names that don't resolve locally (default on for hosted). | M |
| SEC-38 | 8 Outbound | No limit on response headers and no overall deadline per call | Low | `platform-outbound/…/outbound/Outbound.java:536-556` | A destination can send endless headers, or drip bytes just under the read timeout, holding a worker thread. Poll downloads have a size limit but no time limit. | *read* (the httpcore5 defaults are taken from memory; confirm with a test). | `Http1Config` limits (for example 100 headers of 8 KiB) and a per-call deadline that aborts the request. | S–M |
| SEC-39 | 8 Integration (templates) | Path values `.` and `..` are encoded as `%2E` instead of being refused | Low | `platform-integration/…/integration/config/Template.java:503-516` | Many servers normalise `%2E%2E`, so a document attribute or an answer value can move a signed-in call to another path on the same host. The host cannot change. | *read*. | Refuse a value that is exactly `.` or `..` (`INT_VALUE_NOT_ALLOWED`). | S |
| SEC-40 | 12 Build | **FIXED in P043 (617b4e8)** The release job holds a write token throughout `mvn verify`; actions are pinned by tag | Low | `.github/workflows/release.yml:8-10,16` (`contents: write`, `packages: write` for the whole job; `actions/checkout` keeps its token in `.git/config`); `ci.yml` / `release.yml` use `@v4`; repo setting `sha_pinning_required: false` | A compromised test dependency or Maven plugin running during the release's `mvn verify` can read the token and push or publish. A moved action tag runs new code. | *read* (`gh api …/actions/permissions`). | Split the job: verify with `contents: read` and `persist-credentials: false`, then a small deploy job with write rights. Pin actions by SHA (Dependabot keeps them current). | S |
| SEC-41 | 12 Dependencies | **FIXED in P043 (617b4e8)** No dependency-update automation | Low | repo settings: Dependabot alerts off (`vulnerability-alerts` 404), security updates disabled; no `.github/dependabot.yml` | The open advisories in SEC-14 went unnoticed. | *ran* (`gh api`). | Turn on Dependabot alerts and security updates; add `dependabot.yml` for Maven and GitHub Actions. | S |

### Info

| ID | Area | Title | Severity | Where | What an attacker or a mistake can do | Proof | Proposed fix | Size |
|---|---|---|---|---|---|---|---|---|
| SEC-42 | 1 Identity | Entity grants are not kept apart per customer (needed before hosting) | Info (High once one database serves several customers) | `PostgresIdentityLookup.activeGrants` / `find`; `LoginConnections.*(UUID)` take no tenant | Today setup refuses a second customer per database (`SetupRun.requireOnlyThisCustomer`). In a shared database, one customer's connection naming another customer's issuer and audience would collide with it. | *read*. | Before hosting: refuse an ACTIVE issuer and audience already active for another customer, and scope the lookups and draft operations by tenant. | M |
| SEC-43 | 1 Identity, 2 Setup | The development-key gate is set by the operator | Info | `platform-setup/…/setup/SetupInputs.load` (the development mark is read from `--keys`) | Marking the production key `"development": true` lets setup store an http localhost issuer. The docs' "never on a production licence" claim is stronger than the code (see SEC-12). | *read*. | Read development marks only from keys bundled with the release (with the SEC-12 fix). | S |
| SEC-44 | 1 Identity | Group names are normalised asymmetrically | Info | `IdentityClaims.values`; `EntityGroupAuthorization`; `PlatformGroupAuthorization` | Token group names are trimmed, and a string claim is split on commas and spaces: `"Finance Admins"` yields the group `Admins`. | *read*. | Compare exactly and refuse string-form group claims. | S |
| SEC-45 | 1 Identity, 2 Setup | Bidirectional-text characters are not refused in group names and binding descriptions | Info | `RoleMappingFile.invisibleProblem`; `ClientBindingsFile` (`isISOControl`) | The plan output can be made to look different from what it holds, misleading the installer who reviews it. | *read*. | Refuse U+202A–202E and U+2066–2069. | S |
| SEC-46 | 1 Identity | Test sign-in: no minimum RSA key size; the ES curve is not bound to the algorithm | Info | `platform-identity-core/…/identity/signin/OidcTestSignIn.java` | Advisory test only: it never grants access. | *read*. | RSA ≥ 2048; ES256 only with P-256 (and so on). | S |
| SEC-47 | 1 Identity | The push cache keeps a revoked binding working for up to `cache-for` on every node | Info | `PushCaller` (cache; `forget()` is per instance) | Documented behaviour. With `cache-for` up to 10 min, a revocation takes that long to apply everywhere. | *read*. | Install guide: keep the 30 s default; lower the maximum. | S |
| SEC-48 | 4 Crypto | **PARTLY FIXED in P044 (3dcfeaa): deletion latch, reply size, runbook; the fingerprint canonical form is unchanged (stored format, see P044-report.md question 1)** Smaller crypto items | Info | `Fingerprinter.java:157-160`; `VaultKeyService.java:264-268`; `CryptoCommand.java:226-230`; `VaultClient.java:302` | • The fingerprint canonical form is ASCII `\s` with NFC, so NBSP and full-width characters cause missed matches (no collisions).<br>• `destroyCustomerKeys` sets `deletion_allowed=true` before a DELETE that may be refused.<br>• A rerun after a partial failure reports the wrapping key as ABSENT.<br>• Vault replies are read with no size limit. | *read*. | Use Unicode whitespace and consider NFKC; check the delete capability before the config write; add a runbook note; cap the reply size. | S |
| SEC-49 | 5 Erase log | **FIXED in P044 (0805b0c)** Smaller erase-log items | Info | `FileEraseLogStore.java:190` (scan without lock); folder created with default permissions; `PostgresErasePosition.java:209-216` (session lock); `erl_position.replay_required` updatable by the DML user; `EraseRequest.java:33-34` | • A prune can fail on Windows/SMB while a scan holds the file.<br>• The log folder can be read by others.<br>• A failed unlock leaves the lock held by a pooled connection.<br>• The app user can clear `replay_required`.<br>• The value heuristic misses phone numbers and IDs. | *read*. | Lock during scan; create the folder owner-only; use a transaction-level lock; add a trigger that only allows `replay_required` false → true by the app user; document that record IDs must not be personal data. | S |
| SEC-50 | 7 Notifications | Approval links: `http` base URL allowed; no key-rotation grace | Info | `TenantSettings.java:25`; `ActionTokens.java:32-34` | Tokens can travel in clear; they are not logins, since sign-in and a POST are still needed. Rotating the key ends every open link at once. | *read*. | Require https except on loopback; optionally accept the previous key during rotation. | S |
| SEC-51 | 6 Licensing | Smaller licensing items | Info | `Json.parse`; `LicenceCommand.java:93,202`; `Licence.deployment()` | • Files are read whole with no size cap (operator-supplied; depth is capped).<br>• The `deployment` field is never enforced.<br>• Unused private-key imports in `Ed25519.java`, `SignedDocument.java` and `LastSeenReset.java`. | *read*. | Cap the size; enforce or document `deployment`; remove the imports. | S |
| SEC-52 | 8 Integration | **FIXED in P043 (e4317b1)** Smaller integration items | Info | `FolderTarget.java:32-34,87-88` (`safeName`); `Outbound.java:261-266`; `credential/KeystoreRef.java:18-29` | • The Windows device-name check misses `CON .txt`, `CONIN$` and `COM¹`.<br>• The proxy Basic password crosses a plain-http hop (standard, but undocumented).<br>• A keystore path can be any absolute path, even on hosted, so an admin can probe which files exist. | *read*. | Extend the device list and strip trailing spaces; document the proxy point; refuse keystore paths on hosted or confine them to a folder. | S |
| SEC-53 | 9 Monitoring | FailureLog and the check list are process-wide | Info (matters for hosting) | `platform-errors/…/errors/FailureLog.java:19`; `DiagnosticBundles.java:52` | On a service shared by several customers, one customer's bundle and health output would show another customer's failures and integrations. | *read*. | Tag failures and checks with the tenant and filter them, or rule the bundle out in hosted mode. | M |
| SEC-54 | 9 Monitoring | `Verdict`'s generated `toString` shows unscrubbed details | Info | `platform-monitoring/…/monitoring/Verdict.java:12` | An app that logs a `Verdict` writes raw details; scrubbing happens only in `CheckResult.of`. | *read*. | Override `toString` (state and code only). | S |
| SEC-55 | 9 Monitoring | `RequestReferenceFilter` runs after Spring Security | Info | `platform-monitoring-spring` (registered without an order) | 401/403 refusals, including push-caller and admission refusals, get no reference and no MDC `ref`. | *read*. | Register with `Ordered.HIGHEST_PRECEDENCE + 10`. | S |
| SEC-56 | 3 Secrets | Store exceptions keep the driver's `SQLException` as their cause | Info | identity `Sql.java:30`; licensing, notifications and modelgov stores | pgjdbc's `Detail: Key (…)=(…)` can quote values in stack traces. No unique key holds a value in clear today (`fld_record.identity` is a hash; fields already drop the cause). | *read*. | Drop the cause, or keep only the SQL state. | S |
| SEC-57 | 11 Database | "Even for the owner" is overstated; some TRUNCATE triggers are missing; row ownership needs work before row-level security | Info | `int_integration_version` (`V1__integration.sql:92`), `int_credential_change` (`V2…:107`), `fld_field_definition_history` (`V1__fields.sql:106,129`) have no TRUNCATE trigger. `mg_*` tables have no customer column. Queries by bare ID: `PostgresModelGovStore`, `PostgresIntegrationStore:80,96,140,146,165`, `PostgresCredentialStore:47,61,120,167`, `PostgresCallbackOutbox:71,92`, `PostgresLoginConnectionStore:171,231,240`, `PostgresMachineBindingStore:109,124,145`, `PostgresGroupGrantStore:108,165`, `PostgresValueStore:110,135,149,310`, `PostgresFieldStore:123,195`, `PostgresSummaryStore:75,94`, `PostgresOutboxStore:85,127` | • The table owner can disable triggers; the runtime user cannot.<br>• No guide grants TRUNCATE today.<br>• Row-level security on the hosted service needs a customer column, and scoping in these queries. | *read*. | Correct the docs; add the TRUNCATE triggers; a hosting task adds customer columns and scoped queries. | S (docs, triggers) / L (RLS) |
| SEC-58 | 13 Public API | Proof-like types can be built by apps; `PublicApiTest` is blind to member visibility | Info | `LiveModel`, `ModelStamp`, `ValidationRecord`, `Decisions.*`, `PushCallerIdentity`, `EraseEntry` (public constructors); `platform-error-catalogue/src/test/…/PublicApiTest.java`; jars are not sealed | Apart from SEC-5 and SEC-6, the library never accepts these types as proof (checked by grep). An app can still stamp results without the gate. `PublicApiTest` checks only that docs don't name `.internal` types and that testkits don't import other modules' internals. A class put in a platform package can reach package-private constructors (known, `docs/design/public-api.md:27`). | *read*. | Extend `PublicApiTest` with a list of types whose constructors must not be public, and of `store()`-style accessors. | M |
| SEC-59 | 12 Build | **Owner setting, steps in P043-report.md (workflow rights done in 617b4e8)** Branch protection needs 0 approvals; outside contributors need approval only on their first PR | Info | `gh api …/branches/main/protection` (`required_approving_review_count: 0`, `enforce_admins: true`); `fork-pr-contributor-approval: first_time_contributors` | Fine for a single-owner repo: CI on `pull_request` runs with a read-only token and no secrets. A public repo is safer with approval for all outside contributors. | *ran* (`gh api`). | Set the approval policy to all outside collaborators. Require one review once there is a second maintainer. | S |

## Checked and fine

**1. Sign-in and admission (identity)**
- **`LocalTestIssuers`:** exact host set on `URI.getHost()`, so `127.0.0.1.evil.com`, `localhost.`, `[::ffff:127.0.0.1]` and `[0:…:1]` are refused. Userinfo, query and fragment are refused, and http also needs the development licence. `OutboundIssuerFetch` uses loopback only for local http.
- **`OidcTestSignIn`:**
  - fixed algorithm allow-list (no `none`, no HS*);
  - key matched by `kty` / `kid` / `alg`;
  - exact `iss` in discovery and token, `aud` contains, `exp` required, `nbf` leeway one minute;
  - https `jwks_uri`, size caps, `MiniJson` depth 64;
  - no token or claim value in any message.
- **`LoginConnections`:** version checks on every step. The issuer rule is re-checked at validate, activate and rollback. At most one pending connection (database index). Activate and rollback run in one transaction with `for update`.
- **Admission:** an entity needs an ACTIVE candidate and a matching grant. Platform roles are refused in entity grants and the reverse. Several customers matching a platform grant is refused. A store error means refusal. `TrustedEntityAdmission` and `MachineEntityAdmission` need exactly one entity.
- **`PushCaller` (apart from SEC-7 and SEC-16):**
  - token and certificate must point to the same customer, entity and source;
  - `requireTokenAndCertificate` is enforced;
  - two ACTIVE bindings mean refusal, never a guess;
  - entity and source claim mismatches are refused;
  - a store error throws and nothing is cached;
  - the cache is bounded at 10,000 and its key covers every input;
  - the log holds the code and certificate fingerprint only;
  - `PushCallerRequests` reads no headers.
- **`RequiredAudienceValidator`:** a null `aud` fails.
- **SQL:** all identity-jdbc SQL is parameterised.

**2. Setup**
- **Transaction and lock:** `pg_advisory_xact_lock` is taken first. Plan and apply run in one transaction, and every failure path rolls back. Keys are created before commit, and a rerun is safe.
- **`--replace-connection`:** a connection last changed by the app is a CONFLICT without the flag, and drafts are left alone.
- **`--remove-unlisted-grants`:** without the flag, grants are listed as NOT_REMOVED with exit 3.
- **`--no-customer-keys`:** refused when the product's `customerKeys` is `required`.
- **Settings and run log:** database and Vault settings come from the environment only. The run log holds IDs, counts and codes, and option echoes go through `safe()`.
- **Sign-in file:** unknown fields refused; length, edge-space and control-character checks.
- **Mapping CSV:** strict UTF-8, BOM accepted, RFC 4180 quoting, line breaks inside a value refused, invisible characters refused, the alias target must be an entity role.
- **Client-bindings file:** exact header, quoting and column count, slot and source patterns, duplicates, a client bound elsewhere, the certificate ID format.
- **Profiles:** checked with `ProfileLoader`, stored nowhere.
- **One customer per database:** enforced.
- **Vault policy:** no `delete` in the set-up policy (but see SEC-2).

**3. Secrets and leaks**
- **Types that hide secrets in `toString`:** `VaultAuth.*`, `VaultClient.Secret`, `TestSignIn`, `PushCallerInput`, `IssuerFetch.Response`, field value types (`FieldValue.Text` / `Address`, `ValidValue`, `StoredValue`, `StoredRecord`, `RecordValues`, `SearchMatch.*`), `Fingerprinter.Input`, `CallbackEntry`, `Drop.Trigger`, `Outbound.Call` / `Answer`, `ActionTokens.Claims`, `ModelPack.Pack`, `VaultTransitSigner.Token`, notifications `Secret`. The token holders keep a `Secret`, and `CheckResult.toString` shows name, state and code only.
- **Logs:** about 118 log calls in `src/main` log codes, class names and IDs only.
- **Exceptions:** no exception message joins in a value, URI, header, token or body. The JSON parsers report the position only.
- **`SafeText` removes:** PEM blocks, JWTs, Bearer/Basic, `password=` in JDBC URLs, `://user:pass@`, and key-like base64 of 40+ characters.
- **Bundle:** fixed entry names (no zip-slip), 5 MiB limit checked before writing, fixed system properties only, values not resolved.
- **Metric tags:** names, group, integration ID and outcome only.

**4. Crypto**
- **Nonces:** 96-bit `SecureRandom` with a fresh data key per value, record and stream. Stream nonces are a random prefix plus an index, the final record has its own index, and nothing is reused.
- **Tag and associated data:** 128-bit tag. Associated data is domain-separated, and context characters are restricted, so the separators are unambiguous.
- **Streams:** authenticated final record, index order checked, trailing bytes refused. Tests cover reordering, dropping and truncation.
- **Parsing:** strict for `pcv1.`, `pck1.` / `pci1.` and `platform-stream`; unknown versions refused; no downgrade path.
- **"Can't decrypt":** always a `CryptoException` with its code, never null or plaintext. Keys are wiped in `finally`.
- **Keys:** data keys are exactly 32 bytes. HMAC output length and version are checked, and the `-fp` fingerprint key is separate from the wrapping key.
- **Vault client:**
  - redirects never followed;
  - the token is never in a message;
  - one re-login on 403;
  - key names come from the customer ID hex plus a validated prefix, and the mount and KV path are checked, with `..` refused.
- **`crypto destroy-customer-keys`:**
  - the tenant ID must be typed back;
  - the result file is created first;
  - the offboard policy is delete-only;
  - a login without delete is refused before anything changes.

**5. Erase log**
- **Chain walk:** checks the sequence, `prev`, the log ID and the recomputed hash. The line parser is strict (fixed fields, no escapes, no duplicates).
- **Writes:** each append is forced to disk, a partial last line is truncated, and pruning writes a temp file and the checkpoint before an atomic rename.
- **Locks:** JVM lock plus file lock, always taken in the order database lock, then file lock.
- **Position:** the database refuses to move it back or delete it.
- **Replay:** verifies before it clears `replay_required` and stops on a handler failure. A wrong log, a gap, or a database ahead of the log blocks work.
- **`EraseTicket`:** cannot be forged (package-private) and is bound to the record.
- **Legal holds:** checked twice; an unknown answer fails closed.

**6. Licensing**
- **What is verified:** the signature is checked over the canonical re-serialisation of the parsed payload (not the file text).
- **Strict parsing:**
  - duplicate keys refused after unescaping;
  - extra fields refused;
  - the format string is signed, so a licence and a reset cannot be swapped;
  - algorithm pinned, 64-byte signature, exact key ID;
  - null, fractions and leading zeros refused, depth 32.
- **Tenant and reset:** tenant match checked. A reset works once and before its `notAfter`, in a transaction, with a unique ID.
- **Clock and dates:** `advanceTo` uses `greatest()`, and day counting is in UTC.
- **Usage ledger:** appends are serialised on the chain head.
- **SQL:** parameterised.
- **Issuing side:** `platform-licensing` and `-spring` have no signing code and do not depend on `platform-licensing-issue`. Only the testkit (test scope for apps) and the catalogue's tests use it.
- **Vault Transit signer:** verifies each signature it gets back.
- **No private key in the repo:** grep for PKCS#8 Ed25519 found none.

**7. Notifications**
- **Approval links:**
  - HMAC-SHA256 with domain, tenant, request UUID, action, expiry and recipient hash; the key is ≥ 32 characters from `SecretResolver` and wiped after use;
  - constant-time compare;
  - separate Approve and Decline tokens;
  - every check re-run at decision time: own request refused, role checked live, expiry;
  - first decision wins by `UPDATE … WHERE decision IS NULL AND expires_at > ?`;
  - tokens are never stored in the outbox.
- **SMTP:**
  - STARTTLS required with server identity checked, TLS 1.2 and 1.3 only, no trust-all;
  - debug traces off;
  - XOAUTH2 only for Microsoft 365 with an https authority.
- **No header injection:** the subject is built from safe patterns, recipients are checked strictly, and `fromName` refuses control characters.
- **Content rule:** enforced by the API shape (fields are counts or times only, identifier pattern, link path pattern).
- **Webhooks:** https only, no redirects, userinfo refused, the address never logged.
- **SQL:** parameterised.

**8. Integration and outbound**
- **Address guard:**
  - metadata names and addresses (including 169.254.170.2 and 168.63.129.16), link-local, `0/8`, `::`, multicast and broadcast are blocked;
  - `::ffff:` mapped addresses are checked;
  - decimal, octal and hex IPv4 forms get a null `URI` host and are refused;
  - every resolved address must pass.
- **Pinning:** the client connects only to the checked address, on every path: destination steps, health, status, callbacks, tokens, poll list, download and acknowledge, issuer fetch, scanners, and the TLS 1.2 certificate probe.
- **Redirects, cookies, userinfo:** no redirects (set twice), no retries, no cookies, userinfo refused.
- **TLS:** default host-name verifier with SNI on the name. The private CA is added to the system's, never replaces it, and there is no trust-all.
- **Client certificates:** each gets its own client. Uploaded credentials are sealed with `ValueCipher` under the customer's keys. CURRENT/NEXT allows one NEXT, and a switch drops the held token.
- **Callbacks:** constant-time compare, the window checked both ways, IDs kept for twice the window, recorded only after passing (apart from SEC-8).
- **Templates:**
  - fixed placeholders only, with no secret, token or body placeholders;
  - JSON escaping covers quote, backslash, controls and U+2028/2029;
  - the path must start with a single `/`, with no `\`, `#`, `.` or `..` in the literal text and no placeholders in the query;
  - control characters refused in headers;
  - reserved headers refused both at save and at send.
- **Polling:** next links and `Location` must match scheme, host and port; no userinfo or fragment; ≤ 2000 characters. Downloads are streamed with a size limit and SHA-256.
- **Watched folder:**
  - file-list entries are compared by name only, never used as paths;
  - links and junctions are skipped when listing, and inside the claim links and folders are refused, with a `realPath` check;
  - claims are an atomic rename with a random fence;
  - XML: DOCTYPE disallowed, no external entities, no XInclude, secure processing, depth 64;
  - JSON: depth 32, values capped; trigger size checked before parsing.
- **Folder export:** `validName` refuses separators, `:` streams, trailing dots and spaces, `.partial` and devices. Files are created `CREATE_NEW`, read back and checked, the manifest is written last and then the rename. Overwrites are refused, and folders are refused on hosted.
- **Scanners fail closed:**
  - clamd: only an exact `stream: OK` / `… FOUND` counts, replies are capped and must be printable;
  - ICAP: only 204 without a threat header is clean;
  - a garbled reply or timeout holds the drop.
- **Profiles:** ≤ 2 MiB, strict UTF-8. The address, sign-in, sensitive fields and certificates come only from the installation. There is no code or expression path, and the host cannot change.

**9. Monitoring and errors**
- **Platform endpoints:** the platform has no controllers or error handler of its own.
- **Health:** `dependencies` is always UP, so it cannot be used to take the app out of service.
- **`CheckRunner`:** never puts exception text in a cause. `HttpServiceCheck` drops userinfo and the query, and webhook checks show the host only.
- **`ReferenceId`:** uses `SecureRandom`, and its ThreadLocal is restored.
- **`X-Request-Id`:** bounded to 64 safe characters, so there is no header splitting.
- **User messages:** name no internals (checked by `platform-error-catalogue`).

**10. Model governance**
- **Forbidden and approver roles:** forbidden roles are checked before approver roles. A role may not be both, in core and Spring, and Spring refuses an empty forbidden list.
- **Approvals:** `MOD_ALREADY_APPROVED`. A failed high-materiality customer model is blocked in `accept` and `approve`.
- **Insert-only records:** UPDATE, DELETE and TRUNCATE triggers on validation, measure, approval, acceptance, live history, drift, drift action and review notice.
- **Purge function:** `SECURITY DEFINER`, EXECUTE revoked from PUBLIC, the 30-day floor in SQL; `verify` checks EXECUTE.
- **Gate inputs:** digest and fingerprint compared exactly, with lowercase forced and null refused. There is no bypass flag, and `LiveModel` is not cached.
- **Documentation pack:** IDs, numbers, register text and `PackTexts` only, scoped to the entity.

**11. Database**
- **No string-built SQL:** every concatenated SQL string uses constants only (column lists, table names, `sum`/`max`, a status switch). LIKE patterns are escaped (`PostgresValueStore:186-190,257`), and there is no `Statement` / `createStatement` in main code.
- **Forward-only `erl_position`:** it cannot be deleted.
- **Approval store:** tenant-scoped.

**12. Dependencies and build**
- **CI:** `permissions: contents: read`; runs on `pull_request` with no secrets.
- **Release workflow:**
  - checks that the POM version is the tag's without `-SNAPSHOT`, and that the release notes exist;
  - runs `mvn verify` and refuses skipped tests;
  - deploys with the job token only;
  - uses `--verify-tag`.
- **Repo settings:**
  - secret scanning and push protection on;
  - `main` protected: required check `verify`, strict, admins included, no force push or deletion;
  - default workflow token read-only, Actions cannot approve PRs.
- **Scope of the dependency check:** PostgreSQL JDBC is test scope only (apps bring their own), and the test-only advisories (logback, assertj) do not reach apps.

**13. Public API**
- **`EraseTicket`:** package-private constructor, apart from the split-package caveat in SEC-58.
- **`AddressGuard`:** checks whatever a custom resolver returns.
- **`Installation`:** refuses internal addresses and sign-in NONE on hosted.
- **Clock-taking constructors:** fine for trusted app code.

## Proposed fix tasks

The integration release needs every Critical, High and Medium finding fixed (SEC-1 to SEC-15). Low findings are fixed by choice; Info findings are noted.

| Task | Findings | Must before the integration release | Size |
|---|---|---|---|
| FIX-A Filesystem safety (Windows links, scan bytes) | SEC-1, SEC-9, SEC-52 (device names) | yes (SEC-1, SEC-9) | S–M |
| FIX-B Erase-log integrity | SEC-3, SEC-4, SEC-5, SEC-49 | yes (SEC-3, 4, 5) | M |
| FIX-C Key-service policies and Vault hardening | SEC-2, SEC-30, SEC-48 | yes (SEC-2) | S |
| FIX-D Model-governance gate and duties | SEC-6, SEC-31, SEC-32, SEC-33, SEC-34 | yes (SEC-6) | M |
| FIX-E Push-caller and identity rules | SEC-7, SEC-16, SEC-17, SEC-18, SEC-44, SEC-45 | yes (SEC-7) | M |
| FIX-F Integration secrets and callbacks | SEC-8, SEC-10 | yes | M |
| FIX-G Leak backstops (bundle, SafeText, references, logs, health) | SEC-11, SEC-19, SEC-20, SEC-21, SEC-22, SEC-23, SEC-54, SEC-55, SEC-56 | yes (SEC-11) | M |
| FIX-H Licensing trust root and clock | SEC-12, SEC-13, SEC-26, SEC-27, SEC-43, SEC-51 | yes (SEC-12, SEC-13) | M |
| FIX-I Dependencies and release pipeline | SEC-14, SEC-15, SEC-40, SEC-41, SEC-59 | yes (SEC-14, SEC-15) | S–M |
| FIX-J Outbound hardening | SEC-24, SEC-25, SEC-35, SEC-36, SEC-37, SEC-38, SEC-39 | by choice | M |
| FIX-K Crypto binding (format version 2) | SEC-28, SEC-29 | by choice | M |
| Later, before hosting | SEC-42, SEC-46, SEC-47, SEC-50, SEC-53, SEC-57, SEC-58 | noted | L |
