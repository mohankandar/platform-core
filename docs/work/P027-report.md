# P027 report — intake: API polling, watched folder / share with trigger files, and the virus-scan hook

Date: 2026-11-22
Branch: `feature/integration-intake`
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

| PR | What | CI runs | Merge |
|---|---|---|---|
| #13 | P027 A, B0–B4 (`feature/integration-intake`) | 45097512284 ✘ (push), 45097515944 (PR, superseded), 45097902509 ✔ (PR), 45097906488 ✔ (push) | rebase-merged by the README's script line (checks exit 0), `b36f403` |
| (this report) | `docs/work/P027-report.md` | see its PR | |

- Main's own CI run on `b36f403`: 45098459927 ✔. No release (as asked).
- **The failed run, and why.** 45097512284 was the first run on Linux. It found two failures that the laptop
  could not show, and each got a fix. Nothing was re-run to get it green.
  - `PollingTest.aFileOverTheLimitIsLeftAlone` (a real bug). When a download goes over the size limit, the engine
    stops reading and drops the connection, so it never reads the rest. On Linux, closing that dropped connection
    threw an error, which was reported as `INT_CONNECTION_LOST`. Now, when the engine stopped on purpose, an error
    from dropping or closing the connection is not a failure of the call (`Outbound`).
  - `WatchedFolderTest.limitsTwoTriggersAndALinkMakeADropInvalid` (a test bug; skipped on the laptop, where this
    account may not create symbolic links). A new link has the time it was made, so the drop was still settling. The
    test now moves its clock past the stable time.
- **CI (ubuntu-latest): 1141 tests, 0 skipped, 153 reports** (P026: 1085, so 56 new). Per module, from
  `scripts/ci-no-skipped-tests.sh`:

| Module | Tests | | Module | Tests |
|---|---|---|---|---|
| platform-crypto | 20 | | platform-integration | 53 (41 before) |
| platform-crypto-spring | 5 | | platform-integration-spring | 8 (7 before) |
| platform-crypto-testkit | 16 | | platform-integration-testkit | 198 (155 before) |
| platform-erase-log | 16 | | platform-licensing | 144 |
| platform-erase-log-spring | 5 | | platform-licensing-spring | 34 |
| platform-erase-log-testkit | 9 | | platform-licensing-testkit | 47 |
| platform-fields | 91 | | platform-notifications | 81 |
| platform-fields-testkit | 18 | | platform-notifications-spring | 41 |
| platform-identity-core | 113 | | platform-notifications-testkit | 132 |
| platform-identity-jdbc | 41 | | platform-setup | 37 |
| platform-identity-spring | 18 | | | |
| platform-identity-testkit | 14 | | | |

- Locally `mvn verify`: BUILD SUCCESS, 12 skipped. All of them ran and passed on CI:
  - 9 TLS checks: the 8 known ones and the new certificate-switch test. They skip because Norton intercepts TLS to
    127.0.0.1.
  - 2 EICAR tests that write the test file to disk. Norton removes it at once (`Eicar.staysOnDisk`).
  - 1 symbolic-link test (this Windows account may not create links).

## Independence

No other product's name or behaviour in code. The test profile (`example-pickup-v1`), the mock source, the mock
scanner, the drops and every test use made-up sources. `NoProductNamesGuardTest` passed. "ClamAV", "clamd", "ICAP"
and "EICAR" are the standard protocols and test file, not products of ours.

## What was built

**A.** On main, the only uncommitted files were the task file and the P026 decisions section. They were committed
as they were, as the first commit of the branch (`ffeb992`).

**B0, the P026 follow-ups.**
- Removed: `INT_PROXY_SIGN_IN_DISABLED_BY_JVM`, the `Outbound(..., JvmProxySignIn)` constructor and
  `safety.JvmProxySignIn` (it had no other use). The install guide, CLAUDE.md and the tests are updated.
- A switch drops the held token at once. `ClientCredentialsTokens` keeps each token with the fingerprint of the
  client certificate in use when it was fetched. With another certificate in use, the token is dropped and a new one
  fetched. This covers a switch by the admin, a switch at its set time, and a switch made on another app instance.

**B1, API polling** (kind `API_POLL`):
- **Configuration:** `config.PollSource`, with three `CallStep`s:
  - `list`, run once per page;
  - `download`, per item; its answer is the document;
  - `acknowledge`, per item, optional.
- **Item values:** the item's ID and values are read by pointers. Two new placeholders, `{item.id}` and
  `{item.<name>}`, work in the per-item calls only. Anywhere else they are `INT_CONFIG_PLACEHOLDER_PLACE`.
- **Paging:** NONE, CURSOR, NEXT_LINK (a pointer or the `Link` header, same host only) or PAGE_NUMBER, with a page
  size and pages per pass. `resume` is CONTINUE or FROM_START. The engine adds the paging parameters to the query
  string.
- **One pass:** `engine.PollEngine.pass(stored, CursorState, IntakeHandler<PolledItem>)`.
  - It goes through `CallEngine`'s sign-in, certificate, address guard, pinning, proxy, limits and rules.
  - Each download is streamed into the work folder with the size limit and SHA-256, and removed after the item. A
    new `Outbound.BodySink` takes a 2xx body as a stream.
  - The acknowledge is made only after TAKEN or ALREADY_TAKEN.
- **Result:** `intake.PollResult` holds:
  - the next state, which after a failure is the position after the last page fully handled;
  - counts per answer;
  - the app's refusals;
  - items left alone (no ID, a refused download, too large);
  - items quarantined;
  - the failure, with outcome, code, the source's code, status, Retry-After, step and item.
- **Profiles:** format `/1` gets `poll`, added only, with the schema and the format doc updated.
- **Testkit:** `MockSource`. It follows any poll source or polling profile. Its switches are listed under "Tests
  asked for".

**B2, watched folder** (kind `WATCHED_FOLDER`):
- **Configuration:** `config.WatchSource`. It sets:
  - the root;
  - SUBFOLDER or BASE_NAME drops;
  - the trigger pattern (`*`, `?`, `<base>`) and its type (JSON, XML or MARKER);
  - the stable time, the incomplete time and the claim time;
  - drops per pass, and the trigger's largest size;
  - the fixed mapping and an optional file list value.
- **One pass:** `watch.WatchedFolder.pass(stored, IntakeHandler<Drop>)`, in five steps:
  1. Stuck claims are released.
  2. Drops are found. A drop is taken only when its trigger is there and its files are stable. A drop without a
     trigger is reported once as incomplete, and nothing is moved.
  3. The drop is claimed by one atomic rename into `_processing/<drop>-<fence>`, with a claim marker.
  4. The drop is checked: no links or folders in it, the limits, one trigger, and every file's real path inside the
     root. Then SHA-256 and sizes are taken, the trigger is parsed, and the files are scanned.
  5. The handler answers, and the drop goes to `_archive`, `_problem` (with `problem.json`) or `_quarantine`.
- **What the app gets:** `watch.Drop` holds the files, the trigger (name, raw bytes, type), the extracted values, and
  the files not on the list and the names on the list without a file.
- **Parsing:** `watch.TriggerValues` parses JSON and XML hardened, and applies the mapping. JSON uses pointers, plus
  `*` for every list element. XML uses a small path language.
- **Files:** a port `watch.WatchFiles`, which `LocalFiles` now implements. `MockFolder` is extended.

**B3, virus-scan hook:**
- `scan.FileScanner`, with `ClamdScanner` (INSTREAM) and `IcapScanner` (RESPMOD), both through the address guard.
- `scan.Scanning` is off without a scanner. Both polling and the watched folder call it before the handler.
- `scan.ScanEvents.infected` tells the app about an infected file.
- Testkit: `MockScanner` (clamd and ICAP) and `Eicar`.

**B4, Spring and docs:**
- **Properties:** `platform.integration.scanner.*` and `intake.work-folder`, checked at startup with every problem
  listed.
- **Beans:**
  - `FileScanner`, only when `scanner.type` is not NONE;
  - `Scanning`, with the app's `ScanEvents`;
  - `PollEngine`;
  - `WatchedFolder`, with the app's `WatchFiles`.
- **Health:** the `integrations` contributor covers API_POLL and WATCHED_FOLDER, and has a `scanner` entry.
- **Install guide:**
  - section 11: polling;
  - section 12: watched folder or share (rights, trigger last, the four folders);
  - section 13: the scanner;
  - section 14: the new codes.
- **Other docs:** a P027 section in `docs/design/integration-module.md`, the profile format doc, and CLAUDE.md.

**Minimal examples**

One polling pass with a handler:

```java
PollEngine poll = new PollEngine(callEngine, Scanning.OFF, workFolder);
PollResult r = poll.pass(stored, appStore.cursor(stored.id()), item -> {
    if (appStore.has(item.sourceItemId())) return IntakeAnswer.ALREADY_TAKEN;
    try (InputStream in = item.file().open()) { appStore.save(item.sourceItemId(), item.values(), in); }
    return IntakeAnswer.TAKEN;
});
appStore.saveCursor(stored.id(), r.nextState());   // on a failure: after the last page fully handled
if (!r.ok()) schedule.later(r.failure());          // outcome, code, step, item; Retry-After when busy
```

One watched-folder pass with a JSON trigger mapping:

```java
IntegrationConfig c = IntegrationConfig.watchedFolder(entity, "DEMO", "Inbox",
        WatchSource.of("\\\\files01\\inbox", WatchSource.Layout.SUBFOLDER, "*.json")
                .mapping("contract", "/loan/number").mapping("files", "/files/*/name").withFileListValue("files"));
WatchResult r = new WatchedFolder(LocalFiles.INSTANCE, installation, scanning, clock).pass(stored, drop -> {
    // drop.value("contract"), drop.files(), drop.unlisted(), drop.missing(): the app decides what they mean
    return app.take(drop) ? IntakeAnswer.TAKEN : IntakeAnswer.refused("APP-UNKNOWN-CONTRACT");
});
```

The scanner on (Spring: `platform.integration.scanner.type=CLAMD`, `.host`, `.port`):

```java
Scanning scanning = new Scanning(new ClamdScanner(new ScannerAddress("scanner.corp", 3310, Duration.ofSeconds(60)),
        new AddressGuard(installation.allowInternalAddresses())), event -> itAlerts.infected(event));
```

## Tests asked for → where

| Asked for | Where |
|---|---|
| Every polling switch (pages, empty, broken page, slow, busy, down, refused download, failing acknowledge, duplicates; sign-in refused) | `PollingTest` (16) |
| Every watched-folder switch (slow arrival, trigger before files, files without a trigger, unreadable triggers, racing runners, share gone in the middle) | `WatchedFolderTest` (17) |
| Scanner: clean, EICAR, slow, down, garbled, health, address rules | `ScannerTest` (6) |
| Idempotency: handled twice is ALREADY_TAKEN, never taken twice | `PollingTest`: duplicates, failing acknowledge, restart. `WatchedFolderTest`: stuck claim, app answers |
| Two concurrent runners: exactly one claims | `WatchedFolderTest.twoRunnersRacingExactlyOneClaims` |
| A restart in the middle of a pass is safe | `PollingTest.aRestartInTheMiddleOfAPassIsSafe`; `WatchedFolderTest.aStuckClaimIsReleasedAndTheDropTakenAgainSafely`, `theShareDisappearingInTheMiddleOfAPass` |
| XML attack files refused (billion laughs, external entities) | `TriggerValuesTest` (5); `WatchedFolderTest.unreadableAndAttackTriggersGoToProblemWithACodeNeverContent` |
| No document text or secrets in logs | `NoSecretsInLogsTest.nothingSecretOrContentFromIntake` |
| EICAR never reaches the handler | `ScannerTest.eicarFromASourceNeverReachesTheHandlerAndIsNotAcknowledged`, `WatchedFolderTest.eicarIsQuarantinedAndNeverReachesTheHandler` |
| DML-only user for anything stored | `PostgresIntegrationStoreTest.intakeConfigurationsAreStoredWithTheDmlOnlyUser`. Only the configuration is stored; there is no new table |
| Configuration checks and stored form | `IntakeConfigTest` (6) |
| API_POLL in profiles | `PollProfileTest` (2) |
| Spring beans, properties, health | `IntegrationWiringTest.intakeBeansTheScannerAndTheirHealth` |
| B0: token dropped on a switch | `SignInTest.aHeldTokenIsDroppedWhenTheClientCertificateInUseChanges`, `MutualTlsTest.switchingTheCertificateDropsTheHeldTokenAtOnce` (CI) |
| Nothing skipped on CI | CI 45097906488: 1141 tests, 0 skipped |

## Files changed, commits

62 files, +7117 / −221. Commits, as rebase-merged on main:

- `b0e2947` docs: P027 task and the P026 decisions;
- `e181a3e` B0;
- `6bb745d` intake core;
- `059a5eb` polling tests and testkit;
- `0e0db37` watched folder and scanner tests, with two fixes;
- `a2ab320` profiles and the log check;
- `a3e4547` the Spring wiring;
- `ebc82ff` docs;
- `b36f403` the fixes for CI run 45097512284.

New in `platform-integration`:

| Package | New |
|---|---|
| `config` | `PollSource`, `WatchSource` |
| `intake` | `IntakeHandler`, `IntakeAnswer`, `IntakeFile`, `CursorState`, `PolledItem`, `PollResult` |
| `engine` | `PollEngine` |
| `watch` | `WatchedFolder`, `WatchFiles`, `Drop`, `WatchResult`, `TriggerValues` |
| `scan` | `FileScanner`, `ScanResult`, `ScanEvents`, `ScannerAddress`, `ClamdScanner`, `IcapScanner`, `Scanning` |

Removed: `safety.JvmProxySignIn`.

Testkit: `MockSource`, `MockScanner`, `Eicar`; `MockFolder` extended.

**New codes:**

| Area | Codes |
|---|---|
| Polling | `INT_POLL_ITEMS_MISSING`, `INT_POLL_ITEM_ID_MISSING`, `INT_POLL_NEXT_PAGE_INVALID`, `INT_POLL_STORAGE_FAILED`, `INT_POLL_HANDLER_FAILED` |
| Watched folder | `INT_WATCH_ROOT_NOT_FOUND`, `INT_WATCH_NO_PERMISSION`, `INT_WATCH_SHARE_UNREACHABLE`, `INT_WATCH_TRIGGER_UNREADABLE`, `INT_WATCH_CLAIM_LOST`, `INT_WATCH_MOVE_FAILED`, `INT_WATCH_FILE_CHANGED`, `INT_WATCH_DROP_INVALID`, `INT_WATCH_INCOMPLETE`, `INT_WATCH_REFUSED`, `INT_WATCH_HANDLER_FAILED` |
| Scanner | `INT_SCAN_INFECTED`, `INT_SCAN_FAILED`, `INT_SCAN_UNREACHABLE` |
| Configuration | `INT_CONFIG_POLL_INVALID`, `INT_CONFIG_WATCH_INVALID` |

`INT_CONFIG_FOLDER_HOSTED` now also covers a watched folder. Removed: `INT_PROXY_SIGN_IN_DISABLED_BY_JVM`.

## Decisions I had to make

1. **Package and class names.** The task named `PollEngine`, `WatchedFolder`, `IntakeHandler`, `FileScanner` and
   `ScanEvents`, but not their packages.
   - `PollEngine` is in `engine`, next to `CallEngine`, so it shares its sign-in and classification
     (package-private).
   - The shared types are in `intake`; the folder in `watch`; the scanner in `scan`.
   - `IntakeHandler<T>` is generic, so one interface serves polling (`PolledItem`) and the watched folder (`Drop`).
2. **A token bound to the certificate, rather than a call from the switch.** A switch made on another app instance
   never reaches this instance's token cache. Binding the token to the certificate in use covers that case too.
3. **What stops a polling pass, and what is only reported.**
   - Reported, and the pass goes on: an item without an ID, a download the source refuses (REFUSED), and a file over
     the size limit. Stopping on one bad item would block the source for good. These items are not acknowledged.
   - Stops the pass, with the cursor kept: everything else (sign-in, busy, outage, no connection, unclear, a failing
     acknowledge, a scan that failed, the handler).
   - At the end of the list, the next pass reads the last page again. New items may join it, and what the app has is
     ALREADY_TAKEN.
4. **A handler that fails (an exception, not an answer):**
   - polling stops the pass, and nothing is acknowledged;
   - the watched folder puts the drop back where it was found and stops the pass.

   A database outage would otherwise send every drop to `_problem`. A file that changed during the handler is the
   exception: it goes to `_problem` with `INT_WATCH_FILE_CHANGED`.
5. **The stability rule** passes in either of two cases:
   - every file's time is at least the stable time old; or
   - this instance saw exactly the same sizes and times at least that long ago.

   The second case covers clocks on a share that differ from the app's. "Reported once" for incomplete drops is per
   app instance (kept in memory). A restart may report a drop again; the app can de-duplicate by drop ID.
6. **A lost claim on Windows.** A rename that lost the race fails with "access denied" there, not "no such file". A
   failed claim whose source is gone now counts as lost. The racing test found this, and also a real start-up race
   (two instances making `_processing` at the same time); both are fixed.
7. **The trigger is scanned too**, as an incoming file. The ICAP adapter treats a 200 answer to a request that
   allowed 204 as infected, even without a threat header: a file is never let through when unsure.
8. **EICAR** is built at run time from a reversed string, so no source, class or jar holds the signature, and the
   laptop's antivirus does not quarantine the build. Tests that write it to disk skip where it does not stay; CI
   forbids skips.
9. **Profiles:** `poll` takes the place of `steps` and `packaging` for `API_POLL`. It has no `fields` or
   `documentAttributes`, and the direction must be INBOUND. These are additions only: every existing profile still
   passes.

## Not done, and why

- **ICAP over TLS (`icaps`).** It is not built: the connection is plain TCP inside the customer's network, as most
  ICAP set-ups use it (question 2).
- **A mock scanner for the hosted service's own scanner.** Not asked for, and the hosted service has no scanner yet
  (question 1).

## Questions (numbered, each with a recommendation)

1. **Requirement 11 recommends "always on for our hosted service".** Scanning is optional everywhere today.
   *Recommendation:* when the hosted service gets its scanner, make startup on `HOSTED` refuse a missing scanner. Put
   that in the hosted go-live task.
2. **ICAP over TLS.** *Recommendation:* build it when a customer's scanner needs it (a small change to
   `ScannerAddress` and `IcapScanner`).
3. **Incomplete drops are reported once per app instance.** *Recommendation:* keep it so. The app de-duplicates by
   drop ID before it raises an IT notice (pass this to the app threads), rather than writing a marker into the
   customer's folder.
4. **A refused download (4xx) is reported, and the pass moves on.** With CONTINUE the cursor passes it, so it is not
   retried unless the source lists it again. *Recommendation:* the app shows these items to IT (they are in
   `PollResult.problems`). A source that wants retries can be given a rule that maps its status to OUTAGE.
