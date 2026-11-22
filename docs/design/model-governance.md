# platform-model-governance — AI model governance (DESIGN ONLY, for owner review before any build)

**Status:** design draft, 2026-11-22, Shared capabilities thread, at the LexaVault thread's (master) request. **Nothing is built until the owner has reviewed this.** Required **before the first customer**. Project doc: `Model-Governance-Design.md`. Built alongside the integration work (P023+), after review.

## 1. Why

- US banks manage model risk under **SR 26-2** (Federal Reserve, OCC Bulletin 2026-13, FDIC; 2026-04-17), which **replaced SR 11-7**. It keeps the same pillars: sound development, documentation, independent validation, ongoing monitoring, governance, and oversight of **third-party (vendor) models**. It is principles-based and scaled to how much a model matters (materiality).
- **Generative and agentic AI are outside its formal scope**, but bank customers will review our AI as a **vendor model**. So we design to its principles now: a model-risk team must be able to approve our AI, and keep approving it after every change.
- Our AI: LexaVault's redaction AI (detection, document-type recognition, confidence-based review, independent checking pass, handwriting/signatures/ID photos) and vision-based redaction with page routing; Vero's AI features (Ask Vero, flagging what LexaVault missed). The customer may bring **their own model**; otherwise our default local model runs in their environment.

## 2. Split of duties

| Shared (platform-core) | Each app |
|---|---|
| **Model register**: what is in use, where, why, how much it matters | Registers its models and uses |
| **Change gate**: nothing goes live without a recorded validation that meets the agreed thresholds | Its **test sets**, **pass marks** and **measures** (LexaVault: redaction and vision; benchmark 0033 is its first validation. Vero: its AI features) |
| **Ongoing monitoring hooks**: the app reports measures; drift past a threshold alerts | Which signals it reports (e.g. reviewer corrections, wrong-answer flags) |
| **Documentation pack export** for the customer's model-risk team | Its model descriptions, known limits and test results |

## 3. The model register

One entry per **model use** (the same model used for two purposes = two uses):

| Field | Example |
|---|---|
| Model | name, provider (ours or the customer's), version, where it runs (customer environment / hosted) |
| Use | product, feature (e.g. "redaction: detection", "vision: page routing", "Ask Vero: call-center tier"), entity scope |
| Purpose | what it decides or suggests, in plain words |
| **Materiality** | high / medium / low, with the reason (what happens if it is wrong, who relies on it, whether a person checks it) |
| Human in the loop | how a person reviews or can overrule (e.g. LexaVault review queue; Ask Vero informs, never decides) |
| Owner | at us (for our default model) and at the customer (named role) |
| Status | proposed / validated / live / retired; date of last validation; next review due |
| Evidence links | validation records, monitoring history |

- **Customer-supplied models** are registered the same way; the customer owns their validation, but our change gate still runs our test set against them and records the result (third-party oversight).
- **No training on customer documents** (decided earlier). A model reads data when it works and keeps nothing.

## 4. The change gate

- **No model or version goes live without a recorded validation** that meets the agreed thresholds. "Live" = used for the customer's real documents or users.
- A validation record holds: model and version, use, test set (name and version), measures and results, thresholds and pass/fail, who ran it, who approved it, date. Never document content or values.
- **Thresholds are agreed**: the app supplies default pass marks per use; the customer may set stricter ones (never looser) per entity. The thresholds in force are part of the record.
- **Approval:** a pass does not switch a model on by itself; the customer's approver (Business Admin, or a role the customer names) approves, audited. A fail can't be approved.
- **Rollback:** the previous validated version can be switched back at once, audited.
- Every result the AI produces records the **model and version** that produced it, so any past output traces to a validated model.
- Applies to: a new model, a new version, a changed prompt or configuration that changes behaviour, and a change of where it runs.

## 5. Ongoing monitoring hooks

- The app reports measures per use and entity over time (e.g. LexaVault: reviewer correction rate, missed-item reports, confidence distribution, vision routing share, time per page; Vero: wrong-answer flags, "not in the documents" rate, refusal rate, response time).
- Each measure has a threshold (app default, customer stricter); drift past it raises a notification (shared notifications catalogue) to the model owner, and the register shows the use as "needs review".
- **Periodic re-validation:** the test set re-runs on a schedule set per materiality (SR 26-2 drops the fixed annual cadence; frequency follows the model's purpose, changes and data), and after any change.
- No document content or values in any measure.

## 6. Documentation pack export

For the customer's model-risk team, per product and entity, exportable on demand (PDF plus machine-readable JSON), fingerprinted like the compliance report:

1. Model register entries in scope.
2. Per use: purpose, design summary (how it works, e.g. retrieval then answer), inputs and outputs, human in the loop, guardrails, **known limits**.
3. Validation history: every record, thresholds, results, approvals.
4. Monitoring: measures, thresholds, drift events and what was done.
5. Change log: versions, dates, rollbacks.
6. Third-party statement: which models are ours, which the customer's, who validates what.
7. Data statement: no training on customer documents; where data goes (never outside the customer's environment, or the one they chose for hosted).

## 7. Open questions for owner review

1. **Who approves a model going live at the customer:** the Business Admin, or a separate "Model approver" role the customer names? *Recommendation:* a separate role, held by someone from the customer's model-risk or compliance team; the Business Admin can't hold both (like other separation rules).
2. **Materiality defaults:** *Recommendation:* redaction detection and vision routing = high (a miss exposes sensitive data; review reduces but doesn't remove it); Ask Vero call-center and self-service tiers = medium (informs people, never decides; wrong answers to consumers are a UDAAP risk); Ask Vero legal-owner tier = high.
3. **Validation by whom:** our side runs the change gate; is an **independent** validation (someone who didn't build the model, as SR 26-2 expects) needed before the first customer? *Recommendation:* yes for our default models: an external reviewer for the first release, documented in the pack.
4. **Customer-supplied model fails our test set:** block it, or allow with the customer's documented acceptance? *Recommendation:* block for high-materiality uses; allow with documented acceptance for medium.
5. **Where it lives in the screens:** a "Models" page in each app (register, validations, monitoring, export), or in the Platform Console too for hosted? *Recommendation:* each app's admin area; the console shows status only for hosted.
6. **EU AI Act** for future European customers: check per customer (high-risk deadlines moved by the 2026 Digital Omnibus).

## 8. Build (after review)

Shared module `platform-model-governance` (+ -testkit, -spring): register, validation records and the gate, monitoring intake and thresholds, notification events, pack export; Postgres storage via the schema step. Tasks are written after the owner's review, alongside P023+.
