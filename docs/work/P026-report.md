# P026 report — client certificates (mTLS) with rotation, connection pinning, signed callbacks, and "accepted, check later"

Date: 2026-11-22
Branch: `feature/integration-mtls-callbacks`
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| PR | What | CI runs | Merge |
|---|---|---|---|
| #11 | P026 B1–B5 (`feature/integration-mtls-callbacks`) | 45088604225 / 45088610205 ✘, 45089061556 ✘, 45089592873 ✘ (diagnosis run), 45090503883 (push) ✔, 45090508293 (PR) ✔ | rebase-merged by the README's script line (checks exit 0), `98e9c80` |
| (this report) | `docs/work/P026-report.md` | see its PR | |

- Main's own CI run on `98e9c80`: 45090984368 ✔. No release (as asked).
- **The failed runs, and why.** On this laptop the end-to-end mutual TLS calls are skipped (Norton intercepts TLS to
  127.0.0.1), so CI was their first run. No failed run was simply re-run to get it green; each was followed by a fix:
  - 45088604225: two failures in `MutualTlsTest`.
    - A test bug: the OAuth integrations were new ones without a certificate (`INT_CREDENTIAL_MISSING`).
    - A real one: a certificate the server does not trust came back as `INT_CONNECTION_LOST`. With TLS 1.3 the
      server refuses the certificate after the handshake, by closing the connection.
  - 45089061556: the first fix (a TLS 1.2 handshake read for the alert) did not help. A second test bug also showed:
    a token cached by the first attempt hid the "not presenting" case.
  - 45089592873: a run with temporary diagnosis output (exception classes and JDK messages only; removed in the next
    commit). It showed `java.net.SocketException: Broken pipe` in both the call and the TLS 1.2 handshake: the JDK
    server closes without the client ever reading an alert.
  - The final fix does not depend on alert text. In a TLS 1.2 handshake the server sends its own certificate first,
    which our trust check accepts before anything else happens; only then does it ask for the client's. So
    "asked for our certificate, then the handshake failed" means the server refused it. The probe's key manager
    records the request.
- **CI (ubuntu-latest): 1085 tests, 0 skipped, 147 reports** (P025: 1048, so 37 new). Per module, from
  `scripts/ci-no-skipped-tests.sh`:

| Module | Tests | | Module | Tests |
|---|---|---|---|---|
| platform-crypto | 20 | | platform-integration | 41 (38 before) |
| platform-crypto-spring | 5 | | platform-integration-spring | 7 |
| platform-crypto-testkit | 16 | | platform-integration-testkit | 155 (121 before) |
| platform-erase-log | 16 | | platform-licensing | 144 |
| platform-erase-log-spring | 5 | | platform-licensing-spring | 34 |
| platform-erase-log-testkit | 9 | | platform-licensing-testkit | 47 |
| platform-fields | 91 | | platform-notifications | 81 |
| platform-fields-testkit | 18 | | platform-notifications-spring | 41 |
| platform-identity-core | 113 | | platform-notifications-testkit | 132 |
| platform-identity-jdbc | 41 | | platform-setup | 37 |
| platform-identity-spring | 18 | | | |
| platform-identity-testkit | 14 | | | |

- Locally: BUILD SUCCESS, 8 skipped. Each skip is a TLS check that runs only where TLS to 127.0.0.1 is not
  intercepted: 3 in `PrivateCaTest`, 3 in `MutualTlsTest`, and 1 each in `HttpsIssuerTest` and
  `ProxySignInTunnelTest`. All of them ran and passed on CI. The local count is 1086, one more than CI's 1085 (148
  reports against 147). This is very likely a leftover report in `target/` from an earlier run; I did not look into
  it further.

## Independence

No other product's name or behaviour in code. The new test profile and spec (`example-async-v1`), the mock switches
and every test use made-up destinations; `NoProductNamesGuardTest` passed.

## What was built

**B2 first, because everything else uses it: the HTTP client.** I chose **Apache HttpClient 5 (5.3.1)**, as the task
suggested. 5.3.1 is the version Spring Boot 3.3 manages, so an app on Boot gets no second version. The JDK client
cannot take a resolver of our own, so it cannot hold a connection to the checked address. Apache's client looks names
up only through our `DnsResolver`. That resolver returns the address the guard checked for this call and refuses any
other name (only the installation's own proxy is looked up normally). `Outbound`'s API did not change (`Call`,
`Answer`, `Failure`, the codes), so the engine, the token client, the health check, the issuer fetch and callbacks all
go through it unchanged.

- Host name verification (`DefaultHostnameVerifier`) and SNI stay on the original name. The `Host` header is the
  name.
- Redirects, retries, cookies and compression are off. Headers that the HTTP layer sets (`Host`, `Content-Length`,
  ...) and values with line breaks are refused (`INT_VALUE_NOT_ALLOWED`), as the JDK client did.
- The first checked address is used. The JDK client also used one address. Apache would otherwise try the next
  address after a TLS failure and report the wrong failure, which a P023 test caught.
- Proxy calls still connect to the proxy, with the target checked before the CONNECT.
- Proxy sign-in is preemptive, sent by the client itself (see the decisions).

**B1, client certificates** (`credential` package):

- `SignIn.MutualTls(with, certificateForToken)`: alone, or with Basic, API key, bearer or OAuth 2.0. The token request
  can present the certificate too.
- `IntegrationCredentials`:
  - `upload` takes PKCS#12 or PEM. The key must match the certificate (a signed challenge), and an expired
    certificate is refused. It is stored sealed with platform-crypto's `ValueCipher` under the entity's customer.
  - `useKeystore` takes a path, a password reference and an alias. Nothing is copied, and a certificate renewed in
    the file is recorded when it is used.
  - Rotation: CURRENT and NEXT; `switchToNext`, `scheduleSwitch` / `switchDue`, `discardNext`. The old CURRENT
    becomes RETIRED and its key is dropped.
  - `load` is for the engine (`CertificateSource`).
  - Shown only as `CredentialView` (subject, issuer, serial, SHA-256, validity, keystore path and alias).
- `TestAction.runWithNext` tests NEXT.
- `CredentialExpiry.check(now)` warns at 30, 7 and 1 days (changeable) and once expired. An expired CURRENT refuses
  every call with `INT_CREDENTIAL_EXPIRED` (SIGN_IN_FAILED) before anything is sent.
- Storage: `int_credential` (one CURRENT and one NEXT per integration, enforced by unique partial indexes; triggers
  keep a retired row frozen and refuse deletes) and `int_credential_change` (numbered, frozen). Each change also goes
  to `IntegrationAudit.credentialChanged`, a default method.

**B3, signed callbacks** (`callback` package):

- Kind `CALLBACK` with `CallbackTarget(signingSecret)`.
- `CallEngine.sendCallback` makes one signed POST, classified like an API push.
- `Callbacks` is the outbox service:
  - `enqueue` takes one JSON object of at most 64 KiB.
  - `deliverDue` sends what is due. The back-off uses the notifications `RetryPolicy`: 1 minute, doubling up to 6
    hours, given up 24 hours after creation or the last resend. A Retry-After replaces the back-off, up to the longest
    delay.
  - REFUSED gives up at once.
  - `gaveUp` lists what was given up and `resend` sends it again. `purge` deletes finished callbacks.
- `int_callback_outbox` is the table and `PostgresCallbackOutbox` the store. Claims use `FOR UPDATE SKIP LOCKED` with
  a lease, so one sender at a time and safe across restarts.
- `CallbackSignature` signs and `CallbackVerifier` checks: signature, window, repeats, any of several secrets.

**B4, "accepted, check later":**

- `CallStep.Accepted` is allowed only on the last step, run once. The reference comes from a pointer (with a status
  path holding `{status.reference}`) or from the Location header (same host only).
- A 202 ends the attempt `Outcome.ACCEPTED` with a `PendingStatus`, which has `toJson` / `fromJson` for the app's
  store. No attempt is used.
- `CallEngine.checkStatus(stored, pending)` makes one call. It returns SENT, REFUSED (`INT_STATUS_FAILED`), ACCEPTED
  (with the next check time), UNCLEAR, or the status call's own failure.
- The profile format gets `steps[].accepted` and the sign-in type `MUTUAL_TLS`, both additions to the schema.
  `ProfileContract` checks the 202 answer, the reference, the status operation, the state and the codes.

**Spring wiring** (not asked for in B, but every other piece is wired there):

- `CredentialStore`, `IntegrationCredentials` (on the app's `ValueCipher` and `CustomerId` when present),
  `CredentialExpiry`, `CallbackOutbox` and `Callbacks` on the app's DataSource.
- `IntegrationWorker` sends due callbacks, makes timed switches and purges once a day. Properties
  `credentials.expiry-warning-days`, `callbacks.*`, `worker.*`.
- The call engine presents the certificates.

**B5, docs:**

- `docs/install/integration.md`:
  - section 1: new settings, the table rights of schema V2, and a warning about the HTTP library's own wire log;
  - section 2: no JVM option any more;
  - section 4: what pinning does;
  - new section 8: client certificates (upload or keystore, renewing with NEXT, expiry warnings);
  - new section 9: callbacks (the receiver's check in plain steps with a worked example, changing the secret);
  - new section 10: "accepted, check later";
  - section 11: every new code with its fix.
- `docs/design/integration-module.md`: a P026 decisions section.
- `docs/design/integration-profile-format.md`: `accepted`, `MUTUAL_TLS` and the contract kit's new checks.
- CLAUDE.md: the module table.

## Minimal examples

Upload and rotate a certificate:

```java
IntegrationCredentials creds = new IntegrationCredentials(new PostgresCredentialStore(ds), valueCipher,
        entity -> customerId, secretResolver, audit, Clock.systemUTC());
creds.upload(stored, p12Bytes, p12Password, null, "it-admin");            // CURRENT
creds.upload(stored, renewedP12, password2, null, "it-admin");            // NEXT
testAction.runWithNext(stored, "it-admin");                                // fake data, NEXT presented
creds.switchToNext(stored.id(), "it-admin");                               // or scheduleSwitch(id, at, by)
List<CredentialExpiry.Warning> soon = new CredentialExpiry(store).check(Instant.now()); // app raises IT notices
```

Send a callback:

```java
IntegrationConfig hooks = IntegrationConfig.callback(entity, "PRODUCT_A", "Case callbacks",
        Endpoint.of("https://receiver.example/hooks/cases"), new SignIn.None(), SecretRef.of("integration/hooks"));
UUID id = callbacks.enqueue(configs.create(hooks, "it-admin").id(), "{\"case\":\"C-1\",\"state\":\"DONE\"}");
callbacks.deliverDue(50);                     // the Spring IntegrationWorker does this every 15 s
callbacks.gaveUp(entity, "PRODUCT_A", 20);    // for the IT screen; callbacks.resend(id)
```

Verify one (an app receiving signed callbacks):

```java
CallbackVerifier.Result r = CallbackVerifier.verify(request::getHeader, bodyBytes,
        List.of(currentSecret, newSecretDuringAChange), seenIds, Instant.now());
if (!r.ok()) return status(401); // INT_CALLBACK_SIGNATURE / _EXPIRED; _REPLAYED: answer 200, do nothing
```

A 202 flow:

```java
CallStep submit = CallStep.of("submit", HttpMethod.POST, "/v1/jobs").withJsonBody("{\"package\":\"{package.id}\"}")
        .acceptingLater(CallStep.Accepted.byPointer("/jobId", "/v1/jobs/{status.reference}", "/state",
                Set.of("DONE"), Set.of("FAILED"), Set.of("QUEUED", "RUNNING")));
AttemptResult r = engine.attempt(stored, delivery);           // ACCEPTED, r.pending()
String keep = r.pending().toJson();                            // stored with the case
AttemptResult later = engine.checkStatus(stored, PendingStatus.fromJson(keep)); // at or after nextCheckAt
```

## New codes

- Client certificates: `INT_CREDENTIAL_FILE_INVALID`, `_PASSWORD_WRONG`, `_KEY_MISMATCH`, `_EXPIRED`,
  `_NOT_YET_VALID`, `_MISSING`, `_KEY_SERVICE_FAILED`, `_REFUSED` (from the TLS alert), `_KEYSTORE_UNREADABLE`,
  `_ALIAS_NOT_FOUND`, `_NEXT_EXISTS`, `_NO_NEXT`, `_CONFLICT`; `INT_CONFIG_MUTUAL_TLS_INVALID`.
- Callbacks: `INT_CALLBACK_SIGNATURE`, `_EXPIRED`, `_REPLAYED`, `_BODY_INVALID`, `_NOT_FOUND`;
  `INT_CONFIG_CALLBACK_INVALID`.
- 202: `INT_ACCEPTED`, `INT_STATUS_FAILED`, `INT_STATUS_REFERENCE_MISSING`, `INT_STATUS_UNKNOWN`;
  `INT_CONFIG_ACCEPTED_INVALID`.
- New outcome `ACCEPTED`. `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` is no longer given.

Every code IT acts on has a line in the install guide, section 11. The certificate codes also carry a `message()`.

## Tests asked for → where

| Asked | Where |
|---|---|
| mTLS: right, wrong, expired, NEXT tested and switched, keystore source | `MutualTlsTest` (testkit): `theRightCertificateIsPresented`, `aCertificateTheDestinationDoesNotTrustIsRefused`, `anExpiredCurrentCertificateRefusesTheCall`, `nextIsTestedThenSwitchedWithoutDowntime`, `nextBecomesCurrentAtItsSetTime`, `aKeystoreTheCustomerManages`, `uploadsAreCheckedAndSealed`, `withOAuthTheTokenRequestCanPresentTheCertificate`, `expiryWarningsAt30And7And1DaysAndAfter` |
| The key never in logs, results or the mock's records | `NoSecretsInLogsTest.nothingSecretFromCertificatesOrCallbacks` (new); `MutualTlsTest.uploadsAreCheckedAndSealed` |
| Encrypted storage through platform-crypto's testkit key service; DML-only user | `PostgresCredentialsAndCallbacksTest.certificatesSealedRotatedAndHistoryWithTheDmlOnlyUser` (platform-integration), `MutualTlsTest` (in memory) |
| Pinning: rebinding not followed; all P023 safety tests | `PinningTest` (testkit); `AddressGuardTest`, `PrivateCaTest`, `ProxyTest`, `HttpsIssuerTest` unchanged and green |
| Callbacks: signature vectors, retries and Retry-After, GAVE_UP and resend, restart safety, verifier refusals | `CallbackTest` (testkit): `theSignatureVector` (checked against `openssl dgst -hmac`), `retriesWithBackOffAndRetryAfterThenGivesUpAndIsResent`, `aRefusalGivesUpAtOnce`, `aClaimIsALeaseSoOneSenderHasItAtATime`, `theVerifierRefusesWhatIsNotRight`, `duringASecretChangeTheReceiverAcceptsBoth`; Postgres: `theOutboxClaimsWithALeaseAndSurvivesARestart`, `concurrentSendersNeverClaimTheSameCallback` |
| 202: accepted, still working, done, failed, a status call refused, Retry-After; a profile with it, by ProfileContract | `AcceptedLaterTest` (testkit), with the made-up `example-async-v1` profile and OpenAPI file |
| Spring | `IntegrationWiringTest.onTheAppsDataSourceWithTheDmlOnlyUser` (beans on Postgres, the worker sends a callback) |

## Files changed, commits

75 files, 34 of them new (6309 lines added, 284 removed).

- Commits on main (rebase-merged): `c3bba57` (task file and P025 decisions, as they were), `00cd523` (B1–B5),
  `30688e4`, `5ee5c5c` (temporary diagnosis output, removed in the next commit), `98e9c80` (the final fix).
- New code:
  - `credential/` (13 classes), `callback/` (6), `config/CallbackTarget`, `result/PendingStatus`;
  - `jdbc/PostgresCredentialStore`, `jdbc/PostgresCallbackOutbox`, SQL `V2__credentials_callbacks.sql`;
  - testkit `InMemoryCredentialStore`, `InMemoryCallbackOutbox`; Spring `IntegrationWorker`.
- Changed: `Outbound` (rewritten on Apache HttpClient 5), `AddressGuard.checked`, `CallEngine`, `TestAction`,
  `SignIn`, `CallStep`, `IntegrationConfig`, `ConfigJson`, `ConfigCheck`, `Template`, `Kind`, `Outcome`, `Rule`,
  `AttemptResult`, `SignInHeaders`, `ClientCredentialsTokens`, `IntegrationAudit`, `IntegrationSchema` (version 2),
  `IntegrationCode`, `IntegrationProfile`, the profile schema, `MockDestination`, `TestCertificates`,
  `ProfileContract`, the Spring auto-configuration and properties.
- POMs: root (`httpclient5.version`), platform-integration (crypto, httpclient5, crypto-testkit for tests),
  platform-integration-testkit (crypto-testkit for tests; the separate proxy surefire run removed).
- Docs: `docs/install/integration.md`, `docs/design/integration-module.md`,
  `docs/design/integration-profile-format.md`, CLAUDE.md.
- Tests:
  - new: `MutualTlsTest`, `PinningTest`, `CallbackTest`, `AcceptedLaterTest`, `PostgresCredentialsAndCallbacksTest`;
  - new test profile and spec: `example-async-v1`;
  - changed: `NoSecretsInLogsTest`, `ProxySignInTest`, `ProxySignInTunnelTest`, `PostgresIntegrationStoreTest`, the
    test `Pg` helpers, `IntegrationWiringTest`.

## Decisions I had to make

1. **Apache HttpClient 5.3.1** (above). It is the only new runtime dependency of platform-integration, together
   with its httpcore5 and slf4j-api. platform-integration now also depends on platform-crypto (plain Java, no
   dependencies).
2. **The JVM proxy option is gone.** Apache sends the proxy's Basic sign-in on CONNECT itself (preemptively, through
   its auth cache). So `-Djdk.http.auth.tunneling.disabledSchemes=` no longer matters, and the P025 refusal would now
   stop calls that work. `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` stays in the enum, so stored results can still be read,
   but it is never given. The startup warning and the separate surefire JVM for `ProxySignInTunnelTest` are removed.
   `ProxySignInTest` now proves that a default JVM sends the sign-in at once (one CONNECT, accepted). The old
   `Outbound(..., JvmProxySignIn)` constructor still compiles and ignores the JVM answer. (Question 1.)
3. **One address per call:** the first that passed the check (see B2).
4. **The certificate is the integration's credential, not part of its configuration.** Rotating a certificate is not
   a configuration version. It has its own numbered history and audit call, as the task asks ("versioned and audited
   like the configuration").
5. **A retired certificate keeps its details but not its key** (the sealed text is dropped). Nothing could use it
   again, and it keeps less key material at rest.
6. **Uploading while a NEXT exists is refused** (`INT_CREDENTIAL_NEXT_EXISTS`). IT discards it first. This keeps
   "up to two" simple and visible.
7. **A NEXT that is not valid yet, or already expired, cannot be switched to**, by hand or at its time. A timed
   switch then waits and logs a warning.
8. **The key manager always offers the integration's certificate**, whatever CAs the server names. So a server that
   does not trust it refuses it with a TLS alert (`INT_CREDENTIAL_REFUSED`), instead of the client sending none and
   the failure looking like a TLS problem.
9. **Callbacks: REFUSED gives up at once.** A 4xx means the receiver said no; retrying for 24 hours would not help.
   Every other result is retried, including sign-in refused and a missing secret, which IT can fix inside the window.
10. **Callback sign-in NONE is allowed for kind CALLBACK, also on the hosted service**: the signature proves the
    sender. Other types (for example mutual TLS) can be added on top.
11. **"Accepted, check later" only on the last step, run once.** Carrying on with later steps after a 202 would need
    resume-after-accept, which nobody asked for. A rule can never give ACCEPTED.
12. **One customer per installation in the Spring wiring** (the `CustomerId` bean). An installation that serves
    several customers defines its own `IntegrationCredentials` bean with its entity-to-customer function. The class
    takes that function.
13. **A refused client certificate under TLS 1.3** is told by one extra TLS 1.2 handshake to the same checked
    address, made only after a call that presented a certificate lost its connection (or failed TLS) before an
    answer. When that handshake asked for our certificate and then failed, the result is `INT_CREDENTIAL_REFUSED`;
    otherwise the first code stays. A server that offers only TLS 1.3 never asks in a 1.2 handshake, so there the code
    stays `INT_CONNECTION_LOST`. Nothing is sent over the extra connection. How CI showed it is under Result.
14. **TLS tests on the laptop.** Norton intercepts TLS to 127.0.0.1 here, so the end-to-end mutual TLS calls
    (`MutualTlsTest`: 3 tests skipped entirely, and some branches inside other tests) ran for the first time on CI.
    They pass there.

## Not done, and why

Nothing in the task is left out. Not asked for and not built: resuming further steps after a 202; sending the
expiry warnings as notices (the task says the app does that).

## Questions (numbered, each with a recommendation)

1. **`INT_PROXY_SIGN_IN_DISABLED_BY_JVM` is no longer given.** Should it be removed from the enum in the next
   release, which would break code that names it? *Recommendation:* keep it until 1.0, documented as "no longer
   given".
2. **Tokens bound to a certificate.** A token cached for a client stays held across a certificate switch. A
   destination that binds tokens to the certificate refuses the old token once, and the engine then drops it, so the
   next attempt gets a new one. Should a switch drop the integration's held token at once? *Recommendation:* yes, in
   a small follow-up (a call from `switchToNext` into the token cache).
3. **Expiry notice wording and the "already told" record** are app side. *Recommendation:* pass `CredentialExpiry`
   to the app threads with the suggestion to remember (credential ID, threshold).
