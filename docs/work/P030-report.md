# P030 report — independence, the profile format, CI, publishing, and release `platform-core-v0.1.0`

Date: 2026-11-22
Branches: feature/independence-release (PR #1), release/0.1.0 (PR #2), chore/0.2.0-snapshot (PR #3), docs/p030-report
(this report, PR #4); all merged through green PRs and deleted
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

- **Tag:** `platform-core-v0.1.0` (tag object `0d8604f`) on commit **`c13450017a1e1eaec249da96553ae988a8ae85b8`**
  ("release: platform-core 0.1.0", the merge of PR #2).
- **Release:** https://github.com/mohankandar/platform-core/releases/tag/platform-core-v0.1.0, made by the release
  workflow from `docs/releases/platform-core-v0.1.0.md`.
- **Tests:**
  - On the CI runner (ubuntu-latest): **943 tests, 0 skipped.** The Testcontainers PostgreSQL tests and the 3
    end-to-end TLS tests (`PrivateCaTest`, 6 of 6) ran there.
  - On the owner's laptop, `mvn verify` gives the same 943 with the 3 TLS tests skipped (Norton).

| Module | Tests |
|---|---|
| platform-identity-core | 91 (guard test now 2 tests) |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 132 |
| platform-identity-testkit | 7 |
| platform-notifications-spring | 41 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 32 |
| platform-crypto | 20 |
| platform-crypto-testkit | 16 |
| platform-setup | 29 (was 26) |
| platform-fields | 91 |
| platform-fields-testkit | 18 |
| platform-crypto-spring | 5 |
| platform-erase-log | 16 |
| platform-erase-log-testkit | 9 |
| platform-erase-log-spring | 5 |
| platform-integration | 20 |
| platform-integration-testkit | 93 (was 75) |

The `platform-integration-profiles` module (23 tests) was removed, as the task asks.

**PRs**, all merged with `gh pr merge --rebase --delete-branch` after green checks:

| PR | What | Merged as |
|---|---|---|
| #1 | B1–B4: independence, profile format, CI, publishing | `1c118d9` |
| #2 | Release 0.1.0: version, release notes | `c134500` (tagged) |
| #3 | main → `0.2.0-SNAPSHOT` | `f651b09` |
| #4 | This report | — |

**CI runs** (workflow "CI", job `verify`; each PR ran on push and on pull_request):

- PR #1: https://github.com/mohankandar/platform-core/actions/runs/45066981256 and …/45066990521 (943 tests, 0 skipped).
- PR #2: …/45067500486 and …/45067508119. Main after the merge: …/45067899025. The tag: …/45067908114.
- **Release workflow:** https://github.com/mohankandar/platform-core/actions/runs/45067908059. It checked the version
  against the tag, ran verify (943, 0 skipped), deployed and made the release: success.
- PR #3: …/45068642872 and …/45068651102. Main after the merge: …/45068981520.

**Published artifacts** (GitHub Packages, `com.documentplatform.platform`, version 0.1.0). This list comes from the
release run's deploy log: my `gh` token has no `read:packages`, so the API listing was refused (403).

- POMs only: `platform-core` (parent), `platform-bom`.
- Jar and POM:
  - identity: `platform-identity-core`, `-spring`, `-jdbc`, `-testkit`;
  - notifications: `platform-notifications`, `-testkit`, `-spring`;
  - licensing: `platform-licensing`, `-testkit`, `-spring`;
  - `platform-setup`;
  - fields: `platform-fields`, `-testkit`;
  - crypto: `platform-crypto`, `-testkit`, `-spring`;
  - erase log: `platform-erase-log`, `-testkit`, `-spring`;
  - integration: `platform-integration`, `-testkit`.
- **23 packages in all.** The old tags `v0.2.1` and `v0.3.0` were not touched or published.

## Independence

**No other product's name or behaviour in code.**

- **The guard:** `NoProductNamesGuardTest` (in platform-identity-core, the first module of the build) walks the whole
  repository.
  - It refuses the three product names as whole words or identifier parts (`x_y`, `X-Y`, `xY`, any case) in
    `*.java`, `*.sql`, `*.json`, `*.xml`, `*.yml`, `*.yaml`, `*.properties`, `CLAUDE.md`, `README*` and
    `docs/install/**`.
  - `docs/work/` and `docs/design/` are excluded.
  - It also keeps the old product package check. The words are assembled in the test, which also tests the matcher
    itself.
- **Result:** passes locally and on CI (0 hits in 652 checked files before the release PR).

## What was built

### B1. Independence clean-up

- Root POM description: "Shared platform libraries for document products".
- CLAUDE.md:
  - no product names;
  - the independence rule, the released-versions-only rule and the PR rule added;
  - the rule allowing product profiles removed;
  - the build note has only `mvn verify`, released versions and the build-a-tag scripts.
- The root README was rewritten as a neutral platform-core README. It was the old identity library's README and named
  two products.
- `StreamCipher` comment fixed. `EraseLogProperties` example path is now `/mnt/erase-log/<app>`. `docs/install/crypto.md`
  now says "apps that also encrypt documents".
- Identity tests:
  - "ported from …" comments are neutral;
  - the sample audience is `product-a-api`;
  - the word lists in two message tests are assembled.
  - The product codes in the identity, setup and crypto tests were already `PRODUCT_A` / `PRODUCT_B`. Behaviour is
    unchanged.
- **Removed:** `platform-integration-profiles` (from the root modules, the BOM and CLAUDE.md) with:
  - the pinned spec, `PINNED.md`, the product profile, `IngestionApiMock` and their tests;
  - `docs/install/integration-profiles.md`;
  - the `.gitattributes` line. The file now holds only `*.sh text eol=lf`.
- **Moved to generic names:**
  - schema checks → `platform-integration` `json.JsonSchema`;
  - profile loading and completion → `profile.*`;
  - spec-driven mock pieces → stay in the testkit.
- Nothing else of P024 changed: packagings, field mapping, integrity and health rules are as they were.
- `docs/work/README.md`: every report states the independence line (a new report section).

### B2. The profile format `platform-integration-profile/1`

- **Schema:** `platform-integration/src/main/resources/schema/platform-integration-profile-1.schema.json`
  (draft 2020-12, format version **1**). The format in plain words, with the versioning rule:
  `docs/design/integration-profile-format.md`.
- **`ProfileValidator`** checks, in order:
  1. JSON;
  2. the format (any other format is refused);
  3. the schema;
  4. steps or a packaging preset (exactly one);
  5. rules;
  6. no installation data (no address, nothing secret-like, no `{field:<key>}`);
  7. declared values, attributes and fields target, and settings;
  8. then `ConfigCheck`'s save rules on the profile completed with placeholder installation data.

  It has 14 codes, `INT_PROFILE_UNREADABLE` … `INT_PROFILE_INSTALLATION_DATA`, each with a JSON pointer.
- **`ProfileLoader.load(Path)` / `check(Path)`:** reads the file the app gives (UTF-8, at most 2 MiB). Nothing is
  shipped on the classpath.
- **`IntegrationProfile`:**
  - `complete(Completion)` adds entity, product, address, sign-in, field mapping (required fields enforced, others
    only if the profile allows them), allowed sensitive fields and settings. It gives an `IntegrationConfig` for the
    normal save checks.
  - `prepare(Delivery)` checks the required values and fills in or checks the document attributes.
- **Testkit:**
  - `ProfileMock`: any profile, with or without its OpenAPI file.
    - With the file, spec mode: requests are checked, now including multipart parts, and answers are built from the
      response schemas.
    - Without it, one route per step.
    - Switches derived from the rules: busy, down, refuse, already received, sign-in refused, health busy, health
      down, unreachable, wrong checksum.
    - Tokens carry the claims the profile's default settings require.
  - `ProfileContract.checkAgainstSpec` and `checkAgainstServer` (decision 4).
- **Setup:**
  - Optional per-product `"integrationProfiles": [{name, file, tag}]` in `platform-signin/1`.
  - Each profile is checked in preflight: tag present, file valid, the name matches, the tag ends with the profile's
    version.
  - The plan and the run log list each profile as `checked` (`checked=N`). Nothing is stored.
  - Refusals: `SETUP_PROFILE_INVALID` (file and pointer per problem) and `SETUP_PROFILE_TAG_MISSING`.
  - Older sign-in files load unchanged. `docs/install/setup.md` has a new section 3b.

### B3. CI

- **`.github/workflows/ci.yml`:** runs on every push and pull request: JDK 21 (Temurin), `mvn -B verify` on
  `ubuntu-latest`, then `scripts/ci-no-skipped-tests.sh`. That script reads every Surefire report and fails when a
  test is skipped or no report exists.
- **The routine** in `docs/work/README.md`:
  - local checks with `mvn verify` only, and no `mvn install`;
  - merges by `git push` → `gh pr create` → `gh pr checks --watch` → `gh pr merge --rebase --delete-branch`;
  - if `gh` is missing or not signed in, stop with the PR open.

### B4. Releases and publishing

- **`distributionManagement`:** `github` → `https://maven.pkg.github.com/mohankandar/platform-core`
  (maven-deploy-plugin 3.1.3 pinned).
- **`.github/workflows/release.yml`,** on a tag `platform-core-v*`:
  - checks that the POM version is the tag's number, has no `-SNAPSHOT`, and has its release notes;
  - runs `mvn -B verify` and the no-skip check;
  - runs `mvn -B deploy` with `GITHUB_TOKEN` (`packages: write`);
  - runs `gh release create` from `docs/releases/<tag>.md`.
- **`scripts/build-release.ps1` / `.sh`** (`-Tag`, `-Repository`):
  - refuses a tag that is not `platform-core-vX.Y.Z`, and refuses `~/.m2` as the repository;
  - builds the tag in a temporary worktree with `-Dmaven.repo.local=<path>`.
- **The release routine** is in `docs/work/README.md`.

### B5. Release 0.1.0

- PR #2 set 0.1.0 and added `docs/releases/platform-core-v0.1.0.md`: modules, design docs, breaking changes, schema
  versions, and the independence line.
- After its green merge, the tag was pushed, and the release workflow published and created the release.
- PR #3 moved main to `0.2.0-SNAPSHOT`.
- **Confirmed in v0.1.0** (files present at the tag): from P029, `MachineEntityBindings`, `PostgresMachineEntityBindings`
  with the built-in `PostgresMachineClients`, `ClientBindingsFile`, identity `V2__machine_binding.sql`, and setup's
  client-binding load (`SetupRun.planClientBindings`).

## Tests asked for → where

| Asked for | Where |
|---|---|
| Two made-up profiles, different shapes | `platform-integration-testkit/src/test/resources/profiles/`: `acme-archive-v1` (explicit two steps, OAuth 2.0, readiness busy rule, mapped fields, value, attribute, setting) and `example-dms-v2` (MULTIPART preset, API key, error code at `/error/code`, prefix rule), each with a made-up OpenAPI file |
| Validator refusals, one per code | `ProfileValidatorTest.oneRefusalPerCode`: all 14 codes, each with its JSON pointer; no value repeated (`noProblemRepeatsAValue`) |
| The mock driven by each profile for every switch | `ProfileMockTest.everySwitch` (both profiles × with/without OpenAPI), `healthBusyAndDown`, `theIntegrityCheck` |
| The contract kit passing, and failing on a planted spec change | `ProfileContractTest`: both pass. Six planted changes are each caught: a new code without a rule, a new required header, a new required body field, a moved path, a renamed multipart part, a value the profile reads removed. Plus the live check against the mock (passes; reports health down and a wrong credential) |
| Setup's profile check | platform-setup `ProfileSetupTest` (3): checked and stored nowhere (`checked=1` in the run log), tag missing, invalid file with file and pointer, name and version mismatches |
| An end-to-end send for each profile | `ProfileMockTest.everySwitch` starts with a full send for each (typed mapped fields and attribute default for acme; one multipart call with 3 parts for dms), saved first through `IntegrationConfigs` with the field list |
| Product-name guard | `NoProductNamesGuardTest` (2 tests, whole repository) |
| CI: Postgres and TLS tests run, nothing skipped | CI step "No test skipped": 943 tests, 0 skipped, on every run listed above |

## Files changed, commits

- f880ec7 `docs: P030 task` (the last direct commit to main).
- 1c118d9 (PR #1): the B1–B4 change. Main parts:
  - removed: `platform-integration-profiles/**`, `docs/install/integration-profiles.md`, testkit `openapi/Schemas`;
  - new in core: `json/JsonSchema`, `profile/{IntegrationProfile, ProfileValidator, ProfileLoader, ProfileProblem}`,
    `schema/platform-integration-profile-1.schema.json`;
  - new in the testkit: `ProfileMock`, `ProfileContract`, and the MockDestination changes (error-code pointer,
    multipart checks in spec mode, JsonSchema);
  - setup: `SigninFile`, `SetupInputs`, `SetupRun`, `Plan`, `SetupRefusal`, `pom.xml`, and test `ProfileSetupTest` with
    its profile resource;
  - tests: `ProfileFiles`, `ProfileValidatorTest`, `ProfileMockTest`, `ProfileContractTest` and the resources;
  - B1 edits: identity tests, `NoProductNamesGuardTest`, `StreamCipher`, `EraseLogProperties`, `docs/install/crypto.md`,
    `CLAUDE.md`, `README.md`, `pom.xml`, `platform-bom/pom.xml`;
  - new docs: `docs/design/integration-profile-format.md`, `docs/install/setup.md` section 3b, `docs/work/README.md`;
  - CI and publishing: `.github/workflows/{ci,release}.yml`, `scripts/{ci-no-skipped-tests.sh, build-release.sh,
    build-release.ps1}`, `.gitattributes`.
- c134500 (PR #2): version 0.1.0 in all 23 POMs, plus `docs/releases/platform-core-v0.1.0.md`. Tagged.
- f651b09 (PR #3): `0.2.0-SNAPSHOT`.
- This report: PR #4.

## Decisions I had to make

1. **The guard stays in platform-identity-core** (as the old guard was) and walks the whole repository from there.
   That module builds first, so a hit fails the build early.
2. **Error codes in an OpenAPI file:** `ProfileContract` reads them from the response extension `x-error-codes` (a
   list of strings). The format document states this, so any API owner can use it. A code counts as mapped when a
   rule names it, exactly or by prefix. A rule without a code does not count, so a new code always needs a decision.
3. **The setup "tag" rule:** the entry's `tag` must be present and must end with the profile's own `version` (for
   example `acme-archive-profile-v1.2.0` for 1.2.0), and the profile's `name` must equal the entry's name. The task's
   wording "the profile's own name and version must match" leaves open what the version is compared with; this is my
   reading.
4. **`checkAgainstServer` takes two more arguments,** a `SecretResolver` and an `Installation`, besides the profile,
   base URL and sign-in. The sign-in's secrets have to come from somewhere, and the address guard, proxy and private CA
   rules have to be explicit. See question 2.
5. **The profile's save check** runs `ConfigCheck` on the profile completed with placeholder installation data:
   `https://installation.invalid`, a placeholder sign-in of the profile's first type, and placeholder keys for its
   required fields. So a profile meets the same placeholder, header and body rules as any configuration. The
   placeholders never appear in problems.
6. **The spec-mode mock now checks multipart bodies** (required parts, the JSON part against its schema). Without
   that, the live contract check could not see a refusal for an empty multipart request.
7. **The root README was rewritten,** not just cleaned: it described the old identity library and named products.
8. **Main moved with rebase merges.** The tag is on the rebased commit of PR #2 (`c134500`), which is what main holds.

## Not done, and why

Nothing in the task is left. One slip to report: while building B2 I once ran
`mvn install -DskipTests -Dmaven.repo.local=~/.m2/repository` for two snapshot modules, before switching to reactor
builds (`-am`, no install). That put 0.1.0-SNAPSHOT jars into the shared `~/.m2`, which the new rule forbids. Every
later check used `mvn verify` or reactor test runs only. `mvn versions:set` downloaded its plugin into `~/.m2`;
that is a plugin download, not an install. Apps should take `0.1.0` from GitHub Packages; the stale local
`0.1.0-SNAPSHOT` artifacts can be deleted from `~/.m2/repository/com/documentplatform/platform/`.

## Owner settings (exact)

1. **Branch protection for `main`** (Settings → Branches → Add rule, or Rulesets):
   - require a pull request before merging;
   - require status checks to pass, required check **`verify`** (workflow "CI", job `verify`), and require the branch
     to be up to date;
   - do not allow bypassing; block force pushes and deletions.

   With `gh`:
   `gh api -X PUT repos/mohankandar/platform-core/branches/main/protection --input protection.json`, where
   `protection.json` is
   `{"required_status_checks":{"strict":true,"checks":[{"context":"verify"}]},"enforce_admins":true,"required_pull_request_reviews":{"required_approving_review_count":0},"restrictions":null,"allow_force_pushes":false,"allow_deletions":false}`.
2. **Package access:** for each published package (or the repository's packages), open Package settings → "Manage
   Actions access" and add **`mohankandar/secure-document-platform`** and **`mohankandar/secure-document-vault`** with
   the role **Read**. Those repositories' workflows can then read the packages with their own `GITHUB_TOKEN`.
3. **Local product builds:** make a personal access token (classic) with **`read:packages`** only. Put it in your own
   `~/.m2/settings.xml`, never in a repository:

   ```xml
   <settings>
     <servers>
       <server>
         <id>github-platform-core</id>
         <username>mohankandar</username>
         <password>${env.GITHUB_PACKAGES_TOKEN}</password>
       </server>
     </servers>
   </settings>
   ```

   with the token in the environment variable `GITHUB_PACKAGES_TOKEN`. The product POMs then declare the repository
   `github-platform-core` → `https://maven.pkg.github.com/mohankandar/platform-core`, as in the README.

## Questions (numbered, each with a recommendation)

1. **Checking the packages yourself:** my `gh` token has no `read:packages`, so the package list in this report comes
   from the deploy log. *Recommendation:* look once at github.com/mohankandar?tab=packages to confirm the 23 packages,
   and set the Actions access there (owner setting 2).
2. **`ProfileContract.checkAgainstServer` signature** (decision 4): `(profile, baseUrl, signIn, secrets,
   installation)`. *Recommendation:* keep it. Add a three-argument overload later only if API owners ask, with an
   in-memory secret holder and on-premises rules as defaults.
3. **Old branches on origin** (`feature/admission-0.3.0`, `feature/case-fields`, `feature/mapping-0.4.0`,
   `feature/platform-core-setup`) still exist, and the local `origin/HEAD` still points to `feature/mapping-0.4.0`.
   *Recommendation:* the owner deletes the ones that are done; locally, `git remote set-head origin -a`.
