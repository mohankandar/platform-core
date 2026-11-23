# P034 report — model governance, part 2: monitoring, re-validation, documentation pack, Spring; release `platform-core-v0.5.0`

Date: 2026-11-23
Branch: `feature/model-governance-monitoring` (then `release/platform-core-0.5.0`, `chore/0.6.0-snapshot`, `docs/p034-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

- **Release:** tag `platform-core-v0.5.0` on commit `85a90e6f2f1e8a60c85257ffbd446550fc5422fa` (main). Release
  workflow run 45132408965 passed (version check, `mvn -B verify`: "Tests run: 1287, skipped: 0", deploy, GitHub
  release `platform-core-v0.5.0`). Published and checked at 0.5.0 on GitHub Packages: `platform-model-governance`,
  `platform-model-governance-testkit`, `platform-model-governance-spring`, `platform-bom`, `platform-errors`,
  `platform-monitoring-spring` (all modules are released together).
- **PRs**, each merged with the README's stop-on-failed-check line, exit status 0:
  - #25 `feature/model-governance-monitoring`. CI runs 45129058305 (push) and 45129066648 (pull request) passed.
  - #26 `release/platform-core-0.5.0` (version 0.5.0 and `docs/releases/platform-core-v0.5.0.md`).
    - **First push run failed** (45129847671): `NoSecretsInLogsTest.nothingSecretOrContentFromIntake` in
      platform-integration-testkit threw `ConcurrentModificationException`. Cause: the test read its captured log
      lines (`lines.stream()`) outside their lock while a mock server's thread could still be logging. A test race
      (existing since P027), not product code. Fixed in the same PR: every read goes through a copy taken under the
      lock. The check was not simply re-run.
    - Then 45130745730 (push) and 45130751482 (pull request) passed; merged.
  - **Main's CI run on the merge commit** (45131462910) **failed on attempt 1** before any code was built: Maven
    Central answered `429 Too Many Requests` for `maven-resources-plugin:3.4.0`. The cause was the outside service's
    rate limit, so I re-ran it once; attempt 2 passed. Only then was the tag made and pushed. The tag's CI run
    45132409061 also ran.
  - #27 `chore/0.6.0-snapshot`. CI runs 45134278487 and 45134285255 passed. Main is at `0.6.0-SNAPSHOT`.
  - This report through its own PR.
- **Local builds:** `mvn -o clean verify` passed before each push. Twice on the release and snapshot branches a
  local run failed in `platform-identity-core` and passed when run again: once with the log lost (my filter kept
  only the last lines), once with test classes not finding main classes (`cannot find symbol IdentityClaimConfig`).
  Nothing in that module changed. The likely cause is the editor's Java language server writing into the same
  `target/` folders during `mvn clean` on this laptop. CI never showed it. Noted here; nothing in the repo to change.
- **Tests on CI (#26 after the fix, and the release run): 1287, 0 skipped.** P033 had 1265. Per module: crypto 20,
  crypto-spring 5, crypto-testkit 16, erase-log 16, erase-log-spring 5, erase-log-testkit 9, error-catalogue 3,
  errors 6, fields 91, fields-testkit 18, identity-core 117, identity-jdbc 65, identity-spring 28,
  identity-testkit 14, integration 50, integration-spring 8, integration-testkit 196, licensing 144,
  licensing-spring 34, licensing-testkit 47, **model-governance-testkit 64** (was 47), **model-governance-spring 5**
  (new), monitoring-spring 5, monitoring-testkit 16, notifications 81, notifications-spring 41,
  notifications-testkit 132, outbound 8, outbound-spring 4, setup 39.

## Independence

**No other product's name or behaviour in code.** Tests use `PRODUCT_A`, the use "example: detection", made-up
models, roles, measures (`correction_rate`, `confidence`) and pack texts. The install guide names no product.
`NoProductNamesGuardTest` passes.

## What was built

### B1. Monitoring intake and thresholds; re-validation

- `ModelMeasures.report(use, entity, measure, value, MeasurePeriod(from, to, samples))`: numbers only, insert-only
  `mg_measure`, kept for the retention (`measure-retention`, default 400 days, at least 30); `purge()` is the only
  delete, and the database allows it only inside the purge transaction (`mg.purge_before`).
- `Monitoring.MonitorThreshold`: the app's default per use and measure, or the customer's stricter one per entity
  (value at least as strict in the same direction, no more samples; `MOD_THRESHOLD_LOOSER`, `MOD_THRESHOLD_MISSING`),
  with a direction and a minimum sample.
- Drift: a reading past its mark over at least the minimum sample → `mg_drift` (insert only, the mark copied in) and
  `ModelEvent` `DRIFT` (`MOD_DRIFT`) to the app's `ModelEvents`. The use shows "needs review"
  (`needsReview(use)`, `openDrifts()`, the pack's `needsReview`) until `recordAction(use, drift, note, by)`
  (`mg_drift_action`, insert only; `MOD_REASON_REQUIRED`, `MOD_ACTION_ALREADY_RECORDED`).
- `ModelReviews.due(now)`: uses due within 30 days, within 7, and overdue (on or after the date), each stage raised
  once per use and due date (`REVIEW_DUE`, codes `MOD_REVIEW_DUE` / `MOD_REVIEW_OVERDUE`; `mg_review_notice`);
  `overdue(now)` without raising. The next review comes from the materiality (high 90, medium 180, low 365 days, set
  by `review-every.*`) or the use's own `reviewEveryDays`. A change of the model's version, digest or where it runs,
  or of the use's model or configuration, makes the use due at once (next review = today; a history row
  `nextReviewDue` and an audit call).

### B2. Documentation pack

`ModelPack.export(product, entity, from, to)` → `Pack(json, fingerprint, pdf)`. JSON `platform-model-pack/1` with
its JSON Schema `schema/platform-model-pack-1.schema.json` (in the jar); canonical (keys sorted, no whitespace,
lists in a fixed order, no export time), so the same data always gives the same JSON; fingerprint `sha256:` of its
UTF-8 bytes, printed in the PDF. Texts the app supplies through `PackTexts` (design summary, inputs and outputs,
guardrails, known limits; third-party statement with independent review `DocumentRef`s; data statement), checked like
every free text; placeholders "Not supplied by the app." otherwise. Configurations appear only as fingerprints,
measures as numbers.

**PDF library:** platform-core had none. Apache PDFBox 3.0.8: Apache License 2.0, maintained by the Apache Software
Foundation, pure Java, uses the standard Helvetica font, so no font files ship. It was available for the offline
build. The JSON is the record; the PDF is its readable copy.

**Sample pack listing (section titles of the PDF, `ModelPack.SECTIONS`):**

```
Model documentation pack
  (format, product, entity, period, fingerprint of the JSON pack)
1. Model register
2. Uses: purpose, design and limits
3. Validation history
4. Monitoring
5. Change log
6. Third-party statement
7. Data statement
```

### B3. Spring — `platform-model-governance-spring` (new)

- `platform.model-governance.*`: `approver-roles` (required), `forbidden-roles`, `review-every.high|medium|low`
  (whole days), `measure-retention` (≥ 30 days), `worker.enabled|every`, `enabled`. Every problem is listed at once
  (`ModelGovConfig.Invalid`).
- `ModelGovSchema.verify` on the app's DataSource, then `PostgresModelGovStore`; no DataSource and no store bean →
  startup refuses, naming both. Beans `ModelGovernance` (the app's `ModelGovAudit`, else the history tables only,
  said once at startup), `ModelGate`, `ModelMeasures`, `ModelReviews` (the app's `ModelEvents` beans), `ModelPack`
  (the app's `PackTexts`), `ModelGovWorker` (review notices every run, purge daily). Every bean backs off for the
  app's own.
- Monitoring check `model-governance` (`ModelGovCheck`, only with platform-monitoring on the classpath): DOWN with
  the `MG_*` code on a wrong schema; HAVING_PROBLEMS with `MOD_DRIFT_UNHANDLED` or `MOD_REVIEW_OVERDUE`; else WORKING.
  platform-monitoring-spring's database check also verifies the `mg_` tables when `ModelGovStore` is in use.

### B4. Docs

`docs/install/model-governance.md` (database step and grants, settings, the gate, the Model approver and the
separation, a table of start refusals with what to do, re-validation, monitoring, the pack);
`docs/design/model-governance.md` section 10 "As built (P034)" with decisions; release notes
`docs/releases/platform-core-v0.5.0.md` (P033 + P034: modules, the `modelgov` schema, codes, pack format); CLAUDE.md
rows; catalogue regenerated (648 codes).

### Schema V2 (`V2__modelgov_monitoring.sql`)

`mg_model_use.review_every_days` (+ history), `mg_monitor_threshold`, `mg_measure` (insert only, purge-only delete),
`mg_drift`, `mg_drift_action`, `mg_review_notice` (insert only, no TRUNCATE). `ModelGovSchema.VERSION` = 2; the
runtime user needs SELECT and INSERT on the insert-only tables and also DELETE on `mg_measure`.

### New codes

`MOD_DRIFT`, `MOD_DRIFT_UNHANDLED`, `MOD_REVIEW_DUE`, `MOD_ACTION_ALREADY_RECORDED` (with message, cause and fix).

## Tests asked for → where

- Drift both directions with a minimum sample → `ModelGovContract.driftPastAThresholdInBothDirectionsWithAMinimumSample`,
  `monitoringPassMarksOnlyStricterAndRequired` (in memory and Postgres).
- Needs review and its action note → `needsReviewUntilAnActionIsRecorded`.
- Due dates per materiality; due at once after a change → `dueDatesPerMaterialityAndNoticesOncePerStage`,
  `anyChangeMakesTheUseDueAtOnce` (version, digest, where it runs, configuration, another model; a name change alone
  does not).
- The pack deterministic, complete against items 1–7, free of content → `thePackIsDeterministicCompleteAndFreeOfContent`
  (same fingerprint twice; the JSON fits its schema through platform-integration's `JsonSchema`; each item checked;
  the PDF's text holds the fingerprint and every section; a value planted in the configuration never appears in the
  JSON, the PDF or `toString`), `thePackCoversItsPeriodOnly`.
- Retention → `readingsArePurgedAfterTheRetentionDriftsStay`; `ModelGovSchemaTest.readingsLeaveOnlyThroughThePurgeAndMonitoringRecordsAreInsertOnly`
  (app user and owner).
- Spring context with and without app beans; the check's states → `ModelGovWiringTest` (app beans: in-memory store,
  audit, events, texts, clock; without: Postgres with the DML-only user, placeholders; WORKING / HAVING_PROBLEMS for
  drift and overdue / DOWN on a dropped table; startup refused without a DataSource, with no tables, with read-only
  rights; every wrong setting listed; switched off; without platform-monitoring).
- DML-only user → `PostgresModelGovTest` (the whole contract as that user), `ModelGovSchemaTest`, the Spring test.
- Every code reached → the contract's coverage check (`MOD_DRIFT_UNHANDLED` in the Spring test); `MG_VERSION_OLDER`
  is now reached too.
- Nothing skipped on CI → 1287 run, 0 skipped.

## Files changed, commits

- Main commits (rebase merges): `feat: model governance part 2 …` (#25), `release: platform-core 0.5.0 …` and
  `fix: NoSecretsInLogsTest reads the captured log lines only under their lock …` (#26), `chore: main moves to
  0.6.0-SNAPSHOT …` (#27).
- `platform-model-governance` (new: `Monitoring`, `ModelMeasures`, `ModelReviews`, `ModelEvent`, `ModelEvents`,
  `ModelPack`, `PackPdf`, `PackTexts`, `V2__modelgov_monitoring.sql`, `schema/platform-model-pack-1.schema.json`;
  changed: `ModelUse` (`reviewEveryDays`, public `atVersion`), `Model.atVersion` public, `ModelGovStore` (list,
  monitoring and notice methods; `HistoryRow.recorded`), `ModelGovernance`, `ModelGate`, `ConfigFingerprint`,
  `PostgresModelGovStore`, `ModelGovSchema`, codes), the testkit (store, fixtures, tests), the new `-spring` module,
  `BuiltInChecks` of platform-monitoring-spring, root and BOM POMs, docs.

## Decisions I had to make

1. **One open drift per use, entity and measure.** Further readings past the mark are kept but raise no new event
   until an action is recorded; then the next one raises a new drift.
2. **Overdue on the due date** (P033 counted from the day after); "due at once" therefore means overdue today, and a
   use set to refuse an overdue review stops at once after, for example, its model moves to another place.
3. **What makes a use due at once:** the model's version, digest or where it runs; the use's model or configuration.
   A rename alone does not.
4. **`report` needs a pass mark** for the measure (`MOD_THRESHOLD_MISSING`): the task gives every measure one.
5. **Readings carry their sample count** in `MeasurePeriod` (the task's `period`), since the minimum sample needs it.
6. **The pack's period** filters validations, readings, drifts (raised, acted on or still open) and changes; the
   register is as it is at export. The JSON has no export time, so it is deterministic.
7. **Review notices are remembered** (`mg_review_notice`) so the hourly worker raises each stage once.
8. **No audit bean** is allowed (history tables only, said at startup); a missing DataSource and store is refused.
9. **PDFBox** as above; `platform-model-governance` now depends on it besides platform-errors.
10. Fixed the CI test race (NoSecretsInLogsTest) in the release PR where it appeared, since it blocked the release and
    was small.

## Not done, and why

Nothing in P034's scope.

## Questions

None.
