# Model governance — install guide for IT and the model-risk team

The app's AI features run only models that passed a recorded validation and were approved by your **Model
approver**. This page says what that check does, who approves, what a refusal at start means and how to fix it, how
re-validation and monitoring work, and how to export the documentation pack for your model-risk team. Everything is
kept in tables of the app's own database, prefixed `mg_`.

Nothing in these tables holds document content or field values: measures are numbers, a prompt or setting is kept
only as its fingerprint (a SHA-256 hash), and free-text notes are refused when they look like a password or token.

## 1. Database

The tables are created only by the app's schema step, never at app startup.

- **Schema step:** a separate Flyway run with location `classpath:db/platform/modelgov` and history table
  `mg_schema_history`, run as the schema owner, in the same database schema the app uses. Files today:
  `V1__modelgov.sql` (register, validations, approvals, live versions), `V2__modelgov_monitoring.sql`
  (monitoring, re-validation) and `V3__modelgov_purge_guard.sql` (the retention purge as the schema owner, since
  0.6.0) and
  `V4__modelgov_validation_location.sql` (where the model ran, recorded with each validation; since 0.6.0).
- **Runtime user:** data rights only:

  ```sql
  grant select, insert, update, delete on mg_model, mg_model_history, mg_model_use, mg_model_use_history,
      mg_threshold, mg_live, mg_monitor_threshold to app_runtime;
  grant select, insert on mg_validation, mg_validation_measure, mg_approval, mg_acceptance, mg_live_history,
      mg_drift, mg_drift_action, mg_review_notice to app_runtime;
  grant select, insert on mg_measure to app_runtime;
  grant execute on function mg_purge_measures(timestamptz) to app_runtime;
  grant select on mg_schema_version to app_runtime;
  ```

  The validation records, approvals, acceptances, live switches, drift events, their actions and review notices are
  **insert only**: the database refuses to change or delete them, even for the schema owner. Monitoring readings
  (`mg_measure`) are removed only by the retention purge, through the function `mg_purge_measures`, which runs as
  the schema owner and never removes readings younger than 30 days of the database's time; the runtime user cannot
  delete them itself (it needs no DELETE on `mg_measure`; a DELETE granted before 0.6.0 may be revoked).
- **At startup** the app checks the tables (version, completeness, rights) and stops with a code naming what is
  wrong (`MG_TABLES_MISSING`, `MG_VERSION_OLDER`, `MG_VERSION_NEWER`, `MG_TABLES_INCOMPLETE`,
  `MG_PRIVILEGES_MISSING`). The fix is in `docs/errors/platform-error-codes.md`.

## 2. Settings

```yaml
platform:
  model-governance:
    approver-roles: [MODEL_APPROVER, COMPLIANCE_OFFICER]   # who may approve (required)
    forbidden-roles: [BUSINESS_ADMIN]                       # roles an approver may never also hold (required: start-up
                                                            # refuses an empty list)
    review-every:                                           # how long a validation lasts
      high: 90d
      medium: 180d
      low: 365d
    measure-retention: 400d                                 # how long monitoring readings are kept (at least 30d)
    worker:
      enabled: true                                         # review notices and the purge, in the background
      every: 1h
```

The role names are those of your sign-in system's role mapping; the app's documentation lists them. Every wrong
setting is listed at startup at once.

## 3. The change gate

- **Nothing goes live without a validation.** A model use (for example "detection" in one product) is validated by
  running the app's test set against the exact model and configuration. The record keeps the model's name, version
  and **digest** (the exact file or download), the configuration fingerprint, the test set, every measure with the
  pass mark in force, pass or fail, who ran it and when.
- **Pass marks:** the app sets the default ones; you may set stricter ones per entity, never looser.
- **Approval:** a pass does not switch a model on. A person with the **Model approver** role approves it. That person
  may **never also hold the Business Admin role**: the app refuses (`MOD_APPROVER_CONFLICT`), even when one person
  holds both. Where you have no model-risk team, configure the compliance officer's role as an approver role.
- **Your own models:** a model you supply that fails the test set is **blocked** for high-materiality uses
  (`MOD_CUSTOMER_MODEL_BLOCKED`). For medium-materiality uses it can go ahead only with a **documented acceptance**:
  who accepted, when, which results, and why.
- **Go live and roll back:** an approved validation is switched live per entity; the previous one stays ready, and
  a rollback switches back at once. Both are recorded.
- **Every AI result** is stored with the model, version, digest, validation and configuration fingerprint that
  produced it.

## 4. A refusal at start, and how to fix it

When the app starts (or reloads) a model, it checks it against what was approved. If anything differs, the AI
feature does not run, and IT gets a notice with one of these codes:

| Code | What it means | What to do |
|---|---|---|
| `MOD_NOT_VALIDATED` | No validation was recorded for this use and entity. | Run the test set and record it. |
| `MOD_NOT_APPROVED` | The validation is not approved. | Have the Model approver approve it. |
| `MOD_NOT_LIVE` | It is approved but not switched live. | Switch it live on the model screens. |
| `MOD_DIGEST_MISMATCH` | The model file or download is not the one validated (a new pull, a swapped file). | Put back the validated model, or validate and approve the new one. |
| `MOD_CONFIG_CHANGED` | The prompt or settings changed without a validation. | Validate and approve the new configuration, or restore the old one. |
| `MOD_LOCATION_CHANGED` | Where the model runs changed since its live validation (every materiality). | Validate the use where the model now runs, approve it and make it live; or move the model back. |
| `MOD_RETIRED` | The use was retired. | Register it again if it is still needed. |
| `MOD_REVIEW_OVERDUE` | The periodic review is past due. A warning by default; a refusal where you chose so for the use. | Re-run the test set and have it approved. |

Each code's cause and fix are in `docs/errors/platform-error-codes.md`.

## 5. Re-validation

- Each use's **next review** is kept **per entity**: that entity's live validation plus the use's interval: by default high 90 days, medium 180, low 365
  (settings above), or the use's own interval.
- **Any change** of the model, its version or digest, the configuration, or where the model runs makes the use **due
  at once** for each entity validated before the change. A use is listed and noticed by its earliest entity.
- The app sends a notice (`MOD_REVIEW_DUE`) 30 days and 7 days before the date, and `MOD_REVIEW_OVERDUE` on the date;
  each once.

## 6. Monitoring

- The app reports measures per use and entity over time (for example the share of items a reviewer corrected),
  numbers only. Each measure has a pass mark (the app's default, or your stricter one), a direction and a minimum
  number of items, so a quiet day does not count.
- A reading past its pass mark raises a **drift** notice (`MOD_DRIFT`) to the model owner, and the use shows **needs
  review** until someone records what was done (a short note, who, when).
- Readings are kept for `measure-retention` (default 400 days); drift events and their actions are kept.
- With platform-monitoring, the check `model-governance` is **DOWN** when the tables are wrong, and **HAVING
  PROBLEMS** when a drift has no action yet (`MOD_DRIFT_UNHANDLED`) or a review is overdue (`MOD_REVIEW_OVERDUE`).

## 7. The documentation pack

The app exports, per product and entity over a period, the pack for your model-risk team: **JSON** (format
`platform-model-pack/1`, schema `schema/platform-model-pack-1.schema.json` in the module) and **PDF**. It holds:

1. the model register entries;
2. per use: purpose, design summary, inputs and outputs, human in the loop, guardrails and known limits;
3. the validation history with pass marks, results, approvals and acceptances;
4. monitoring: pass marks, readings, drifts and what was done;
5. the change log: versions, digests, configuration fingerprints, go-lives and rollbacks;
6. the third-party statement (which models are the vendor's and which are yours, who validates what), with the
   independent review as a document reference;
7. the data statement (no training on your documents; where data goes).

The pack is **fingerprinted**: the PDF prints the SHA-256 of the JSON. The same data always gives the same JSON, so
anyone can check that a PDF and a JSON belong together, and that a pack was not changed.
