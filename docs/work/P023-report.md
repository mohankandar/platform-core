# P023 report — platform-integration core and the mock destination server

Date: 2026-11-22
Branch: feature/integration-core (merged into main, deleted), then main (this report)
Outcome: DONE. Three end-to-end TLS tests are skipped on the owner's laptop, because Norton intercepts TLS even to
127.0.0.1. The private-CA trust itself is tested without the network, and those three tests run on any machine
without TLS interception. See "Not done, and why".

## Result (tests per module, merge and push results)

- `mvn clean verify`: **BUILD SUCCESS, 897 tests, 0 failures, 3 skipped.** That is the 812 existing tests, all
  passing and unchanged, plus 85 new ones. Then `mvn install` (with `-DskipTests`, straight after the verify):
  BUILD SUCCESS.

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
| platform-crypto | 20 |
| platform-crypto-testkit | 16 |
| platform-setup | 20 |
| platform-fields | 91 |
| platform-fields-testkit | 18 |
| platform-crypto-spring | 5 |
| platform-erase-log | 16 |
| platform-erase-log-testkit | 9 |
| platform-erase-log-spring | 5 |
| **platform-integration** (new) | **14** |
| **platform-integration-testkit** (new) | **71** (3 skipped on this laptop, see below) |

- **Start (A):** the uncommitted `docs/design/integration-module.md` and `docs/work/P023.md` were committed as they
  were (62a7d32). Then `feature/integration-core` was branched from main.
- **Merge:** fast-forward, `main` e1ed85c → 437d1e8, pushed.
- **Branch:** `feature/integration-core` was deleted locally. It was never pushed, so there was nothing to delete on
  origin.
- **`feature/case-fields`:** still on origin, so the local branch stays.
- **This report:** the next commit on main, pushed.
- **Left alone:** files someone else changed while I worked: `docs/design/setup.md` (modified, new section 6.1),
  `docs/design/model-governance.md` (new) and `docs/work/P029.md` (new). They are not in my commits.

## What was built

### Modules

- **`platform-integration`**: plain Java, no Spring, package `com.documentplatform.integration`. It depends on
  `platform-notifications` only for `SecretRef`, `Secret` and `SecretResolver`, and the mail library is excluded.
- **`platform-integration-testkit`**: test scope only.
- Both modules are in the root `<modules>` list and in `platform-bom`, and CLAUDE.md's module table has a row for each.

### Configuration model, store, history and audit (B3)

**`config.IntegrationConfig`**

- What it holds:
  - id, entity UUID, product code;
  - kind (API_PUSH now; FOLDER_EXPORT, API_POLL and WATCHED_FOLDER are stored for P025 and P027), with the direction
    following from the kind;
  - name and enabled;
  - `Endpoint` (base address, use the proxy or not);
  - `SignIn`;
  - `Limits`;
  - steps;
  - `errorCodePointer` (where the destination puts its own code);
  - classification rules;
  - `HealthCheck`;
  - `TestMode` (FULL or CHECK_ONLY);
  - `sendCorrelationId`;
  - `profile` (a name, used from P024).
- Secrets are always `SecretRef`.

**`config.ConfigCheck`** runs when a configuration is saved, under the installation's rules:

- **Errors** refuse the save. Each one has an `INT_CONFIG_*` code and says where it is (for example
  `steps[1].headers.X-Name`), never a value.
- **Warnings** are kept with the version. Today there is one: `INT_CONFIG_SIGN_IN_NONE_WARNING`.

**`config.ConfigJson`** is the stored form, `platform-integration-config/1`. It holds references only. It also lists
the names of the fields that changed between two versions.

**`store.IntegrationConfigs`** saves configurations:

- It checks the configuration, then stores a new version with who and when. A save that changes nothing is not a
  new version.
- The app's audit (`IntegrationAudit`) gets one `ConfigChange` per change: version, who, when, the changed **field
  names only**, and the warnings. It also gets one entry per Test action.
- A stale `expectedVersion` gives `INT_CONFIG_VERSION_CONFLICT`.
- Entity, product and kind never change.

**`jdbc.PostgresIntegrationStore`** keeps the configurations in Postgres:

- Tables: `int_integration` (the current version) and `int_integration_version` (every version, as jsonb, with
  `changed_fields` and `warnings`).
- SQL: `db/platform/integration/V1__integration.sql`, history table `int_schema_history`, run only by the schema
  step.
- At startup `IntegrationSchema.verify` only reads.
- The runtime user needs only these rights:
  - SELECT, INSERT and UPDATE on `int_integration`;
  - SELECT and INSERT on `int_integration_version`;
  - SELECT on `int_schema_version`.
- Database triggers refuse four things, even for the owner:
  - changing or deleting a stored version;
  - skipping a version;
  - deleting an integration;
  - changing an integration's entity, product or kind.

### The call engine for API push (B3)

**`engine.CallEngine.attempt(stored, delivery)`** runs the steps once.

Each **step** (`config.CallStep`) is built from these blocks:

- method;
- a path template, added to the base address;
- header templates;
- a body: NONE, JSON (from a template) or FILE (the document as it is);
- run ONCE or EACH_DOCUMENT (consecutive per-document steps run document by document);
- `read`: value name → JSON pointer, plus `required`.

A later step uses an earlier step's values as `{step.<step>.<value>}`.

**Templates** (`config.Template`) use fixed placeholders only:

- the placeholders: `{correlationId}`, `{package.id}`, `{package.documentCount}`, `{doc.sourceDocumentId}`,
  `{doc.fileName}`, `{doc.contentType}`, `{doc.sha256}`, `{doc.size}`, `{value.<name>}` and
  `{step.<step>.<value>}`;
- no expressions, loops or scripts.

**Escaping depends on where the value goes:**

- In a path, it is percent-encoded and stays one segment. `/` becomes `%2F`, and `..` becomes `%2E%2E`.
- In a header, control characters are refused.
- In JSON, the value is escaped inside its string. Outside a string, only the two numeric placeholders may go.

**Refused when a configuration is saved:**

- a placeholder for a secret, sign-in or a document's content (`{secret.*}`, `{token}`, `{doc.content}`, …);
- unknown placeholders;
- placeholders in the query string;
- `doc.*` placeholders in steps that run once;
- step values that no earlier step reads;
- reserved headers: Authorization, Host, Cookie, Content-Length, … and the API key's own header;
- paths that could change the host (`//host`) and `.` or `..` segments;
- JSON bodies that do not render to JSON.

**Resume:** `Delivery.Resume.from(result)` carries the steps done, the values read and the documents done.

**Logs** hold the integration ID, version, step, status and codes only.

### Sign-in

| Type | Behaviour |
|---|---|
| NONE | On premises only, and only when the installation allows it (off by default). Refused when saving on the hosted service, refused when off. Warned in the log, and the warning is recorded with the version and given to the audit. Checked again at every attempt. |
| BASIC | User name, plus a password from the secret store. |
| API_KEY | The header name is configurable. The key goes in that header and never in the address. |
| BEARER_FIXED | A fixed token from the secret store. |
| OAUTH2_CLIENT_CREDENTIALS | Through the generic token client `signin.ClientCredentialsTokens`, generalised from `MicrosoftTokens`. Details below. |

The generic token client:

- works with any token address, an optional scope and an optional audience;
- accepts a client secret in the form or as HTTP Basic, or a certificate as an RFC 7523 client assertion (RS256, with
  `x5t` and `x5t#S256`);
- keeps a token until 5 minutes, or half its lifetime, before it expires;
- drops the token when the destination refuses it;
- has specific failure codes: `INT_TOKEN_UNREACHABLE`, `INT_TOKEN_REFUSED`, `INT_TOKEN_CREDENTIAL_REFUSED`,
  `INT_TOKEN_CLIENT_UNKNOWN`, `INT_TOKEN_CREDENTIAL_INVALID`, `INT_TOKEN_CREDENTIAL_EXPIRED`,
  `INT_TOKEN_UNEXPECTED_REPLY`, `INT_SECRET_NOT_FOUND` and `INT_SECRET_UNAVAILABLE`.

Microsoft is a preset: `OAuth2ClientCredentials.microsoft(directory, client, type, ref, scope)`. Notifications is
unchanged.

### Safety (B5)

All calls, including token requests, go through `http.Outbound`.

- **https only.** Plain http is allowed only on premises with internal addresses allowed.
- **`safety.AddressGuard`** checks every address before each call, after the name is resolved:
  - Always refused: cloud metadata addresses (169.254.169.254, fd00:ec2::254, 168.63.129.16, 100.100.100.200,
    `metadata.google.internal`, …), link-local, "any", multicast and broadcast. The IPv4-mapped forms are refused too.
  - Refused unless allowed: loopback and private ranges, 100.64/10 and 198.18/15. These are allowed only on premises,
    with `Installation.allowInternalAddresses`.
  - If any address the name resolves to is blocked, the call is refused.
- **Outbound proxy** (`safety.ProxySettings`): host, port, and hosts that bypass it (exact, or a `.suffix`). Each
  integration chooses whether it uses the proxy. A refusal by the proxy is `INT_PROXY_REFUSED`.
- **Private certificate authority** (`Installation.privateCaPem`): added to the system's CAs (`safety.TrustBundle`),
  never replacing them. Host names are always checked. There is no "trust all".
- **Redirects** are never followed. A 3xx is `NOT_CONFIGURED` with `INT_REDIRECT_REFUSED`.
- **Limits per integration:** connect and read timeouts, the largest file, the most documents, and the largest answer
  that is read (default 1 MiB; a larger answer is not read).

### The one-attempt result (B4)

`result.AttemptResult` holds:

- the outcome;
- the platform's `INT_*` code;
- the destination's own code (safe characters, at most 40);
- the HTTP status;
- `retryAfter`, passed through as sent (seconds or an HTTP date);
- `attemptUsed`;
- the correlation ID;
- the step and document reached;
- the values read, per step and per document;
- the steps done and the documents done;
- every call made;
- `test`.

It never holds content, a secret or the destination's message. The engine never sleeps or retries.

The outcomes are SENT, ALREADY_RECEIVED, REFUSED, SIGN_IN_FAILED, CANT_CONNECT, BUSY and OUTAGE, plus UNCLEAR
(a 2xx that does not map, or an answer that is too large) and NOT_CONFIGURED (nothing was sent).

**Classification rules are data** (`config.Rule`):

- A rule matches on the status (`any`, `503`, `5xx` or `500-599`) and on the destination's code. The code can be
  any, `-` (none sent), a prefix (`X-SEN-*`) or an exact code. A rule can also require one field of the answer
  (`/receipt` = `DUPLICATE_ALREADY_STORED`).
- A rule gives an outcome, and optionally a platform code and whether the attempt is used.
- A profile ships its rules as JSON (`ConfigJson.readRules`). They are tried in order, before
  `Classification.DEFAULTS`.
- The defaults:
  - 2xx: sent;
  - 3xx: redirect refused;
  - 401 and 403: sign-in;
  - 404 without a code: wrong address (`NOT_CONFIGURED`, `INT_ADDRESS_NOT_FOUND`);
  - 408 and 429: busy, no attempt used;
  - other 4xx: refused;
  - 5xx: outage.

### Health check and Test action

- **`CallEngine.health(stored)`** makes the configured call, with or without sign-in, and classifies it with its own
  rules. A 2xx gives `INT_HEALTHY`.
- **`engine.TestAction.run(stored, by)`** takes no delivery from the app, so real documents cannot be passed to it.
  - **FULL:** every step, with a one-page PDF made in memory (`TestAction.FAKE_PDF`, "Integration test page - no real
    data") and `test-<name>` for every `{value.<name>}`.
  - **CHECK_ONLY:** the health check and the sign-in only (secrets found, token fetched). Nothing is sent. The code
    is `INT_TEST_CHECKED`.
  - Every run is audited (`IntegrationAudit.tested`).

### Mock destination server (testkit)

`testkit.MockDestination` is built on the JDK `HttpServer`, with http, or https with a given certificate. It answers
any path.

**Switches**, per test or through the admin call on its own port (`POST /mode?name=…`, `/health?failing=`, `/reset`,
`GET /received`):

| Switch | Answer |
|---|---|
| working | 201 with `{id, sha256, size}`, or a route's answer (`route(method, pathRegex, status, body)`) |
| down / up | stops listening; listens again on the same port |
| slow | answers normally after a delay |
| busy | any status, with or without a code, with or without Retry-After |
| outage | 503 without a code |
| refuse | a status and a code |
| already received | 200 with a replay body |
| sign-in refused | 401 with a code |
| health failing | the health path answers 503. This switch is independent of the others, so health can fail while uploads work, and the reverse. |

**Sign-in it expects:** none, Basic, an API key, a fixed bearer, or OAuth 2.0 tokens from its own token endpoint
(`/oauth2/token`). The token endpoint accepts a client secret in the form or as Basic, or a certificate client
assertion (signature, `aud`, `iss`/`sub` and `exp` are checked). It also has its own switches: REFUSE_CLIENT,
REFUSE_REQUEST, DOWN and BAD_REPLY.

**What it records:** method, path and query; headers, with Authorization, Cookie and the API-key header masked as
`[present]`; the top-level fields of JSON bodies; and for other bodies only the SHA-256, size and media type. **It
never records document text.** It also records token requests (client, how it signed in, scope, audience), never the
secret.

Error bodies are `{"code": …, "message": …}`, so set `errorCodePointer` to `/code`.

**Also in the testkit:**

- `MockProxy`: CONNECT tunnel, plain-http forwarding, a refuse switch, and the targets it was asked for.
- `TestCertificates`: made with keytool; a private CA, a server certificate it signs (with SAN) and a self-signed
  client credential.
- `InMemoryIntegrationStore`: the same rules as the Postgres store.

### How an app configures and calls one destination (minimal example)

```java
// Once, from the app's configuration (Spring properties come in P025):
Installation installation = Installation.onPremises()
        .withProxy(ProxySettings.of("proxy.customer.local", 3128))
        .withPrivateCa(customerCaPem);
IntegrationConfigs configs = new IntegrationConfigs(
        new PostgresIntegrationStore(dataSource), appAudit, installation, Clock.systemUTC());
CallEngine engine = new CallEngine(new Outbound(installation), secretResolver, Clock.systemUTC());

// An IT admin saves a destination (version 1; audited; refused with INT_CONFIG_* codes if not valid):
IntegrationConfig dest = IntegrationConfig.apiPush(entityUuid, "DEMO", "Records archive",
                Endpoint.of("https://archive.customer.example"),
                OAuth2ClientCredentials.secret(URI.create("https://login.customer.example/oauth2/token"),
                        "archive-sender", SecretRef.of("integrations/archive/client-secret"), "ingest"))
        .withSteps(List.of(
                CallStep.of("create", HttpMethod.POST, "/api/v1/submissions")
                        .withJsonBody("{\"sourceSubmissionId\":\"{package.id}\","
                                + "\"expectedDocumentCount\":{package.documentCount},"
                                + "\"metadata\":{\"loanId\":\"{value.loanId}\"}}")
                        .reading("submissionId", "/submissionId", true),
                CallStep.of("upload", HttpMethod.POST, "/api/v1/submissions/{step.create.submissionId}/documents")
                        .eachDocument().withFileBody()
                        .withHeader("X-Filename", "{doc.fileName}")
                        .withHeader("Idempotency-Key", "{package.id}:{doc.sourceDocumentId}")
                        .reading("documentId", "/documentId", true)))
        .withErrorCodePointer("/code")
        .withRules(ConfigJson.readRules("[{\"status\":\"503\",\"code\":\"ARC-BUSY\",\"outcome\":\"BUSY\"}]"))
        .withHealth(HealthCheck.get("/actuator/health/readiness", false));
StoredIntegration saved = configs.create(dest, "it-admin@customer");

// The Test button:
AttemptResult test = new TestAction(engine, appAudit).run(saved, "it-admin@customer");

// The app's export job: one attempt; the app keeps the schedule.
StoredIntegration current = configs.current(dest.id()).orElseThrow();
AttemptResult r = engine.attempt(current, Delivery.of(packageId, documents, Map.of("loanId", "L-100")));
switch (r.outcome()) {
    case SENT, ALREADY_RECEIVED -> markDone(r.configVersion(), r.documentValues());
    case BUSY -> retryLater(r.retryAfter());                 // r.attemptUsed() == false
    case OUTAGE, CANT_CONNECT -> checkHealthThenRetry(engine.health(current));
    case REFUSED -> waitingForYou(r.destinationCode());
    case SIGN_IN_FAILED -> credentialProblem(r.code());
    case UNCLEAR, NOT_CONFIGURED -> needsIt(r.code());
}
// Next time, carry on where it stopped:
//   engine.attempt(current, delivery.resumingFrom(Delivery.Resume.from(r)));
```

## Tests asked for → where

All test classes are in `com.documentplatform.integration`.

| Asked for | Where |
|---|---|
| Every sign-in type against the mock and its token endpoint | testkit `SignInTest` (13): NONE allowed or not; Basic, right and wrong; API key in the header, never in the address; fixed bearer; OAuth2 client secret in the form (cached: one token for two attempts), as Basic with an audience, and as a certificate assertion; an expired or unusable certificate; token endpoint refused, bad reply or down; a refused token dropped and fetched again; short-lived tokens renewed; secret missing or the store down; the Microsoft preset |
| Every switch → the right result class | testkit `MockSwitchesTest` (15): working, down then up, slow (timeout, and within the timeout), busy with and without Retry-After (no attempt used), outage, refuse with a code, already received through a rule, sign-in refused, health failing while uploads work and the reverse, health's own rules, switching by the admin call, the recording without document text |
| Classification rules as data, incl. the Vero-style 503 busy vs outage rule with a made-up profile | testkit `ClassificationRulesTest` (8): a JSON profile (`ACME-…` codes) where 503 + the busy code is BUSY with no attempt used and Retry-After passed through, 503 without a code is OUTAGE, AUT-004 is SIGN_IN_FAILED, prefix codes, a field rule for "already received", a rule's `attemptUsed`, the defaults, redirects not followed, a round trip; core `JsonAndConfigTest.classificationDefaults` and `rulesAreValidated` |
| Metadata address guard (blocked, and internal allowed on premises) | testkit `AddressGuardTest` (6): metadata, link-local and IPv4-mapped forms blocked everywhere; internal blocked when hosted and allowed on premises; a name blocked if any of its addresses is; DNS rebinding to metadata blocked for the destination and the token address without connecting; hosted refuses loopback; literal metadata addresses and plain http refused when saving |
| Proxy and private CA | testkit `ProxyTest` (4): calls and token requests through the proxy, a refusing proxy, an unreachable proxy, bypass by name and by suffix, integrations that do not use the proxy. testkit `PrivateCaTest` (6): trust without the network (the private CA accepted, the system's kept, another CA refused), an unreadable bundle refused, untrusted without the CA end to end; **skipped here**: trusted with the CA, host name checked, through the proxy with the CA |
| No secret or content in any log (captured logs) | testkit `NoSecretsInLogsTest`: every sign-in type, success and failures, health, Test action, a refused configuration; planted password, API key, bearer, client secret, document text, a value, private key and issued tokens are checked against captured JUL log lines, result texts and exception texts; the mock's records are checked for secrets and document text |
| Configuration versioning and history | testkit `ConfigVersioningTest` (5): versions, who, when, changed field names, the audit, older versions readable, stale version conflict, no-change save, entity/product/kind fixed, NONE warning recorded and refused when hosted, template building-block refusals. core `PostgresIntegrationStoreTest.versionsAndHistoryWithTheDmlOnlyUser` |
| The Test action sends no real content | testkit `TestActionTest` (3): FULL sends only the fake PDF (its SHA-256 and size at the mock) and `test-` values, and is audited; CHECK_ONLY sends no step, only health and the token; failures report `health` or `sign-in`. Also `MockSwitchesTest.theTestActionSendsOnlyFakeData` |
| DML-only user | core `PostgresIntegrationStoreTest` (3): everything works through a user with only the listed rights; the database freezes versions; the app user cannot run DDL or DELETE; the startup check says missing, older, newer or rights missing, and changes nothing |
| (also) | testkit `CallEngineTest` (10): step values escaped into paths, a missing required value is unclear, resume, the stop point reported, limits checked before sending, a too-large answer, off or missing values, JSON escaping, unreadable content, correlation ID. core `TemplateTest` (5), `JsonAndConfigTest` (6) |

## Files changed, commits

- 62a7d32 `docs: P023 task; decisions on the P022 proposal` (`docs/design/integration-module.md`,
  `docs/work/P023.md`, as they were)
- 437d1e8 `feat(integration): platform-integration core and the mock destination server (P023)`: 66 files.
  - `pom.xml` and `platform-bom/pom.xml`: the two modules.
  - `CLAUDE.md`: the two module rows.
  - `platform-integration/`: `IntegrationCode`; `config/` (IntegrationConfig, Kind, Direction, Endpoint, SignIn,
    Limits, CallStep, HttpMethod, HealthCheck, Template, Rule, Classification, ConfigCheck, ConfigProblem,
    ConfigJson); `json/Json`; `safety/` (Installation, AddressGuard, ProxySettings, TrustBundle); `http/` (Outbound,
    RetryAfter); `signin/` (ClientCredentialsTokens, ClientAssertion, SignInHeaders); `engine/` (CallEngine,
    Delivery, TestAction); `result/` (Outcome, AttemptResult, StepResult, HealthResult); `store/` (IntegrationStore,
    StoredIntegration, ConfigChange, IntegrationAudit, IntegrationConfigs); `jdbc/` (PostgresIntegrationStore,
    IntegrationSchema, IntegrationSchemaException); `db/platform/integration/V1__integration.sql`; 4 test classes.
  - `platform-integration-testkit/`: MockDestination, MockProxy, TestCertificates, InMemoryIntegrationStore; 11 test
    classes (including the fixture `Fx`).
- This report: the next commit on main.

## Decisions I had to make

1. **`SecretResolver` comes from `platform-notifications`.** This follows P022 question 3, which has not been decided
   separately. Integration uses only `notifications.secret`, and angus-mail is excluded from the dependency.
   Notifications itself is not changed.
2. **The generic defaults treat a 5xx as an outage and a 429 or 408 as busy**, following owner decision 2. This is
   not what the P022 B4 table says ("503 = busy"). A 503 means "busy" only when a profile rule says so. A 404 without
   a destination code is a wrong address (`NOT_CONFIGURED`), not a refusal, which avoids LexaVault's 0031 trap.
3. **DNS rebinding.** The guard resolves and checks every address right before each call, and the JDK's address
   cache normally gives the HTTP client that same answer. The JDK HTTP client cannot be told to connect to a given
   address, so the window is narrowed, not closed. See question 1.
4. **Proxy sign-in is not built.** The JDK switches off Basic authentication for CONNECT by default, and turning it
   back on needs a JVM-wide property, which a library must not set. See question 3.
5. **Building blocks for P024.** P023 has the generic blocks: JSON bodies, raw file bodies, steps run once or per
   document, and response mapping. These four are left to P024: the MULTIPART body; the packaging presets
   (TWO_STEP, MULTIPART, FILE_PER_CALL); `{field:<key>}` mapping by platform-fields key; and lists in JSON templates
   (for example `expectedDocuments[]`).
   Rules apply to the whole integration; there are no per-step rules yet.
6. **"Already received" for the whole attempt** only when every call in it was a replay. When some calls were new and
   some were replays, the attempt is SENT, and each call's own outcome is in `steps`.
7. **A timeout after sending is `CANT_CONNECT` / `INT_TIMEOUT`, as B4 says.** The request may have arrived. The app
   resends with the same idempotency key (`{package.id}:{doc.sourceDocumentId}` in the example).
8. **The engine checks the configuration again at every attempt**, under the current installation. A configuration
   saved on premises and later run on the hosted service is not run (`INT_CONFIG_INVALID`).
9. **History times are kept to the microsecond**, as Postgres keeps them, so the stored history and the audit give the
   same times.
10. **The private-CA tests on a machine that intercepts TLS.** The trust checks now run without the network
    (`TrustBundle.trustManager`). The proxy tests use plain http through the proxy. The three tests that need a TLS
    connection to succeed are skipped when the test sees that the certificate it receives is not the mock's own. They
    are skipped with an explanation; no machine-specific setting is in the repo.

## Not done, and why

- **Three end-to-end TLS tests did not run on this laptop.** They are `PrivateCaTest.trustedWithThePrivateCa`,
  `theHostNameIsAlwaysChecked` and `throughTheProxyWithThePrivateCa`.
  - Norton 360 intercepts TLS from Java even to 127.0.0.1, inside a proxy tunnel too. It re-signs the mock's
    certificate with "Norton Web/Mail Shield Untrusted Root", so no private CA can make the connection trusted.
  - The trust they rely on is tested without the network, and they run on any machine without TLS interception.
- **Proxy sign-in:** see decision 4 and question 3.
- **Out of scope for P023**, as the task split says:
  - MULTIPART, the packaging presets, field mapping and the Vero profile with its contract tests: P024.
  - Spring wiring and folder export: P025.
  - mTLS and callbacks: P026.
  - Intake: P027.
  - The push-API sign-in check: P028.

## Questions (numbered, each with a recommendation)

1. **Is the DNS-rebinding check strict enough for the hosted service?** Every address is checked right before each
   call, and the JDK's 30-second address cache normally means the client connects to an address that was checked.
   But the connection is not pinned to it. *Recommendation:* accept this for V1 and document it in P025's install
   guide. If the hosted service needs strict pinning, add it in P026, using the socket layer P026 builds for mTLS
   anyway.
2. **The three skipped TLS tests.** *Recommendation:* the owner either adds a Norton exclusion for Java on loopback
   connections (outside the repo), or accepts that they run only in CI or on another machine. Nothing in the repo
   needs to change.
3. **Proxy sign-in (user and password for the customer's proxy).** *Recommendation:* add it in P025 with the Spring
   properties. The install guide tells the customer to start the app with
   `-Djdk.http.auth.tunneling.disabledSchemes=` when their proxy needs Basic, and the library reads the proxy password
   by secret reference. The library itself never sets the JVM property.
4. **The generic default for 503.** It is an outage (decision 2), and busy only by a profile rule or a 429.
   *Recommendation:* keep this. Confirm it for destinations other than Vero, which often send 503 with Retry-After to
   mean busy. A profile can add that rule.
