# platform-core 0.5.0

AI model governance (tasks P033 and P034): a register of every model use with its exact model digest; a change gate
(validation against agreed pass marks, approval by a Model approver who is never also the Business Admin, the
customer-model rule, go live and rollback); a check at model start that refuses a model or configuration that was
not validated; result stamps; monitoring with drift and "needs review"; re-validation dates; and a fingerprinted
documentation pack (JSON and PDF) for the customer's model-risk team. Every module has the same version. Import the
BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.5.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Modules

All modules of 0.4.0, plus:

| Module | What it is |
|---|---|
| `platform-model-governance` (**new**) | Register (`Model`, `ModelUse`, `ModelDigest.sha256Of`, optional `ManifestDigests` for a model server's OCI manifest digest, `ConfigFingerprint`), change gate `ModelGovernance` (pass marks, insert-only validation records, `approve`, `accept`, `goLive`, `rollback`), `ModelGate.requireLive` at model start, `LiveModel.stamp()` → `ModelStamp`; monitoring `ModelMeasures` (readings, drift, actions, retention purge), re-validation `ModelReviews`, documentation pack `ModelPack` (JSON `platform-model-pack/1` + PDF), `ModelEvents`, `PackTexts`; Postgres store `jdbc.PostgresModelGovStore`, startup check `jdbc.ModelGovSchema.verify`. Depends on platform-errors and Apache PDFBox 3.0.8. |
| `platform-model-governance-testkit` (**new**) | `InMemoryModelGovStore` (same rules as Postgres), `ModelGovFixtures`, `RecordingAudit`, `FakeModelServer`. Test scope only. |
| `platform-model-governance-spring` (**new**) | `platform.model-governance.*` (approver and forbidden roles, review intervals, reading retention, worker), the schema check, the Postgres store on the app's DataSource, all the beans above, `ModelGovWorker` (review notices, purge), the monitoring check `model-governance` (with platform-monitoring). |
| `platform-monitoring-spring` | The database check also verifies the `mg_` tables when model governance is in use. |
| `platform-errors` | `ReferenceId.next()` never repeats a reference within a process (before, many references in the same tenth of a second could collide). |

## Design docs implemented

`docs/design/model-governance.md` (owner-approved 2026-11-22), sections 3 to 6 and 8; "As built" sections 9 and 10.
Install guide: `docs/install/model-governance.md`.

## The documentation pack format

`platform-model-pack/1`, JSON Schema `schema/platform-model-pack-1.schema.json` (in the platform-model-governance
jar). Top-level members: `format`, `product`, `entity`, `period`, `register` (item 1), `uses` (2), `validations`
(3), `monitoring` (4: `thresholds`, `readings`, `drifts`), `changes` (5: `models`, `uses`, `live`), `thirdParty` (6),
`dataStatement` (7). Canonical form (keys sorted, no whitespace, lists in a fixed order, no export time); the
fingerprint is `sha256:` plus the SHA-256 of the JSON's UTF-8 bytes and is printed in the PDF. No document content or
values: measures are numbers, configurations only fingerprints.

## New codes

Model governance (`ModelGovCode`): `MOD_UNKNOWN`, `MOD_VALUE_INVALID`, `MOD_VERSION_CONFLICT`,
`MOD_THRESHOLD_LOOSER`, `MOD_THRESHOLD_MISSING`, `MOD_APPROVER_ROLE`, `MOD_APPROVER_CONFLICT`,
`MOD_VALIDATION_FAILED`, `MOD_ALREADY_APPROVED`, `MOD_CUSTOMER_MODEL_BLOCKED`, `MOD_ACCEPTANCE_NOT_APPLICABLE`,
`MOD_REASON_REQUIRED`, `MOD_NOT_VALIDATED`, `MOD_NOT_APPROVED`, `MOD_NOT_LIVE`, `MOD_DIGEST_MISMATCH`,
`MOD_CONFIG_CHANGED`, `MOD_RETIRED`, `MOD_REVIEW_OVERDUE`, `MOD_NO_ROLLBACK_TARGET`, `MOD_WRONG_USE`, `MOD_DRIFT`,
`MOD_DRIFT_UNHANDLED`, `MOD_REVIEW_DUE`, `MOD_ACTION_ALREADY_RECORDED`. Schema check
(`ModelGovSchemaException.Problem`): `MG_TABLES_MISSING`, `MG_VERSION_UNKNOWN`, `MG_VERSION_OLDER`,
`MG_VERSION_NEWER`, `MG_TABLES_INCOMPLETE`, `MG_PRIVILEGES_MISSING`, `MG_CHECK_FAILED`. All in
`docs/errors/platform-error-codes.md` / `.json` (648 codes).

## Breaking changes

None: everything is new. platform-monitoring-spring's database check verifies one more schema, only when the app
uses model governance.

## Database schemas

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 4 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 2 |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | the app's (location added to its own run) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |
| platform-model-governance (**new**) | `db/platform/modelgov` | `mg_schema_history` | 2 (`V1__modelgov.sql`, `V2__modelgov_monitoring.sql`) |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
