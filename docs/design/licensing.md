# platform-licensing and platform-setup — design (agreed 2026-11-21)

Source of truth: the owner's Shared capabilities thread (project doc `Licensing-Design.md`). This is the copy coding sessions read. Do not change the rules without the owner. **No prices anywhere in this repo**: the code only knows "this licence allows X".

## 1. The licence

- **One signed licence per customer** (tenant), not per entity. It lists the products bought and **entity slots**; each slot has its own ID and its own allowance.
- **Contents:** customer, tenant ID, products (product codes, not hard-coded app names in shared logic), entity slots (ID + allowance each), kind (install or production), start and expiry dates, deployment (hosted or on-prem), signing key ID.
  - Allowance types the model must support: pages per month; storage; concurrent seats; named seats. Each product uses the types it needs.
- **Signing:** Ed25519. Private key only in the secret store (never in the repo, never exported); apps ship the public keys. Each licence names its key ID so keys can rotate without breaking old licences.
- **Entity slots:** the licence pre-issues slot IDs, unique across all customers. The customer's Business Admin activates and names a slot in the app. IDs always come from us.
- **Adding entities or changing allowances:** a new licence version.
- **Install licence:** short (30–60 days). Usage under an install licence is recorded but marked **not billable**.

## 2. Where the licence lives

- The setup command, or the licence command for renewals, stores the signed licence **in the database**; the app reads it from there.
- The signature is verified every time it is read; an edited row is detected.
- Renewal takes effect without a restart.

## 3. Licence states

| State | App starts? | Intake | Viewing and export |
|---|---|---|---|
| Valid | Yes | Yes | Yes |
| Expiring: warnings 60, 30, 7, 1 days before | Yes | Yes | Yes |
| Expired, **30-day grace period** | **Yes** | Yes, with daily critical warnings | Yes |
| Expired, grace over | **Yes** | Stopped, with a clear message | Yes |
| Missing, forged, tampered, wrong customer, unknown key ID | **No**, specific message and stable code | — | — |

- A restart never takes the app down because of expiry.
- **Clock rollback:** remember the latest time seen; a clock moved backwards beyond a small tolerance is reported as tampering, with a specific message.
- The module reports the state; each app decides what "intake" means and stops it after grace.

## 4. Usage

- **Never stop at a limit.** 80% and 100% warnings and over-limit are reported; work continues; overage is recorded.
- The module provides a **usage ledger**: the app records units at its own meter point (e.g. pages processed, daily peak of concurrent seats, named seats assigned, storage). Records carry entity slot, period, amount, billable flag (false under an install licence or when the app marks it free), and a hash chained to the previous record so edits are detected.
- Usage per entity per month can be read for the app's screens and notifications.
- **Usage file export** for any period, on demand: tamper-evident, no document data, no prices. How often it is sent is a contract term; nothing in the software reacts to a late file.

## 5. Licence-issuing command (our side only)

- Builds and signs a licence from an input file (customer, products, entity slots and allowances, kind, dates, deployment).
- Generates tenant and entity-slot IDs unique across all customers (random, collision-checked against its own issued records).
- Reads the signing key from the secret store; writes a record of every licence issued (no key material in it); every run logged.
- Later wrapped by the Platform Console (request by Account Manager, approval by Licensing Admin).

## 6. Setup command (`platform-setup`, later task)

Reads the licence, stores it, creates customer, tenant and entity slots, loads the sign-in config file into the login hub. Idempotent: never duplicates or deletes. No inserts at app startup; the schema step stays separate.

## 7. Rules that always apply

- No generic errors: every refusal has a stable code and an exact cause (see the shared "pinpointing problems" rule).
- Never log or print key material, licence signatures in full, secrets or document data.
- Database: tables only via the app's schema step (separate Flyway run, own history table), runtime user DML only, startup only verifies.
