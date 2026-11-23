# P041 report — public API: hide internals; then release `platform-core-v0.6.0`

Date: 2026-11-23
Branch: `api/hide-internals` (then `release/0.6.0`, `chore/0.7.0-snapshot`, `docs/p041-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs (green) | Merged as |
|---|---|---|---|
| The rule, hidden internals, `platform-licensing-issue` | #42 | 45253036716, 45253050882 | 17 commits, `8ab5a8b` … `2f3d380` |
| Version 0.6.0 and release notes | #43 | 45254474338, 45254485612 | `272854b` (main CI 45255543517) |
| Tag `platform-core-v0.6.0` on `272854b` | — | release workflow 45256455310 (verify, deploy to GitHub Packages, GitHub release) | GitHub release `platform-core-v0.6.0` |
| Main to `0.7.0-SNAPSHOT` | #44 | green | `b86f492` |
| This report and the row marks | this PR | | |

Every merge used the README's stop-on-failed-check line. No check failed or was re-run.

`mvn clean verify` locally: **1414 tests**, green except one EICAR test in `ScannerTest`. The laptop's antivirus cuts
that test's download; it is unchanged code and passes on CI (all the runs above).

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 29 | platform-integration | 55 |
| platform-crypto-spring | 6 | platform-integration-spring | 9 |
| platform-crypto-testkit | 16 | platform-integration-testkit | 204 (9 skipped) |
| platform-erase-log | 25 | platform-licensing | 52 |
| platform-erase-log-spring | 8 | platform-licensing-issue (new) | 104 |
| platform-erase-log-testkit | 9 | platform-licensing-spring | 37 |
| platform-error-catalogue | 15 | platform-licensing-testkit | 49 |
| platform-errors | 9 | platform-model-governance-spring | 7 |
| platform-fields | 99 | platform-model-governance-testkit | 74 |
| platform-fields-testkit | 20 | platform-monitoring-spring | 11 |
| platform-identity-core | 121 | platform-monitoring-testkit | 17 |
| platform-identity-jdbc | 68 | platform-notifications | 85 |
| platform-identity-spring | 30 (2 skipped) | platform-notifications-spring | 44 |
| platform-identity-testkit | 14 | platform-notifications-testkit | 135 |
| platform-outbound | 9 | platform-outbound-spring | 6 |
| platform-setup | 47 | | |

platform-licensing went from 152 tests to 52, and the new module has 104. The tests that need to sign a file moved
with the signing code, because a test-scope dependency back from platform-licensing would be a Maven cycle. None was
dropped.

## Independence

**No other product's name or behaviour in code.**

## What was built

- **The rule**, written in `CLAUDE.md` ("Public API") and in `docs/design/public-api.md`:
  - public to apps is what a module's install guide, design doc or CLAUDE.md line names, plus what an app must call
    or implement;
  - everything else is package-private, or lives in a package whose name contains `.internal`;
  - `internal` packages may change in any release and are not supported for apps;
  - test hooks are not public on production classes;
  - testkits use public types, or the internals of the module they test.
- **No JPMS.** The testkits split their production modules' packages, and Spring reflects into the modules. So the
  rule is held by package names, visibility and a build test instead: `PublicApiTest`. It checks that no install
  guide or design doc names an internal type, that testkits use only public types or their own module's internals,
  and that the rule is written down. It fails when a doc names an internal type; I checked by planting one.
- **The about 60 candidates** of the tick-off were each hidden or kept public with a reason. Every moved or hidden
  type is listed in `docs/releases/platform-core-v0.6.0.md`, "Breaking changes". Kept public, with reasons:
  - `errors.Catalogue`: the catalogue build check needs it.
  - The `*ConfigException` types of monitoring, notifications and outbound: an app's start-up tests catch them by
    type, and they carry a code.
  - `ModelGovConfig`: it holds the public `Invalid`.
  - `MonitoringMetrics`: its metric names.
  - `SummaryBucket`, `OutboxEntry`, `OutboxState`, `ConditionState`: rows of public store SPIs.
  - `outbound.TrustBundle`: named in CLAUDE.md.
  - `AddressGuard`'s resolver: production code in platform-integration uses it.
  - identity's `mapping` parsers: setup uses them, and they are now documented as for the setup command.
  - `PostgresMachineBindingStore`, `IdentityStoreException`, `VaultClient` (except `writeSecret`), `VaultEnvironment`.
  - `integration.signin.ClientCredentialsTokens`: a `CallEngine` constructor parameter.
  - `ClockGuard` and `ClockReading`: in the public `LicenceService` and `LicenceStatus`. `ClockGuard`'s javadoc says
    "not for apps".
  - `licensing.json`: setup and the issuing side read with it, so it is marked "not for apps"; one shared JSON is
    later work.
- **Store SPIs** say "SPI: implemented by stores; apps call …" in their javadoc (xc-public-api-17).
- **The secret port** `notifications.secret.*` is recorded as the known exception (xc-public-api-18), with no move.
- **`platform-licensing-issue`**, new and not in the BOM. It holds:
  - the issuing command, the Vault Transit signer, `IdGenerator`, `IssuedRecords`, `IssueError` and `IssueException`,
    keeping the package `licensing.issue`;
  - the signing half of the formats: `LicenceSigner` (moved from `licensing`), `DocumentSigner` (from
    `SignedDocument.sign`), `ResetSigner` (from `LastSeenReset.sign`), `SigningKeys` (from `Ed25519`'s private-key
    methods).

  platform-licensing only verifies now. The licensing testkit depends on the new module, which is test scope for
  apps. Setup and the apps do not depend on it, apart from setup's tests. CLAUDE.md, the licensing design and the
  licence-signing guide say so.
- **Release `platform-core-v0.6.0`**: its notes cover P037–P041:
  - the 21 new codes;
  - the tests;
  - model governance schema V3 and V4;
  - the doc catch-up;
  - the API rule and every moved type.

## Tests asked for → where (rows)

| Row | Result | Main commit |
|---|---|---|
| xc-public-api-1 (rule, guard) | FIXED: `PublicApiTest` | `8ab5a8b`, `08a5588` |
| xc-public-api-4 (`SignInHeaders`) | FIXED: package-private in `engine` | `dda8d6c` |
| xc-public-api-5 (issuing side) | FIXED: `platform-licensing-issue` | `8805839` |
| xc-public-api-6 (setup) | FIXED | `a5cd39b` |
| xc-public-api-7 (JSON helpers) | FIXED: `fields.json` and `notifications.webhook.Json` hidden; `integration.json.Json` internal; `licensing.json` marked | `f356239`, `0382e41`, `dda8d6c` |
| xc-public-api-8 (integration) | FIXED | `dda8d6c` |
| xc-public-api-9 (notifications) | FIXED | `0382e41` |
| xc-public-api-10 (licensing) | FIXED | `8dd3896`, `8805839` |
| xc-public-api-12 (crypto) | FIXED: guide states the app policy; `writeSecret` package-private | `45b74a5` |
| xc-public-api-13 (erase-log SPI) | FIXED (documented) | `11c197b` |
| xc-public-api-14 (Spring wiring) | FIXED: wiring types package-private, each analyzer still loads (`FailureAnalyzerRegistrationTest`, `WiringVisibilityTest`) | `1b2ec12`, `9928aad`, `abff9d2` |
| xc-public-api-15 (test hooks) | FIXED | `8805839`, `8dd3896`, `0382e41` |
| xc-public-api-16 (identity mapping) | FIXED: package documented; `MappingTargets` package-private (not dead: the UUID form uses it) | `8b3da38` |
| xc-public-api-17 (SPI marking) | FIXED (javadoc) | `11c197b`, `94c3545`, `8dd3896` |
| xc-public-api-18 (secret port) | FIXED as recorded (no move: a package rename is its own task) | `94c3545` |

## Files changed, commits

- 17 commits on main for the code. Three agents did the per-module hiding in separate worktrees, split by module
  group, and I reviewed and cherry-picked them. I did the rule, the guard test and the new module myself.
- New module `platform-licensing-issue`: root `<modules>` and the catalogue module's pom; not in the BOM.
- New: `docs/design/public-api.md`, `PublicApiTest`, `docs/releases/platform-core-v0.6.0.md`.

## Decisions I had to make

1. **"Keep Java package names stable" against "move to `.internal`".** The task asks for internal packages, so I
   used them, but only for the types that another package or module needs. A type used only in its own package
   became package-private instead. No existing package was renamed: the issuing side keeps `licensing.issue`, in a new
   module.
2. **`platform-licensing-issue` is not in the BOM.** CLAUDE.md asks every new module to be added to the BOM. Like the
   catalogue check, our own issuing tool is the exception, because the BOM is what apps import. CLAUDE.md now says so.
3. **The licensing testkit depends on the issuing module** to sign test licences. That puts it on apps' test
   classpath only, never on a customer's runtime classpath.
4. **Licensing's signing tests moved with the signing code** (see the test counts above).
5. **A few gap rows were judged differently:**
   - `SummaryBucket` stays public: a public SPI record holds it.
   - `AddressGuard`'s resolver is not a test hook: platform-integration's save-time check uses it.
   - `MappingTargets` is not dead.

## Not done, and why

- The second half of xc-public-api-17, listing the SPI types in each install guide, was not done. The javadoc marks
  them; the guides already name the stores where an app needs them.

## Questions (numbered, each with a recommendation)

None. This is the last task of the chain.
