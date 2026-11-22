# platform-core 0.2.0

Login-connection drafts with a test sign-in and rollback, local test login servers, role aliases, the grant history,
and the customer-keys guard (task P031). Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.2.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Modules

All modules of 0.1.0, unchanged in name and coordinates. What is new:

| Module | New in 0.2.0 |
|---|---|
| `platform-identity-core` | `store.LoginConnections` (draft, test sign-in, activate, rollback, discard; `ConnectionOutcome`, `ConnectionValues`); `signin.OidcTestSignIn`, `IssuerFetch`, `TestSignIn`, `TestReport`; `signin.LocalTestIssuers`, `DevelopmentLicence`; `store.GrantHistory`, `GrantChange`, `GrantAuthor`, `GrantStep`; role aliases (`EntityRoleCatalog.aliasTarget`, `MappingRow.alias`) |
| `platform-identity-jdbc` | Identity schema **V3**; Postgres drafts, activate and rollback; grant history writes and `PostgresGrantHistory`; `LicenceSigningKey` |
| `platform-identity-testkit` | The same in memory; `testkit.MockIssuer` (mock OIDC issuer: discovery, keys, test tokens) |
| `platform-integration` | `http.OutboundIssuerFetch`: the test sign-in's fetch over the outbound rules. Now depends on `platform-identity-core` (which has no dependencies) |
| `platform-licensing` | `trusted-keys.json` keys may carry `"development": true` (`TrustedKeys.isDevelopmentKey`) |
| `platform-setup` | http issuers for local test login servers, role aliases, `customerKeys`, grant history with the run ID, drafts left alone |

## Design docs implemented

`docs/design/setup.md` section 7 (P031), on top of everything in 0.1.0.

## New codes

- Login-connection steps (`ConnectionOutcome.Result`): `IDN_TEST_FAILED`, `IDN_DRAFT_EXISTS`, `IDN_VERSION_CONFLICT`,
  `IDN_CONNECTION_UNKNOWN`, `IDN_NOT_A_DRAFT`, `IDN_NOT_VALIDATED`, `IDN_NO_PREVIOUS_CONNECTION`,
  `IDN_HTTP_ISSUER_NOT_ALLOWED`, `IDN_VALUES_REQUIRED`, `IDN_VALUES_INVALID`, `IDN_CUSTOMER_UNKNOWN`.
- Test sign-in (`TestReport.Code`): `IDN_TEST_ISSUER_UNREACHABLE`, `IDN_TEST_DISCOVERY_MISMATCH`,
  `IDN_TEST_KEYS_MISSING`, `IDN_TEST_TOKEN_UNREADABLE`, `IDN_TEST_TOKEN_SIGNATURE`, `IDN_TEST_TOKEN_ISSUER`,
  `IDN_TEST_AUDIENCE`, `IDN_TEST_EXPIRED`, `IDN_TEST_GROUPS_CLAIM_MISSING`.
- Setup: `SETUP_HTTP_ISSUER_NOT_ALLOWED`, `SETUP_CUSTOMER_KEYS_REQUIRED`.

## Breaking changes

None for existing callers: every public method and constructor of 0.1.0 is still there. To note when upgrading:

- **Run the identity schema step** (`V3__grant_history.sql`) before starting an app on 0.2.0; until then it stops
  with `IDN_VERSION_OLDER`, as for every schema version. `GroupGrantStore.add` / `remove` now also write the grant
  history (as made in the app by `app`; the new overloads with a `GrantAuthor` say who).
- New interface methods on `LoginConnectionStore` and `GroupGrantStore` are default methods, so an app's own
  implementation still compiles. `MappingRow` gained a component (`alias`); its earlier constructors are kept.
- Setup's run log line gains `run=<run ID>` (after `code=`, before `keys=`), and the plan lists created grants in
  sorted order.
- Older files load as before: `trusted-keys.json` without `development`, `product-roles.json` without `aliases` or
  `customerKeys`, sign-in files with an https issuer.

## Database schemas

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | **3** (`V3__grant_history.sql`: `idn_grant_history`, insert only; at most one draft login connection per customer and product) |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | the app's (location added to its own run) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 1 |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
