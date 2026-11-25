# P056 report — platform-core 1.2.0: additions (LV-25.1, LV-23, LV-24.2/4/5/10, LV-25.3/4/5)

Date: 2026-11-25
Branch: `feature/1.2.0` (then `release/1.2.0`, `chore/1.3.0-snapshot`, `docs/p056-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Commit | Tag | Release run |
|---|---|---|---|---|---|
| Code, docs, rc.1 | #94 | 45685613948; main 45686795882 | `08fea4f` | `platform-core-v1.2.0-rc.1` | 45688094927: published; **install proof passed on Linux and Windows** |
| 1.2.0, version and notes | #95 | 45691392484; main 45692744349 | `0157c65` | `platform-core-v1.2.0` | 45693997837 (attempt 3): published |
| Main to `1.3.0-SNAPSHOT` | #96 | 45697938485 | `b9183c9` | — | — |

Every CI check on PRs and main was green the first time. **The release run of 1.2.0 (45693997837) needed two re-runs,** each after its cause was found;
the tag was not moved:

| Attempt | Failed step | Cause |
|---|---|---|
| 1 | `verify`, "The tagged commit is on main … and its CI there passed" | GitHub's filtered run list was stale: main's CI ended 20:20:08, the tag's run asked at 20:20:30 and got 0; asked minutes later, it answered 1. The condition was true |
| 2 | `publish`, `mvn deploy` while reading the POMs | Maven Central answered 429 (too many requests) for `spring-boot-dependencies`; the job has no cache by design. Nothing was uploaded (the newest package version was still 1.2.0-rc.1) |
| 3 (`publish` only, 10 minutes later) | — | published |

The coding session was interrupted after tagging rc.1 and resumed there; no step was redone.

Local: the changed modules, then one full `mvn -B verify`. It had **one failure**, `ReleasedSqlFilesTest`: the new `V6__test_sign_in.sql` was not
pinned yet. Pinned, that test and the other guards passed; the full build was not repeated locally (CI's is the gate).

**Page of 25 records:** 50 statements on the value store before, **1** now, and 1 key service call for the sensitive ones (0 for `read`). On top, as
before, one statement per entity and product for its field definitions. "One database query" holds for the records; with the definitions it is two.

## Independence

No other product's name or behaviour in code.

## Per item

| Item | New signatures | Tests |
|---|---|---|
| 1. LV-25.1 | `List<SensitiveRead> FieldValues.read(List<RecordRef>)`; SPI `default Map<RecordRef, StoredRecord> ValueStore.findAll(Collection<RecordRef>)` | `PostgresValueStoreTest.aPageOf25IsOneQueryForTheRecords`, `ReadSensitiveListTest.aPlainPage…` |
| 2. LV-23 | `LoginConnections.updateDraft / validate / activate / discardDraft(String tenantId, String productCode, UUID, …)` (the old four deprecated); SPI `default updateTested`, `default testSignInPassed`; `IDN_TEST_SIGN_IN_REQUIRED`; command `setup identity rollback-login-connection`; `SETUP_NO_PREVIOUS_CONNECTION` | `InMemoryLoginConnectionsTest` (3 new), `LoginConnectionDraftsTest`, `SetupIdentityChangesTest` (3 new) |
| 3. LV-24.2 | settings `platform.notifications.mail-servers.<name>.*`; `NotificationsProperties.MailServerSettings` | `PropertyValidationTest` (3 new), `MonitoringWiringTest.everyMailServerIsChecked` |
| 4. LV-24.5 | `ApprovalResult Approvals.decidedElsewhere(TenantId, UUID, Decision, String where, boolean tellRequester)`; `default void ApprovalAudit.recordElsewhere(Entry, String)` | `ApprovalsTest` (3 new) |
| 5. LV-24.4 | thresholds `warnDaysFirst` 60, `warnDaysSecond` 30, `warnDaysThird` 7, `warnDaysLast` 1 | `LicensingNoticesTest` (2 new) |
| 6. LV-24.10 | `boolean LicenceBuild.isDevelopment()`, `isDevelopmentLicence(Licence)` | `LicenceBuildTest` |
| 7. LV-25.3 | `List<FieldKey> FieldValuesException.conflictingKeys()` | `ReadSensitiveListTest.aConflict…` |
| 8. LV-25.4 | `List<FieldDefinition> FieldDefinitions.change(FieldScope, List<FieldChange>, String)`; `record FieldChange`; SPI `default FieldStore.saveAll`, `record FieldStore.Save` | `FieldDefinitionsTest`, `PostgresFieldStoreTest.aListIsSavedWholeOrNotAtAll` |
| 9. LV-25.5 | `static FieldValues.withoutKeyService(…)` (two); `VALUES_KEY_SERVICE_REQUIRED` | `ReadSensitiveListTest.withoutAKeyService…` |

`docs/api/public-api-1.txt`: 50 lines added, none changed. Three codes added. **Test first was not kept:** tests and code of
each item were written in one step, so I saw no test fail before its code. Checked afterwards instead, on the rc.1 code: eight behaviours switched off one
by one in the source (the token check, the customer check, setup's conflict after a rollback, the admin's days, the server choice, the own-request refusal, the
page query, the development flag) made nine tests fail, each on its own assertion. Items 7, 8 and 9 were not checked this way.

**Migration:** identity `V6__test_sign_in.sql`, one nullable column (`idn_login_connection.test_sign_in_version`), no new rights. No other schema changed.

## Decisions I had to make

1. **LV-23, the check: a recorded test sign-in with a real token,** through the module's own test (`validate` with a token), not an app hook. It is
   recorded for one version of the draft, so an edit takes it away. It is the safer of the two: an app cannot claim a sign-in that was not checked.
   Without a token a draft still becomes VALIDATED.
2. **This is a new refusal of something 1.1 allowed** (replacing an ACTIVE connection after an issuer-only test). The task asks for it. It cannot break a switch
   in progress: nothing is switched, the ACTIVE connection keeps working, the admin tests with a token. The first connection needs none. **An app whose screen has no field
   for a token must add one**; the release notes say so. `LoginConnectionDraftsTest` and two other existing tests were changed for it.
3. **Drafts already carried their customer** (`tenant_id` since V1), so no column for it; what was missing was the check. The four old calls take only an ID
   and cannot check; they stay (additive rule), deprecated.
4. **A rollback by the setup command counts as a change in the app** for a later `setup apply`: `conflict`, never undone silently.
5. **A store of one's own that does not record the test** (`LoginConnectionStore` defaults) can never replace an ACTIVE connection: fail closed.
6. **LV-24.2: per tenant and per destination.** A message to a mail server has no entity; an entity gets its own server through a destination of its own.
7. **LV-24.5: the place goes to the app's audit only,** not into `notif_approval_request`: `Decision` and `ApprovalRequest` are released records. An app that does not override `recordElsewhere` loses the place. The approver role is the app's own check there.
8. **LV-24.4:** platform-core's notifications design has no section 7.4. I read it as the admin's warning days for "licence expiring" and used the settings that exist for such numbers (0 to 180 days, 0 for none).
   `LicenceState.EXPIRING` stays at 60 days.
9. **LV-25.4:** version 0 in the list adds a field. `FieldStore.saveAll`'s default refuses: a loop would not be all or none.

## Not done, and why

- **A test sign-in through a real browser** against a new issuer: not in the module; the guide says what an app with one fixed decoder must do.
- **Old instances during the upgrade:** 1.1.x does not start on identity version 6, so there is no rolling upgrade for that step. The notes say so.

## Questions

1. **LV-23 (decision 2):** do both apps' screens offer the token? Recommendation: confirm on the board before either app takes 1.2.0.
2. **LV-24.5 (decision 7):** is the app's audit enough for the place? Recommendation: yes; a column later is additive if not.
3. **Release guard:** it asks GitHub's run list once. Recommendation: let it ask for up to 2 minutes before it fails (a small `release.yml` change; not in this task).
4. **Section 7.4 (decision 8):** if the apps' design says other bounds, tell me; they are constants in `LicensingEvents`.
