# P059 report — platform-core 1.3.0: the app's own transaction (LV-25.2), token requests, Teams and Slack through outbound (LV-24.3, step 1), dropdown display names (LV-28)

Date: 2026-11-26
Branch: `feature/1.3.0` (then `release/1.3.0`, `chore/1.4.0-snapshot`, `docs/p059-report`); task file by `docs/p059-task`
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| Step | PR | CI runs | Commit | Tag | Release run |
|---|---|---|---|---|---|
| Task file | #102 | green | `279ec99` | — | — |
| Code, docs, rc.1 | #103 | 45868757914; main 45869658597 | `44b9515` | `platform-core-v1.3.0-rc.1` | 45870568230: published; **install proof passed on Linux and Windows** |
| 1.3.0, version and notes | #104 | 45873011235; main 45874156696 | `a234473` | `platform-core-v1.3.0` | 45875220863: verify and publish passed the first time |
| Main to `1.4.0-SNAPSHOT` | #105 | 45877191646 | `c58e7bc` | — | — |

Every CI check, main run and release run was green the first time; no re-run.

**Published:** all 32 artifacts of the BOM list (BOM, parent and modules) have version 1.3.0 in GitHub Packages (packages API), and
`platform-bom`, `platform-fields` and `platform-notifications-spring` (with their dependencies) downloaded at 1.3.0 into an empty local
repository with `dependency:get`. A clean `dependency:get` of every module one by one was not run: the permission prompt refused that
command (it started with a folder delete), so I used the packages API for the full list.

**Local:** the changed modules and their tests while working, then one full `mvn -B verify` before pushing (build success, 15 min).
Two findings on the way:

- `ReleasedSqlFilesTest`: the new `V3__fields_option_names.sql` was not pinned yet. Pinned.
- On this laptop Norton intercepts TLS even to 127.0.0.1, so a client trusting only the private CA cannot finish the handshake with the
  local test server. Two tests of `ViaOutboundTest` (through the proxy with the private CA; the webhook time-out) skip themselves when
  they detect that, exactly as `HttpsIssuerTest` and the integration testkit's `PrivateCaTest` do; CI runs them (it forbids skips) and they
  passed there.
- `AppTransactionTest.twoTransactionsClaimingOneNewPrimaryValueEndOnOneRecordWhenTheFirstCommits` timed out once in a long local run
  (passed three times alone). It now waits until PostgreSQL shows the second transaction waiting on a lock (`pg_stat_activity`) instead
  of a fixed 500 ms, and allows 30 s.

## Independence

No other product's name or behaviour in code.

## What was built

**Part 1, fields in the app's transaction (LV-25.2)**, as note sections 1.2–1.6:
`FieldValues.in(Connection)`, `FieldDefinitions.in(Connection)`, `PostgresValueStore.onConnection(Connection)`,
`PostgresFieldStore.onConnection(Connection)`, SPI defaults `ValueStore.joined(Connection)` and `FieldStore.joined(Connection)`
(`UnsupportedOperationException`), codes `VALUES_TRANSACTION_UNSUITABLE`, `FIELD_TRANSACTION_UNSUITABLE`. Both stores' transaction
code is now one class, `jdbc.Db`: the store's own transaction on its data source (unchanged), or the app's connection: every call checks
auto-commit off and READ COMMITTED, each write runs in a savepoint rolled back to on failure, and the store never calls `commit`, a full
`rollback`, `setAutoCommit`, `setTransactionIsolation` or `close`. Erasing is refused in a bound `FieldValues` and a bound
`PostgresValueStore.delete` (`VALUES_ERASE_OUTSIDE_LOG`). No Spring bean; the guide has the `DataSourceUtils` lines. Key service calls
inside the transaction are allowed. No settings, no schema change.

**Part 2, outbound for token requests, Teams and Slack (LV-24.3 step 1)**, as note sections 2.2–2.5 minus SMTP:
port `com.documentplatform.notifications.transport.HttpTransport` (`post(URI, headers, body, connectTimeout, readTimeout)` → `Reply`,
or `Failed` with kind REFUSED / UNREACHABLE / TIMEOUT and an outbound code name as reason; `MAX_REPLY_BYTES` 64 KiB); new constructors
`MicrosoftTokens(Clock, Duration, Function<MicrosoftOAuth, HttpTransport>)`, `TeamsChannel(…, WebhookOptions, HttpTransport)`,
`SlackChannel(…, WebhookOptions, HttpTransport)`; codes `MAIL_OUTBOUND_REFUSED`, `WEBHOOK_OUTBOUND_REFUSED` (not retried). Without a
transport the 1.2.0 code path runs unchanged. platform-notifications-spring: `internal.OutboundHttpTransport` over the `Outbound` bean of
platform-outbound-spring (`platform-outbound` optional dependency; the auto-configuration now runs after `OutboundAutoConfiguration`),
start-up check of the Microsoft sign-in hosts by the address guard without connecting.

**Part 3, dropdown display names (LV-28):** `FieldSpec.Option(code, name)` with `displayName()`, `FieldSpec.namedOptions()` (new last
record component), `FieldSpec.displayName(code)`, `Builder.namedOptions(List|…)`; the 21-argument constructor of 1.2.0 stays.
`options()` stays the codes. Codes `FIELD_OPTION_NAME_INVALID`, `FIELD_OPTION_NAME_REPEATED`. Schema V3: nullable
`fld_field_definition.option_names` (same length as `options`, checked); null when no option has a name, so code-only rows stay as they
were. The order was already kept (Postgres array, list in the file and the history); tests now pin it. Field list file: a named option is
`{"code", "name"}`, an unnamed one stays a string.

**Settings and defaults:** `platform.notifications.mail.via-outbound=false`, `platform.notifications.mail-servers.<name>.via-outbound=false`,
`platform.notifications.webhook.via-outbound=false`. Nothing else new.

**Migration:** fields V3 (`V3__fields_option_names.sql`), in the fields run, after V2; stop 1.2.x instances first.

## Tests asked for → where, and the failure each showed before its code

Each part's tests were committed first (`fc2c712`, `9c4759f`, `51f2c88`), with the new signatures as stubs that threw
`UnsupportedOperationException("stub")`. So the first failures were the stubs, not the old behaviour.

| Asked for | Test | Before the code |
|---|---|---|
| App rolls back → no field values | `AppTransactionTest.theAppRollsBackSoNoFieldValueNorDefinitionStays` (also a sensitive claim, a definition) | stub |
| Commit → both, unseen before | `…theAppCommitsSoBothAreThereAndNeitherWasSeenBefore` | stub |
| Conflict check under two writers | `…twoTransactionsClaimingOneNewPrimaryValueEndOnOneRecordWhenTheFirstCommits`, `…MakeTheSecondsRecordWhenTheFirstRollsBack` | stub |
| Refused write leaves the app's row writable | `…aRefusedWriteLeavesTheAppsTransactionUsable` (`VALUES_PRIMARY_TAKEN`, `VALUES_CONFLICT`, then more writes) | expected `FieldValuesException`, got the stub |
| Version conflict retried | `…aVersionConflictInsideIsRetriedAndSucceeds` | stub |
| Wrong isolation / auto-commit refused | `…autoCommitOnAndOtherIsolationLevelsAreRefusedWithNothingWritten` | expected the code, got the stub |
| Store never ends the transaction | `…aBoundStoreNeverEndsOrChangesTheAppsTransaction` (proxy connection that fails on `commit`, full `rollback`, `setAutoCommit`, `setTransactionIsolation`, `close`) | stub |
| Erasing in a bound store refused | `…erasingInTheAppsTransactionIsRefused` | expected the code, got the stub |
| Every store test twice (note 1.5.8) | `PostgresValueStoreBoundTest` (27), `PostgresFieldStoreBoundTest` (9): the existing tests with every call on a bound store | 27 and 8 errors (stub) |
| Spring (note 1.5.9) | `platform-app-check` `FieldsInSpringTransactionTest` (`@Transactional` with an exception; `REQUIRES_NEW` around it) | written after the store code; checked by making `FieldValues.in` return `this` (1.2.0 behaviour): the rollback test failed (`expected 0` field records), `REQUIRES_NEW` passes either way |
| via-outbound off → nothing changes | the existing notifications tests, untouched, all green; `ViaOutboundTest.offByDefaultNothingGoesThroughOutbound` (proxy configured but unused) | — |
| On → token, Teams, Slack through outbound | `ViaTransportTest` (fake transport: the same form and payloads, time-outs, codes; 7 of 8 red on the stubs); `ViaOutboundTest` (real `Outbound`: proxy CONNECT seen, private CA trusted, missing CA → refused, internal and metadata addresses refused, webhook time-out, start-up problems) | `ViaTransportTest`: stub. `ViaOutboundTest` was written before the Spring code; its first run failed on a mistake in the test itself (app beans registered twice), so it never showed a clean red of the feature |
| Refused address reported clearly | `ViaTransportTest.aRefusedAddressIs…`, `…aWebhookAddressTheOutboundSettingsRefuse…`; `ViaOutboundTest.theAddressGuardRefuses…` | stub |
| Order kept through save, read, change | `DropdownNamesTest.optionsKeepTheAdminsOrderWithTheirNames`, `DropdownNamesPostgresTest.theOrderIsKeptThroughSaveReadAndChange` (history too) | stub |
| Names change, values untouched | `DropdownNamesPostgresTest.namesChangeWithoutTouchingAnyStoredValue` (raw `fld_value` rows and record version compared) | stub |
| Duplicate name refused | `DropdownNamesTest.namesAreUniqueWithinAField…`, `DropdownNamesPostgresTest.aDuplicateNameIsRefusedAndNothingIsSaved` | expected `FieldDefinitionException`, got the stub |
| Code-only definition reads unchanged | `DropdownNamesTest.aCodeOnlyDefinitionReadsAsBefore`; `DropdownNamesPostgresTest.aCodeOnlyDefinitionFromBeforeReadsUnchanged` (row and history written at V2 as 1.2.0 did, then V3) | stub |
| Search and import by code | `DropdownNamesPostgresTest.importAndSearchStillMatchByCode`, `DropdownNamesTest.valuesAreMatchedByCodeNeverByName` | stub |

## Files changed, commits

`fc2c712` test(fields) part 1 · `383e7ee` feat(fields) part 1 · `9c4759f` test(fields) part 3 · `6c14b8b` feat(fields) part 3 ·
`51f2c88` test(notifications) part 2 · `4abe092` feat(notifications) part 2 · `44b9515` docs, build rc.1 · `a234473` 1.3.0 · `c58e7bc`.
Docs: `docs/install/fields.md` (sections 6, 7, V3), `docs/install/notifications.md` ("Through the outbound settings"),
`docs/install/switch-over.md` ("New in 1.3.0", fields 3), `docs/design/case-fields.md` and `docs/design/notifications.md` ("Added in
P059"), `docs/design/1.3.0-transactions-and-outbound.md` (what was built), CLAUDE.md (two module lines), release notes rc.1 and 1.3.0,
the catalogue, `code-names.txt`, `public-api-1.txt` (lines added only).

## Decisions I had to make

1. **One port for the three calls**, `transport.HttpTransport`, instead of the note's mail-only `MailTransport`: the note's open question 4
   foresaw a shared port once Teams and Slack were in. SMTP sockets join it (or a sibling) in 1.4.0.
2. **Teams and Slack under one setting**, `webhook.via-outbound`, next to the existing `webhook.*` time-outs.
3. **`via-outbound` on a password mail server** logs a warning at start-up, rather than stopping the app: nothing of it goes through
   outbound in 1.3.0. In 1.4.0 the same setting will take its SMTP connection under the address guard and private CA; the guide and the
   release notes say so.
4. **Start-up:** only the Microsoft sign-in host is checked; Teams and Slack addresses are secrets read at each send, so they are checked
   then. A blocked sign-in host stops the app; one that does not resolve yet is a warning.
5. **Mapping of outbound codes:** REFUSED for address blocked, not secure, TLS failed, proxy sign-in refused, certificate refused, proxy
   password missing, header not allowed; TIMEOUT for `OUT_TIMEOUT`; the rest UNREACHABLE (retried, as today's codes).
6. **Option names:** unique ignoring capitals and extra spaces; a blank name counts as no name; a name may equal another option's code
   (only names are compared). The names column holds null when no option has a name.
7. **`FieldValues.in`** reads the definitions in the same transaction when their store can join it, otherwise through the store as it is;
   an unsuitable connection found while reading the definitions is reported with the values code.
8. **The `FieldSpec` record** got its new component last; the 1.2.0 constructor stays. An app using a record pattern on `FieldSpec`
   would need one more component (noted in the release notes); I know of none.

## Not done, and why

- SMTP through outbound, `mail.allowed-hosts`, `mail.smtp-proxy` and monitoring's `channel-mail` path: step 2, 1.4.0, as the task says.
- A clean one-by-one `dependency:get` of every module: replaced by the packages API check plus three real downloads (above).

## Questions (numbered, each with a recommendation)

1. Should `via-outbound` on a password mail server stop the app in 1.3.x, so that nobody is surprised when 1.4.0 adds the SMTP
   connection to the same setting? Recommendation: no; keep the warning, and name it again in the 1.4.0 release notes.
2. For 1.4.0 (SMTP): keep one setting `via-outbound` for token and SMTP, as the note proposes? Recommendation: yes.
