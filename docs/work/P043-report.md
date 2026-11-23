# P043 report — security fixes 1: filesystem safety; release pipeline and dependency upkeep

Date: 2026-11-23
Branch: `sec/filesystem-release-pipeline` (then `release/0.6.1`, `chore/0.7.0-snapshot-after-0.6.1`, `docs/p043-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Merged as |
|---|---|---|---|
| Fixes FIX-A and FIX-I part 1 | #47 | 45281324169, 45281339508 | `7d03a11` (task files), `e4317b1`, `617b4e8`, `0a4653d`, `3f98def` (main CI 45282442396) |
| Version 0.6.1 and release notes | #53 | 45283408799, 45283420063 | `4029f9e` (main CI 45284371650) |
| Tag `platform-core-v0.6.1` on `4029f9e` | — | release workflow 45285405697: `verify` success (the commit is on main and its CI passed, version, tests, no skip), `publish` success | GitHub release `platform-core-v0.6.1`, packages on GitHub Packages |
| Main back to `0.7.0-SNAPSHOT` | #54 | 45287170203, 45287182145 | `367f13d` |
| This report | this PR | — | — |

**Local `mvn verify` on the fix branch: 1428 tests, 0 failures, 11 skipped.** CI forbids skips and ran every test; CI runs have no skip.

The 11 local skips are on this laptop only:
- 10 are TLS tests that skip where TLS to 127.0.0.1 is intercepted;
- 1 is `WatchedFolderTest`'s symbolic-link test, because this Windows account may not make symbolic links. The new link tests use junctions on Windows, so they don't skip.

Tests per module:

| Module | Tests | Module | Tests |
|---|---|---|---|
| platform-crypto | 29 | platform-integration-testkit | 215 (+11 new) |
| platform-crypto-spring | 6 | platform-licensing | 52 |
| platform-crypto-testkit | 16 | platform-licensing-issue | 104 |
| platform-erase-log | 28 (+3 new) | platform-licensing-spring | 37 |
| platform-erase-log-spring | 8 | platform-licensing-testkit | 49 |
| platform-erase-log-testkit | 9 | platform-model-governance-spring | 7 |
| platform-error-catalogue | 15 | platform-model-governance-testkit | 74 |
| platform-errors | 9 | platform-monitoring-spring | 11 |
| platform-fields | 99 | platform-monitoring-testkit | 17 |
| platform-fields-testkit | 20 | platform-notifications | 85 |
| platform-identity-core | 121 | platform-notifications-spring | 44 |
| platform-identity-jdbc | 68 | platform-notifications-testkit | 135 |
| platform-identity-spring | 30 | platform-outbound | 9 |
| platform-identity-testkit | 14 | platform-outbound-spring | 6 |
| platform-integration | 55 | platform-setup | 47 |
| platform-integration-spring | 9 | | |

## Independence

No other product's name or behaviour in code.

## What was built

### Findings

| Finding | Result | Commit on main | Test (fails without the fix) |
|---|---|---|---|
| SEC-1 (High) links followed when deleting | FIXED | `e4317b1` | `LinkSafetyTest` (9 cases, with 2 controls), `FileEraseLogLinkTest` (3 cases) |
| SEC-9 scanned bytes are not the handed-over bytes | FIXED | `e4317b1` | `LinkSafetyTest.aFileSwappedBetweenTheHashAndTheScanNeverReachesTheApp` |
| SEC-52 device names | FIXED | `e4317b1` | `LinkSafetyTest.windowsDeviceNamesAreRefusedInEveryForm` |
| SEC-15 release of an unreviewed commit | FIXED (workflow); tag ruleset is an owner setting, below | `617b4e8` | Release 0.6.1 passed the new check. The check's query was run against main's commit (count 1) and against an unmerged branch commit (not on main, count 0). |
| SEC-40 write token during tests, actions by tag | FIXED | `617b4e8` | `release.yml` / `ci.yml` (read-only `verify`, separate `publish`, SHA pins, `persist-credentials: false`) |
| SEC-41 no dependency upkeep | FIXED (`dependabot.yml`; it already opened #48–#52); alerts and security updates are an owner setting, below | `617b4e8` | — |
| SEC-59 outside-contributor approval | Owner setting, below (the workflow rights part is done in `617b4e8`) | — | — |
| SEC-14 in part: httpclient5, httpcore5, Nimbus | FIXED; the Spring line is not changed, as the task says | `617b4e8` | the full suite; `ProxyTest` found the behaviour change below |
| P042 question 3: `ScannerTest` on the laptop | DONE, then changed at the owner's request (decision 3) | `617b4e8`, `3f98def` | `ScannerTest`, `WatchedFolderTest` |
| Found on the way: a refused proxy tunnel read as the destination's answer | FIXED | `0a4653d` | `ProxyTest.aRefusingProxyHasItsOwnCode` (failed after the update, passes with the fix) |

To prove each test reproduces its attack, the four fixed classes (`LocalFiles`, `FolderExport`, `WatchedFolder`, `FolderTarget`) and `FileEraseLogStore` were set back to their main versions and the new tests run against them:
- `LinkSafetyTest`: 9 of 11 failed. The 2 that passed are controls: a plain folder is still deleted, an unchanged file is still handed over.
- `FileEraseLogLinkTest`: 3 of 3 failed. The hard-link case showed the file behind the link overwritten with checkpoint text.

With the planted `.partial` junction, the unfixed export even reported `SENT`, after deleting what the junction pointed to.

### FIX-A

- **`LocalFiles`** never deletes, renames or moves a symbolic link, a junction or another reparse point (`isSymbolicLink() || isOther()`, read with NOFOLLOW):
  - it refuses a link at the path or anywhere inside it, with the new `LinkRefusedException`;
  - a delete walks the whole tree first, so a link anywhere refuses before anything goes, and checks again while deleting;
  - `ExportFiles.link(Path)` is a new default method, so an app's own port gets it.
- **Folder export:** a link where the package folder or its `.partial` folder belongs is `INT_FOLDER_LINK_REFUSED` (`NOT_CONFIGURED`), and so is a refusal during clean-up or the Test action's clean-up.
- **Watched folder:**
  - `_processing`, `_archive`, `_problem` and `_quarantine` are checked at the start of every pass and in the health check;
  - the destination is checked again right before each move into it;
  - a claim or its marker that became a link is refused;
  - all of these are `INT_WATCH_LINK_REFUSED`.
- **Erase log location:**
  - before every locked operation and every scan, none of the store's own files may be a link (`ERASE_LOG_LINK_REFUSED`);
  - temporary files (`.pruning`, `.new`) are deleted and created new (`CREATE_NEW`), so a hard link left in their place, which cannot be told from a file, is replaced and never written through.
- **Diagnostic bundle:** it has no temporary files; it is built in memory, so there was nothing to change. The other `Files.delete*` / `move` / `createTempFile` uses in main code are fresh random temp files or `deleteIfExists`, which removes a link itself; none follows a link.
- **SEC-9:**
  - the bytes the scanner reads pass through a counting, hashing `ScanTap`;
  - a CLEAN verdict counts only when exactly the size and SHA-256 taken at claim time were read;
  - otherwise the drop goes to `_problem` with `INT_WATCH_FILE_CHANGED`;
  - the app's self-checking stream accepts only the same bytes, so the hash, the scan and the hand-over must all see one content.
- **SEC-52:** one check, `FolderTarget.device` (package-private), for `validName` and `safeName`. It strips the spaces Windows ignores before an extension and adds `COM0`, `LPT0`, `CONIN$`, `CONOUT$`, `CLOCK$` and COM / LPT with superscript digits.
- **Codes and docs:**
  - 3 new codes with message, cause and fix; catalogue and `code-names.txt` regenerated;
  - `docs/install/integration.md` "Codes you can see", `docs/install/erase-log.md` health, `docs/design/integration-module.md` folder-export table.

### FIX-I part 1

- **`release.yml`:**
  - `permissions: {}` at the top;
  - job `verify` (`contents: read`, `actions: read`) checks, with a message that says why, that `GITHUB_SHA` is an ancestor of `origin/main` and that `ci.yml` has a successful push run on `main` for that SHA; then the version, `mvn -B verify` and no skip;
  - job `publish` (`contents: write`, `packages: write`, `needs: verify`, no Maven cache) deploys and creates the release.
- **`ci.yml`:** `contents: read`. In both workflows, checkout has `persist-credentials: false` and the actions are pinned by commit SHA: checkout v4.4.0 `11d5960a…`, setup-java v4.9.1 `cf277c60…`.
- **`.github/dependabot.yml`:** Maven and GitHub Actions, weekly, minor and patch grouped. Minor and major updates of the Spring line (Boot, Framework, Security) are ignored, because that move waits for its own decision.
- **Versions:**
  - httpclient5 5.3.1 → 5.6.5; httpcore5 / httpcore5-h2 5.2.4 → 5.4.4, declared in `platform-outbound`;
  - nimbus-jose-jwt 9.37.3 → 9.37.4, declared in `platform-identity-spring`;
  - checked with `dependency:list`; `micrometer-core` is still optional.
- **Behaviour change found by the tests:** from HttpClient 5.4, a refused CONNECT returns the proxy's answer instead of throwing `TunnelRefusedException` (confirmed in the 5.6.5 bytecode). `Outbound` now treats a proxied https call without a TLS session as the proxy's refusal (`OUT_PROXY_REFUSED`, or `OUT_PROXY_SIGN_IN_REFUSED` for 407). Before, a proxy's 403 was taken for the destination's answer (`SIGN_IN_FAILED`).
- **Release 0.6.1:** release notes `docs/releases/platform-core-v0.6.1.md`, which name SEC-1 as High.

### Owner settings (to apply; not changed by this task)

1. **Tag rulesets** for `platform-core-v*`. A bypass applies to a whole ruleset, so the rules need two rulesets: one
   that only the owner (repository admin) may create these tags, and one that nobody, the owner included, may move or
   delete one.
   ```
   gh api -X POST repos/mohankandar/platform-core/rulesets --input - <<'EOF'
   {
     "name": "release tags: created by the owner only",
     "target": "tag",
     "enforcement": "active",
     "conditions": { "ref_name": { "include": ["refs/tags/platform-core-v*"], "exclude": [] } },
     "rules": [ { "type": "creation" } ],
     "bypass_actors": [ { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" } ]
   }
   EOF
   gh api -X POST repos/mohankandar/platform-core/rulesets --input - <<'EOF'
   {
     "name": "release tags: never moved or deleted",
     "target": "tag",
     "enforcement": "active",
     "conditions": { "ref_name": { "include": ["refs/tags/platform-core-v*"], "exclude": [] } },
     "rules": [ { "type": "update" }, { "type": "deletion" }, { "type": "non_fast_forward" } ],
     "bypass_actors": []
   }
   EOF
   ```
   - `actor_id` 5 is the built-in Admin role.
   - Check the result with `gh api repos/mohankandar/platform-core/rulesets`: two rulesets, both `active`.
2. **Dependabot alerts and security updates:**
   - In the browser: Settings → Code security → "Dependabot alerts" Enable, and "Dependabot security updates" Enable.
   - Or with `gh api -X PUT repos/mohankandar/platform-core/vulnerability-alerts` and `gh api -X PUT repos/mohankandar/platform-core/automated-security-fixes`.
   - Check: `gh api repos/mohankandar/platform-core/vulnerability-alerts` answers 204.
3. **SEC-59, outside contributors:**
   - Settings → Actions → General → "Fork pull request workflows from outside collaborators": choose "Require approval for all outside collaborators".
   - Or `gh api -X PUT repos/mohankandar/platform-core/actions/permissions/fork-pr-contributor-approval -f approval_policy=all_external_contributors`.

## Tests asked for → where

| Asked for | Where |
|---|---|
| SEC-1: a junction or symlink test where the OS allows it; Linux symlinks on CI, the Windows branch in the same test | `platform-integration-testkit` `LinkSafetyTest` (export, watched folder, `LocalFiles`); `platform-erase-log` `FileEraseLogLinkTest` (log location). `mklink /J` on Windows, `Files.createSymbolicLink` elsewhere; ran on Windows locally and on Linux in CI, with no skip. |
| SEC-9 | `LinkSafetyTest.aFileSwappedBetweenTheHashAndTheScanNeverReachesTheApp`; control `anUnchangedFileIsScannedAndHandedOver` |
| SEC-52 | `LinkSafetyTest.windowsDeviceNamesAreRefusedInEveryForm` |
| SEC-15 | the release of 0.6.1 itself, plus the check's query run on main's commit and on an unmerged one |
| `ScannerTest` on the laptop | `Eicar.staysOnDisk` / `testFile` (decision 3); CI sets `RUN_EICAR_TESTS=true` and has no skip |

## Files changed, commits

- **Main code:**
  - `platform-integration`: `folder/LocalFiles`, `folder/ExportFiles`, new `folder/LinkRefusedException`, `folder/FolderExport`, `watch/WatchedFolder`, `config/FolderTarget`, `IntegrationCode` (+ `.codes.properties`);
  - `platform-erase-log`: `file/FileEraseLogStore`, `EraseLogError` (+ `.codes.properties`);
  - `platform-outbound`: `Outbound`.
- **Testkit:** `Eicar`, `MockScanner`.
- **Tests:** new `LinkSafetyTest`, `FileEraseLogLinkTest`; `ScannerTest`, `WatchedFolderTest`.
- **Build:** `pom.xml`, `platform-outbound/pom.xml`, `platform-identity-spring/pom.xml`; `.github/workflows/ci.yml`, `.github/workflows/release.yml`, new `.github/dependabot.yml`; all POMs (version 0.6.1, then 0.7.0-SNAPSHOT).
- **Docs:** `docs/errors/*`, `docs/install/integration.md`, `docs/install/erase-log.md`, `docs/design/integration-module.md`, `docs/releases/platform-core-v0.6.1.md`, `docs/work/security-findings.md` (findings marked), this report.
- **Commits on main:** `7d03a11`, `e4317b1`, `617b4e8`, `0a4653d`, `3f98def`, `4029f9e` (release), `367f13d` (snapshot).

## Decisions I had to make

1. **The link check sits in the built file port (`LocalFiles`), with `ExportFiles.link` as a default method.** The task asked for a check "before every delete, move and directory walk". Putting it in the port covers every caller at once, including future ones. The callers add checks only where the port can't know the intent: our own subfolders, and the destination of a move.
2. **Refuse, not "delete the link itself".** A link inside the platform's own folders means someone tampered with them, so the whole operation is refused with a code that names it, and a person looks. This is as the task says.
3. **EICAR tests.** First, as P042 question 3 proposed, `Eicar.staysOnDisk` gained a loopback-transfer check. During the task the owner asked instead for the EICAR fixtures (the `eicar-probe` file and the `/items/bad-1` mock endpoint) to be opt-in behind `RUN_EICAR_TESTS=true`, with a harmless placeholder by default:
   - `Eicar.testFile()` is now that placeholder, which `MockScanner` reports as infected exactly like EICAR, so the quarantine paths are still tested locally with no skip;
   - with `RUN_EICAR_TESTS=true` it is the real EICAR file, and `staysOnDisk` (disk plus loopback) is checked only then;
   - **CI and the release workflow set `RUN_EICAR_TESTS=true`**, so they keep testing with the real file and still forbid skips. That last point is my choice, to keep the coverage the P027 tests had.
4. **The proxy behaviour change was fixed in this task, not left for later.** The httpclient5 update was this task's, and leaving it would have made a proxy's refusal look like the destination's answer.
5. **The findings are marked in this report's PR, not the fix PR.** `gh pr merge --rebase` gives new commit hashes on main, so only the hashes on main are final.
6. **Dependabot's first PRs (#48–#52) were left open, not merged.**
   - #48 setup-java 4 → 6; #49 checkout 4 → 7; #51 JUnit 5 → 6; #52 Nimbus 9 → 10 (needs Spring Security 7, so it belongs with the Spring decision);
   - #50 is a group of 17 minor and patch updates.

   They are outside this task (question 1).

## Not done, and why

- **The Spring line (SEC-14 rest):** not in this task, by the task's own words.
- **The tag ruleset, Dependabot alerts and security updates, and the outside-contributor approval:** owner settings, with the exact steps above.

## Questions

1. **Dependabot PRs #48–#52:** merge them now, or in an upkeep task of their own? Recommendation:
   - an upkeep task after P045, that merges the patch and minor group (#50) and the action majors (#48, #49) once CI is green, and JUnit 6 (#51) on its own;
   - close #52 (Nimbus 10) until the Spring decision.

   This doesn't change what P044 or P045 builds.
2. **Apply the three owner settings** above. Recommendation: yes, before the next release (0.7.0 at the end of P045), so that release already runs under the tag ruleset.
