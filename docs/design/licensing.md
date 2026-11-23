# platform-licensing and platform-setup — design (agreed 2026-11-21)

Source of truth: the owner's Shared capabilities thread (project doc `Licensing-Design.md`). This is the copy coding sessions read. Do not change the rules without the owner. **No prices anywhere in this repo**: the code only knows "this licence allows X".

## 1. The licence

- **One signed licence per customer** (tenant), not per entity. It lists the products bought and **entity slots**; each slot has its own ID and its own allowance.
- **Contents:** customer, tenant ID, products (product codes, not hard-coded app names in shared logic), entity slots (ID + allowance each), kind (install or production), start and expiry dates, deployment (hosted or on-prem), signing key ID.
  - Allowance types the model must support: pages per month; storage; concurrent seats; named seats. Each product uses the types it needs.
- **Signing:** Ed25519. Private key only in the secret store (never in the repo, never exported); apps ship the public keys. Each licence names its key ID so keys can rotate without breaking old licences.
- **Entity slots:** the licence pre-issues slot IDs, unique across all customers. The customer's Business Admin activates and names a slot in the app. IDs always come from us.
- **Adding entities or changing allowances:** a new licence version.
- **Install licence:** short: at most 60 days, with no minimum (P009 decision 7, section 8; the issuing command refuses
  a longer one with `ISSUE_INSTALL_TOO_LONG`). Usage under an install licence is recorded but marked **not billable**.

## 2. Where the licence lives

- The setup command, or the licence command for renewals, stores the signed licence **in the database**; the app reads it from there.
- The signature is verified every time it is read; an edited row is detected.
- Renewal takes effect without a restart. *(A renewal stored before its start date waits: owner decision O2, section 8.)*

## 3. Licence states

| State | App starts? | Intake | Viewing and export |
|---|---|---|---|
| Valid | Yes | Yes | Yes |
| Expiring: warnings 60, 30, 7, 1 days before | Yes | Yes | Yes |
| Expired, **30-day grace period** | **Yes** | Yes, with daily critical warnings | Yes |
| Expired, grace over | **Yes** | Stopped, with a clear message | Yes |
| Missing, forged, tampered, wrong customer, unknown key ID | **No**, specific message and stable code | — | — |

- A restart never takes the app down because of expiry.
- **Clock rollback:** remember the latest time seen; a clock moved backwards beyond a small tolerance is reported as tampering, with a specific message. *(Replaced by owner decision O1, section 8: the app always starts; dates use the later of the server clock and the latest time seen.)*
- The module reports the state; each app decides what "intake" means and stops it after grace.

## 4. Usage

- **Never stop at a limit.** 80% and 100% warnings and over-limit are reported; work continues; overage is recorded.
- The module provides a **usage ledger**: the app records units at its own meter point (e.g. pages processed, daily peak of concurrent seats, named seats assigned, storage). Records carry entity slot, period, amount, billable flag (false under an install licence or when the app marks it free), and a hash chained to the previous record so edits are detected.
- Usage per entity per month can be read for the app's screens and notifications.
- **Usage file export** for any period, on demand: tamper-evident, no document data, no prices. How often it is sent is a contract term; nothing in the software reacts to a late file.
- **The export anchor is the app's.** Each export continues from the previous file's sequence, final chain value and
  last period (`ExportAnchor`). The app keeps the anchor of the last file it exported (`UsageExport.nextAnchor()`);
  there is no `lic_` table for it. If the usage tables are wiped but the app keeps its anchor, the next export holds
  no records and continues the file chain. Only when the anchor is lost too does the next file start from the
  beginning, and the file check shows `EXPORT_START_MISMATCH` (owner answer Q5, section 8).

## 5. Licence-issuing command (our side only)

Since P041 (0.6.0) the issuing side is its own module, `platform-licensing-issue`: the command, the Vault Transit
signer, and the signing half of the formats (`LicenceSigner`, `ResetSigner`, `DocumentSigner`, `SigningKeys`). It is
not in the BOM and is never on a customer's classpath; `platform-licensing` only verifies.

- Builds and signs a licence from **command options**, not an input file: `--customer`, `--products`, `--slot` (one
  per entity slot, with its allowances), `--type`, `--kind`, `--starts`, `--expires` or `--updates-until`,
  `--deployment`, `--tenant-id` for a new version of an existing tenant, `--key-id`. Set-up and the Vault options:
  `docs/install/licence-signing.md`.
- Generates tenant and entity-slot IDs unique across all customers (random, collision-checked against its own issued records).
- Reads the signing key from the secret store; writes a record of every licence issued (no key material in it); every
  run logged. *(Since P011 the key never leaves the secret store: signing happens in Vault Transit; see section 8.)*
- Later wrapped by the Platform Console (request by Account Manager, approval by Licensing Admin).

## 6. Setup command (`platform-setup`, later task)

Reads the licence, stores it, creates customer, tenant and entity slots, loads the sign-in config file into the login hub. Idempotent: never duplicates. No inserts at app startup; the schema step stays separate.

The first rule here was "never deletes". Later setup decisions changed that, on request only: `--remove-unlisted-grants`
removes group access that is no longer in the role mapping file, and a line removed from a client-binding file
switches that binding off (kept, never deleted). Customers, entities and slots are still never deleted. The rules as
built are in `docs/design/setup.md` (section 2 rule 6, sections 5 and 6).

## 7. Rules that always apply

- No generic errors: every refusal has a stable code and an exact cause (see the shared "pinpointing problems" rule).
- Never log or print key material, licence signatures in full, secrets or document data.
- Database: tables only via the app's schema step (separate Flyway run, own history table), runtime user DML only, startup only verifies.

## 8. Decisions

### Owner decisions on P009 (2026-11-21)

- **O1. Server clock behind: the app always starts.** The effective time is the later of the server clock and the
  latest time seen, so turning the clock back can never extend a licence. A server clock behind by more than the
  tolerance (default 1 hour, `platform.licensing.clock-tolerance`) is reported with a flag and a message that gives
  the exact difference (for example "The server clock is 3 hours behind …"). It is never a refusal;
  `LICENCE_CLOCK_ROLLBACK` is removed. If a wrong future clock ever poisons the latest time seen, our side issues a
  signed **last-seen reset** (`platform-last-seen-reset/1`, issuing command option `--reset-last-seen`). The customer
  applies it with the licence command (`apply-reset`). A reset works once, only for its tenant, and only until its
  `notAfter` time (7 days by default, at most 30).
- **O2. Early renewal.** A licence whose start date is still in the future is stored as **waiting**. The current
  licence stays in force until the effective date reaches the renewal's start date, and then the renewal takes over.
  Intake never stops because of an early renewal. On a first install, with no current licence, the licence is
  stored as current even before its start date (state `NOT_STARTED`).
- **O3. Confirmed:** one allowance per product and type in a slot; storage in whole GB.
- **O4. Chain head.** The usage chain head (newest sequence and hash) is stored apart from the records, moved in the
  same transaction as every append, and checked on read. Deleting the newest records is then detected
  (`USAGE_CHAIN_HEAD_MISMATCH`).

### P009 decisions (as built; 3 and 5 updated by O1 and O2)

1. Two modules, `platform-licensing` and `platform-licensing-testkit`, as for notifications. P010 added
   `platform-licensing-spring`.
2. One JSON file with the signature block inside it. The signature covers the canonical payload, not the file text.
3. Refusal codes beyond the design's five: `LICENCE_MALFORMED` and `LICENCE_UNSUPPORTED_FORMAT` (they refuse
   start-up too: an unreadable licence cannot be checked). `LICENCE_CLOCK_ROLLBACK` was removed by O1. Storing a
   licence can also be refused with `LICENCE_OLDER_VERSION` or `LICENCE_VERSION_CONFLICT`; the stored licence stays
   in force.
4. Dates are UTC calendar dates; `expiresOn` is the last valid day. Expiring covers 0–60 days before expiry; warning
   days are exactly 60, 30, 7 and 1, and every day of grace. Grace is days 1–30 after expiry; intake stops on day 31.
5. State `NOT_STARTED` before the start date: the app starts and intake is stopped. Since O2 this happens only on a
   first install; a renewal waits instead.
6. A subscription covers every release; "updates until" exists only on perpetual licences.
7. An install licence is a subscription of at most 60 days, checked when issuing. There is no minimum.
8. Each allowance names its product (product + type + amount).
9. Storage is in whole GB. Pages are summed per month; storage and both seat types take the month's highest
   reading.
10. Use without an allowance is recorded and reported `OVER_LIMIT`. An unknown slot or product is refused with its
    own code (`USAGE_UNKNOWN_SLOT`, `USAGE_UNKNOWN_PRODUCT`).
11. Billable = kind `PRODUCTION` and not marked free by the app.
12. Install and production licences share a tenant; non-production gets its own tenant and slots. A new version
    uses `--tenant-id`, and an existing slot is kept with `--slot SLOT_ID@…`.
13. IDs: `TEN-`, `ENT-`, `LIC-` (and `RST-` for resets) plus 16 Crockford base32 characters, checked against all
    issued IDs.
14. Licensing has its own signing interface (no dependency on notifications). *(P011: `SigningKeyResolver` and the
    environment-variable default were replaced by `Signer.sign(keyId, bytes)` and Vault Transit.)*
15. Clock tolerance defaults to 1 hour; it can be changed.
16. The usage export has its own chain across files. The ledger's chain check, including the head since O4, is
    written into the export header; an export is never refused.
17. Refusals are logged with the code only; the issuing command logs IDs and codes.

### P010 decisions (as built; source docs/work/P010-report.md, written here in P037)

- **Expected tenant (P010-3).** A licence must be for `platform.licensing.tenant-id`; before that setting existed, for
  the stored licence's tenant. The first licence stored sets it.
- **A broken stored row never blocks a good licence (P010-4).** If the stored licence row cannot be read, storing a
  valid licence still works and replaces it.
- **Versions only go up (P010-5).** A lower version of the same licence is `LICENCE_OLDER_VERSION`; the same licence
  ID and version is `ALREADY_STORED`; a licence that starts now drops an older waiting one.
- **Last-seen reset (P010-7).** A reset applies at or before its `notAfter` time. `NOT_NEEDED` (the latest time seen
  is already earlier) still uses up the reset ID.
- **Clock tolerance (P010-9)** is capped at 1 day in the Spring settings (`platform.licensing.clock-tolerance`).
- **Health (P010-10, -11).** The health indicator gives the state, the days to expiry and the renewal's start; never
  the customer's name.
- **The clock (P010-12).** Spring apps set licensing's clock with a `LicensingClock` bean; an app's own `Clock` bean
  is never picked up, so a test clock elsewhere in the app cannot move the licence.

### Owner answers to the P010 questions (2026-11-21)

- **Q1. Product code not in the licence:** keep it as stopped intake. Start-up is not refused.
- **Q2. Tenant ID:** `platform.licensing.tenant-id` becomes required in the `platform-setup` task (which writes it),
  not now. *Done in P014:* required, with the startup message "platform.licensing.tenant-id is missing"; setup writes
  it into `platform-tenant.properties`.
- **Q3. Reset validity:** 7 days by default, at most 30. Confirmed.
- **Q4. Resets stay out of the issued records** (run log only).
- **Q5. Wiping both usage tables accepted:** it looks like a new, empty ledger. The export anchor is kept by the
  app, not in a `lic_` table (section 4), so the next export continues from the app's anchor with no records. Only if
  the app's anchor is lost too does the next usage export show a chain start that does not match the previous file
  (`EXPORT_START_MISMATCH`).

### P011 decisions (notification events and Vault signing)

As built; source docs/work/P011-report.md (the last six items written here in P037):

- **Licensing notifications** live in `platform-licensing-spring`, with `platform-notifications` as an optional
  dependency. The app registers the event types (`LicensingEvents.register`) and supplies `ActivatedSlots`. Business
  events are published per activated entity slot (owner decision); clock and version events are technical, per
  tenant. An hourly check publishes; one-off events are claimed in `lic_notice_sent` first, so restarts and several
  instances never publish twice; conditions use `conditionFailing` / `conditionCleared`.
- **Signing:** `Signer.sign(keyId, bytes)` replaces `SigningKeyResolver`. Production signing is `VaultTransitSigner`
  (Vault Transit ed25519; the private key never leaves Vault). Key ID = Transit key name + version (`lic-2026:1`), so
  the key-ID rule now allows an optional `:VERSION`. Set-up: `docs/install/licence-signing.md`.
- **Timing (P011-1, -2).** "Daily" events go in the 08:00 summary in the tenant's zone. Critical conditions alert at
  once and repeat every 24 hours; "server clock behind" repeats every 4 hours.
- **Expiring bands (P011-3).** "Licence expiring" is sent once per band (60, 30, 7 and 1 days), not only on the exact
  day, so a day the app was down is not missed; band 1 includes the last valid day.
- **Grace hands over (P011-4).** When grace ends, the grace condition hands over to "intake stopped" without a
  "recovered" message (`conditionSuperseded`).
- **Condition keys (P011-5).** Licence conditions are keyed by event, tenant and entity (not the licence ID, so a
  renewal does not start a new condition); usage conditions by `PRODUCT:TYPE`.
- **Usage levels (P011-6).** "Near limit" at the admin's percentage (default 80, 50 to 99); "at limit" when use reaches
  the allowance (and then "near limit" is skipped); "over limit" is a condition. Use without an allowance gets no
  notice.
- **Housekeeping (P011-7, -9, -11).** Notice claims (`lic_notice_sent`) are purged after 400 days; only event types
  in the app's catalogue are published; "version not covered" needs the app's `release-date`.
