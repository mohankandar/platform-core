# P046 report — Spring Boot 4.2, Dependabot PRs, release 0.8.0

Date: 2026-11-24
Branch: `build/spring-boot-4.2` (then `build/p046-follow-up`, `release/0.8.0`, `release/0.9.0`, `docs/p046-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Merged as |
|---|---|---|---|
| The move | #62 | 45322160720, 45322169010 | `b9447ca` |
| Follow-up: no Nimbus pin, Maven plugins, release notes | #64 | 45324228360, 45324233088 | `8a839da` |
| Version 0.8.0 | #65 | 45326054785, 45326060525; main CI 45326951285 | `6579b05` |
| Tag `platform-core-v0.8.0` on `6579b05` | — | Release run 45327663672, passed | — |
| Main to `0.9.0-SNAPSHOT` | #66 | 45329026761, 45329032724 | `e743a15` |
| This report | this PR | — | — |

## Versions

| | Before | Now |
|---|---|---|
| Spring Boot | 3.3.2 | 4.2.0 (the newest, and only, 4.2 release) |
| Spring Framework | 6.1 | 7.1.0 |
| Spring Security | 6.3.1 | 7.2.0 |
| Micrometer | 1.13.2 | 1.18.0 |
| Nimbus | 9.37.4 (pinned) | 10.9.1 (Security's, no pin) |
| JUnit | 5.10.3 | 6.1.3 |
| Servlet API / SLF4J / Logback (test) / AssertJ | 6.0.0 / 2.0.13 / 1.5.18 / 3.25.3 | 6.1.0 / 2.0.20 / 1.6.6 / 3.27.7 |
| Maven compiler / deploy / surefire plugins | 3.13.0 / 3.1.3 / 3.5.2 | 3.16.0 / 3.2.0 / 3.6.1 |

## Independence

No other product's name or behaviour in code.

## What was built

- **Health:** from `spring-boot-actuator` to `spring-boot-health` (optional, as before); `IntegrationHealthContributor` gives a
  `stream()`.
- **Names by text:** `DataSourceAutoConfiguration` and the availability health auto-configuration moved in Boot 4, and Boot
  ignores a missing name silently. `BootNamesTest` (new) loads every Boot class our wiring names by text, and checks that
  our readiness indicator takes the place of Boot's.
- **No Spring in plain modules:** the dependency list shows Spring jars outside the `-spring` modules in test scope only.
- **Deprecations:** a clean compile shows none from Spring or Boot. Three remain in `Outbound`, from HttpClient 5.6, not Spring.
- **Docs:** `docs/design/platform-baseline.md`, the minimum in the install guides, README and CLAUDE.md.
  `docs/install/licence-signing.md` has no such line: it is our own tool, not an app.

## Tests

**Local `mvn verify`:** 31 modules, 0 failures, 11 skipped on this laptop only. CI forbids skips. Tests changed: health imports;
`LicensingActuatorHealthTest` adds Boot's registry auto-configuration, which is separate in Boot 4; `IntegrationWiringTest`
uses `HealthContributors.Entry` and hides the new package; monitoring-spring has `spring-web` and `spring-boot-jdbc` in test
scope. No assertion was weakened.

Tests per module: errors 13, identity-core 123, licensing 58, licensing-issue 104, licensing-testkit 51, identity-testkit 14,
notifications 85, notifications-testkit 135, licensing-spring 38, identity-jdbc 76, outbound 9, outbound-spring 6, crypto 34,
crypto-testkit 16, fields 100, integration 56, integration-testkit 219, identity-spring 31, notifications-spring 44, setup 48,
fields-testkit 20, crypto-spring 6, erase-log 35, erase-log-testkit 23, erase-log-spring 11, integration-spring 9,
monitoring-testkit 17, monitoring-spring 14 (+2), model-governance-testkit 89, model-governance-spring 7, error-catalogue 15.

## Advisories

| | Before (P042, 81 artifacts) | Now (77 artifacts, all scopes) |
|---|---|---|
| Compile or runtime | open Critical and High on Boot 3.3, Framework 6.1, Security 6.3, Micrometer 1.13 | **none** |
| Test | Logback 1.5.18, AssertJ 3.25.3 | **none** |

GitHub advisory database through `gh api graphql`, 2026-11-23. Control: the same script on the old Boot, Nimbus, Logback,
httpcore5 and spring-core versions found the known advisories. The list was resolved with Nimbus 10.10; 10.9.1 was checked on
its own afterwards: none. SEC-14 is marked `FIXED in P046 (b9447ca)`.

## Dependabot PRs

| PR | Result | Why |
|---|---|---|
| #48 setup-java 6.3.0 | merged (`a8c020e`) | green |
| #49 checkout 7.0.1 | merged (`e1466b9`) | green |
| #50 Maven group | closed by Dependabot | versions follow Boot 4.2; its plugin updates are in #64 |
| #51 JUnit 6.1.3 | closed by Dependabot | in place through the move |
| #52 Nimbus 10.10 | closed by Dependabot | Security 7.2 brings 10.9.1 |
| #63 Maven group | closed | plugin updates on main; httpcore5 5.5 and Logback 1.6.8 are beyond what Boot 4.2 manages |

## Migration checklist

In `docs/releases/platform-core-v0.8.0.md`.

## Decisions I had to make

1. **Versions are pinned, not imported.** `platform-bom` inherits the root, so importing Boot's BOM there would bring Boot's
   versions into every app.
2. **Nimbus:** I first moved it to 10.10, which the task's rule does not allow without a fix in it. Corrected in #64.

## Not done, and why

- Nothing of the task. The three HttpClient deprecations in `Outbound` were left: the task asks for Spring's.

## Process notes

- `git add -A` in P045 also committed the design chat's task files and its edits of `security-findings.md`; nothing was lost,
  but five rows there carry the P045 mark twice. Files are now staged by name.
- A command I wrote held a `git push --force-with-lease` that was never reached; GitHub's activity log shows no force push but
  Dependabot's own.

## Questions

1. **Dependabot will propose httpcore5 and Logback beyond Boot's versions again.** Recommendation: add them to the ignore list
   in `dependabot.yml` (P052 could take it).
