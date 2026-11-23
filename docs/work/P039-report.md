# P039 report — untested rules: setup, identity, notifications, monitoring, model governance

Date: 2026-11-23
Branch: `test/setup-identity-notifications-monitoring-mg` (then `docs/p039-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs (green) | Merged |
|---|---|---|---|
| Decision, fixes, tests, docs | #38 | 45233335326, 45233346174 | 11 commits, `eba60f2` … `7e7f586` |
| This report and the row marks | this PR | | |

The merge used the README's stop-on-failed-check line. No check failed or was re-run.

`mvn verify` (with the catalogue regenerated): **1386 tests, 0 failures, 13 skipped** (the same skips as before).

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 27 | platform-integration | 55 |
| platform-crypto-spring | 5 | platform-integration-spring | 9 |
| platform-crypto-testkit | 16 | platform-integration-testkit | 204 (11 skipped) |
| platform-erase-log | 25 | platform-licensing | 152 |
| platform-erase-log-spring | 8 | platform-licensing-spring | 37 |
| platform-erase-log-testkit | 9 | platform-licensing-testkit | 48 |
| platform-error-catalogue | 11 | platform-model-governance-spring | 6 |
| platform-errors | 9 | platform-model-governance-testkit | 73 |
| platform-fields | 98 | platform-monitoring-spring | 9 |
| platform-fields-testkit | 20 | platform-monitoring-testkit | 17 |
| platform-identity-core | 120 | platform-notifications | 83 |
| platform-identity-jdbc | 68 | platform-notifications-spring | 42 |
| platform-identity-spring | 30 (2 skipped) | platform-notifications-testkit | 134 |
| platform-identity-testkit | 14 | platform-outbound | 9 |
| platform-setup | 44 | platform-outbound-spring | 4 |

## Independence

**No other product's name or behaviour in code.** The tests use `PRODUCT_A`, the demo's made-up slots, and hosts
under `example` / `corp.example` (and `mail.invalid`).

## What was built

### The decision on P035 question 1

**A change of where a model runs goes through the gate, for every materiality.** This follows the owner-approved
design section 4 and replaces P034's "due for review with a warning".
- Each validation now records where the model ran: `ValidationRecord.runsWhere`, in model-governance **schema V4**
  (`V4__modelgov_validation_location.sql`, column `mg_validation.runs_where`).
- `ModelGate.requireLive` refuses with the new code **`MOD_LOCATION_CHANGED`** while the live validation is for
  another location. It keeps refusing until a validation where the model now runs is recorded, approved and made
  live.
- A validation recorded before V4 has no location. For those, a change in the model's history at or after the
  validation's time counts, which keeps the safe side.
- The use is still due for review at once, as before.
- The decision is written in `docs/design/model-governance.md`, "Decision and fix (P039)".

### Other changes

- **model-governance-19:** an entity's pass mark counts only while it is at least as strict as the default in force.
  When the app tightens a default, a looser mark stops counting and the default applies. This holds for validations
  (the threshold copied into the record) and for monitoring. `looserEntityMarks(useId)` on `ModelGovernance` and
  `ModelMeasures` lists such marks for the register. The pack's format is unchanged: it shows the default as the mark
  in force.
- **install-model-governance-5:** an empty `platform.model-governance.forbidden-roles` stops the start with
  `MOD_SETTINGS_INVALID`.
- **monitoring-28:** `platform.monitoring.every` is now the interval of every check that does not set its own. A
  check that overrides `every()` keeps its own interval. `DependencyStatus` has a constructor that takes the interval.
- **monitoring-34:** the mail check is built from notifications-spring's checked `NotificationsConfig` (an optional
  dependency of monitoring-spring), so it uses the sender's own host, port and from-address:
  - the port from the security when none is set (465 for TLS, not 587);
  - under Microsoft 365, the default host and the sender mailbox, so there is no false DOWN without `from-address`.
- **notifications-78:** a build guard (`ReleasedSqlFilesTest`, in platform-error-catalogue) pins the SHA-256 of every
  `db/platform` SQL file. An edited or removed file fails the build. A new file fails until it is pinned, and the
  failure message gives the line to add. Model governance's V3 and V4 are pinned as not yet released.
- **setup.md §5** now states the P014 rules the code follows:
  - grants of slots the mapping file no longer names are "not in the file";
  - grants under another issuer are left alone for now;
  - a waiting renewal's slots are stored at once;
  - a connection the app disabled is a conflict until `--replace-connection` is given.

## Tests asked for → where (row → result)

| Row | Result | Main commit | Test |
|---|---|---|---|
| monitoring-28 | FIXED | `e0b4e89` | `MonitoringWiringTest.theEverySettingIsTheIntervalOfChecksThatDoNotChooseTheirOwn` |
| monitoring-30 | TESTED | `e0b4e89` | `MonitoringWiringTest.theKeyServiceCheckComesFromTheBeanAndIsRequiredOnlyWhenTheProductSaysSo` |
| monitoring-34 | FIXED | `e0b4e89` | `MonitoringWiringTest.theMailCheckUsesTheSendersOwnSettings` |
| model-governance-19 | FIXED | `eba60f2` | `ModelGovContract.aCustomerMarkLeftLooserByATightenedDefaultStopsCounting` (both stores) |
| model-governance-33 | FIXED (decision) | `eba60f2` | `ModelGovContract.aChangeOfWhereTheModelRunsStopsItUntilValidatedThere`, `anOlderValidationWithoutItsLocationIsJudgedByTheModelsHistory` |
| model-governance-105 | FIXED in P037 | `0b08363` | with model-governance-84 (schema V3) |
| install-model-governance-5 | FIXED | `eba60f2` | `ModelGovWiringTest.wrongSettingsAreAllListed` |
| notifications-65 | TESTED | `6e22171` | `SecretsNeverLeakTest.noSecretInLogsReportsMessagesOrTables` |
| notifications-78 | TESTED (new guard) | `b4602b6` | `ReleasedSqlFilesTest` |
| notifications-145 | TESTED | `40468ef` | `ApprovalExpiryOnPostgresTest.twoInstancesAtOnceTellEachRequesterOnce` |
| setup-98 | TESTED | `0e7e3a4` | `PushCallerTest.aCertificateCallerStillNeedsTheCustomersActiveConnection` (memory and Postgres) |
| setup-115 | TESTED + doc | `c211a6b`, `fa4d697` | `SetupCommandTest.grantsOfASlotTheMappingFileNoLongerNamesAreNotInTheFile`, `grantsUnderAnotherIssuerAreLeftAlone` |
| setup-116 | TESTED + doc | `fcbf967`, `fa4d697` | `SetupCommandTest.theSlotsOfARenewalThatStartsLaterAreStoredAtOnce` |
| setup-117 | TESTED + doc | `17ff075`, `fa4d697` | `SetupIdentityChangesTest.aConnectionTheAppDisabledIsAConflictUntilReplaceConnectionIsGiven` |

**Each test fails without its rule.** For each one I broke the rule on purpose:
- the gate's location check switched off;
- the key-service setting ignored;
- the old env-based mail check put back;
- `for update skip locked` removed;
- the `hasActiveConnection` check removed;
- the planning loops changed.

In each case the test failed, and the code was restored afterwards.

## Files changed, commits

- 11 commits on main, `eba60f2` … `7e7f586`. An agent wrote the setup, identity and notifications rows in a separate
  worktree, and I reviewed and cherry-picked them.
- New:
  - `V4__modelgov_validation_location.sql`;
  - the code `MOD_LOCATION_CHANGED` (catalogue regenerated: 669 codes);
  - `ReleasedSqlFilesTest`, `ApprovalExpiryOnPostgresTest`.
- Changed: `platform-monitoring-spring/pom.xml`, with notifications-spring as an optional dependency.
- Docs:
  - `docs/design/model-governance.md` and `setup.md`;
  - `docs/install/model-governance.md` and `monitoring.md`;
  - `CLAUDE.md`.

## Decisions I had to make

1. **"The register shows it"** (model-governance-19). The rule says the register shows a mark that stopped counting.
   I made that a query, `looserEntityMarks`, and did not change the pack format `platform-model-pack/1`: its
   `setFor` field would have needed a new enum value. The pack still names the default as the mark in force, which is
   true.
2. **A check that chooses its own interval keeps it** (monitoring-28). The guide said `every` is "how often each check
   runs". I made it the interval of checks that don't set one, and wrote that into the guide.
3. **The mail check needs notifications-spring.** Without its checked settings there is no mail check. An app that
   doesn't use notifications-spring has no platform mail to check.
4. **`ValidationRecord` has a new component** (`runsWhere`, last, nullable). The old constructor is kept for records
   without it. Model governance first shipped in 0.5.0; the 0.6.0 notes list the change.

## Not done, and why

Nothing.

## Questions (numbered, each with a recommendation)

None.
