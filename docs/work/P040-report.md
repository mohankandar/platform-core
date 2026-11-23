# P040 report — design docs and install guides catch up; root notes; a wider name guard

Date: 2026-11-23
Branch: `docs/catch-up` (then `docs/p040-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs (green) | Merged |
|---|---|---|---|
| Docs, guides, guard, leftover tests and texts | #40 | 45242979585, 45242988935 | 24 commits, `ad9ced5` … `5059d03` |
| This report and the row marks | this PR | | |

The merge used the README's stop-on-failed-check line. No check failed or was re-run.

`mvn clean verify` locally was green, except one EICAR test in `ScannerTest` (platform-integration-testkit): the
laptop's antivirus cuts the test file's download. Its code is unchanged in this task, and it passes on CI (the runs
above). The new tests are in platform-crypto, platform-crypto-spring, platform-erase-log, platform-fields,
platform-licensing, platform-licensing-testkit, platform-model-governance-testkit, platform-notifications-spring,
platform-notifications-testkit, platform-setup, platform-error-catalogue and platform-identity-core (the guard).

## Independence

**No other product's name or behaviour in code.**
- The guard now reads every text file outside `docs/work/` and `docs/design/`: root Markdown files, `docs/install`,
  `docs/errors`, `docs/releases`, scripts and workflow files. It finds nothing.
- The two root notes that named apps are gone: their generic content is now `docs/install/identity.md` §10.
- `docs/design/` no longer names a product: each mention is now a generic example ("a document vault app", "a
  redaction app", "an earlier app"). A grep for the product words gives 0 in every design doc.

## What was built

- **DOC GAPs** written into the design docs' bodies, each with its source report:
  - erase log (P021);
  - case fields (P017–P019, plus the sealing contexts and sealed form pinned by P038);
  - crypto (P019, P021, P026, P032);
  - monitoring (P032);
  - model governance (P033, P034);
  - integration (P022–P032);
  - licensing and its formats (P009–P011, P031);
  - notifications (P005, P015);
  - setup (P014, P028).
- **DIFFERENT rows where the code follows a later decision:** each doc body now says what the code does, and why.
  None of these rows found the code on the wrong side.
- **Install guides checked against the code:**
  - settings, defaults and limits (for example `platform.monitoring.tick`, the review intervals, `clock-tolerance`
    0 to 1 day, the erase log's `recheck` 5 s to 1 h);
  - codes, each pointing to the catalogue;
  - options (the licence command, the issuing command's Vault options).
- **The rows that asked for a test or a text got one:**
  - erase-log-31, crypto-30, -62, -65, -82;
  - licensing-83;
  - install-licence-signing-3 and its texts;
  - notifications-85, -167;
  - setup-1;
  - install-model-governance-2: a test runs the guide's own grant statements and checks they are exactly enough;
  - crypto-112 and crypto-113: texts.
- **One code fix:** the diagnostic bundle now reads `mg_schema_version` (install-monitoring-5).
- **Notifications' SQL files:** the guide now advises their own Flyway run with their own history table. Their V1
  cannot share one Flyway run with an app's own V1: Flyway refuses two files with the same version. The code needs
  nothing: its startup check reads `notif_schema_version`. Design B4 says why.

## Tests asked for → where (rows)

All 82 rows of S2-7 are marked in `gaps.md` with their commits. The main ones:

| Rows | Result | Main commit |
|---|---|---|
| xc-independence-1 | FIXED: content moved, both root notes removed | `21f5663` |
| xc-independence-2 | FIXED: `NoProductNamesGuardTest.theGuardCoversEveryTextFileOutsideTheHistoryFolders` | `ad9ced5` |
| xc-names-1 | FIXED: 0 product words in `docs/design/` | `bbf2289`, `d1c3774`, `a9399bd`, `bc19f9b`, `5a6e619`, `2cacae1` |
| install-monitoring-1, -3 (B) | FIXED (design and guide) | `ca0d211` |
| install-notifications-1 (B) | FIXED (design B4 and guide) | `5a6e619`, `87bdaa9` |
| erase-log, case-fields, crypto rows and guides | FIXED | `bbf2289`, `d1c3774`, `a9399bd`, `522a53e`, tests `14f89a3`, `33d937c`, `f477d16` |
| monitoring, model-governance, integration rows and guides | FIXED | `ca0d211`, `3a6f0a6`, `bc19f9b`, `522a53e` |
| licensing, licence-signing, notifications, setup, identity rows and guides | FIXED | `dd2dc2c`, `5a6e619`, `2cacae1`, `21f5663`, tests `241cfa7`, `934c35a`, `e556f84`, `735f200`, `f7595eb`, `a47fed5` |

**Each new test fails without its rule.** For each one the rule was broken on purpose:
- an extra log line;
- a changed `.hcl` capability;
- `destroyMethod = ""`;
- bounds made exclusive;
- `setDaemon(false)`;
- the old texts put back;
- the owner's rights instead of the guide's.

## Files changed, commits

24 commits on main, `ad9ced5` … `5059d03`. Four agents wrote the docs and the leftover tests in separate worktrees,
split by area so no two touched the same file, and I reviewed and cherry-picked them.

The task's own changes, for the report:
- **Removed:** `INTEGRATION-NOTES.md`, `SCHEMA_CONTRACT.md`.
- **New tests:** `PolicyTextInDocsTest`, `VaultTransitSignerStubTest`, `SetupTextsTest`.
- **Catalogue:** regenerated for the changed texts (`ISSUE_VAULT_SEALED`, `ISSUE_VAULT_ERROR`,
  `SETUP_CONFIG_OTHER_TENANT`, `CRYPTO_KEY_MISSING`, `MG_PRIVILEGES_MISSING`). No code was added or renamed.

## Decisions I had to make

1. **Rows of S2-7 that asked for a test or a text were done in this task.** The task says "don't change code" for
   DIFFERENT rows, and none of these were DIFFERENT rows where the code was wrong. Each change was small: tests, code
   texts, one javadoc, and one missing table name in the bundle. Leaving them out would have left the rows open.
2. **Notifications' schema run.** Design B4 originally asked for a separate run, and the code later stopped naming a
   history table. A docs agent found that running the files inside the app's own Flyway run fails on the duplicate
   V1. I wrote the advice to match what works (a separate run, any history table name). This changes nothing in the
   code.
3. **"Tenant" in texts (setup-1):** the check covers setup's texts only. Identity and licensing still say "customer
   (tenant)" in a few causes and in javadoc. They are not wrong, and renaming wording there is not in this row.

## Not done, and why

Nothing in scope.

## Questions (numbered, each with a recommendation)

1. **A watched folder's Test (integration-module-32).** In CHECK_ONLY mode, the watched folder's Test answers
   `INT_TEST_CHECKED` without looking at the folder. Only `WatchedFolder.health` checks it. The design now says so.
   Recommendation: make the Test of a watched folder run `WatchedFolder.health`, as folder export's Test runs its own
   check. That is a behaviour change for a later polish task (S2-9), and it doesn't change what P041 builds.
