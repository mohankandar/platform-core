# P025 report — Spring wiring, folder export with a manifest, proxy sign-in, and CI hardening

Date: 2026-11-22
Branch: `feature/integration-spring-folder`
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| PR | What | CI runs | Merge |
|---|---|---|---|
| #9 | P025 B0–B5 (`feature/integration-spring-folder`) | 45081334170 (push) ✔, 45081354998 (PR) ✔ | rebase-merged by the new script line (checks exit 0), `b98fdaa` |
| (this report) | `docs/work/P025-report.md` | see its PR | |

- Main's own CI run on `b98fdaa`: 45081765033 ✔. No release (as asked).
- **CI (ubuntu-latest): 1048 tests, 0 skipped, 142 reports.** Per module, from `scripts/ci-no-skipped-tests.sh`:

| Module | Tests | | Module | Tests |
|---|---|---|---|---|
| platform-crypto | 20 | | platform-integration | 38 |
| platform-crypto-spring | 5 | | platform-integration-spring (new) | 7 |
| platform-crypto-testkit | 16 | | platform-integration-testkit | 121 |
| platform-erase-log | 16 | | platform-licensing | 144 |
| platform-erase-log-spring | 5 | | platform-licensing-spring | 34 |
| platform-erase-log-testkit | 9 | | platform-licensing-testkit | 47 |
| platform-fields | 91 | | platform-notifications | 81 |
| platform-fields-testkit | 18 | | platform-notifications-spring | 41 |
| platform-identity-core | 113 | | platform-notifications-testkit | 132 |
| platform-identity-jdbc | 41 | | platform-setup | 37 |
| platform-identity-spring | 18 | | | |
| platform-identity-testkit | 14 | | | |

- Locally: BUILD SUCCESS, 1048 tests (1049 counted, because a report of a temporary test I ran and deleted was
  still in `target/`), 5 skipped. All five are TLS checks that skip only where TLS to 127.0.0.1 is intercepted (this
  laptop's Norton): 3 in `PrivateCaTest`, 1 each in `HttpsIssuerTest` and `ProxySignInTunnelTest`. They all ran and
  passed on CI.
- **The 1990 / 943 question.** P031's 1990 tests and 6 skipped are exactly twice the true numbers. Main before this
  task (`435e104`) has **995 tests, 3 skipped, 132 reports**, counted from the Surefire reports. P031 added up every
  `Tests run:` line of the Maven log: the per-class lines *and* each module's total line, so every test counted twice.
  It was not a double run. 943 (P030) → 995 (P031) is exactly P031's 52 new tests. Fix: `ci-no-skipped-tests.sh` now
  prints the tests per module, and reports quote that.
- New tests in P025: 53 (1048 − 995).

## Independence

No other product's name or behaviour in code. `NoProductNamesGuardTest` passes. Tests use `PRODUCT_A` / `PRODUCT_B`,
made-up destinations (`api.example.test`, `fileserver.example.test`), the generic mock destination, proxy, folder and
issuer.

## What was built

### B0 The intermittent `ProxyTest`, and the merge rule

**Cause.** `MockProxy` forwarded a plain-http request and then closed the connection after one answer. But it relayed
the destination's answer as it came, and the JDK `HttpServer` behind it sends no `Connection: close` (checked: its
answer to a `Connection: close` request carries none). So the HTTP client put the proxy connection back in its pool.
The next request (in `ProxyTest`, the upload step) could take that pooled connection just as the proxy closed it, and
was lost: `CANT_CONNECT INT_CONNECTION_LOST` at the upload step, as P031 saw on CI twice.

**Proof of the cause.** With the old `MockProxy` and a 100 ms pause before it closes its side (only to widen the
window), the second request of a loop already fails with `IOException: HTTP/1.1 header parser received no bytes`.
With the fixed `MockProxy` and the same pause, 300 requests in a row pass (`MockProxyTest`).

**Fix (testkit only, the test is neither retried nor skipped).** `MockProxy` now relays exactly one request: the head,
then exactly its body (by `Content-Length`, or chunked). The answer goes back with `Connection: close`, so the client
never reuses that connection. Before a 403 or 407, it reads the request body, so the client sees the refusal and not
a reset. New `MockProxyTest` (4 tests): the answer says `Connection: close`; 300 POSTs of 64 KiB in a row through one
client all arrive whole; a chunked body; the Basic sign-in check.

**30-run proof:** `mvn -o -pl platform-integration-testkit -am test -Dtest=ProxyTest` was run 30 times in a loop: **30 of 30 passed**.
Each run ran the class in both of the module's surefire JVMs, so that is 60 class runs and 240 test runs, with no
failure. The CI runs above (push, PR, main) ran it again, green. Before the fix it had failed 2 times in about 10 CI

**Merge rule** (`docs/work/README.md`, routine step 5 and release step 2): the merge is one script line that merges
only when the checks' exit status is 0:
`gh pr checks <n> --watch --fail-fast && gh pr merge <n> --rebase --delete-branch` (PowerShell:
`…; if ($LASTEXITCODE -eq 0) { gh pr merge … }`). If a required check fails: stop and report, never merge, never tag.
A tag also waits for main's own CI run on the merge commit (`gh run watch <id> --exit-status`).

### B1 `platform-integration-spring` (new module, in the BOM, the root POM and CLAUDE.md)

- **Properties** `platform.integration.*`, checked at startup with every problem listed at once
  (`INTEGRATION_SETTINGS_INVALID`; problems name the property, never a value):
  - `installation` (`ON_PREMISES` / `HOSTED`, required), `allow-internal-addresses`, `allow-sign-in-none` (both
    refused on hosted), `private-ca-file` (absolute PEM file, checked);
  - `proxy.host`, `.port`, `.bypass`, `.user`, `.password-secret` (a secret reference; a pasted address or password
    is refused without being repeated);
  - default `limits.*` (connect/read timeout, max file/response/package size as Spring `DataSize`, max documents);
  - `profile-folder`;
  - `health.enabled`, `health.cache-for` (5 s – 1 h).
- **Beans** (each backs off for the app's own): `IntegrationSettings`, `Installation`, `integrationDefaultLimits`,
  `Outbound` (with the proxy sign-in), `CallEngine`, `IntegrationStore` (Postgres on the app's DataSource after
  `IntegrationSchema.verify`, read only), `IntegrationConfigs` (with the app's `FieldLists` if given), `FolderExport`
  (the app's `ExportFiles` if given, else `LocalFiles`), `TestAction`, `ProfileFolder` (new `profile.ProfileFolder`
  in the core module: lists the folder's `*.json` files, loads and checks one by name, no paths).
- **App beans:** `SecretResolver` and `IntegrationAudit`. Without them startup refuses, naming each missing one.
  Without a DataSource or an `IntegrationStore` it refuses too. A `FailureAnalyzer` turns these into Spring Boot's
  "APPLICATION FAILED TO START".
- **Health:** contributor `integrations`, one entry per enabled API push or folder export integration, named by its
  ID. The list is read from the store when asked (new `IntegrationStore.enabled()`, Postgres and in-memory). The
  details are `outcome` and `code` only. Healthy and busy are UP; anything else is DOWN. Results are kept for
  `cache-for`, and a new version is checked again.
- **Startup warning** when the proxy has sign-in but the JVM disables Basic for tunnels.
- **Identity, while here:** `platform-identity-spring` gets three small auto-configurations, with optional
  dependencies:
  - an `IssuerFetch` = `OutboundIssuerFetch` over the `Outbound` bean (when platform-integration-spring is there);
  - a `LoginConnectionStore` = `PostgresLoginConnectionStore` on the DataSource after `IdentitySchema.verify`;
  - a `DevelopmentLicence` from platform-licensing-spring's licence service (else `NONE`);
  - `LoginConnections` when a store and a fetch exist.
- **The https `MockIssuer`** missing from P031: `MockIssuer.startHttps(SSLContext)`, named `https://localhost:<port>`.
  It takes the server context from the integration testkit's `TestCertificates`, so the identity testkit gets no new
  dependency. Tested in `HttpsIssuerTest`: the test sign-in over `OutboundIssuerFetch` is untrusted without the
  private CA, and passes with it.

**Minimal Spring example** (properties, then a folder export):

```properties
platform.integration.installation=ON_PREMISES
platform.integration.proxy.host=proxy.corp.example
platform.integration.proxy.port=3128
platform.integration.proxy.user=svc-documents
platform.integration.proxy.password-secret=integration/proxy-password
platform.integration.profile-folder=/etc/documents/integration-profiles
```

```java
@Bean SecretResolver secretResolver(...) { ... }          // the app's (Vault)
@Bean IntegrationAudit integrationAudit(...) { ... }      // the app's audit

// IT saves the integration once (audited, versioned):
StoredIntegration saved = configs.create(IntegrationConfig.folderExport(entityUuid, "PRODUCT_A", "Share export",
        FolderTarget.of("\\\\fileserver\\exports\\in")).withLimits(defaultLimits), "it-admin");

// Each package (the app keeps its own retry schedule):
AttemptResult r = folderExport.attempt(configs.current(id).orElseThrow(),
        Delivery.of(packageId, documents, Map.of()).withFields(caseFields),
        new ExportInfo("ENT-0001", "{\"caseRef\":\"C-1\"}"));
switch (r.outcome()) { case SENT, ALREADY_RECEIVED -> done(r.values().get(FolderExport.FOLDER_NAME_VALUE)); ... }

testAction.run(saved, "it-admin");                         // fake PDF + manifest, removed again, audited
```

This is the code path of `IntegrationWiringTest.withTheAppsBeansEverythingIsWiredAndAFolderExportRuns`.

### B2 Proxy sign-in

- `ProxySettings` gains `user` and `password` (a `SecretRef`), via `withSignIn(user, ref)`. `toString` shows no
  reference. A user with `:` or control characters is refused.
- `Outbound(installation, secretResolver)` reads the password at every call. It sends
  `Proxy-Authorization: Basic …` on the request; the JDK puts it on the CONNECT for https and keeps it off the
  tunnelled request (tested: the destination never sees it). `Outbound(installation)` refuses a proxy with sign-in.
- The library never sets JVM properties. `safety.JvmProxySignIn` reads `jdk.http.auth.tunneling.disabledSchemes`
  (and `…proxying…`) as the JDK does: the start option first, then the JDK's `conf/net.properties`. If Basic is
  disabled for tunnels, an https call through a proxy with sign-in stops before connecting with
  `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` (`NOT_CONFIGURED`). Its message names `-Djdk.http.auth.tunneling.disabledSchemes=`.
- A 407 from the proxy gives `INT_PROXY_SIGN_IN_REFUSED` (`CANT_CONNECT`). That holds for plain http, for CONNECT
  (the JDK hands back the 407 answer), and for token requests (the token client keeps the proxy codes instead of
  "token unreachable").
- `IntegrationCode.message()`: the codes IT acts on (proxy, folder export) carry a plain-words message.
- **Tests:** `MockProxy.requireBasic(user, password)`.
  - `ProxySignInTest` (default JVM): right, wrong and missing credentials on plain http and token requests; the
    password rotated in without a restart; `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` for https, with nothing sent.
  - `ProxySignInTunnelTest`: its own surefire execution (`proxy-sign-in-tunnel`, argLine
    `-Djdk.http.auth.tunneling.disabledSchemes=`). Right, wrong and missing credentials on CONNECT; the JVM really
    has the option.

### B3 Folder / network-share export with a manifest written last

As specified. The format and the outcome table are in `docs/design/integration-module.md`, new section "Folder
export and its manifest".

- **Configuration:** `IntegrationConfig.folderExport(entity, product, name, FolderTarget)`, a new record component
  `folder`, stored in `platform-integration-config/1` as `"folder":{"folder","folderName","fileName"}`. Older
  versions without it still read.
  - `FolderTarget.validFolder`: a drive, share or Unix absolute path; no `.`/`..`, no `* ? " < > |`, no stream `:`.
  - Folder and file name templates are a new `Template.Place.NAME`: one segment, placeholders percent-encoded as in
    P023.
  - The rendered name must be valid on Windows and Unix: no device names, no trailing dot or space, never ending in
    `.partial`.
  - The default file name is the document's own name made safe. Case-insensitive duplicates get ` (2)`, and a
    document named `manifest.json` becomes `manifest (2).json`.
  - Save check: folder placeholders are `{package.*}`, `{value.*}` and `{field:*}`; file names may also use
    `{doc.*}`. A sensitive field is never allowed in a name, even when allowed for the manifest. No endpoint, steps,
    health call or sign-in bundle. Refused on hosted.
- **Writing:** exactly steps 1–5 of the task, through a small file port (`folder.ExportFiles`; `LocalFiles` forces
  each file to the disk on close and reads it back for the SHA-256 and size check).
- **Outcomes:** all the task's codes, plus `INT_FOLDER_WRITE_FAILED` and `INT_TIMEOUT` (decisions 3 and 4).
- **Health and Test action:** as asked. The Test action is in `TestAction`: a new constructor takes the
  `FolderExport`, and the result is audited like API push.
- **`MockFolder`** (testkit): switches for missing, read-only, unreachable, full after N bytes, reported space, slow,
  a corrupting read-back, and an existing package folder.

**Example manifest**, as written by the code (a test run in this session, with two small documents; the file is one
compact line, shown indented here):

```json
{
  "format": "platform-export-manifest/1",
  "packageId": "PKG-2026-0042",
  "correlationId": "fa2a5dc6-459c-445a-9339-81db312cd5a1",
  "createdAt": "2026-11-22T23:00:53Z",
  "product": "PRODUCT_A",
  "entitySlotId": "ENT-0001",
  "integration": { "id": "ffea10ca-859c-4ea4-a14c-5f51002fe36d", "configVersion": 3 },
  "documents": [
    { "fileName": "statement.pdf", "sourceDocumentId": "doc-1", "contentType": "application/pdf", "size": 18,
      "sha256": "0e0c4bec05ae31d537ce30a5c68ec87d461395935d32b2745c9fa5f61e35389e" },
    { "fileName": "letter.pdf", "sourceDocumentId": "doc-2", "contentType": "application/pdf", "size": 15,
      "sha256": "d9c2649d18ac2776f94479589c8247d72e7c070977240abf79e33a2ffd54302c" }
  ],
  "fields": {
    "fields": [
      { "currency": "USD", "key": "amount", "type": "MONEY", "value": "$1,250" },
      { "key": "holder_name", "type": "TEXT", "value": "Ann Example" },
      { "key": "reference_number", "type": "TEXT", "value": "R-42" }
    ],
    "format": "platform-field-values/1"
  },
  "app": { "caseRef": "C-9", "levels": [1, 2] }
}
```

`holder_name` is sensitive and listed in `allowedSensitiveFields`. The delivery's other sensitive field, `tax_id`, is
not allowed, so it is left out. The `fields` object is platform-fields' own `platform-field-values/1` document, as
written (values as entered). `FolderExportTest.writesTheDocumentsThenTheManifestThenRenamesTheFolder` checks a
manifest like this against the schema.

### B4 JSON "omit when missing" (P024 question 1)

`{name?}` in a JSON body template, as the whole value of an object member, either in a string
(`"loan":"{value.loan?}"`) or bare (`"amount":{field:loan_amount?}`, `{package.documentCount?}`). When the value is
missing, the whole member is left out with one neighbouring comma: the comma before it, or the one after it when it
was the first member. This works inside `{#each doc}` too. A value that is present but cannot be placed is refused,
not left out (`Template.Values.has`).

It is refused at save, with `INT_CONFIG_OMIT_PLACE`, inside a longer string, as a key, in a list, next to another
placeholder, in a path, in a header or in a name. In a profile it is reported as `INT_PROFILE_TEMPLATE`. The save
check also renders the body with every optional member left out, to prove it is still JSON. There are still no
expressions.

### B5 Install guide

`docs/install/integration.md`, in plain words for the customer's IT. It covers:
- the properties and the database rights;
- the proxy and the start option;
- the private CA;
- internal addresses and what the address check does and does not guarantee (each resolved address checked right
  before each call; not pinned yet; pinning comes with mTLS, and the hosted service needs it);
- the profile folder and how setup checks profile files;
- the service account's rights on an export share (Modify on share and NTFS; delete is needed for `.partial`, the
  probe and the Test folder);
- health and the liveness/readiness groups;
- every `INT_*` code a customer can see, with the fix.

### Also

`Rule.busyWhen503WithRetryAfter()` and `Rule.healthBusyWhen503WithRetryAfter()` are the ready-made rule "503 with
Retry-After means busy" for the configuration screen ("Decisions on the P023 report", item 4).

## Tests asked for → where

| Asked | Where |
|---|---|
| B0 cause fixed in the testkit, 30 runs, CI runs | `MockProxyTest` (4); `ProxyTest` 30-run loop (above); CI runs (above) |
| B1 context with and without the app beans | `IntegrationWiringTest.withoutTheAppsBeansStartupRefusesNamingEachOne`, `…withTheAppsBeansEverythingIsWiredAndAFolderExportRuns` |
| B1 with and without the proxy | `IntegrationWiringTest.withTheProxyCallsGoThroughItWithItsSignIn` (with); the test above (without: no proxy) |
| B1 bad settings, health, profile folder, no Actuator, DML-only user, schema missing | `IntegrationWiringTest` (7 tests in all) |
| B1 identity `LoginConnections` + `OutboundIssuerFetch` | `LoginConnectionsWiringTest` (3: with integration, without store/fetch, identity tables checked first) |
| B2 right, wrong, missing; forked JVM with the option; one without proving the code | `ProxySignInTest` (6, default JVM), `ProxySignInTunnelTest` (4, execution `proxy-sign-in-tunnel`) |
| B3 happy path, every code, idempotency, schema, health, Test, mock switches | `FolderExportTest` (10), `FolderConfigTest` (6) |
| B3 temporary folder and Windows-style paths | `FolderExportTest.realFoldersOnThisSystem`, `…windowsStylePathsWhereTheJdkAllowsThem` (forward-slash drive path on Windows; a share path on Linux is not a path there); UNC rules in `FolderConfigTest` |
| B4 happy path and every refusal | `OmitWhenMissingTest` (7) |
| No secrets or document text in logs (folder export, proxy sign-in) | `NoSecretsInLogsTest.nothingSecretOrContentFromProxySignInOrFolderExport` (proxy password right and wrong, its Base64, document text, a sensitive field allowed into the manifest, the folder path, app section) |
| DML-only user | `IntegrationWiringTest.onTheAppsDataSourceWithTheDmlOnlyUser` (store, `enabled()`); folder export uses no database |
| https `MockIssuer` | `HttpsIssuerTest` (3) |
| Ready-made 503 rule | `ReadyMadeRuleTest` (2) |

## New codes

- Proxy: `INT_PROXY_SIGN_IN_REFUSED`, `INT_PROXY_SIGN_IN_DISABLED_BY_JVM`.
- Folder export: `INT_FOLDER_NOT_FOUND`, `INT_FOLDER_NO_PERMISSION`, `INT_FOLDER_FULL`, `INT_FOLDER_UNREACHABLE`,
  `INT_FOLDER_EXISTS_DIFFERENT`, `INT_FOLDER_WRITE_FAILED` (with `INT_INTEGRITY_MISMATCH`, `INT_TIMEOUT`,
  `INT_ALREADY_RECEIVED` reused).
- Configuration: `INT_CONFIG_OMIT_PLACE`, `INT_CONFIG_FOLDER_INVALID`, `INT_CONFIG_FOLDER_HOSTED`.
- Spring startup: `INTEGRATION_SETTINGS_INVALID` (exception text, with each problem).

## Files changed, commits

- `ec02604` docs: P025 task; `452c359` feat (P025 B0–B5), merged as `b98fdaa`.
- New module `platform-integration-spring`. New in platform-integration: `folder/` (`FolderExport`, `ExportFiles`,
  `LocalFiles`, `ExportManifest`, `ExportInfo`), `config/FolderTarget`, `safety/JvmProxySignIn`,
  `profile/ProfileFolder`, `schema/platform-export-manifest-1.schema.json`. New in identity-spring: three
  auto-configurations. New in the testkit: `MockFolder`.
- Changed: `Template`, `ConfigCheck`, `ConfigJson`, `IntegrationConfig`, `IntegrationCode`, `Rule`, `Outbound`,
  `ProxySettings`, `CallEngine`, `TestAction`, `SignInHeaders`, `ClientCredentialsTokens`, `ProfileValidator`, stores
  (`enabled()`), `MockProxy`, `MockIssuer`, POMs (root, BOM, identity-spring, integration-testkit), CLAUDE.md,
  `docs/work/README.md`, `scripts/ci-no-skipped-tests.sh`, the two integration design docs, `docs/install/integration.md`.

## Decisions I had to make

1. **The manifest's "identical".** Two attempts of one package differ in `createdAt` and `correlationId`, and after a
   configuration change in `integration.configVersion`. "Same package" ignores exactly these three; everything else
   (documents, checksums, fields, the app's section) must be equal. So an app section that changes per attempt
   counts as "different". The app should keep it stable per package.
2. **Sensitive case fields in the manifest** go only if allowed (`allowedSensitiveFields`); the others are left out
   silently, not refused. The manifest takes every field of the delivery, unlike a template, which names its
   fields. **Names never carry a sensitive field**, even when allowed: everyone with access to the folder sees names.
3. **`INT_FOLDER_WRITE_FAILED` (`OUTAGE`)** is an extra code for a write failure that is none of the listed causes.
   `INT_FOLDER_FULL` is `OUTAGE` (the destination cannot take it now); `INT_FOLDER_NO_PERMISSION` is
   `NOT_CONFIGURED` (IT must act).
4. **Write timeout:** writing one file must finish within the integration's read timeout (`INT_TIMEOUT`,
   `CANT_CONNECT`), so a hanging share cannot block an attempt for ever. This is what `MockFolder`'s "slow" switch
   tests.
5. **Folder export is refused on the hosted service** (`INT_CONFIG_FOLDER_HOSTED`): a folder there would be our own
   disk.
6. **Proxy sign-in is sent preemptively** (`Proxy-Authorization` on the request) rather than through a JDK
   `Authenticator`. It goes in the first request; the JDK applies its disabled-schemes rules to it either way, and
   the check before connecting gives the specific code.
7. **Health status:** busy is UP (the destination answers; the details say `BUSY`). The install guide says to keep
   `integrations` out of liveness/readiness probes. Results are cached (`cache-for`, 60 s), so `/actuator/health` does
   not call every destination each time.
8. **`ProfileFolder`** went into the core module (plain Java), so apps without Spring have it too. The Spring bean only
   wraps it.
9. **Tests that depend on the OS or on TLS interception** are one test with a branch inside (Windows / other), so CI
   skips nothing. The TLS-success checks keep P023's rule: skipped only where TLS to 127.0.0.1 is intercepted (this
   laptop), and CI forbids skips.
10. **Test counting:** see "Result". Not a double run, so nothing to fix in the build. The skip script now prints the
    tests per module, so reports quote one counted number.

## Not done, and why

Nothing from the task. Not built because it was not asked for: watched-folder intake and API polling (P027);
connection pinning (P026, with mTLS); a UNC test against a real share (as the task says, UNC is covered by the path
rules and, on Windows, by the JDK's own handling).

## Questions (numbered, each with a recommendation)

1. Should a folder export's manifest also carry each document's `doc.attr.*` values (for example a sensitivity
   level)? Today they go only in the app section. **Recommendation:** no; the app section is where app-specific
   per-document data belongs, and the envelope stays stable.
2. `INT_FOLDER_FULL` is `OUTAGE`. Should a full disk raise an IT notice at once instead of waiting for the app's
   retry schedule? **Recommendation:** yes, on the app side: the first `INT_FOLDER_FULL` of a destination gives one IT
   notice (as the busy-spell notice does).
3. The `integrations` health contributor is DOWN when one destination is down. Should it instead be UP with each
   entry's own status, so the overall health never depends on another system? **Recommendation:** keep it as built,
   because the install guide keeps it out of the probes; revisit if an installation needs it in readiness.
