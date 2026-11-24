# platform-core 1.0.0-rc.1

Release candidate 1 for 1.0.0, made for the install proof (P047). **Requires Spring Boot 4.2 and Java 21.** Not for production:
wait for 1.0.0. Import `platform-bom` 1.0.0-rc.1. Published to GitHub Packages,
`https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Since 0.8.1

- **A database that cannot be reached, or tables that are not ready, stop the app with the module's code** in Spring Boot's
  failure report, for identity, erase log, integration and model governance too (before: a stack trace). The `*_CHECK_FAILED`
  texts say "could not be reached, or refused".
- **The public API is recorded** (`docs/api/public-api-1.txt`): from 1.0.0 on, a build that removes or changes a public type or
  member fails.
- **New guide:** `docs/install/switch-over.md`. Guides corrected: the lists of schema files, the push callers' default claims,
  backing up the whole database (`pg_trgm`), an unreachable database at start.
- **The sample app and the install proof** (`examples/sample-app`, `.github/workflows/install-proof.yml`), not published.

## Breaking changes, schemas

None. Schema versions as in 0.8.1: identity 5, licensing 2, fields 2, erase log 3, integration 3, model governance 5,
notifications 1.
