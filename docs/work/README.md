# Work folder: tasks and reports

How the design chat (the Shared capabilities thread, Claude in the Lexavault project) and the coding session (Claude Code on this machine) work together on platform-core. The owner (Kr) starts each step and approves each result. Same routine as LexaVault and SDV.

## Files

| File | Written by | Holds |
|---|---|---|
| `PNNN.md` | The design chat | One task: what to do, what to check, what to report |
| `PNNN-report.md` | The coding session | What was done, what was run, what passed or failed, decisions, questions |

`PNNN` is P plus a three-digit number. A task and its report share the number.

## Routine for the coding session

When the owner says "do the next task":

1. Find the highest-numbered `PNNN.md` that has no matching `PNNN-report.md`. If there is none, say so and stop.
2. Read `CLAUDE.md` and follow its rules. They always apply.
3. Do the task exactly as written. If the task is unclear, or would break a rule in `CLAUDE.md`, stop and write the question in the report instead of guessing.
4. Check locally with **targeted tests only**; the full build runs in CI on the PR, so do not repeat it locally:
   - while working: only the modules you changed and what they need, `mvn -B -pl <module>[,<module>] -am verify`,
     or one test class, `mvn -B -pl <module> -am verify -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`;
   - a change to the root POM, the BOM, a shared build plugin or a public API used by other modules: run
     `mvn -B verify` once locally before pushing;
   - CI's full `mvn -B verify` on the PR (no test skipped) is the gate; the install proof runs only for a release
     and monthly. A full local build is never needed just to "double-check" CI.
   - Never `mvn install` into the shared `~/.m2` (apps use released versions from GitHub Packages;
     `scripts/build-release.*` builds a tag into a separate repository when needed).
5. Merge through a pull request, never locally into main:
   1. push the branch (`git push -u origin <branch>`);
   2. open a PR (`gh pr create`) and note its number `<n>`;
   3. wait for the checks and merge **only if that command's exit status is 0**, as one script line that stops on a
      failed check:
      - bash: `gh pr checks <n> --watch --fail-fast && gh pr merge <n> --rebase --delete-branch`
      - PowerShell 5.1: `gh pr checks <n> --watch --fail-fast; if ($LASTEXITCODE -eq 0) { gh pr merge <n> --rebase --delete-branch }`
   4. If a required check failed (non-zero exit status), **stop and report it: never merge, never tag.** Do not
      re-run a failed check to get it green without finding why it failed; a re-run is noted in the report with the
      failed run's ID.
   If `gh` is missing or not signed in, stop with the PR open (or the branch pushed) and say so in the report.
6. Write `PNNN-report.md` using the layout below, and bring it to main the same way (a PR). **Questions for the
   owner go in the report** (short, numbered, each with a recommendation). Every report states **"No other product's
   name or behaviour in code"**, or lists the exceptions with their planned fix.
7. In the chat window, state the result in one line, exactly one of: "PNNN done", "PNNN partially done: <what is left>", "PNNN not done: <the problem>".

Do not edit task files (except committing them as they are). Do not start a later task while an earlier one has no report.

## Report layout

```
# PNNN report — <task title>

Date:
Branch:
Outcome: DONE / PARTLY DONE / STOPPED

## Result (tests per module, PRs, CI runs, merge results)
## Independence ("No other product's name or behaviour in code", or the exceptions with their planned fix)
## What was built
## Tests asked for → where
## Files changed, commits
## Decisions I had to make
## Not done, and why
## Questions (numbered, each with a recommendation)
```

## Release routine

Releases are tags `platform-core-vX.Y.Z` on main. Tags are never moved or reused. The old tags `v0.2.1` and
`v0.3.0` (the identity library before platform-core) stay as they are and are not published.

1. A PR sets the version: `mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`. It also adds
   `docs/releases/platform-core-vX.Y.Z.md`, with:
   - the modules;
   - the design docs implemented;
   - breaking changes;
   - the database schema versions;
   - the line "No other product's name or behaviour in code".
2. Merge the PR with the script line of step 5 above (it merges only when every check passed). Then wait for main's
   own CI run on the merge commit (`gh run watch <run-id> --exit-status`); only when it exits 0, tag the merge commit
   and push the tag:
   `git tag -a platform-core-vX.Y.Z -m "platform-core X.Y.Z" <commit> && git push origin platform-core-vX.Y.Z`.
   The release workflow (`.github/workflows/release.yml`) then:
   - checks that the POM version is the tag's, without `-SNAPSHOT`;
   - runs `mvn -B verify` (no test may be skipped);
   - deploys to GitHub Packages;
   - creates the GitHub release from `docs/releases/platform-core-vX.Y.Z.md`.
3. A second PR moves main to the next `-SNAPSHOT` (`mvn versions:set -DnewVersion=X.Y+1.0-SNAPSHOT`).
