# Integration standards and operations — requirements (owner-approved, not yet designed)

Recorded 2026-11-21 by the Shared capabilities thread, from an app capabilities thread's list "Decisions not yet captured in the repos" (items 11–16 and 20). Project docs: `Shared-Capabilities.md` capabilities 4 and 5 and section 6, `Redaction-Capabilities.md`. Each item gets its own design before it is built. Order: after case fields (definitions, then value storage).

## Round 1 — needed for the first app-to-vault delivery (a redaction app delivering to a document vault)

| # | Requirement | Shared part (platform-core) | App part |
|---|---|---|---|
| 11 | **Virus scan on intake, optional (owner, 2026-11-21).** On-premises: used only if the customer provides a scanner; we configure it to their scanner; if they provide none, scanning is off. When on: every incoming file is scanned before processing; infected files are quarantined and never processed; the IT Admin is alerted. (Recommendation to confirm: always on for our hosted service.) | Scanner interface + adapters (e.g. ICAP / ClamAV-style), quarantine rule, alert event, specific failure codes | Call it at intake |
| 12 | **Status back to the sender:** a status API ("where is case X?") and an optional callback (done or failed). | The **callback**: signed, retried, idempotent (reusing the notifications outbox pattern) | The status API endpoint and its data |
| 13 | **Integration credentials:** watch folders and file locations use a customer service account; APIs accept mutual TLS (customer client certificate) as well as service-account credentials; one credential per integration per entity; rotation without downtime; expiry warnings to the IT Admin. At the time, the first app refused mTLS for delivery. | Credential model, mTLS support, rotation with an overlap window, expiry warning events | Use it in each integration |
| 14 | **Integrity check on delivery** (same checksum idea as intake SHA-256). | Part of the app-to-vault delivery contract (with the case-field delivery format) | Send and verify |

## Round 2 — promised in the contract checklist

| # | Requirement | Shared part | App part |
|---|---|---|---|
| 15 | **Monitoring hooks:** health check endpoint; logs and metrics for the customer's tools (Splunk, Datadog); **diagnostic bundle** the customer can send us, with no document data. Detail: `Shared-Capabilities.md` section 6 (dependency self-checks with specific causes, failure reasons, reference IDs). | Health/self-check framework, metrics naming, log format, diagnostic bundle builder | Register its dependencies and checks |
| 16 | **API versioning and documentation:** published API specification and a test entity for customer developers. `/v1` exists. | A common standard: versioning rules, error format (stable codes), request IDs | Each app's own specification |

## Identity storage addition

| # | Requirement | Shared part |
|---|---|---|
| 20 | **Per-entity time zone** for off-hours migration, reports and retention timers. Today notifications holds one time zone per customer. Entities can be separate companies in different places. **Built in P020** (`docs/install/identity.md` section 5). | `idn_entity.time_zone`, defaulting to the customer's; notifications and the apps read the entity's zone |

## Already captured elsewhere (no action here)

Security testing, disaster recovery, upgrade and rollback: project docs `Redaction-Capabilities.md` sections 11–12, `Business-Readiness-Checklist.md` 4.3 and 5.5, `First-Customer-Readiness.md` 16, `First-Install-Success-Criteria.md` 13 and 15.
