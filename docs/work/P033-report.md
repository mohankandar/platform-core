# P033 report — model governance, part 1: register, change gate, digest check, result stamping

Date: 2026-11-23
Branch: `feature/model-governance-gate` (then `docs/p033-report`)
Outcome: DONE

## Result (tests per module, PRs, CI runs, merge results)

- **PR #23** `feature/model-governance-gate`, merged with the README's stop-on-failed-check line, exit status 0.
  Merge commit `81c9b0aed2ecacbd68e09bfba86934b740a71313` on main. No release (P034 releases).
  - **First CI run failed** (run 45123504100, push): `ErrorsTest.referencesAreShortTimeOrderedAndUnguessable` in
    platform-errors (P032 code), "unique ==> expected: <true> but was: <false>". Cause: `ReferenceId.next()` was a
    tenth-of-a-second time part plus 25 random bits, so the test's 2000 references within one tenth collide with
    about 6 % probability (birthday bound), and so could a busy app's. A real defect, not a flaky test. Fixed in the
    same PR: within the same tenth the random part steps on by a random amount (on overflow the time part moves on;
    a clock that goes back keeps the last time part), so references never repeat in a process and stay
    time-ordered and hard to guess. New test: 100 000 references at a fixed clock, all unique and increasing. The
    check was not simply re-run. Design note in `docs/design/error-codes.md` section 2 updated.
  - Then CI runs 45124225955 (push) and 45124230532 (pull request) passed.
- **Tests on CI (#23): 1265, 0 skipped** ("Tests run: 1265, skipped: 0"). P032 had 1217. Per module: crypto 20,
  crypto-spring 5, crypto-testkit 16, erase-log 16, erase-log-spring 5, erase-log-testkit 9, error-catalogue 3,
  **errors 6** (+1), fields 91, fields-testkit 18, identity-core 117, identity-jdbc 65, identity-spring 28,
  identity-testkit 14, integration 50, integration-spring 8, integration-testkit 196, licensing 144,
  licensing-spring 34, licensing-testkit 47, **model-governance-testkit 47** (new; the module's tests live there, as
  for monitoring), monitoring-spring 5, monitoring-testkit 16, notifications 81, notifications-spring 41,
  notifications-testkit 132, outbound 8, outbound-spring 4, setup 39.
- Local check: `mvn -o clean verify` passed before each push.

## Independence

**No other product's name or behaviour in code.** Tests use `PRODUCT_A`, the use "example: detection", made-up models
(`example-detector`, `customer-detector`) and made-up roles (`MODEL_APPROVER`, `COMPLIANCE_OFFICER`,
`BUSINESS_ADMIN`; platform-core code names no role: the app's `ModelGovSettings` does). The manifest adapter speaks
the generic OCI distribution API, no particular model server. `NoProductNamesGuardTest` passes.

## What was built

New modules `platform-model-governance` (plain Java, depends only on platform-errors; package
`com.documentplatform.modelgov`) and `platform-model-governance-testkit`; both in the root `<modules>` and the BOM.

### B1. Register

- `Model` (provider `VENDOR` / `CUSTOMER`, version, `ModelDigest` algorithm + lowercase hex, `RunsWhere`
  CUSTOMER_ENVIRONMENT / HOSTED) and `ModelUse` (every field of design section 3: product, feature, entity or every
  entity, purpose, `Materiality` HIGH / MEDIUM / LOW with reason, human in the loop, owners, model, configuration
  fingerprint, status PROPOSED / VALIDATED / LIVE / RETIRED, last validation, next review due, whether an overdue
  review refuses).
- `ModelDigest.sha256Of(Path)` (streamed file digest); `ManifestDigests` (optional adapter: `HEAD
  /v2/<name>/manifests/<ref>`, `Docker-Content-Digest`, redirects never followed).
- `ConfigFingerprint.of(json)` = `sha256:` + SHA-256 of `canonical(json)` (keys sorted, no whitespace, shortest
  plain numbers, minimal escapes; a strict parser refuses trailing commas and trailing text).
- Every change is a new version with who, when and the changed field names (`mg_model_history`,
  `mg_model_use_history`), and goes to the app's `ModelGovAudit`; a stale version is `MOD_VERSION_CONFLICT`.
- Values are checked (lengths, names, product code) and secret-looking text is refused (`MOD_VALUE_INVALID`, through
  `SafeText`).

### B2. Validation records and the gate

- `ModelGovernance.setDefaultThreshold` / `setEntityThreshold` (`MOD_THRESHOLD_LOOSER`, `MOD_THRESHOLD_MISSING`),
  `thresholdsInForce`.
- `recordValidation(use, entity, testSetName, testSetVersion, results, ranBy)`: every pass mark in force needs a
  result and every result a pass mark; the pass marks in force are copied into each measure; numbers only;
  insert-only `mg_validation` + `mg_validation_measure`. A pass moves the use to VALIDATED and sets the next review
  (`ModelGovSettings.reviewEvery`, defaults high 90 days, medium 180, low 365).
- `approve(validationId, Approver)`: `MOD_APPROVER_CONFLICT` when the approver holds any forbidden role (checked
  first, so holding both roles is refused), `MOD_APPROVER_ROLE` without an approver role, `MOD_ALREADY_APPROVED`,
  `MOD_VALIDATION_FAILED`; a failed customer model is `MOD_CUSTOMER_MODEL_BLOCKED` for high materiality.
- `accept(validationId, approver, reason)`: only for a failed customer model of a medium use (who, when, the failed
  results as numbers, a reason with no secret-looking text); `MOD_REASON_REQUIRED`, `MOD_ACCEPTANCE_NOT_APPLICABLE`.
  An accepted validation may then be approved.
- `goLive(use, entity, validationId, by)` (only approved, of that use and entity: `MOD_NOT_APPROVED`,
  `MOD_WRONG_USE`); the previous live validation stays as the rollback target; `rollback(use, entity, by)` switches
  back at once (`MOD_NO_ROLLBACK_TARGET`). Both are written with an insert-only `mg_live_history` row and audited.
- `ModelGate.requireLive(use, entity, observedDigest, observedConfigFingerprint)` → `LiveModel`, or `MOD_UNKNOWN`,
  `MOD_RETIRED`, `MOD_NOT_VALIDATED`, `MOD_NOT_APPROVED`, `MOD_NOT_LIVE`, `MOD_DIGEST_MISMATCH`, `MOD_CONFIG_CHANGED`,
  `MOD_REVIEW_OVERDUE` (a warning on the `LiveModel` unless the use refuses it). Every refusal is a
  `ModelGovException` implementing `HasPlatformCode`, with cause and fix in `ModelGovCode.codes.properties`.
- `LiveModel.stamp()` → `ModelStamp` (use, model name, version, digest, validation ID, configuration fingerprint),
  plain data, safe to log.
- **Storage:** `ModelGovStore`; `jdbc.PostgresModelGovStore` (plain JDBC, each change and its history row in one
  transaction, versions checked in the update); SQL `db/platform/modelgov/V1__modelgov.sql` (history table
  `mg_schema_history`, run only by the schema step); `jdbc.ModelGovSchema.verify` (read only; `MG_*` codes); the
  insert-only tables refuse UPDATE, DELETE and TRUNCATE by trigger, even for the schema owner.

### B3. Testkit

`InMemoryModelGovStore` (same rules: keys unique, references checked, versions, insert only), `ModelGovFixtures`,
`RecordingAudit`, `FakeModelServer` (OCI manifest digests on 127.0.0.1, failure and redirect switches).

### Minimal example

```java
ModelGovernance gov = new ModelGovernance(store, audit,
        ModelGovSettings.of(Set.of("MODEL_APPROVER"), Set.of("BUSINESS_ADMIN")), Clock.systemUTC());
Model m = gov.registerModel(Model.create("example-detector", Model.Provider.VENDOR, "1.0",
        ModelDigest.sha256Of(modelFile), Model.RunsWhere.CUSTOMER_ENVIRONMENT), "register-admin");
ModelUse use = gov.registerUse(ModelUse.propose("PRODUCT_A", "example: detection", null, "Finds example items",
        ModelUse.Materiality.MEDIUM, "A person checks each item", "A reviewer confirms each item",
        "vendor owner", "customer owner", m.modelId(), ConfigFingerprint.of(configJson)), "register-admin");
gov.setDefaultThreshold(Threshold.byDefault(use.useId(), "accuracy", new BigDecimal("0.90"),
        Threshold.Direction.HIGHER_IS_BETTER), "register-admin");

ValidationRecord v = gov.recordValidation(use.useId(), entity, "example-set", "3",
        Map.of("accuracy", new BigDecimal("0.95")), "validator-1");
gov.approve(v.validationId(), new Approver("approver-1", Set.of("MODEL_APPROVER")));
gov.goLive(use.useId(), entity, v.validationId(), "release-manager");

// at model start
LiveModel live = new ModelGate(store, Clock.systemUTC())
        .requireLive(use.useId(), entity, ModelDigest.sha256Of(modelFile), ConfigFingerprint.of(configJson));
ModelStamp stamp = live.stamp();   // stored with each AI result
```

### New codes

`MOD_UNKNOWN`, `MOD_VALUE_INVALID`, `MOD_VERSION_CONFLICT`, `MOD_THRESHOLD_LOOSER`, `MOD_THRESHOLD_MISSING`,
`MOD_APPROVER_ROLE`, `MOD_APPROVER_CONFLICT`, `MOD_VALIDATION_FAILED`, `MOD_ALREADY_APPROVED`,
`MOD_CUSTOMER_MODEL_BLOCKED`, `MOD_ACCEPTANCE_NOT_APPLICABLE`, `MOD_REASON_REQUIRED`, `MOD_NOT_VALIDATED`,
`MOD_NOT_APPROVED`, `MOD_NOT_LIVE`, `MOD_DIGEST_MISMATCH`, `MOD_CONFIG_CHANGED`, `MOD_RETIRED`, `MOD_REVIEW_OVERDUE`,
`MOD_NO_ROLLBACK_TARGET`, `MOD_WRONG_USE`; schema check `MG_TABLES_MISSING`, `MG_VERSION_UNKNOWN`,
`MG_VERSION_OLDER`, `MG_VERSION_NEWER`, `MG_TABLES_INCOMPLETE`, `MG_PRIVILEGES_MISSING`, `MG_CHECK_FAILED`. All in
the regenerated catalogue `docs/errors/platform-error-codes.md` / `.json`.

## Tests asked for → where

All in `platform-model-governance-testkit/src/test`. `ModelGovContract` (19 tests) runs once per store:
`InMemoryModelGovTest` and `jdbc.PostgresModelGovTest` (testcontainers Postgres 16, DML-only app user after
`ModelGovSchema.verify`).

- Every refusal code → the contract's `@AfterAll` fails the class unless every `ModelGovCode` was seen as a
  refusal; `ModelGovSchemaTest.verifyNamesWhatIsWrong` for the `MG_*` codes (except `MG_CHECK_FAILED`, a database
  error during the check, and `MG_VERSION_OLDER`, unreachable with one version).
- Separation of duties, both roles at once → `approverRolesAndSeparationOfDuties` (also for acceptance in
  `failedCustomerModelForMediumOnlyWithDocumentedAcceptance`).
- Acceptance only for medium → `failedCustomerModelBlockedForHigh`, `failedCustomerModelForMediumOnlyWithDocumentedAcceptance`,
  `acceptanceNotForLowOrPassed`, `failedVendorValidationNeverApproved`.
- Rollback → `rollbackSwitchesBackAtOnce`.
- Digest or configuration change refused at the gate → `digestChangeRefusedAtTheGate`, `configurationChangeRefusedAtTheGate`.
- Review overdue as a warning and as a refusal → `reviewOverdueWarnsOrRefuses`.
- Insert-only validation record, even the owner can't change it → `ModelGovSchemaTest.recordsAreInsertOnlyEvenForTheOwner`
  (UPDATE / DELETE as the app user and as the owner, TRUNCATE as the owner).
- History and audit calls → `historyAndAuditOfEveryChange`, plus the live history in `rollbackSwitchesBackAtOnce`.
- No content in any record or log → `recordsAndRefusalsHoldNoContent` (the configuration text never appears in
  records, refusals, audit lines or the stamp; only its fingerprint); secret-looking reason refused. The module
  writes no log lines.
- DML-only user → `PostgresModelGovTest` (all rules as that user), `ModelGovSchemaTest.theAppUserCannotChangeTheTables`
  and `verifyPassesForTheAppUserAndCreatesNothing`.
- In-memory and Postgres behave the same → the shared contract, and `ModelGovSchemaTest.bothStoresGiveBackTheSameRows`.
- Digests → `DigestsTest` (file SHA-256, text form, manifest digest from `FakeModelServer` incl. 404, 500 and a
  redirect not followed, canonical JSON).
- Nothing skipped on CI → 1265 run, 0 skipped.

## Files changed, commits

- `platform-model-governance/` (new), `platform-model-governance-testkit/` (new), root `pom.xml`, `platform-bom/pom.xml`,
  `platform-error-catalogue/pom.xml`, `docs/errors/*`, `docs/design/model-governance.md` (section 9 "As built"),
  `CLAUDE.md` (two rows).
- `platform-errors` `ReferenceId` + `ErrorsTest`, `docs/design/error-codes.md` (the CI fix above).
- Commits on main (rebase merge of #23): the feature commit and `fix: ReferenceId never repeats within a process`.

## Decisions I had to make

1. **Forbidden role checked before the approver role**, so a person holding both gets `MOD_APPROVER_CONFLICT` (the
   rule that matters), and someone with only the Business Admin role gets it too.
2. **Acceptance needs an eligible approver** (an approver role and no forbidden role), the same check as approval.
   The task says who accepts is recorded but not which role; this is the stricter reading.
3. **Validations are per entity** even for a use scoped to every entity (`entity` null): the gate, go-live and
   rollback are per use and entity, as the task says.
4. **Review due date** is the validation date (UTC) plus the materiality's period; overdue from the day after.
5. **Thresholds** are replaced in place (`mg_threshold` is not insert-only); each change goes to the audit. The
   pass marks that matter for evidence are copied into each validation record, which is insert-only.
6. No install guide yet: design section 8 places it in P034. The schema step and grants are in the SQL file's header
   and `ModelGovSchema`'s Javadoc.
7. Fixed the `ReferenceId` defect found by CI in this PR rather than a separate task, since it blocked the merge
   and was small.

## Not done, and why

Nothing in P033's scope. Monitoring intake, drift and re-validation events, the documentation pack, Spring wiring,
the install guide and the release are P034.

## Questions

None that change what P034 builds.
