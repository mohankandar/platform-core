# platform-core 1.1.1-rc.2

Candidate of 1.1.1, for the install proof. Not for apps: take 1.1.1. The same code as rc.1, whose tag started no release run on GitHub and which was never published.

Patch release on 1.1.0 (branch `release/1.1.x`). **Bug fixes only: no API change**, no new setting, error code or
schema version. No other product's name or behaviour in code.

## Fixes

| Finding | Module | Who it affects | What changed |
|---|---|---|---|
| LV-24.1 (privacy) | platform-notifications | every installation whose notices go by e-mail to a role held by more than one person | All holders stood in one message's `To:` and saw each other's address. Now each gets a message of their own. Delivery and refusal are kept per recipient; a temporary failure is retried for that recipient only. Messages with approval links were already sent one by one; Teams and Slack messages never held an address. |
| LV-24.7 | platform-erase-log | an app with the key service and a new, empty erase log | The app refused work with `ERASE_LOG_NOT_SEALED` before the first entry could seal the log. A new, empty log now works and is sealed at its first entry. A log with entries and no seal is refused as before, and so is a log that was used and then emptied. |
| LV-22 | platform-licensing | usage ledgers written with a clock finer than a microsecond (Windows and Linux both) | The time was hashed with more digits than PostgreSQL keeps, so the chain check failed on reading it back (`USAGE_CHAIN_RECORD_EDITED`). The chain now takes the time in whole microseconds before hashing and storing. |
| LV-24.8 | platform-erase-log | an installation whose file storage was restored without the database | The replay did not erase again what came back below the database's position, so its check failed. It now applies those entries again, without moving the position, and checks again; while anything is left, work stays blocked. |
| LV-24.9 | docs | IT | `docs/install/erase-log.md` section 5: restore the database and the file storage together; what is shown when they do not match; what to do. |

## Existing usage ledgers (LV-22)

Nothing to run. Entries already stored are not changed. An entry whose time had no digits below a microsecond verifies as
before. One that had them was already broken when it was written: the chain check names it as
`USAGE_CHAIN_RECORD_EDITED` ("Record n was changed after it was recorded") and stays so after the upgrade, because the
ledger is never rewritten. New entries are linked to it correctly.

## Upgrading

- Nothing to configure, no schema step.
- **With several instances, stop all of them before starting 1.1.1.** An instance still on 1.1.0 would go on sending
  shared messages while it runs.
- Apps that count messages in their own tests: a notice to three people is now three e-mails.
- The outbox keeps one finished row per recipient of such a notice, for the same retention as before.

## Modules, design, schema versions

The modules of 1.1.0, at 1.1.1. Schema versions as in 1.1.0. Design docs brought up to date:
`docs/design/notifications.md`, `docs/design/erase-log.md`.

## Breaking changes

None.
