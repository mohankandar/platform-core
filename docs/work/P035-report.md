# P035 report — strength check, step 1: tick every design rule off against the code (read-only)

Date: 2026-11-23
Branch: `docs/p035-tick-off`
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

- No code changed. The task file, the two design-doc decision sections, the tick-off files, `gaps.md` and this report
  come to main in one PR (number and CI run below).
- `mvn verify` was not needed for a docs-only change. CI runs the full build on the PR, including the product-name
  guard.
- **1305 rules** were ticked off across the 13 design docs, plus 86 never-leak places, 7 schema runs, 22 modules'
  public types and 11 install guides.
- **Gaps: 342 rows: A 3 · B 85 · C 254** ([gaps.md](gaps.md)).

PR: see the merge note at the end.

## Independence

**No other product's name or behaviour in code.** Confirmed by:
- the guard (`NoProductNamesGuardTest`), which walks the whole repository, so every module is covered, including
  those added after P030;
- my read of the code;
- the tick-off agents, each told to flag any name they met.

The exceptions are two old notes at the repository root:
- **`INTEGRATION-NOTES.md`** names two apps.
- **`SCHEMA_CONTRACT.md`** names one app and describes its schema choices. The guard does not read root Markdown
  files.

Planned fix: S2-7, row `xc-independence-1`. Move the generic content into `docs/install/identity.md`, delete both
files, and widen the guard to every text file outside `docs/work/` and `docs/design/` (`xc-independence-2`).

The design docs name the apps in places. That is allowed (the guard skips `docs/design/` as history and examples) and
is noted as C (`xc-names-1`).

## What was built

No code was changed. These documents were produced:
- [tick-off/](tick-off/): the full tables, one row per rule with ID, status, code and test, per design doc or group of docs.
  - [case-fields-and-erase-log.md](tick-off/case-fields-and-erase-log.md)
  - [crypto.md](tick-off/crypto.md)
  - [integration.md](tick-off/integration.md) (integration-module, integration-profile-format, integration-standards)
  - [licensing.md](tick-off/licensing.md) (licensing, licensing-file-format)
  - [notifications.md](tick-off/notifications.md)
  - [setup-and-identity.md](tick-off/setup-and-identity.md)
  - [errors-monitoring-model-governance.md](tick-off/errors-monitoring-model-governance.md) (error-codes, monitoring, model-governance)
  - Cross-checks: [xc-catalogue-and-schemas.md](tick-off/xc-catalogue-and-schemas.md) (B2.2, B2.4),
    [xc-never-leak.md](tick-off/xc-never-leak.md) (B2.3), [xc-product-independence.md](tick-off/xc-product-independence.md)
    (B2.5), [xc-public-api.md](tick-off/xc-public-api.md) (B2.6). The install-guide check (B2.1) is in each doc's file,
    under "Install guide check".
- [gaps.md](gaps.md): every gap, grouped into nine proposed step-2 tasks.

### Counts per design doc

| Design doc | Rules | DONE | UNTESTED | PARTLY | MISSING | DIFFERENT | APP-SIDE | DOC GAP |
|---|---|---|---|---|---|---|---|---|
| case-fields | 134 | 105 | 6 | 1 | 0 | 0 | 8 | 14 |
| crypto | 115 | 72 | 16 | 12 | 0 | 6 | 5 | 4 |
| erase-log | 103 | 74 | 6 | 12 | 0 | 2 | 6 | 3 |
| error-codes | 44 | 26 | 6 | 4 | 0 | 1 | 6 | 1 |
| integration-module | 192 | 138 | 11 | 4 | 3 | 11 | 9 | 16 |
| integration-profile-format | 38 | 31 | 2 | 5 | 0 | 0 | 0 | 0 |
| integration-standards | 14 | 10 | 0 | 0 | 1 | 0 | 3 | 0 |
| licensing | 105 | 74 | 6 | 4 | 0 | 4 | 4 | 13 |
| licensing-file-format | 73 | 58 | 8 | 3 | 0 | 3 | 0 | 1 |
| model-governance | 112 | 88 | 3 | 4 | 0 | 1 | 11 | 5 |
| monitoring | 73 | 52 | 7 | 5 | 1 | 2 | 3 | 3 |
| notifications | 168 | 124 | 9 | 10 | 0 | 2 | 11 | 12 |
| setup | 134 | 93 | 5 | 0 | 3 | 1 | 9 | 23 |
| **Total** | **1305** | **945** | **85** | **64** | **8** | **33** | **75** | **95** |

What these counts mean:
- **MISSING:** these are all items the design itself puts off to later tasks:
  - hub mode, the organization claim and grants under an older issuer (P012 section 13);
  - live-system opt-in tests, the scanner being required on the hosted service, and ICAP over TLS (P027);
  - one monitoring item.
- **DIFFERENT:** almost all follow a later decision, so the code is right and the design body is stale. The
  exceptions are listed under the A and B gaps.
- **DOC GAP:** decisions recorded in a task report but not in the design doc's body. The largest groups are P014
  (setup), P017 and P019 (case fields), P010 and P011 (licensing), and P023–P028 (integration).

### Cross-check results

| Check | Result | Gaps (A / B / C) |
|---|---|---|
| B2.1 Install guides | 11 guides, about 500 items. 40 differences, mostly missing settings or codes, and one property documented but read by nothing (`platform.monitoring.every`). | in each doc's file |
| B2.2 Error catalogue | All 38 code enums implement `PlatformCode`. Not codes yet: every Spring module's startup settings error (`*_SETTINGS_INVALID`, `ModelGovConfig.Invalid`, the missing-bean refusals) and two string codes (`ERASE_HANDLER_ERROR`, `FLD_PARTIAL_SEARCH_UNAVAILABLE`). 16 exceptions carry a code but don't implement `HasPlatformCode`, so monitoring reports them as `MON_CHECK_FAILED`. 6 store failures have no code. Setup's fix texts for `SETUP_DB_SETTINGS_MISSING` and `SETUP_KEY_SERVICE_SETTINGS_MISSING` lost their variable names when they moved to properties in P032. | 0 / 12 / 5 |
| B2.3 Never-leak | 86 places: 66 covered by a test, 17 untested, 3 leak (verified below). | 1 / 4 / 14 |
| B2.4 Schemas | All 7 runs have documented runtime rights and a tested startup verify. Missing: a test that the runtime user cannot run DDL for licensing, notifications and fields; some verify outcomes untested (erase log, integration). | 0 / 5 / 4 |
| B2.5 Independence | The guard covers every module. Two root notes are outside its file list (above). | 0 / 1 / 1 |
| B2.6 Public API | 550 public types; no `module-info`, no internal packages, no visibility rule. About 60 internal-looking types and two unsafe entry points (`ValueStore.delete`, which goes around the erase log, and `WebhookOptions.allowingPlainHttpForTests`). | 0 / 2 / 16 |

### The A gaps (each verified in the code)

1. **`xc-never-leak-2` / `-11`:** `SafeText.scrub` (platform-errors) has no pattern for a password in a URL's user
   part. `DiagnosticBundles.configuration()` copies every property of the app's own property sources and only
   scrubs it. So `spring.datasource.url=jdbc:postgresql://user:pw@host/db` reaches the support bundle with the
   password in it, and so does any `PlatformError` detail that holds such a URL.
2. **`xc-devkey-1`:** `LicensingConfig.java:94` rebuilds the trusted keys with `TrustedKeys.of(keys)`, which drops the
   `"development": true` marks. In a Spring app the development-key licence is therefore never recognised, and a
   local http login issuer is refused even under the demo licence. This fails safe, but it is shipped behaviour that's
   wrong, and no test covers it.
3. **`model-governance-39`:** in `ModelGovernance.validate` (lines 166–171), a pass for one entity moves the review
   date of the whole use. For a use that covers every entity, another entity can then run past its own review
   without the gate or `ModelReviews` noticing. This is my P034 code.

## Tests asked for → where

The task asks for no tests. Each tick-off row names the test that proves the rule, or says none does.

## Files changed, commits

- `docs/work/P035.md`, `docs/design/integration-module.md`, `docs/design/monitoring.md`: committed as they were
  (task A1).
- `docs/work/tick-off/*.md` (11 files), `docs/work/gaps.md`, `docs/work/P035-report.md`: new.
- One commit, `docs: P035 tick-off, gap list and report`.

## Decisions I had to make

1. **The work was split among ten read-only agents**, each with the same brief: DONE needs code and a test that
   would fail if the rule broke, and both must be named. I then checked every A row and three B rows in the code
   myself:
   - the setup fix texts;
   - the modelgov purge guard;
   - the development-key marks.
   
   I also checked the guard's coverage.
2. **Grades changed after checking:**
   - `model-governance-39` went from B to A. A model can run for an entity past its review.
   - `xc-never-leak-23` went from A to B. `AuthenticatedIdentity`'s default `toString` prints the raw claims, but
     the platform never prints it; only an app that logs it would leak.
3. **"Done by construction":** some agents counted rules as DONE that are held by structure, with no test. Examples
   are the licence check never enforcing, monitoring storing nothing in the database, and liveness never depending on
   checks. Structure proven by the build counts. For behaviours, I left the counts as the agents gave them and added
   one C row (`xc-strict-1`) to add an assertion test each.
4. **How rows are grouped into tasks:**
   - every A row goes to S2-1;
   - every code and catalogue row to S2-2;
   - every leak, safety and unsafe-API row to S2-3;
   - schema rows to S2-4;
   - the other B rows by module to S2-5 and S2-6;
   - docs and guides to S2-7, internals to S2-8, and the remaining C rows to S2-9.
   
   Rows that only point to another row are kept, so the fix ticks both off.
5. **Install-guide differences are in each doc's tick-off file**, not in a file of their own, so each one sits next
   to the rules it describes.

## Not done, and why

- **P012 sections 5.1–5.6** (identity connection rules) were not ticked off one by one. `setup.md` section 3 points to
  them, and the rules they hold are checked where `setup.md` and the install guides repeat them.
- **Install-guide item counts are approximate** in the setup and identity check.

## Questions (numbered, each with a recommendation)

1. **A change of where a model runs** (`model-governance-33`). Design section 4 says it goes through the gate. P034
   decision 2 made it only "due for review", with a warning by default. Recommendation: follow the design for HIGH
   materiality (the gate refuses until revalidated) and keep the warning for MEDIUM and LOW. This goes in S2-6.
2. **The two grade changes** above (decision 2). Recommendation: accept them.
3. **Hiding internals (S2-8)** is C, but after apps pin the integration release it becomes a breaking change.
   Recommendation: do S2-8 before the release, at least a rule (an `internal` package) for the about 60 candidates.
4. **Task size.** Nine step-2 tasks are proposed. S2-2, S2-3 and S2-4 are small and could be one task.
   Recommendation: run S2-1 alone first (the three A rows), then the others as listed.
