# P057 report — design note for platform-core 1.3.0 (LV-25.2, LV-24.3)

Date: 2026-11-25
Branch: `docs/p057-design` (then `docs/p057-report`)
Outcome: DONE

## Result

| Step | PR | CI run | Commit |
|---|---|---|---|
| The note, `docs/design/1.3.0-transactions-and-outbound.md` | #98 | 45699732130 | `1bee423` |

Design only. No code, setting, schema or test changed.

## Independence

No other product's name or behaviour in code.

## What the note settles

| | LV-25.2, transactions | LV-24.3, mail through outbound |
|---|---|---|
| Way | a connection handed over per call (`in(Connection)`, `onConnection`), as the identity stores have | a port in notifications, wired in notifications-spring; `via-outbound` |
| Default | today's own transactions | off, for new installations too |
| Schema | none | none |
| Size | medium | large; two steps proposed |

## Decisions I had to make

1. **READ COMMITTED only** for a bound store; other isolation levels are refused. The stores' retry and their read after `on conflict` need to see the other writer.
2. **One savepoint per write,** because a unique violation aborts a PostgreSQL transaction, and the app's own work must survive a refusal.
3. **Erasing is not possible in the app's transaction** (the erase log has its own).
4. **SMTP goes direct, also with a proxy set,** unless `smtp-proxy=CONNECT`.

## Found, and worth knowing

- **platform-outbound has no allow-list.** The task speaks of one ("a mail host missing from the allow-list"). It has the address guard, the proxy's bypass list and the private CA. The note uses those and
  proposes `mail.allowed-hosts`, for mail only, as a question.
- **platform-outbound depends on platform-notifications,** so notifications cannot depend on it.

## Not done, and why

Nothing left out. The sizes are estimates from reading the code; no prototype was built.

## Questions for the apps

1. Is `in(Connection)` enough, or is a Spring bean wanted that finds the current transaction? Recommendation: `in(Connection)`, with a helper in the guide.
2. Key service calls inside the app's open transaction? Recommendation: yes for 1.3.0; a `prepare` step only if an app measures long locks.
3. `mail.allowed-hosts`, as there is no allow-list today? Recommendation: yes.
4. Teams and Slack the same way? Recommendation: only if the apps want it, then in the same build.
5. Two steps, token requests in 1.3.0 and SMTP in 1.4.0? Recommendation: yes, unless an app needs SMTP through a proxy now.

## Addition to P056's report

- Its row "Main to `1.3.0-SNAPSHOT`" lacks the CI run: **45697938485** (filled in with this PR).
- **Every module of the BOM is at 1.2.0 in GitHub Packages** (33 of 33 listed), as the design chat's note of 15:40 asked. P056 was marked DONE before I read that note; the clean
  resolve it also asked for was run after: install proof by hand against published 1.2.0, run 45700050747, passed on Linux and Windows.
