# P053 report — monthly install proof against the newest release

Date: 2026-11-24
Branch: `build/monthly-install-proof` (then `docs/p053-report`)
Outcome: DONE

## Result

| Step | PR | CI run | Commit |
|---|---|---|---|
| Schedule, newest release, failure issue, docs | #81 | 45419013275 (green; merged by the stop-on-failed-check line) | `c5c4d1f` |

**Schedule:** `17 6 3 * *` (the 3rd of every month, 06:17 UTC) in `.github/workflows/install-proof.yml`.

| Run on main, by hand | Version | Result |
|---|---|---|
| [45420338311](https://github.com/mohankandar/platform-core/actions/runs/45420338311) | none given → found 1.0.0, checked out `platform-core-v1.0.0` | **passed** on Linux and Windows; the issue job did not run |
| [45420395609](https://github.com/mohankandar/platform-core/actions/runs/45420395609) | `0.0.0` | failed at "Build the sample app from the published artifacts" on both; **opened issue #82** |
| [45421214714](https://github.com/mohankandar/platform-core/actions/runs/45421214714) | `0.0.0` again | failed the same way; **commented on #82**, no second issue |

**Issue:** #82, label `install-proof`, with version, jobs, step and run link. Closed.

The scheduled trigger itself has not fired yet: its first run is December 3. The run without a version takes the same path.

Tests: no Java change. Locally `actionlint` 1.7.7 on the workflows (0 findings); the full `mvn -B verify` ran in the PR's CI.

## Independence

No other product's name or behaviour in code.

## What was built

- `install-proof.yml`: manual (version now optional) and monthly. Job `version` takes the given version, or the newest published
  release with a tag `platform-core-vX.Y.Z` (no draft, pre-release or `-rc`). Job `issue` runs on failure, with `issues: write` only.
- `install-proof-run.yml`: the proof's jobs, steps unchanged; read rights only.
- `release.yml`: one line, calls `install-proof-run.yml`.
- Docs: `docs/work/README.md` "Monthly install proof"; `docs/design/platform-baseline.md` "Install proof on CI".

## Tests asked for → where

| Asked | Where |
|---|---|
| By hand without a version: newest release, both systems | run 45420338311 |
| Wrong version: fails early, opens or comments on the issue | runs 45420395609, 45421214714; #82 |

## Files changed, commits

`9876cd9` (P053.md, README rule); `c5c4d1f` (three workflows, `docs/work/README.md`, `docs/design/platform-baseline.md`).

## Decisions I had to make

1. **The proof's jobs moved to a file of their own.** GitHub requires a calling workflow to grant every right the called file's
   jobs ask for, even for jobs it skips. With the issue job in the called file, `release.yml` would need `issues: write`.
2. **The newest release is proved from its own tag's** sample app and guides, as a candidate was. Main's may already use
   unreleased code. A version given by hand still uses the chosen branch, as before.
3. **By-hand failures report too** (the task's failure proof needs it). Runs stopped by hand, and candidates, do not.
4. **No design doc described the CI rules;** I added the section to `platform-baseline.md`.
5. **I ran the wrong version twice,** to prove the comment path as well.

## Not done, and why

- **The release workflow's changed line has not run:** it runs only for an `-rc` tag, and this task has no release. The same file
  with the same rights was called by the runs above, and `actionlint` accepts it. The next candidate proves it.
- **`docs/work/P048.md`** had an uncommitted change (SDV-25) at the start, which the task did not expect. I left it uncommitted.

## Questions

1. **Vault stays at the pinned 1.21.4,** so the monthly run notices a problem with its download but not a new Vault version. The
   task names Vault changes. Recommendation: move the pin by a small task when Vault releases a new line; keep it pinned.
2. **GitHub disables schedules in a public repository after 60 days without activity.** Recommendation: none needed while work goes
   on; the weekly Dependabot pull requests are not certain to count.
3. **Should `docs/work/P048.md` be committed with the next task?** Recommendation: yes, as that task's first commit.
