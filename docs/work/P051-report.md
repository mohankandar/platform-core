# P051 report — v0.8.0 checked against the apps' Spring Boot 4.2 needs; patch 0.8.1

Date: 2026-11-24
Branch: `fix/boot-4.2-app-needs` (then `release/0.8.1`, `release/0.9.0`, `docs/p051-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Merged as |
|---|---|---|---|
| Check module, setup jar | #68 | 45332671753, 45332679361 | `94fa254` (task file), `9b4347d` |
| Version 0.8.1 | #69 | 45333614915, 45333622392; main CI 45334531950 | `c3829c2` |
| Tag `platform-core-v0.8.1` on `c3829c2` | — | Release run 45335160937, passed | — |
| Main back to `0.9.0-SNAPSHOT` | #70 | 45336507703, 45336513045 | `aa89014` |
| This report | this PR | — | — |

## Independence

No other product's name or behaviour in code.

## Items

All tests are in the new module `platform-app-check` (build check only: never deployed, not in the BOM).

| # | Item | Result | Test |
|---|---|---|---|
| 1 | Auto-configuration order; a plain Boot 4.2 app starts with each module | passed in 0.8.0 | `AppContextTest` (9 modules alone, all together, on a DataSource made by Boot; our readiness in place of Boot's), `BootNamesTest` (P046) |
| 2 | One Nimbus, Security's | passed in 0.8.0 | `DependenciesTest` |
| 3 | One BOM version, nothing of Boot 3 | passed in 0.8.0 | `DependenciesTest` |
| 4 | Names apps use stay public | passed in 0.8.0 | `StableNamesTest` (25 types, the `platform.outbound.*` names) |
| 5 | Migrations unchanged; run under Flyway 12 | passed in 0.8.0 | `ReleasedSqlFilesTest` (pinned), `FlywayRunsTest` (7 runs, second run does nothing, our `verify` accepts) |
| 6 | No Jackson 2 | passed in 0.8.0 | `DependenciesTest` (POMs and public signatures) |
| 7 | The setup jar runs with `java -jar` | **failed in 0.8.0, fixed in 0.8.1** (`9b4347d`) | `SetupJarTest` (`--help`; refusal without keys; a wrapper's `plan` against PostgreSQL) |
| 8 | Notes list versions and defaults | done in 0.8.1 | `docs/releases/platform-core-v0.8.1.md` |

**Local runs:** a full `mvn verify` passed except one error of the new `DependenciesTest` itself (a servlet class this app does not
have); corrected, and the two remaining modules were run again: green. CI ran the final state in full. New: app-check 18 tests.

## Decisions I had to make

1. **A module of its own for the checks,** because they need Boot's own dependency management and starters, as an app has.
2. **The plain setup jar refuses to plan** (`SETUP_KEYS_INVALID`): the keys are in the product's wrapper (P045). The test makes
   such a wrapper for the dry run.
3. **`setup --help`** was added; the item asks for it and it did not exist.

## Not done, and why

- **Item 4, "unchanged":** the test checks that the types are public and the property names stay. It does not compare every
  method signature. `StableNamesTest` could do that from 1.0.0 on.
- **Item 1** shows that beans are there with Boot's DataSource; the order itself is covered by `BootNamesTest` (the names exist).
- **Item 8** lists the Boot defaults that touch the platform (probes, moved classes, Flyway, Jackson); for the rest it points
  to Boot's own notes.

## Questions

1. **Should `StableNamesTest` hold full signatures from 1.0.0 on?** Recommendation: yes, as part of P047's final checks.
