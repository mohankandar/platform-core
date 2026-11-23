# P028 report — push-API caller check, the outbound safety module, platform-level grants, release `platform-core-v0.3.0`

Date: 2026-11-22
Branch: `feature/push-caller` (then `release/platform-core-0.3.0`, `chore/0.4.0-snapshot`, `docs/p028-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

- **Release:** tag `platform-core-v0.3.0` on commit `b13b05ada347f59fb58147548aabe56584534106` (main). Release
  workflow run 45110591232 passed: version checked, `mvn -B verify`, deployed to GitHub Packages (checked:
  `platform-outbound` 0.3.0, `platform-outbound-spring` 0.3.0, `platform-identity-spring` 0.3.0 listed), GitHub
  release from `docs/releases/platform-core-v0.3.0.md`.
- **PRs**, each merged with the README's stop-on-failed-check line, exit status 0:
  - #15 `feature/push-caller` (P028 B1–B5). CI runs 45109035674 (push) and 45109040751 (PR) passed.
  - #16 `release/platform-core-0.3.0` (version 0.3.0, release notes). CI runs 45109613734 and 45109619107 passed;
    main's own run on the merge commit, 45110057218, passed before the tag.
  - #17 `chore/0.4.0-snapshot` (main to `0.4.0-SNAPSHOT`). CI runs 45111513642 and 45111518485 passed.
  - This report through its own PR.
- **Tests on CI (PR #15): 1188, 0 skipped** (`scripts/ci-no-skipped-tests.sh`: "Tests run: 1188, skipped: 0").
  P027 had 1141. Locally the same 1188 pass; 13 TLS tests skip only on the owner's laptop, where TLS to 127.0.0.1 is
  intercepted.
- **Per module (local run, same counts as CI):** identity-core 117, licensing 144, licensing-testkit 47,
  identity-testkit 14, notifications 81, notifications-testkit 132, licensing-spring 34, identity-jdbc 65,
  **outbound 8**, **outbound-spring 4**, crypto 20, crypto-testkit 16, fields 91, integration 50,
  integration-testkit 196, identity-spring 28, notifications-spring 41, setup 39, fields-testkit 18,
  crypto-spring 5, erase-log 16, erase-log-testkit 9, erase-log-spring 5, integration-spring 8.
- No re-run of a failed check was needed.

## Independence

**No other product's name or behaviour in code.** Tests use `PRODUCT_A` / `PRODUCT_B`, made-up claim names
(`delivery_entity`, `sending_system`, `app_client`, `appid`), made-up clients (`0oaSender01`) and two throwaway
self-signed certificates (`CN=push-caller-one.example`, `CN=push-caller-two.example`; public parts only).
`PushCallerSettings` holds no product's claim names: the client ID defaults are the generic login-system claims
`client_id`, `azp`, `cid`, `sub`; entity and source claims are each product's configuration
(`platform.identity.push.*`). `NoProductNamesGuardTest` passes.

## What was built

### B1. `PushCaller` (identity-core; Postgres and in-memory)

- Package `com.documentplatform.identity.push`: `PushCaller`, `PushCallerInput` (verified token: issuer, audiences,
  claims; and/or a verified `X509Certificate`), `PushCallerSettings` (product, client ID claims, optional entity and
  source claims, `requireTokenAndCertificate`, cache time), `PushCallerIdentity` (customer, slot ID, entity UUID,
  source key, client ID, `Via` TOKEN / CERTIFICATE / TOKEN_AND_CERTIFICATE), `PushCallerResult` (identity or
  `PushRefusal`, the certificate's fingerprint for IT; `NEUTRAL` text for the caller), `PushRefusal` (the nine
  `IDN_PUSH_*` codes).
- Lookup port `PushCallerDirectory` (three reads: ACTIVE connections by product and issuer; whether a customer has an
  ACTIVE connection; a client ID's bindings with their entity's status). Postgres: `PostgresPushCallerDirectory`
  (identity-jdbc, SELECT only). In memory: `InMemoryIdentityStore.pushCallerDirectory()`.
- Rules, token: issuer → ACTIVE connections of the product with exactly that issuer (`IDN_PUSH_NO_CONNECTION`); the
  token's audiences must hold the connection's `apiAudience` (`IDN_PUSH_AUDIENCE`); the client ID is the first of the
  configured claims that is a non-blank string (`IDN_PUSH_NO_CLIENT_ID`); bindings of that client under those
  customers: none `IDN_PUSH_NOT_BOUND`, none ACTIVE `IDN_PUSH_BINDING_OFF`, entity not ACTIVE
  `IDN_PUSH_ENTITY_NOT_ACTIVE`; a configured entity claim (slot ID or entity UUID) or source claim present in the token
  and different from the binding `IDN_PUSH_CLAIM_MISMATCH`.
- Certificate: client ID `cert-sha256:` + SHA-256 of the DER, lowercase hex (`PushCaller.fingerprint`); bindings as
  above with `IDN_PUSH_CERT_NOT_BOUND`; the customer's connection for the product must be ACTIVE. Two fingerprints for
  the same entity and source both work (rotation overlap).
- Both presented (or `requireTokenAndCertificate`): both checked; different customer, entity or source
  `IDN_PUSH_TOKEN_AND_CERT_DIFFER`; a missing half when both are required gives `IDN_PUSH_NO_CLIENT_ID` (no token) or
  `IDN_PUSH_CERT_NOT_BOUND` (no certificate).
- Cache: allowed and refused answers per caller (issuer, audiences, client ID, the configured claims' values,
  fingerprint) for `cacheFor` (default 30 s, 0 to 10 min; at most 10 000 kept); `forget()` drops them. A store error
  throws and is never kept; nothing falls back.
- Log: WARNING `Push caller refused for <product>: <code>` (plus ` (certificate cert-sha256:…)`); DEBUG on admission
  with slot and source. Never a token, claim, client ID or certificate content.
- Beside `MachineEntityBindings` (unchanged): `admission.MachineSourceBindings` (entity, slot, source for a
  `ConnectionKey` and client principal) with `push.DirectoryMachineSourceBindings` over any directory.

### B2. Spring (identity-spring)

- `IdentityPushCallerAutoConfiguration`: when `platform.identity.push.product-code` is set, a `PushCaller` bean on the
  app's `PushCallerDirectory` or `PostgresPushCallerDirectory` on the app's DataSource after `IdentitySchema.verify`.
  Properties `PushCallerProperties` (`product-code`, `client-id-claims`, `entity-claim`, `source-claim`,
  `require-token-and-certificate`, `cache-for`). Bad settings or no directory stop the app
  (`IDENTITY_PUSH_SETTINGS_INVALID: …`).
- `PushCallerRequests`: `input(Jwt)`, `input(Jwt, X509Certificate)` (either may be null, not both),
  `certificate(Object requestAttribute)` for `jakarta.servlet.request.X509Certificate`. The issuer is the token's raw
  `iss`.
- No filter that decides on its own. The class's Javadoc and `docs/install/identity.md` section 9 say: trust a
  forwarded-certificate header only from the app's own TLS-terminating proxy, configured by the app; platform-core
  reads no headers.

### B3. Setup and docs

- Setup accepts `cert-sha256:` client IDs (the reader's rules are unchanged; they already allowed them), marks them
  `(client certificate)` in the plan, and refuses a `cert-sha256:` value that is not 64 lowercase hex digits
  (`line N: CLIENT_ID_INVALID`, `SETUP_CLIENT_BINDINGS_INVALID`).
- `docs/install/setup.md` 3a: getting the fingerprint (a PowerShell and an openssl command, checked to give the same
  value) and renewing a certificate without a break. `docs/install/identity.md` section 9 "Push-API callers": claims,
  settings, refusals, cache time. `docs/design/setup.md` 6.1: the additive line; section 8: P028.
  `docs/design/integration-module.md`: a P028 decisions section.

### B4. `platform-outbound` (+ `platform-outbound-spring`)

- **Moved** (git renames) from platform-integration to `com.documentplatform.outbound`: `Installation`,
  `AddressGuard` (with the pinning resolver), `TrustBundle`, `ProxySettings`, `Outbound` (the Apache HttpClient 5
  set-up, no redirects, timeouts, answer limit), `RetryAfter`, and `ClientCertificate` / `CertificateInfo` (the
  client needs them for mutual TLS). New `OutboundCode` (`OUT_*`).
- Integration maps every outbound failure with `IntegrationCode.of(OutboundCode)` to the `INT_*` code of the same
  name; no integration result changes (all 196 integration-testkit tests pass unchanged apart from imports; the five
  that call the moved classes directly now expect `OUT_*` and assert the mapping back).
- **Codes that moved:** `INT_CANT_CONNECT`, `INT_HOST_UNKNOWN`, `INT_CONNECT_TIMEOUT`, `INT_TIMEOUT`,
  `INT_CONNECTION_LOST`, `INT_TLS_FAILED`, `INT_ADDRESS_BLOCKED`, `INT_ADDRESS_NOT_SECURE`, `INT_PROXY_REFUSED`,
  `INT_PROXY_SIGN_IN_REFUSED`, `INT_CREDENTIAL_REFUSED`, `INT_SECRET_NOT_FOUND`, `INT_SECRET_UNAVAILABLE`,
  `INT_VALUE_NOT_ALLOWED` start as `OUT_*` in platform-outbound; integration keeps the `INT_*` ones in its results.
  The identity test sign-in's refusal reason (`TestReport.reason`) is now `OUT_*`.
- `platform-integration` depends on `platform-outbound` and no longer on `platform-identity-core`. It used identity's
  `EntityUuid` for the folder export's slot rule; that is a 40-line copy `folder.SlotIds`, with `SlotIdsTest`
  (integration-testkit) checking both give the same UUIDs.
- `OutboundIssuerFetch` moved to **platform-identity-spring** (package `identity.spring`), which now depends on
  `platform-outbound-spring` (decision 2).
- `platform-outbound-spring`: `OutboundProperties` (`platform.outbound.*`), `OutboundSettings` (every problem listed,
  `OUTBOUND_SETTINGS_INVALID`, failure analyzer), `OutboundAutoConfiguration` (active when any `platform.outbound.*`
  property is set; beans `Installation` and `Outbound`, the app's `SecretResolver` for the proxy password). Identity's
  and integration's Spring modules both use these beans. Integration refuses the old
  `platform.integration.installation`, `.allow-internal-addresses`, `.allow-sign-in-none`, `.private-ca-file` and
  `.proxy.*`, naming the new property.
- The identity-only proof: `HttpsIssuerTest.anIdentityOnlyContextTestSignsInOverHttps` (identity-spring) starts
  `MockIssuer` over https with a private CA, an `ApplicationContextRunner` with only the outbound and identity
  auto-configurations (and a class loader hiding `com.documentplatform.integration`), `platform.outbound.*` with the CA
  file, and validates a draft. `LoginConnectionsWiringTest` runs identity-only too (no integration auto-configuration).
  identity-spring's POM has no platform-integration dependency any more (test scope keeps the integration testkit for
  `TestCertificates` only).

### B5. Platform-level grants

- Role mapping (`platform-role-mapping/1`, additive): `entity_id` `*` = platform-wide for the customer and product;
  only in files read for one product with slot IDs (setup). `entity_name` must be empty; the role (after aliases) must
  be one of the product's `platformRoles` (`ENTITY_ROLE_PLATFORM_WIDE`); a platform role on an entity row is
  `PLATFORM_ROLE_ON_ENTITY` (was `PLATFORM_ROLE` in these files). `MappingRow.platformWide()`, entity null.
- Identity **V4** (`V4__platform_grants.sql`, V1–V3 untouched): `idn_platform_grant` (customer, product, issuer,
  group, role); `idn_grant_history.scope` ENTITY or PLATFORM (default ENTITY, so V3 rows stay ENTITY), `slot_id` and
  `entity_uuid` nullable with a check that ENTITY rows have both and PLATFORM rows neither; an index for the platform
  audit query. `IdentitySchema.VERSION` 4, `idn_platform_grant` in the checked tables.
- Store `store.PlatformGrantStore` (`platformGrants`, `changePlatform` with history in one transaction, one CHANGED
  row for a swapped role) in `PostgresGroupGrantStore` and `InMemoryIdentityStore`; history `PlatformGrantChange`,
  `GrantHistory.forPlatform` (default method); `forEntity` reads ENTITY rows only.
- Setup: `*` rows planned (`platform grant PRODUCT_A (every entity): group "…" -> ROLE`), applied in the same
  transaction, unlisted ones `not removed` / `remove` with `--remove-unlisted-grants`, history with the run ID,
  counted in the run log. Refusals `SETUP_PLATFORM_ROLE_ON_ENTITY`, `SETUP_ENTITY_ROLE_PLATFORM_WIDE`.
- Admission: `PlatformGroupGrants` (implemented by `PostgresIdentityLookup` and the in-memory `Lookup`) and
  `PlatformGroupAuthorization.platformRoles(key, groups)`: roles of the groups under the customer's ACTIVE connection
  matching the key; empty for no grant or no ACTIVE connection; refused when the store cannot be read, when two
  customers' connections match with platform grants, or when a grant names a non-platform role
  (`ENTITY_ROLE_IN_PLATFORM_GRANT`). `EntityGroupAuthorization` still refuses platform roles in entity grants.
- `docs/install/setup.md` section 3: `*` rows in plain words (for whom); `docs/install/identity.md` sections 1, 7, 8.

## Minimal controller example

```java
@RestController
class PushController {

    private final PushCaller pushCaller; // bean from platform.identity.push.product-code=PRODUCT_A

    PushController(PushCaller pushCaller) {
        this.pushCaller = pushCaller;
    }

    /** A token caller (Spring Security resource server has verified the JWT). */
    @PostMapping("/api/push/documents")
    ResponseEntity<?> push(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] document) {
        PushCallerResult r = pushCaller.check(PushCallerRequests.input(jwt));
        if (!r.allowed()) {
            log.warn("push refused: {}", r.refusal());                        // the code, for IT
            return ResponseEntity.status(403).body(PushCallerResult.NEUTRAL); // nothing about why
        }
        PushCallerIdentity caller = r.identity();       // slotId(), entityUuid(), sourceKey()
        return ResponseEntity.accepted().build();
    }

    /** A certificate caller (the server's TLS layer verified the client certificate against the customer's CA). */
    @PostMapping("/api/push/mtls/documents")
    ResponseEntity<?> pushWithCertificate(HttpServletRequest request, @RequestBody byte[] document) {
        X509Certificate cert = PushCallerRequests
                .certificate(request.getAttribute(PushCallerRequests.CERTIFICATE_ATTRIBUTE)).orElse(null);
        if (cert == null) return ResponseEntity.status(403).body(PushCallerResult.NEUTRAL);
        PushCallerResult r = pushCaller.check(PushCallerRequests.input(null, cert));
        if (!r.allowed()) {
            log.warn("push refused: {} {}", r.refusal(), r.fingerprint());
            return ResponseEntity.status(403).body(PushCallerResult.NEUTRAL);
        }
        return ResponseEntity.accepted().build();
    }
}
```

A `PushCaller.check` that throws (the identity tables cannot be read) is the app's own 5xx.

## New codes

- Push callers: `IDN_PUSH_NO_CONNECTION`, `IDN_PUSH_AUDIENCE`, `IDN_PUSH_NO_CLIENT_ID`, `IDN_PUSH_NOT_BOUND`,
  `IDN_PUSH_BINDING_OFF`, `IDN_PUSH_ENTITY_NOT_ACTIVE`, `IDN_PUSH_CLAIM_MISMATCH`, `IDN_PUSH_CERT_NOT_BOUND`,
  `IDN_PUSH_TOKEN_AND_CERT_DIFFER`; Spring `IDENTITY_PUSH_SETTINGS_INVALID`.
- Outbound: the 14 `OUT_*` codes listed under B4; Spring `OUTBOUND_SETTINGS_INVALID`.
- Platform grants: mapping `PLATFORM_ROLE_ON_ENTITY`, `ENTITY_ROLE_PLATFORM_WIDE`; setup
  `SETUP_PLATFORM_ROLE_ON_ENTITY`, `SETUP_ENTITY_ROLE_PLATFORM_WIDE`; admission `ENTITY_ROLE_IN_PLATFORM_GRANT`.

## Tests asked for → where

| Asked for | Where |
|---|---|
| Every refusal | `PushCallerTest` (identity-jdbc): `everyTokenRefusal`, `certificatesBound…`, `tokenAndCertificate…`, `configuredEntityAndSourceClaims…` |
| A token from each claim order | `PushCallerTest.aTokenFromEachClaimOrderFindsTheEntityAndSource` (and a product's own order) |
| Entity or source claim matching, and differing | `PushCallerTest.configuredEntityAndSourceClaimsMustMatchWhenPresent` |
| Certificate bound, not bound, rotation overlap | `PushCallerTest.certificatesBoundNotBoundAndInARotationOverlap`; setup: `ClientBindingsSetupTest.certificateFingerprintsAreClientIdsAndTwoGiveARotationOverlap` |
| Token and certificate both required, same and different | `PushCallerTest.tokenAndCertificateBothRequiredTheSameOrDifferent` |
| Binding switched off within the cache time | `PushCallerTest.aBindingSwitchedOffTakesEffectWithinTheCacheTime` |
| Inactive entity | `PushCallerTest.everyTokenRefusal` (never activated, suspended), certificate case too |
| DML-only user | `PushCallerTest` and `PlatformGrantsTest` run the Postgres side as the DML-only app user after `IdentitySchema.verify` |
| No token or certificate content in logs; fingerprint allowed | `PushCallerTest.noTokenOrCertificateContentInTheLog` |
| In-memory follows Postgres | every `PushCallerTest` and `PlatformGrantsTest` case runs on both (`@ValueSource memory, postgres`) |
| Spring helper with a test `Jwt` and a test certificate | `PushCallerWiringTest` (identity-spring) |
| A database error throws, never falls back | `PushCallerTest.aDatabaseErrorThrowsAndNeverFallsBack` |
| B4: outbound tests from the new place; integration unchanged; identity-only https test sign-in | `OutboundRulesTest` (platform-outbound), `OutboundWiringTest` (platform-outbound-spring), all integration-testkit tests (`AddressGuardTest`, `PinningTest` assert `OUT_*` and the mapping back), `IntegrationWiringTest` (old names refused, missing installation), `SlotIdsTest`, `HttpsIssuerTest.anIdentityOnlyContextTestSignsInOverHttps`, `LoginConnectionsWiringTest`, `OutboundIssuerFetchTest` |
| B5: `*` rows load, wrong kind refused, removed only with the flag, history scope PLATFORM | `PlatformWideRowsTest` (identity-core), `SetupIdentityChangesTest.starRowsLoad…` (setup, Postgres) |
| B5: `PlatformGroupAuthorization` no grant, one grant, connection not ACTIVE; DML-only | `PlatformGrantsTest` (identity-jdbc, both stores) |
| Schema V4 | `IdentitySchemaTest`, `GrantHistoryTest.aVersion2DatabaseIsRefusedUntilV3AndV4AreRun…` (a V3 history row is ENTITY after V4), `MachineBindingsTest.aV1DatabaseIsRefusedUntilV2IsRun` |
| Nothing skipped on CI | CI step "No test skipped": 1188 tests, 0 skipped |

## Files changed, commits

On main (rebased from PR #15, #16, #17):

- `939afd5` docs: P028 task and the P027 decisions (the two files as they were).
- `4183f7c` refactor: platform-outbound and platform-outbound-spring, integration on them, `OutboundIssuerFetch` to
  identity-spring (B4).
- `90055ec` feat: push-API caller check, directories, `MachineSourceBindings`, Spring bean and helper, setup
  certificate client IDs (B1–B3).
- `299716f` feat: platform-wide grants, identity V4, docs (B3–B5).
- `b13b05a` release: platform-core 0.3.0.
- `542ed3c` chore: main to 0.4.0-SNAPSHOT.

139 files in #15 (new modules `platform-outbound`, `platform-outbound-spring`; new SQL
`V4__platform_grants.sql`; docs `CLAUDE.md`, `docs/install/identity.md`, `integration.md`, `setup.md`,
`docs/design/setup.md`, `integration-module.md`), plus `docs/releases/platform-core-v0.3.0.md`.

## Decisions I had to make

1. **A second new module, `platform-outbound-spring`.** The task says the `platform.outbound.*` properties are shared
   by identity's and integration's Spring modules. Putting the properties in either of them would make the other
   depend on it, so they live in their own small Spring module (in the BOM and the root list). It is active as soon as
   any `platform.outbound.*` property is set, so a proxy set without `installation` is refused, not ignored.
2. **`OutboundIssuerFetch` in platform-identity-spring, no `platform-identity-outbound`.** identity-spring is used
   only by Spring Boot apps, which have Apache HttpClient 5 through Spring Boot's dependency management anyway.
3. **`allow-sign-in-none` moved to `platform.outbound.*` as well.** It is a field of `Installation`, and the call
   engine reads it from `Outbound.installation()`; keeping it under integration would need a second installation.
4. **`config.Limits` stays in integration.** It holds delivery limits (documents, file and package sizes). The
   outbound limits (connect and read timeouts, answer size) are parameters of each `Outbound.send`, as before.
5. **`ClientCertificate` and `CertificateInfo` moved too**, because `Outbound` presents the certificate. Their
   `key()` and `chain()` became public (only `CredentialFiles` uses them, to seal the stored form); documented as
   never logged.
6. **Integration's slot rule copied (`folder.SlotIds`)** instead of a dependency on identity-core, with a test that
   both derive the same UUIDs. The User-Agent stays `platform-integration` so destinations see no change.
7. **Push callers: issuer matching across customers.** The issuer is not unique across customers (shared login hub),
   so the token's issuer may match several ACTIVE connections. The client's binding must be under one of the matching
   customers whose audience matches; bound under two of them is refused as `IDN_PUSH_NOT_BOUND` (never guessed).
8. **Certificate callers need the customer's ACTIVE connection too** (`IDN_PUSH_NO_CONNECTION`), as the binding
   applies under that connection (setup.md 6.1).
9. **Both presented but not required:** both are checked and must agree. A caller sending a token for one binding and
   a certificate for another is refused rather than admitted on either.
10. **The required audience comes from the connection only** (no separate `audience` setting), as the task says.
11. **Platform grants and shared login hubs:** when more than one customer's connection matches and has platform
    grants, `PlatformGroupGrants` returns null (refused), never a merge.
12. **`*` rows only in setup files** (read for one product with slot IDs); other readers see `*` as no entity ID.
13. **Integration-spring keeps a fallback `Outbound` bean** for an app that defines its own `Installation` only, and
    a fallback `Installation` bean that fails with integration's problem list when no outbound settings are given (so
    the missing `platform.outbound.installation` is named whichever bean asks first).

## Not done, and why

Nothing of the task is left. Not built because the task did not ask for it: an IT Admin screen for platform grants,
and a call to `PushCaller.forget()` from the app (questions 1 and 2).

## Questions (numbered, each with a recommendation)

1. **The IT Admin's view of platform grants.** Setup writes them; the app has `PlatformGrantStore` and
   `GrantHistory.forPlatform` but no screen yet. Recommendation: the app's IT Admin grants page shows them read-only
   first, edits later through the same store.
2. **`PushCaller.forget()` after an edit in the app.** The IT Admin's integration page (later) switching a binding
   off could call `forget()` so the change is immediate on that instance (others within 30 s). Recommendation: yes,
   when that page is built.
3. **Should a certificate caller also be matched to an entity claim?** There is no token, so entity and source come
   from the binding alone. Recommendation: keep it so; a product needing more requires both (token and certificate).
