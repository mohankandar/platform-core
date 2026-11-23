# P036 report — fix the three A gaps, then release `platform-core-v0.5.1`

Date: 2026-11-23
Branch: `fix/a-gaps` (then `release/0.5.1`, `chore/0.6.0-snapshot-after-0.5.1`, `docs/p036-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs (all green) | Merged as |
|---|---|---|---|
| The three fixes and the task files P036–P041 | #30 | 45193496971, 45193514199 | `875367e`, `dc2860a`, `dd2d3e8`, `912f3cd` (main CI 45194670562) |
| Version 0.5.1 and release notes | #31 | 45194791723, 45194806556 | `0b8a3bd` (main CI 45195984996) |
| Tag `platform-core-v0.5.1` on `0b8a3bd` | — | release workflow 45196771646 (verify, deploy to GitHub Packages, GitHub release) | GitHub release `platform-core-v0.5.1` |
| Main back to `0.6.0-SNAPSHOT` | #32 | 45198960042, 45198972443 | `b8810dc` |
| This report and the row marks | this PR | | |

Every merge used the README's stop-on-failed-check line, and no check failed or was re-run.

`mvn verify` on the fix branch: **1292 tests, 0 failures**. Per module:

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 20 | platform-integration | 50 |
| platform-crypto-spring | 5 | platform-integration-spring | 8 |
| platform-crypto-testkit | 16 | platform-integration-testkit | 196 |
| platform-erase-log | 16 | platform-licensing | 144 |
| platform-erase-log-spring | 5 | platform-licensing-spring | 34 |
| platform-erase-log-testkit | 9 | platform-licensing-testkit | 47 |
| platform-error-catalogue | 3 | platform-model-governance-spring | 5 |
| platform-errors | 7 (+1) | platform-model-governance-testkit | 66 (+2: one contract test, two stores) |
| platform-fields | 91 | platform-monitoring-spring | 5 |
| platform-fields-testkit | 18 | platform-monitoring-testkit | 16 |
| platform-identity-core | 117 | platform-notifications | 81 |
| platform-identity-jdbc | 65 | platform-notifications-spring | 41 |
| platform-identity-spring | 30 (+2) | platform-notifications-testkit | 132 |
| platform-identity-testkit | 14 | platform-outbound | 8 |
| platform-setup | 39 | platform-outbound-spring | 4 |

## Independence

**No other product's name or behaviour in code.** The new tests use `PRODUCT_A`, the testkit's `TEN-TEST-0001`, and
made-up hosts under `corp.example`.

## What was built

| Row | Result | Commit | Test |
|---|---|---|---|
| `xc-never-leak-2` / `-11` | FIXED | `dc2860a` | `ErrorsTest.aPasswordInAUrlsUserPartNeverReachesAnError`; `DiagnosticBundleTest.secretsAndContentNeverReachTheBundle` (planted properties and a failure detail) |
| `xc-devkey-1` (`licensing-file-format-44`) | FIXED | `dd2d3e8` | `DevelopmentKeyWiringTest` (platform-identity-spring): `aDevelopmentSignedLicenceAllowsALocalHttpIssuer`, `aProductionSignedLicenceStillRefusesALocalHttpIssuer` |
| `model-governance-39` | FIXED | `912f3cd` | `ModelGovContract.oneEntitysPassNeverMovesAnotherEntitysReviewDate` (in-memory and Postgres) |

1. **The user part of a URL is removed** (`SafeText.scrub`). `://` up to the last `@` before the first `/`, `?`,
   `#` or space becomes `://[removed]@`. So `redis://user:pw@host`, `jdbc:postgresql://u:p@h/db` and
   `https://token@host` lose the user part, while an `@` in a path or query is kept. I checked every scrubbing path
   the bundle and errors use. All of them go through `SafeText.scrub` or `SafeText.forName`, so this one fix covers
   them all:
   - `DiagnosticBundle`: configuration, check causes, failure lines, app versions, system lines;
   - `PlatformError` details, and the cause built from them.

   There is no second scrubber to fix.
2. **The development marks are kept** (`LicensingConfig.from`). The file's `"development": true` key IDs are
   collected and passed to `TrustedKeys.of(keys, development)`. Inline keys (`platform.licensing.trusted-keys.<id>`)
   cannot carry a mark, so they stay production keys. The wiring test runs the real `LicensingAutoConfiguration`
   (with the testkit's in-memory licence stores) together with identity-spring's login-connection wiring, and
   creates a draft for the mock issuer, which is http on 127.0.0.1.
3. **Review dates per use and entity** (`ReviewDates`, package-private; used by `ModelGate`, `ModelReviews` and
   `ModelGovernance`). For each entity:
   - **Reference validation:** the entity's live validation, or its latest passed one while it is not live.
   - **Date:** the reference validation's date plus the use's interval.
   - **Due at once:** on the day of the first change since that validation. A change of the use's model or
     configuration, or of the model's version or digest, is found by comparing with what the validation recorded.
     A change of where the model runs is read from the model's history.

   Where the date is used:
   - **The gate** checks the entity's own date. `MOD_REVIEW_OVERDUE` now names the entity.
   - **`ModelReviews`** lists each use by its earliest entity, so the notices stay once per use, due date and stage.
   - **The register's `nextReviewDue`** is the earliest date over the use's entities. It is recomputed on a pass, a
     go-live and a rollback.

## Tests asked for → where

- "The tests plant such a property and such a detail" → `DiagnosticBundleTest` (properties `app.cache.url`,
  `app.reports.datasource`, and an `address` detail on the failure) and `ErrorsTest` (details `folder` and `note`).
- "A wiring test through identity-spring: a development-signed licence plus a local http issuer is allowed; a
  production licence is still refused" → `DevelopmentKeyWiringTest`.
- "A use covering two entities, where one passes and the other runs past its date" →
  `oneEntitysPassNeverMovesAnotherEntitysReviewDate`. It covers the warning, the refusal for the overdue entity, the
  other entity still starting, `ModelReviews.overdue`, the register's date, and that a new validation counts only once
  it is live.

**Each test fails without its fix.** I ran the new tests with the old code put back:
- the bundle and error tests found `Url-Pw-Planted-6` and `Url-Pw-Planted-1`;
- the development test got `IDN_HTTP_ISSUER_NOT_ALLOWED`;
- the review test got `2027-03-31` where `2027-01-30` was expected.

The production-licence test passes either way, as it should: it guards against allowing too much.

## Files changed, commits

- `platform-errors`: `SafeText.java`, `ErrorsTest.java`.
- `platform-monitoring-testkit`: `DiagnosticBundleTest.java`.
- `platform-licensing-spring`: `LicensingConfig.java`.
- `platform-identity-spring`:
  - `pom.xml`: test dependency on `platform-licensing-testkit`;
  - `DevelopmentKeyWiringTest.java` (new).
- `platform-model-governance`:
  - `ReviewDates.java` (new);
  - `ModelGate.java`, `ModelReviews.java` (settings constructors; the old ones deprecated);
  - `ModelGovernance.java`;
  - `ModelGovStore.java` (`validationEntities`), `jdbc/PostgresModelGovStore.java`.
- `platform-model-governance-testkit`: `InMemoryModelGovStore.java`, `ModelGovContract.java`.
- `platform-model-governance-spring`: `ModelGovAutoConfiguration.java` (passes the settings).
- Docs:
  - `docs/design/model-governance.md` (section 10, "Fix (P036)");
  - `docs/install/model-governance.md` section 5;
  - `CLAUDE.md` (model-governance line);
  - `docs/releases/platform-core-v0.5.1.md`.
- Row marks: `gaps.md` and the three tick-off files, in this PR.
- Commits on main: `875367e` (task files as they were), `dc2860a`, `dd2d3e8`, `912f3cd`, `0b8a3bd` (release),
  `b8810dc` (snapshot).

## Decisions I had to make

1. **The URL pattern is greedy up to the last `@` of the authority.** A password that holds an `@` is therefore
   removed whole. The proposed `://[^/\s@]+@` would have left its tail.
2. **The reference validation is the live one, else the latest passed one.** The task says "from that entity's
   live validation". An entity that is validated but not live yet would otherwise have no date. The existing tests
   rely on such uses being due, and the register shows a date for them. A newer pass for a live entity counts only
   once it is live. The gate checks what actually runs.
3. **A change of where the model runs is read from the model's history.** It is the only change a validation record
   does not hold. A change at the same instant as the validation counts as after it: when the two can't be told
   apart, I chose the side that keeps the review due. P039 replaces this rule (`MOD_LOCATION_CHANGED`).
4. **API for a patch.** The fix needs the review intervals in the gate and in `ModelReviews`, which had no settings,
   so I added constructors that take `ModelGovSettings`. The old constructors are deprecated and use the default
   intervals; the Spring wiring passes the app's settings. `ModelGovStore` gained `validationEntities(useId)`. Both
   built stores have it; an app's own store would need it. Model governance first shipped in 0.5.0, the release
   before this one. The release notes say all this.
5. **Rows are marked in the report PR**, with main's commit hashes, because a rebase merge gives the commits new
   hashes.
6. **`xc-never-leak-2`'s second clause** ("any short secret in a detail or value whose name is not secret-like")
   cannot be fixed from the value. A short secret looks like any short word, so the name decides (`forName`). The
   row is DONE for the URL user part, with this noted.

## Not done, and why

Nothing in scope.

## Questions (numbered, each with a recommendation)

None that change a later task.
