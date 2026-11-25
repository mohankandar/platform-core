# P055 report — platform-core 1.1.1: fixes from the integration (LV-22, LV-24.1, LV-24.7, LV-24.8, LV-24.9)

Date: 2026-11-25
Branch: `release/1.1.x` from tag `platform-core-v1.1.0`; `fix/1.1.1`, `release/1.1.1-rc.2`, `release/1.1.1-final`, `forward/1.1.1`
Outcome: DONE

## Result

| Step | PR | CI runs | Commit | Tag | Release run |
|---|---|---|---|---|---|
| Task files | #88 | 45648380484 | `a7d021a` (main) | — | — |
| Fixes, docs, rc.1 | #89 | 45650944691; branch 45651892250 | `09e42e9` | `platform-core-v1.1.1-rc.1` | **none started**, not published (see below) |
| rc.2, the same code | #91 | 45662962344; branch 45664363206 | `604f170` | `platform-core-v1.1.1-rc.2` | 45665802791: published; **install proof passed on Linux and Windows** |
| 1.1.1, version only | #92 | 45668729440; branch 45670131652 | `d092351` | `platform-core-v1.1.1` | 45671269742: published |
| Forward to main | #90 | 45651183046, 45673656100 | `51de93c` | — | — |

No check failed, and none was re-run. Every merge needed a green check first.

**GitHub trouble.** GitHub's API answered 502 for part of the day: opening and merging PRs was retried, always with the check read again
before the merge. The push of tag rc.1 started no release run at all (looked for 50 minutes later; the tag is on GitHub, the workflow
file parses, and the same file ran for rc.2). Most likely the event was lost in that trouble; this was not proved. The tag was left as it is.

`docs/api/public-api-1.txt`, the error catalogue and every SQL file are unchanged against 1.1.0.

## Independence

No other product's name or behaviour in code.

## Per item

Each test was written first and seen to fail, unless said otherwise.

| Item | Test that showed it | What failed before | Fix | Commit (main's copy is in #90) |
|---|---|---|---|---|
| LV-24.1 | `EmailChannelTest.eachRecipientGetsAMessageOfTheirOwn`, `.oneBadAddressDoesNotStopTheOthers`; `OneMessagePerRecipientTest` (5 of 7 failed) | one message, `To:` with every address | one message per recipient; outcome per recipient in the outbox | `4b61ffa` |
| LV-24.7 | `KeyedFileEraseLogTest.aNewEmptyLogWorksAndIsSealedByItsFirstEntry`; `KeyedEraseLogWiringTest.anAppStartsAndTakesWorkOnANewEmptyKeyedLog` | `ERASE_LOG_NOT_SEALED` | a never-written log has nothing to verify or apply | `1e8a1ff` |
| LV-22 | `PostgresStoresTest.aTimeFinerThanAMicrosecondKeepsTheChainIntact`, `.aLongChainOnANanosecondClockVerifies` (200 entries) | `USAGE_CHAIN_RECORD_EDITED: Record 1 …` | microseconds before hashing, in `UsageChain` | `1e8a1ff` |
| LV-24.8 | `RestoreAndReplayTest.aFileStorageRestoredAloneIsErasedAgainByTheReplay`, `.aFileStorageThatCannotBeErasedAgainBlocksTheSystem` | `ERASE_VERIFY_FAILED` | the replay applies those entries again | `71a734f` |
| LV-24.9 | documentation | — | `docs/install/erase-log.md` section 5 | `fadf80b` |

Guards that passed already and stay: a log with entries and no seal is refused; a used, then emptied log is refused
(`KeyedFileEraseLogTest`).

**LV-24.1.** A notice to a role with several holders becomes one message each. A person's final outcome is an outbox row under that
person's key, inserted as SENT or FAILED (never PENDING, so nothing else picks it up). A temporary failure of one person is retried by
the notice itself, which leaves out those with a row and looks the holders up again. A failure of the server or the sign-in ends
the attempt after one try (a refused login is not repeated per person). Teams and Slack messages hold no recipient; approval links
were already sent one by one.

**LV-22, existing ledgers.** Entries stored with whole microseconds are unchanged and verify. An entry with finer digits was already
broken when written; it shows as `USAGE_CHAIN_RECORD_EDITED` at its number, before and after the upgrade.
**Other hash chains:** the erase log keeps its times as text in full, so it has no such fault (`KeyedFileEraseLogTest
.aNanosecondClockKeepsTheChainIntact`, green without a change). The identity grant history has no hash chain. Notification keys use
milliseconds. The model-governance fingerprints hash no time that is read back. No further chain was found (search for SHA-256 and
"chain" over every module).

**LV-24.8, the choice: apply again.** Handlers must be safe to run twice, so this is supported. The replay checks every entry;
those at or below the database's position that can be read again go through their handler once more (after a second chain check),
the position does not move, and everything is checked again. While something is readable, the restore is recorded, so every instance
is blocked (before, the message said "stays blocked" although nothing was recorded).
**Not detected at startup:** a file storage restored alone. The database and the log are in step, and only the handlers can see the
files; asking them for every entry at each start would be slow. The guide says to run the replay after any restore. The other two
mismatches are named at startup as before (`ERASE_LOG_RESTORE_DETECTED`, `ERASE_LOG_BEHIND_DATABASE`).

## Files changed, commits

`7f35e9c` (1.1.1-SNAPSHOT), `4b61ffa`, `1e8a1ff`, `71a734f`, `fadf80b`, `09e42e9` (rc.1), `604f170` (rc.2), `d092351` (1.1.1).

## Decisions I had to make

1. **Existing tests changed:** six tests in the notifications testkit and licensing-spring, and one in `EmailChannelTest`, expected one shared message or
   its `Message-ID`; they now expect one per recipient.
2. **`Message-ID`** of a message to one of several recipients has a suffix per person; to a single recipient it is as before.
3. **The microsecond rule sits in `UsageChain`,** so an app's own ledger gets it too.
4. **`release.yml`** accepts a tag on main, or on `release/<major>.<minor>.x` of the tag's own version, each with its own green CI run;
   `ci.yml` runs on pushes to such branches. The install proof needed no change.
5. **rc.2 instead of moving rc.1's tag.**
6. **Worktrees under `C:\dev\shared` (`pc-111`, `pc-main`):** an editor's Java compiler was writing into the checkout's `target`
   ("Unresolved compilation problem" in a test run); likely also the cause of P054's one failed build.
7. `release/1.1.x` was made by pushing the tag's commit; everything after it came by PR.

## Not done, and why

- No startup detection of a file storage restored alone (above).
- Outbox rows: a notice to n people leaves n + 1 finished rows for the retention, instead of 1.
- While instances of 1.1.0 and 1.1.1 run together, the old one still sends shared messages. The release notes say to stop all first.

## Questions

1. **Neither main nor `release/1.1.x` is a protected branch** (GitHub API: `protected: false`), so "merged through a PR" rests on
   the routine, not on GitHub. Recommendation: the owner protects `main` and `release/*.x` (PR and the `verify` check required).
2. **`platform-core-v1.1.1-rc.1` is a tag without a release.** Recommendation: leave it; nothing refers to it.
