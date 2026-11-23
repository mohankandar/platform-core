# platform-core 0.5.1

A patch release with three fixes (task P036, the A gaps of the P035 strength check). No new modules, no schema
change. Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.5.1</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**Apps that use identity should take 0.5.1** for the development-key fix (fix 2): with 0.5.0, a Spring app never
recognised a development-signed licence, so a local http login server was refused even under the demo licence.

**No other product's name or behaviour in code.**

## Fixes

1. **A password in a URL's user part is removed from support bundles and errors** (`platform-errors`,
   `SafeText.scrub`). `scheme://user:password@host`, including `jdbc:postgresql://…`, becomes
   `scheme://[removed]@host` everywhere `SafeText` scrubs: the diagnostic bundle's configuration, check causes,
   failure lines and app versions, and every `PlatformError` detail. Before, a property such as
   `spring.data.redis.url=redis://user:pw@host:6379` reached the bundle with its password.
2. **The development-key marks of `trusted-keys.json` are kept in Spring apps** (`platform-licensing-spring`,
   `LicensingConfig.from`). A key marked `"development": true` in the trusted-keys file now counts as a development
   key, so a licence it signed allows a local http login server (identity's `DevelopmentLicence`). Keys listed inline
   under `platform.licensing.trusted-keys` carry no mark and stay production keys. A production-signed licence still
   refuses an http issuer (`IDN_HTTP_ISSUER_NOT_ALLOWED`).
3. **Model review dates are kept per use and entity** (`platform-model-governance`). The next review of an entity is
   its own live validation (else its latest passed one) plus the use's interval, and is due at once after a change
   since that validation. The gate (`MOD_REVIEW_OVERDUE`) and `ModelReviews` use the entity's own date; the
   register's `nextReviewDue` is the earliest over the use's entities. Before, a pass for one entity moved the date of
   the whole use, so another entity could run past its review unnoticed. A new validation counts once it is live.

## API notes

No breaking change for apps that use the Spring wiring.

- `ModelGate(store, settings, clock)` and `ModelReviews(store, events, settings, clock)` take the app's
  `ModelGovSettings` for the review intervals. The constructors without settings still work, are deprecated, and use
  the default intervals.
- `ModelGovStore` has one more method, `validationEntities(useId)`. Both built stores (Postgres and the testkit's
  in-memory store) have it; an app's own store would need to add it.

## Design docs implemented

`docs/design/model-governance.md` "Fix (P036)" in section 10. Install guide: `docs/install/model-governance.md`
section 5.

## Database schemas

Unchanged from 0.5.0:

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 4 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 2 |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | the app's (location added to its own run) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |
| platform-model-governance | `db/platform/modelgov` | `mg_schema_history` | 2 |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
