# platform-model-governance — AI model governance (owner-approved design, 2026-11-22)

**Status:** design **approved by the owner 2026-11-22** (answers in section 7). Required **before the first customer**. Built in **P033 and P034**, after P032. Generic: no product's name or behaviour in code; the products named below are examples of users of this module.

## 1. Why

- US banks manage model risk under **SR 26-2** (Federal Reserve, OCC Bulletin 2026-13, FDIC; 2026-04-17), which **replaced SR 11-7**. It keeps the same pillars: sound development, documentation, independent validation, ongoing monitoring, governance, and oversight of **third-party (vendor) models**. It is principles-based and scaled to how much a model matters (materiality).
- **Generative and agentic AI are outside its formal scope**, but bank customers will review an app's AI as a **vendor model**. So the module follows its principles: a model-risk team must be able to approve the AI, and keep approving it after every change.
- Example uses: a redaction app's detection, document-type recognition, independent checking pass and vision page routing; a document vault's question answering. The customer may bring **their own model**; otherwise the app's default local model runs in their environment.

## 2. Split of duties

| Shared (platform-core) | Each app |
|---|---|
| **Model register**: what is in use, where, why, how much it matters | Registers its models and uses |
| **Change gate**: nothing goes live without a recorded validation that meets the agreed thresholds | Its **test sets**, **pass marks** and **measures** |
| **Ongoing monitoring hooks**: the app reports measures; drift past a threshold raises an event | Which signals it reports (for example reviewer corrections, wrong-answer flags) |
| **Documentation pack export** for the customer's model-risk team | Its model descriptions, known limits and test results; its screens |

## 3. The model register

One entry per **model use** (the same model used for two purposes = two uses):

| Field | Example |
|---|---|
| Model | name, provider (the app's or the customer's), version, **exact digest** (for example the model file's SHA-256 or the model server's manifest digest), where it runs (customer environment / hosted) |
| Use | product, feature (for example "redaction: detection"), entity scope |
| Purpose | what it decides or suggests, in plain words |
| **Materiality** | high / medium / low, with the reason (what happens if it is wrong, who relies on it, whether a person checks it) |
| Human in the loop | how a person reviews or can overrule |
| Owner | at the vendor (for a default model) and at the customer (the **Model approver** role, section 4) |
| Status | proposed / validated / live / retired; date of last validation; next review due |
| Evidence links | validation records, monitoring history |

- **Customer-supplied models** are registered the same way; the customer owns their validation, but the change gate still runs the app's test set against them and records the result (third-party oversight).
- **No training on customer documents.** A model reads data when it works and keeps nothing.

## 4. The change gate

- **No model or version goes live without a recorded validation** that meets the agreed thresholds. "Live" = used for the customer's real documents or users.
- A validation record holds: model, version and digest, use, the configuration fingerprint (prompt and settings), test set (name and version), measures and results, thresholds and pass/fail, who ran it, who approved it, date. Never document content or values.
- **Thresholds are agreed**: the app supplies default pass marks per use; the customer may set stricter ones (never looser) per entity. The thresholds in force are part of the record.
- **Approval:** a pass does not switch a model on by itself; the customer's **Model approver** approves, audited. It is a separate role that the **Business Admin can never also hold**; where the customer has no model-risk team, a **compliance officer** may hold it. A fail can't be approved (except as in "customer-supplied models" below).
- **Digest pinning:** the validation records the model's exact digest. **Apps refuse to run a model whose digest doesn't match the validated one** (specific code, IT Admin alerted), so a changed model pull or a swapped file can't get past the gate.
- **Customer-supplied model that fails the test set:** **blocked for high-materiality uses**; for medium-materiality uses **allowed with the customer's documented acceptance** (who accepted, when, which results), kept with the validation record and shown in the documentation pack.
- **Rollback:** the previous validated version can be switched back at once, audited.
- Every result the AI produces records the **model, version and digest** that produced it, so any past output traces to a validated model.
- Applies to: a new model, a new version or digest, **a change to a prompt or setting in the app's own release** (it goes through the gate like a model change), and a change of where it runs.
- **Independent validation:** the vendor's default models are validated by an **independent outside reviewer** (someone who didn't build them) **before the first customer**; the review is part of the documentation pack.

## 5. Ongoing monitoring hooks

- The app reports measures per use and entity over time (for example reviewer correction rate, missed-item reports, confidence distribution, routing share, time per page; wrong-answer flags, "not in the documents" rate, refusal rate, response time).
- Each measure has a threshold (app default, customer stricter); drift past it raises an event for the app's notifications to the model owner, and the register shows the use as "needs review".
- **Periodic re-validation:** the test set re-runs on a schedule set per materiality (SR 26-2 drops the fixed annual cadence; frequency follows the model's purpose, changes and data), and after any change.
- No document content or values in any measure.

## 6. Documentation pack export

For the customer's model-risk team, per product and entity, exportable on demand (PDF plus machine-readable JSON), fingerprinted:

1. Model register entries in scope.
2. Per use: purpose, design summary, inputs and outputs, human in the loop, guardrails, **known limits**.
3. Validation history: every record, thresholds, results, approvals, documented acceptances.
4. Monitoring: measures, thresholds, drift events and what was done.
5. Change log: versions, digests, dates, rollbacks.
6. Third-party statement: which models are the vendor's, which the customer's, who validates what; the independent review.
7. Data statement: no training on customer documents; where data goes.

## 7. Owner decisions (2026-11-22)

1. **Model approver:** a separate role the Business Admin can't also hold; a compliance officer may hold it where the customer has no model-risk team.
2. **Materiality** (app defaults): redaction detection, vision and the independent checking pass = **high**; question answering for call-center and self-service = **medium**; question answering for the legal owner = **high**.
3. **Independent validation:** yes, an independent outside reviewer validates the default models before the first customer.
4. **Customer model failing the test set:** blocked for high-materiality uses; allowed with documented acceptance for medium.
5. **Screens:** each app's admin area holds the model screens (register, validations, monitoring, approvals, export); the Platform Console shows status only, for hosted.
6. **EU AI Act:** checked per customer, later.
7. **Additions:** the register records each model's exact digest and apps refuse a model whose digest doesn't match; a prompt or setting change in the app's own release goes through the gate; every AI result records model, version and digest.

## 8. Build

`platform-model-governance` (+ `-testkit`, `-spring`), own schema run `db/platform/modelgov` (tables `mg_*`):
- **P033:** register, validation records and the change gate (Model approver with the separation rule, documented acceptance for medium-materiality customer models, rollback), digest and configuration-fingerprint check at model start, result stamping.
- **P034:** monitoring intake and thresholds, drift and re-validation-due events, documentation pack export (PDF + JSON, fingerprinted), Spring wiring, install guide; release.

Each app then adds its test sets, pass marks, measures and admin screens in its own repo.

## 9. As built (P033)

Package `com.documentplatform.modelgov`, plain Java, depends only on platform-errors.

- **Register:** `Model` (provider `VENDOR` / `CUSTOMER`, version, `ModelDigest` algorithm + lowercase hex, `RunsWhere`)
  and `ModelUse` (product, feature, entity or null = every entity, purpose, `Materiality` with reason, human in the
  loop, owners, model, configuration fingerprint, `Status`, last validation, next review due, whether an overdue
  review refuses). `ModelDigest.sha256Of(Path)` hashes a model file; `ManifestDigests` reads a model server's OCI
  manifest digest (`HEAD /v2/<name>/manifests/<ref>`, `Docker-Content-Digest`, no redirects). `ConfigFingerprint.of`
  gives `sha256:` of the canonical JSON (keys sorted, no whitespace, shortest numbers) of the app's prompt and
  settings. Every change is a new version with who, when and the changed field names (`mg_model_history`,
  `mg_model_use_history`) and goes to the app's `ModelGovAudit`.
- **Change gate (`ModelGovernance`):** default pass marks per use and stricter ones per entity (`MOD_THRESHOLD_LOOSER`,
  `MOD_THRESHOLD_MISSING`); `recordValidation` copies the pass marks in force into the record (numbers only) and moves
  the review date on (`ModelGovSettings.reviewEvery`, defaults high 90 days, medium 180, low 365);
  `approve(validationId, Approver)` with the approver and forbidden roles named by the app's `ModelGovSettings`
  (`MOD_APPROVER_ROLE`, `MOD_APPROVER_CONFLICT`, `MOD_VALIDATION_FAILED`, `MOD_ALREADY_APPROVED`); a failed customer
  model is `MOD_CUSTOMER_MODEL_BLOCKED` for high materiality and needs `accept(validationId, approver, reason)` for
  medium (`MOD_REASON_REQUIRED`, `MOD_ACCEPTANCE_NOT_APPLICABLE` otherwise); `goLive` keeps the previous live
  validation as the rollback target, `rollback` switches back at once (`MOD_NO_ROLLBACK_TARGET`).
- **Gate at model start (`ModelGate.requireLive`):** `MOD_RETIRED`, `MOD_NOT_VALIDATED`, `MOD_NOT_APPROVED`,
  `MOD_NOT_LIVE`, `MOD_DIGEST_MISMATCH`, `MOD_CONFIG_CHANGED`, `MOD_REVIEW_OVERDUE` (a warning on the `LiveModel`
  unless the use refuses it). `LiveModel.stamp()` gives the `ModelStamp` stored with each result.
- **Storage:** `ModelGovStore`; `jdbc.PostgresModelGovStore` on the `mg_` tables (SQL `db/platform/modelgov`, history
  table `mg_schema_history`, run only by the schema step, `jdbc.ModelGovSchema.verify` at startup, DML-only user;
  `mg_validation`, `mg_validation_measure`, `mg_approval`, `mg_acceptance`, `mg_live_history` refuse UPDATE, DELETE
  and TRUNCATE by trigger, even for the owner); `InMemoryModelGovStore` in the testkit with the same rules.
