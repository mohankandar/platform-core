# P045 report — security fixes 3: model gate, push callers, leak backstops, licensing; release 0.7.0

Date: 2026-11-23
Branch: `sec/gate-push-leaks-licensing` (then `release/0.7.0`, `release/0.8.0`, `docs/p045-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Merged as |
|---|---|---|---|
| FIX-D, FIX-E, FIX-G, FIX-H | #58 | 45312161051, 45312166826 | `47be6c5`, `45e2277`, `db9401f`, `72109b9` |
| Version 0.7.0 | #59 | 45312964248, 45312971675; main CI 45313898940 | `34a0422` |
| Tag `platform-core-v0.7.0` on `34a0422` | — | Release run 45314473839, passed | — |
| Main to `0.8.0-SNAPSHOT` | #60 | 45315950287, 45315955608 | `8c0ad9f` |
| This report | this PR | — | — |

Release notes: `docs/releases/platform-core-v0.7.0.md`.

On #60, GitHub's API answered with an error while the checks were being watched, so the merge command did not run and the
PR stayed open. The watch was run again and the PR merged only after its checks passed.

**Local `mvn verify`:** 31 modules, 0 failures, 11 skipped on this laptop only (10 where TLS to 127.0.0.1 is intercepted, 1
symbolic-link right). CI forbids skips and ran everything.

Tests per module:

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 34 | platform-integration-testkit | 219 |
| platform-crypto-spring | 6 | platform-licensing | 58 (+6) |
| platform-crypto-testkit | 16 | platform-licensing-issue | 104 |
| platform-erase-log | 35 (+1) | platform-licensing-spring | 38 (+1) |
| platform-erase-log-spring | 11 | platform-licensing-testkit | 51 (+2) |
| platform-erase-log-testkit | 23 | platform-model-governance-spring | 7 |
| platform-error-catalogue | 15 | platform-model-governance-testkit | 89 (+15) |
| platform-errors | 13 (+4) | platform-monitoring-spring | 12 (+1) |
| platform-fields | 100 (+1) | platform-monitoring-testkit | 17 |
| platform-fields-testkit | 20 | platform-notifications | 85 |
| platform-identity-core | 123 (+2) | platform-notifications-spring | 44 |
| platform-identity-jdbc | 76 (+8) | platform-notifications-testkit | 135 |
| platform-identity-spring | 31 (+1) | platform-outbound | 9 |
| platform-identity-testkit | 14 | platform-outbound-spring | 6 |
| platform-integration | 56 (+1) | platform-setup | 48 (+1) |
| platform-integration-spring | 9 | | |

## Independence

No other product's name or behaviour in code.

## Findings

"Probed": the fix was switched off in a separate working copy and the named test failed. The others reproduce the attack or
mistake, but were not run against unfixed code.

| Finding | Result | Commit on main | Test |
|---|---|---|---|
| SEC-6 gate trusts `mg_live` | FIXED | `47be6c5` | `ModelGovContract` (both stores): an approval by a forbidden role, another entity's validation, a failed validation made live past the service. **Probed.** |
| SEC-31 four eyes | FIXED | `47be6c5` | `ModelGovContract` (`MOD_APPROVER_SAME_PERSON`) |
| SEC-32 role spelling, empty list | FIXED | `47be6c5` | `ModelGovContract` |
| SEC-33 history rewritable | FIXED | `47be6c5` | `HistoryInsertOnlyTest` (identity, model governance) |
| SEC-34 temporary-table trick | PARTLY FIXED | `47be6c5`, `72109b9` | `SearchPathTest` in five modules, `PostgresStoresTest.everyFunctionHasAFixedSearchPath` (licensing; **probed**), the attack in `ModelGovSchemaTest`. Not done: see below. |
| SEC-7 person as machine | FIXED | `45e2277` | push caller tests (`IDN_PUSH_NOT_MACHINE`) |
| SEC-16 certificate binding by name | FIXED | `45e2277` | push caller tests |
| SEC-17 grants of a replaced login system | FIXED | `45e2277` | setup tests |
| SEC-18 secret shapes | FIXED, except the threshold (decision 4) | `45e2277` | client-binding and sign-in file tests |
| SEC-44, SEC-45 group names, text direction | FIXED | `45e2277` | claims and mapping tests |
| SEC-11 bundle | FIXED | `db9401f` | `BundleSourcesTest`. **Probed** (sources). |
| SEC-19 SafeText | FIXED, lower-case hex kept (decision 5) | `db9401f` | `LeakBackstopTest` |
| SEC-20 forged log lines | FIXED | `db9401f` | `LeakBackstopTest` (`PlatformError`); the same call in `DependencyStatus` has no test of its own |
| SEC-21, SEC-55 reference | FIXED | `db9401f` | `MonitoringWiringTest`. SEC-21 **probed**; for SEC-55 the test checks the filter's declared order, not a running security chain. |
| SEC-22 guessable references | FIXED | `db9401f` | `LeakBackstopTest`. **Probed.** |
| SEC-23 health details | FIXED | `db9401f` | `EraseLogWiringTest`, `MonitoringWiringTest` (adjusted); install guide |
| SEC-54 `Verdict` | FIXED | `db9401f` | no dedicated test (three-line override) |
| SEC-56 driver text | FIXED | `db9401f` | `BrokenStoreLogsTest` runs against it; no dedicated test that a driver's `Detail:` text is absent |
| SEC-12, SEC-43 trust root | FIXED | `72109b9` | `LicenceBuildTest`, `LicensingWiringTest.anInstallationCannotAddOrChangeATrustedKey`, `DevelopmentKeyWiringTest`. **Probed** (second copy, development switch). |
| SEC-13 clock and versions | FIXED | `72109b9` | `PostgresStoresTest` (runtime user), `LicenceServiceTest`, `LicensingWiringTest.anEmptyStore…`, `LicenceCommandTest`. **Probed** (guard, floor, version check). |
| SEC-26 canonical form | FIXED | `72109b9` | `LicenceBuildTest`. **Probed.** |
| SEC-27 usage chain claim | FIXED | `72109b9` | `UsageExportTest.aRewrittenFileIsFoundByTheKeptAnchorOnly`; design wording |
| SEC-51 smaller items | FIXED | `72109b9` | `LicenceBuildTest` (signed file size). `deployment` documented as not enforced; imports removed. |

## What was built (FIX-G and FIX-H)

- **`LicenceBuild`:** `META-INF/platform/licence-keys.json` and `licence-build.properties` (`built-at`,
  `development-keys`), each exactly once on the classpath and in one place. A setting of an earlier release stops the app.
  `--keys` is gone from the setup and licence commands.
- **Licensing schema V2:** forward-only `lic_last_seen`, insert-only `lic_clock_reset`, `lic_version_floor` kept by a
  trigger. `verify` asks for fewer rights on these.
- **Time floor:** effective time is never before the licence's issue time or the build time.
- **References:** see decision 1.
- **Schema versions:** identity 5, licensing 2, fields 2, erase log 3, integration 3, model governance 5; all pinned.

## Decisions I had to make

1. **References sort by tenth of a second, not strictly.** My first SEC-22 fix moved the time part on for every further
   reference in a tenth. With one reference per request a busy server's references would have run ahead of the clock and in
   time overflowed. Now the time is real and repeats within a tenth are refused. `ErrorsTest`'s strict-order check was
   loosened for that.
2. **A clock reset needs the schema owner's login** (`RESET_NEEDS_OWNER`). The database cannot check our signature, so any
   function the everyday user may call to move the time back would undo SEC-13.
3. **`LicenceStore` got a method** (`highestVersion`) and the licensing API changed. No app runs licensing yet.
4. **The secret-shape threshold stays at 40** (SEC-18): 32 flagged legitimate client IDs.
5. **Lower-case hex stays visible in `SafeText`:** SHA-256 digests are part of many messages.
6. **Identity behaviour changed, API not:** `sub` is no longer a default client-ID claim, group names are exact, and setup
   lists grants of a replaced login system. Described under "Upgrading to 0.7.0" and in the release notes.

## Not done, and why

- **SEC-34, part:** `ModelGovSchema.verify` does not warn when the app's user holds DELETE on `mg_measure`. The trigger
  refuses the delete either way.
- **SEC-48, part** (P044): the fingerprint canonical form, unchanged by decision.

## Questions

1. **Is `apply-reset` with the schema owner's login acceptable?** Recommendation: yes. A reset is rare and IT runs the
   schema step with the same login.
2. **The 1.0.0 gate (P047 B5) asks for no open Medium finding.** SEC-14 is taken by P046. All others still open are Low or
   Info in `security-findings.md`; SEC-24 is rated "Low (Medium if tenant admins ever enter webhooks)" and SEC-42 "Info (High
   once one database serves several customers)". Recommendation: treat both as before-hosting items.
