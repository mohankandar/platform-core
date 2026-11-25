# P058 report — release guard: wait for GitHub's run list

Date: 2026-11-25
Branch: `ci/release-guard-wait` (then `docs/p058-report`)
Outcome: DONE

## Result

PR #100, CI 45701679478 (green the first time; its step "Release guard script" passed on the runner), merged as `e22eb77`. CI only, no release, no tag.

## Independence

No other product's name or behaviour in code.

## What was built

- `scripts/release-ci-passed.sh <commit> <branch>`: the guard's question, asked up to 13 times, every 15 seconds (3 minutes). Passes only when GitHub lists a successful
  CI run; a missing or failed run, an API error or any other answer is not a pass. The same final message as before.
- `release.yml` calls it. The check that the commit is on main or its patch branch is unchanged.
- `docs/work/README.md`, "Release routine": one line.

**Other steps that read GitHub's run list once (work item 2): none.** `release.yml` has no other. `install-proof.yml` reads the list of *releases* and the jobs of *its own* run for the
failure issue; `install-proof-run.yml` reads neither.

## How it was proved, without a new tag

| Check | Result |
|---|---|
| `scripts/test-release-ci-passed.sh` with a `gh` and a `sleep` of its own: green at once, at the third and at the last answer; never green; no answer; an error text; recovery | 7 of 7, locally and in CI |
| The same test on a copy that asks once, as before | fails at "the list lags" |
| The real script against GitHub, 1.2.0's commit | exit 0 at once |
| The real script against GitHub, `604f170` (its CI ran on `release/1.1.x`, not on main) | exit 1 after 3 min 7 s, with the message |

Not proved: the step inside a real release run. The next release is the first to use it. Tags on `release/1.1.x` use that branch's own older workflow.

## Decisions I had to make

1. **A script instead of lines in the workflow,** so that it can be tested; CI runs the test on every PR (under a second).
2. **A failed run also waits the 3 minutes** before the guard fails: the query asks for successful runs only, as before, and cannot tell failed from not listed.

## Not done, and why

Nothing.

## Questions

1. Copy this to `release/1.1.x` too? Recommendation: only with the next 1.1.x patch, if there is one.
