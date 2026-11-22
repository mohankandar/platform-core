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
4. Write `PNNN-report.md` using the layout below. **Questions for the owner go in the report** (short, numbered, each with a recommendation).
5. In the chat window, state the result in one line, exactly one of: "PNNN done", "PNNN partially done: <what is left>", "PNNN not done: <the problem>".

Do not edit task files (except committing them as they are). Do not start a later task while an earlier one has no report.

## Report layout

```
# PNNN report — <task title>

Date:
Branch:
Outcome: DONE / PARTLY DONE / STOPPED

## Result (tests per module, merge and push results)
## What was built
## Tests asked for → where
## Files changed, commits
## Decisions I had to make
## Not done, and why
## Questions (numbered, each with a recommendation)
```
