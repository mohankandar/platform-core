# P031 report — login-connection drafts, test sign-in and rollback; local test issuers; role aliases; grant history; customer-keys guard; release `platform-core-v0.2.0`

Date: 2026-11-22
Branch: `feature/identity-0.2.0` (then `release/platform-core-0.2.0`, `chore/0.3.0-snapshot`)
Outcome: DONE (one rule slip in the release PR's merge, see "Decisions" 8)

## Result (tests per module, PRs, CI runs, merge results)

- Local `mvn verify` (whole reactor): BUILD SUCCESS, 1990 tests, 6 skipped (the TLS tests, which skip only on this
  laptop because of the TLS interception; CI forbids skips).
- New tests: identity-core `OidcTestSignInTest` 10, `LocalTestIssuersTest` 4, `GrantStepTest` 4,
  `RoleAliasMappingTest` 4; identity-testkit `InMemoryLoginConnectionsTest` 6, `InMemoryGrantHistoryTest` 1;
  identity-jdbc `LoginConnectionDraftsTest` 4, `GrantHistoryTest` 5; integration `OutboundIssuerFetchTest` 3;
  licensing `TrustedKeysDevelopmentTest` 3; setup `SetupIdentityChangesTest` 8. Changed: `IdentitySchemaTest` and
  `MachineBindingsTest` (version 3).

| PR | What | CI runs | Merge |
|---|---|---|---|
| #5 | P031 B1–B5 (`feature/identity-0.2.0`) | 45073668038 (push) ✔, 45073685300 (PR) ✔ | rebase-merged, `2429622` |
| #6 | Release 0.2.0 (version + `docs/releases/platform-core-v0.2.0.md`) | 45074152487 ✔; 45074157087 ✘ at first (`ProxyTest`, see below), ✔ on rerun | rebase-merged, `35d18f6` |
| #7 | Main to 0.3.0-SNAPSHOT | 45074908910 ✔, 45074913949 ✔ | rebase-merged, `b8ed11a` |
| (this report) | `docs/work/P031-report.md` | see its PR | |

- **Tag:** `platform-core-v0.2.0` on `35d18f6` (main's own CI run 45074493102 on that commit ✔). Release workflow
  run 45074887151: ✔ (deployed to GitHub Packages, GitHub release created; the tag's CI run 45074887249 ✔).
- **CI on main after #5** (run 45074086852) failed once in `platform-integration-testkit`
  `ProxyTest.callsAndTokenRequestsGoThroughTheProxy` (`CANT_CONNECT INT_CONNECTION_LOST` at the upload step through
  `MockProxy`); the same test failed once on PR #6. It passed in every earlier run, in both runs of PR #5, in the
  rerun, on main's run for `35d18f6`, and 10 of 10 times locally. P031 does not touch `Outbound`, the engine or
  `MockProxy`. It is an intermittent failure of the test proxy (question 1).

## Independence

No other product's name or behaviour in code. `NoProductNamesGuardTest` passes; tests use `PRODUCT_A` / `PRODUCT_B`,
made-up issuers (`login.example.test`) and the generic `MockIssuer`.

## What was built

### B1 Login connections: drafts, test sign-in, activate, rollback

- **identity-core** `store.LoginConnections` over `LoginConnectionStore` (new default methods `connections`,
  `pending`, `activate`, `rollback`, `rollbackCandidate`), `ConnectionOutcome`, `ConnectionValues`.
  - `createDraft` copies the ACTIVE connection (or takes the first one's values), expected version = the ACTIVE's
    version or 0; `updateDraft` (expected version) edits it, and a VALIDATED draft becomes DRAFT again.
  - At most one DRAFT/VALIDATED per customer and product: store check plus the partial unique index
    `idn_login_connection_one_pending` (in V3).
  - `validate` runs `signin.OidcTestSignIn`: discovery at `<issuer>/.well-known/openid-configuration`, exact
    `issuer` match, JWKS at `jwks_uri` (RSA/EC, `use` sig), then optionally the token: signature
    (RS/PS/ES 256/384/512; `none` and HS refused), `iss`, `aud`, `exp`/`nbf` with 1 minute leeway, groups claim
    (name given, default `groups`). A pass makes the draft VALIDATED; a failure leaves a DRAFT as it is and makes a
    VALIDATED one DRAFT. The token is never stored, logged or shown (`TestSignIn.toString` hides it).
  - `activate` (VALIDATED only): old ACTIVE → DISABLED and this → ACTIVE, in one transaction.
  - `rollback`: the newest ACTIVE history row whose values differ from the current ACTIVE's, made ACTIVE as a new
    version of the connection that had them (the current one DISABLED if it is another row); refused
    (`IDN_NO_PREVIOUS_CONNECTION`) when none exists. Drafts are left alone.
  - Extra: `discardDraft` (DRAFT → DISABLED), so a draft can be dropped (decision 3).
- **Fetch through the outbound rules:** `IssuerFetch` (identity-core) and `integration.http.OutboundIssuerFetch`,
  which uses `Outbound` (https only, address guard, proxy, private CA, no redirects, size limits); refusals carry the
  `INT_…` code as the report's `reason`.
- **History:** every step writes `idn_login_connection_history` (who, when, source APP).
- **Postgres** (`PostgresLoginConnectionStore`) and **in-memory** (`InMemoryIdentityStore`) with the same rules.
- **Setup:** reads only ACTIVE/DISABLED connections (drafts are the app's). Its "changed in the app" conflict still
  works after an app activation, and `--replace-connection` replaces the ACTIVE connection in place, leaving drafts
  alone (tested).
- **Testkit:** `identity.testkit.MockIssuer`: JDK server on 127.0.0.1, discovery + JWKS (RS256), `token`, `claims`,
  `sign`, `signWithOtherKey`, switches `down`, `discoveryIssuer`, `discoveryStatus`, `keysStatus`, `noKeys`,
  `withoutJwksUri`; `MockIssuer.fetch()` for tests.

**What an app calls (minimal):**

```java
IssuerFetch fetch = new OutboundIssuerFetch(new Outbound(installation));               // platform-integration
DevelopmentLicence dev = LicenceSigningKey.of(licenceService, trustedKeys);            // or DevelopmentLicence.NONE
LoginConnections lc = new LoginConnections(new PostgresLoginConnectionStore(ds),
        new OidcTestSignIn(fetch, Clock.systemUTC()), dev);

ConnectionOutcome d = lc.createDraft(tenantId, "PRODUCT_A", active.configurationVersion(), null, adminId); // copy
ConnectionOutcome e = lc.updateDraft(draftId, 1, newValues, adminId);                  // edit (DRAFT again)
ConnectionOutcome v = lc.validate(draftId, 2, TestSignIn.withToken(pastedToken), adminId); // or issuerOnly()
ConnectionOutcome a = lc.activate(draftId, 3, adminId);                                // VALIDATED only
ConnectionOutcome r = lc.rollback(tenantId, "PRODUCT_A", adminId);                      // previous ACTIVE again
ConnectionOutcome x = lc.discardDraft(draftId, version, adminId);
// audit: outcome.result(), outcome.connection().connectionId()/configurationVersion(), outcome.test().failure()
```

### B2 http issuers for local test login servers

- `identity.signin.LocalTestIssuers.check(issuer, developmentLicence)`: https as before; http only when the host is
  exactly `localhost`, `127.0.0.1` or `[::1]` **and** the licence is signed by a development key. No query, fragment
  or user part, as before.
- Development key: `platform-licence-keys/1` gains an optional `"development": true` per key
  (`TrustedKeys.isDevelopmentKey`); the demo `development-trusted-keys.json` now carries it. identity-jdbc
  `LicenceSigningKey.of(LicenceService, TrustedKeys)`.
- Setup: `SETUP_HTTP_ISSUER_NOT_ALLOWED` (checked right after the licence is verified). Connection operations:
  `IDN_HTTP_ISSUER_NOT_ALLOWED`, checked at create, update, validate, activate and rollback (a licence replaced by a
  production one stops a local issuer from being activated). The key address of a local test issuer may be http on
  this machine only; `OutboundIssuerFetch` calls it directly (no proxy).

### B3 Role aliases

- `product-roles.json` (`platform-product-roles/1`) gains optional `"aliases": {"Reviewer": "READER"}`. An alias may
  not equal a role or another alias (ignoring case, since role names are matched ignoring case) and must name one of
  `roles` exactly; at most 500.
- `EntityRoleCatalog.aliasTarget` (default empty); `RoleMappingFile` resolves an alias when reading and stores the
  role; `MappingRow.alias` keeps what was written. An alias row and its role row for the same group are a
  `DUPLICATE_ROW`. Unknown names refused as before.
- Plan: `create  grant ENT-… PRODUCT_A: group "X" -> Reviewer -> READER` and one
  `checked  role alias "Reviewer" -> READER (n rows of file; stored as READER)` line per alias used.

### B4 Grant history (identity schema V3)

- `V3__grant_history.sql` (V1, V2 unchanged); `IdentitySchema.VERSION = 3`. Table `idn_grant_history`: customer,
  product, slot + entity UUID, issuer + group, role before / after, change (ADDED / REMOVED / CHANGED), source
  (SETUP / APP), who, when, setup run ID (required for SETUP, absent for APP). Triggers refuse UPDATE, DELETE and
  TRUNCATE, even for the owner. The startup check asks only SELECT and INSERT on it.
- Writers: `GroupGrantStore.change(slot, product, issuer, add, remove, GrantAuthor)` and new `add`/`remove`/`changeRole`
  overloads; the old `add`/`remove` write rows as APP by `app`. One group losing one role and gaining one in a call
  is one CHANGED row (`GrantStep`). Setup writes per slot in its transaction with `GrantAuthor.setup("setup", runId)`;
  the run ID is new (UUID), printed on the first line and in the run log (`run=`).
- Reading: `GrantHistory.forEntity(entityUuid, from, to)`, `PostgresGrantHistory`, in-memory too; the DML-only user
  is enough (tested).

### B5 Customer-keys guard

`"customerKeys": "required" | "optional"` in `product-roles.json` (missing = optional). With `required`,
`--no-customer-keys` → `SETUP_CUSTOMER_KEYS_REQUIRED` (exit 2, nothing written). Tested: a customer stored with
`--no-customer-keys` gets both keys from the next run without the flag; rows and connection versions unchanged.

### Docs

`docs/install/identity.md` (sections 6 drafts and test sign-in, 7 grant history, V3, rights), `docs/install/setup.md`
(http issuers, aliases, grant history and run ID, drafts left alone, keys guard, new codes), `docs/install/setup.md`
and `docs/install/licensing.md` carry near the top: *"Until the app turns licensing on, the licensing tables and the
stored licence exist, but nothing checks the allowance. Licensing is not live."* Also `docs/design/setup.md`
section 7, `platform-setup/demo/README.md`, `CLAUDE.md`, `docs/releases/platform-core-v0.2.0.md`.

## New codes

- `ConnectionOutcome.Result`: `IDN_TEST_FAILED`, `IDN_DRAFT_EXISTS`, `IDN_VERSION_CONFLICT`, `IDN_CONNECTION_UNKNOWN`,
  `IDN_NOT_A_DRAFT`, `IDN_NOT_VALIDATED`, `IDN_NO_PREVIOUS_CONNECTION`, `IDN_HTTP_ISSUER_NOT_ALLOWED`,
  `IDN_VALUES_REQUIRED`, `IDN_VALUES_INVALID`, `IDN_CUSTOMER_UNKNOWN` (plus results `DRAFT_CREATED`,
  `DRAFT_UPDATED`, `UNCHANGED`, `VALIDATED`, `ACTIVATED`, `ROLLED_BACK`, `DRAFT_DISCARDED`).
- `TestReport.Code`: `IDN_TEST_ISSUER_UNREACHABLE`, `IDN_TEST_DISCOVERY_MISMATCH`, `IDN_TEST_KEYS_MISSING`,
  `IDN_TEST_TOKEN_UNREADABLE`, `IDN_TEST_TOKEN_SIGNATURE`, `IDN_TEST_TOKEN_ISSUER`, `IDN_TEST_AUDIENCE`,
  `IDN_TEST_EXPIRED`, `IDN_TEST_GROUPS_CLAIM_MISSING`.
- Setup: `SETUP_HTTP_ISSUER_NOT_ALLOWED`, `SETUP_CUSTOMER_KEYS_REQUIRED`.

## Tests asked for → where

| Asked | Where |
|---|---|
| B1 happy path, every refusal, history, DML-only, against the mock issuer | `InMemoryLoginConnectionsTest`, `LoginConnectionDraftsTest` (Postgres, DML user, one-draft index, version race), `OidcTestSignInTest` (every `IDN_TEST_*`), `OutboundIssuerFetchTest` |
| B1 setup conflict and `--replace-connection`, drafts left alone | `SetupIdentityChangesTest.setupLeavesTheAppsDraftsAlone…`, existing `SetupCommandTest` conflict test |
| B2 accepted / refused, older files | `LocalTestIssuersTest`, `SetupIdentityChangesTest` (http accepted, refused without development key, refused off this machine, other schemes), `InMemoryLoginConnectionsTest.anHttpIssuer…`, `LoginConnectionDraftsTest.anHttpIssuerFollowsTheLicencesSigningKey`, `TrustedKeysDevelopmentTest` |
| B3 aliases, bad files, older files, unknown names | `RoleAliasMappingTest`, `SetupIdentityChangesTest.aMappingFileMayUseAnAlias…`, `…badAliases…`; the demo files without aliases in all other setup tests |
| B4 rows, triggers, DML user, V2→V3, rerun writes nothing | `GrantHistoryTest`, `InMemoryGrantHistoryTest`, `GrantStepTest`, `SetupIdentityChangesTest.setupWritesTheGrantHistory…`, `IdentitySchemaTest`, `MachineBindingsTest` |
| B5 refused with flag, keys on the next run, nothing else changes | `SetupIdentityChangesTest.requiredCustomerKeys…`, `…aCustomerStoredWithoutKeysGetsBoth…` |

## Files changed, commits

- `1127079` docs: P031 task; `2429622` feat (P031 B1–B5); `35d18f6` release: platform-core 0.2.0; `b8ed11a` chore:
  main to 0.3.0-SNAPSHOT.
- New: identity-core `signin/` (`OidcTestSignIn`, `IssuerFetch`, `TestSignIn`, `TestReport`, `LocalTestIssuers`,
  `DevelopmentLicence`, `MiniJson`), `store/` (`LoginConnections`, `ConnectionOutcome`, `ConnectionValues`,
  `GrantHistory`, `GrantChange`, `GrantAuthor`, `GrantStep`); identity-jdbc `V3__grant_history.sql`,
  `PostgresGrantHistory`, `LicenceSigningKey`; identity-testkit `testkit/MockIssuer`; integration
  `http/OutboundIssuerFetch`.
- Changed: `LoginConnectionStore`, `GroupGrantStore`, `EntityRoleCatalog`, `MappingRow`, `RoleMappingFile`,
  `IdentitySchema`, `PostgresLoginConnectionStore`, `PostgresGroupGrantStore`, `InMemoryIdentityStore`, `TrustedKeys`,
  setup (`SetupCommand`, `SetupInputs`, `SetupRun`, `SigninFile`, `ProductRoles`, `SetupRefusal`, `Plan`), POMs
  (integration → identity-core; test deps on identity-testkit), demo keys file, docs.

## Decisions I had to make

1. **Where the development key is marked:** an optional `"development": true` per key in `trusted-keys.json`
   (`platform-licence-keys/1`), not a key-ID prefix. A production release lists no such key, so nothing there can
   turn it on.
2. **Where the fetch lives:** identity-core has no dependencies, so it defines `IssuerFetch`; the implementation over
   the outbound rules is in platform-integration (`OutboundIssuerFetch`), which now depends on identity-core
   (no cycle; identity-core has no dependencies).
3. **`discardDraft` added.** With "at most one draft", an admin needs a way to drop one; it becomes DISABLED and stays
   in the history (it never counts as a rollback target).
4. **Two extra test codes**, `IDN_TEST_TOKEN_UNREADABLE` and `IDN_TEST_TOKEN_ISSUER`: a pasted text that is not a JWT,
   and a token from another issuer, did not fit the seven listed causes.
5. **Rollback target:** the newest ACTIVE history row whose values differ from the current ACTIVE's (this covers both
   an activated draft and setup's in-place `--replace-connection`). Two rollbacks in a row swap back and forth.
6. **One-draft index in V3** (file named `grant_history`, as the task said): the only migration of this release.
7. **Run-log field order:** `run=` goes after `code=` and before `keys=`, so existing readers of
   `exit=… create=…`, `exit=2 code=…` and `… keys=…` still match.
8. **Rule slip:** PR #6 was merged while its second CI run (45074157087) had failed on the intermittent `ProxyTest`.
   My merge command did not stop on the failed check. I then held the tag back, re-ran the failed job (green) and
   waited for main's own run on `35d18f6` (green) before tagging. From now on the merge runs only on a checked exit
   status, as it did for PR #7.

## Not done, and why

- The test sign-in over a real https issuer is tested with an in-memory fetch (`OidcTestSignInTest`) and over
  `Outbound` against the local http mock. There is no https `MockIssuer` yet (it would need the integration
  testkit's certificates).
- No Spring wiring for `LoginConnections` (not asked; the app builds it as in the example above).

## Questions (numbered, each with a recommendation)

1. `ProxyTest.callsAndTokenRequestsGoThroughTheProxy` (integration-testkit) failed twice on CI runners in P031, never
   locally, in code P031 does not touch. **Recommendation:** a small task to make `MockProxy`'s plain-http
   forwarding wait for the request body before closing (or to retry the upload in that test), so CI stays
   trustworthy.
2. Should a failed test sign-in also be written to the connection history (today only steps that change the
   connection are, and the app audits every outcome)? **Recommendation:** no; the app's audit holds it.
3. Should `customerKeys` default to `required` at the next format change of `product-roles.json`?
   **Recommendation:** yes for products that keep sensitive values, decided per product.
