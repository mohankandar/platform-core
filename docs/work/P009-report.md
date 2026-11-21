# P009 report — platform-licensing core

Branch `feature/licensing-core` (from `main`), pushed, not merged.

## What was built

Two new modules, added to the root `<modules>` list and to `platform-bom`, at the root version:

| Module | Contents |
|---|---|
| `platform-licensing` | `com.documentplatform.licensing`. Plain Java, no Spring, **no runtime dependencies** (JDK Ed25519 and SHA-256). |
| `platform-licensing-testkit` | `com.documentplatform.licensing.inmemory`: `InMemoryUsageLedger`, `InMemoryLastSeenStore`, `InMemorySigningKeyResolver`, `TestLicences` (throwaway key pair + signed test licences). Test scope only. |

In `platform-licensing`:

- **Model:** `Licence` (licence ID, version, customer, tenant ID, product codes, entity slots, type, kind, start date,
  expiry date or "updates until" date, deployment, key ID, issued-at), `EntitySlot`, `Allowance` (product, type,
  amount), `AllowanceType` (`PAGES_PER_MONTH`, `STORAGE`, `CONCURRENT_SEATS`, `NAMED_SEATS`, each with its unit),
  `LicenceType` (`SUBSCRIPTION` by default, `PERPETUAL`), `LicenceKind` (`INSTALL`, `PRODUCTION`, `NON_PRODUCTION`,
  with `usageBillable()` and `testEnvironment()`), `Deployment`.
  `Licence.coversRelease(date)` and `releaseNotCoveredMessage()` cover the "updates until" check.
- **Signing and verification:** `LicenceSigner`, `LicenceVerifier`, `TrustedKeys` (the public keys an app ships,
  by key ID), `LicenceFormat`, `Ed25519`. There are stable refusal codes (`LicenceRefusal`) and `LicenceException`.
- **States:** `LicenceStatus.at(licence, instant)` gives the `LicenceState`, the warning band and warning day,
  days to expiry, grace end and grace days left, whether intake is allowed, and a message. `ClockGuard` with a
  `LastSeenStore` checks for a clock rollback. `LicenceChecker` does what an app calls on every read: verify, then
  the clock check, then the state.
- **Usage:** `UsageLevel` (`WITHIN`, `NEAR_LIMIT` at 80% or more, `AT_LIMIT` at 100%, `OVER_LIMIT`; it only reports).
  `UsageLedger` is the interface: append, records, chain check, usage and billable usage per slot, product, type
  and month. `UsageChain` builds and checks the hash chain. `UsageRecorder` sets the billable flag from the licence
  kind, records, and returns the assessment. It never blocks.
- **Usage export:** `UsageExport.create(...)` writes the file for any range of months. Each file continues from an
  `ExportAnchor`, which is the previous file's final chain value. `UsageExportVerifier` checks one file
  (`verify`) or a run of files (`verifySequence`) and returns `ExportCheck` with a stable code.
- **Issuing command:** `issue.IssueLicenceCommand` (has a `main`). It parses the options, holds a lock beside the
  records file, and generates tenant, slot and licence IDs that are checked against all issued records. It reads
  the key through `SigningKeyResolver`; the default, `EnvironmentSigningKeyResolver`, reads
  `PLATFORM_LICENCE_KEY_<KEY ID>`. It signs, writes the licence file (never overwrites one), appends the issued
  record and writes a run-log line for every run. Each refusal has its own `IssueError` code. Exit codes: 0 issued,
  1 refused, 2 bad options.

The formats are documented in **`docs/design/licensing-file-format.md`** (new). `docs/design/licensing.md` is
committed **unchanged**; it was untracked on `main`. CLAUDE.md lists the two new modules.

## File format (summary; full spec in `docs/design/licensing-file-format.md`)

- The file is one JSON document: `{"format":"platform-licence/1","licence":{payload},"signature":{"algorithm":"Ed25519","payloadSha256":…,"value":…}}`.
- The signed message is the UTF-8 of `"platform-licence/1\n" + canonical(payload)`. Canonical means RFC 8785 (JCS)
  for a strict subset: sorted keys, no whitespace, whole numbers only, no `null`. Ed25519 is deterministic, so the
  same payload and key always give the same file. Whitespace is not signed.
- `payloadSha256` is the SHA-256 of the signed message. It separates `LICENCE_TAMPERED` (the file was edited) from
  `LICENCE_FORGED` (the signature was not made by the named key).

Sample, made with a throwaway key generated for this report (not a real key):

```json
{
  "format": "platform-licence/1",
  "licence": {
    "customer": "Example Customer Ltd",
    "deployment": "ON_PREM",
    "entitySlots": [
      {
        "allowances": [
          { "amount": 10000, "product": "PRODUCT_A", "type": "PAGES_PER_MONTH" },
          { "amount": 25, "product": "PRODUCT_A", "type": "NAMED_SEATS" }
        ],
        "slotId": "ENT-9NKK-T9AZ-47YX-Y175"
      },
      {
        "allowances": [ { "amount": 500, "product": "PRODUCT_A", "type": "STORAGE" } ],
        "slotId": "ENT-EJVF-2055-5QZP-1RCP"
      }
    ],
    "expiresOn": "2027-11-30",
    "issuedAt": "2026-11-21T10:00:00Z",
    "keyId": "test-sample-01",
    "kind": "PRODUCTION",
    "licenceId": "LIC-YN19-Q159-CW3X-GHAS",
    "products": [ "PRODUCT_A" ],
    "startsOn": "2026-12-01",
    "tenantId": "TEN-G0D2-1PAP-WYDC-KYT1",
    "type": "SUBSCRIPTION",
    "version": 1
  },
  "signature": {
    "algorithm": "Ed25519",
    "payloadSha256": "50286018358d22f235fd52a9b558caa11430e0535eea10bb33b793c25c78f3ea",
    "value": "X2f+KznDwQfmd0HKkqEJsciF/zgfht5/fNpyUQd/KTr9uJk1D/B73KewBWDm/+0h1ySKkdK5f6gpvU/G66/rAQ=="
  }
}
```

The test public key is `MCowBQYDK2VwAyEAGAK4ENFFDSCloKEjiehEskki1ND+E9kBdPxlPov5/aQ=` (key ID `test-sample-01`).

## Tests

`mvn -o clean verify`: **414 tests, 0 failures, 0 errors**. That is the 283 existing tests (identity-core 73,
identity-spring 15, notifications 65, notifications-testkit 101, notifications-spring 29) plus **131 new**:
platform-licensing 100 and platform-licensing-testkit 31. `mvn -o install` succeeded. `NoProductNamesGuardTest` also
covers the new modules. Test product codes are neutral (`PRODUCT_A`).

What the tests cover:

- **State boundaries.** Each day tested exactly: 61, 60, 59, 31, 30, 29, 8, 7, 6, 2, 1 and 0 days before expiry
  (state, band, warning day); grace days 1, 15, 30 and 31, and 400 days after; 23:59:59Z and 00:00Z on both sides of
  the expiry and grace ends; the day before the start date.
- **Types.** A perpetual licence stays `VALID` up to 2199. "Updates until" covers a release dated that day and does
  not cover one dated the next day. A subscription covers every release.
- **Kinds.** The billable flag for each of the three kinds, with and without "free". The test-environment flag.
- **Refusal codes.** Every code has a test. **Every single byte of a licence file**, changed one at a time, is
  refused.
- **Signatures and keys.** A signature changed under a valid encoding gives `LICENCE_FORGED`. A licence signed by
  an attacker's key under our key ID gives `LICENCE_FORGED`. Swapping the key ID to another trusted key gives
  `LICENCE_TAMPERED`. After a key rotation, the old key ID still verifies. Refusal messages contain no signature
  or key.
- **Clock rollback.** Exact tolerance edge, refused beyond it, accepted again once the clock is corrected, and
  rolling back to leave "grace over" is refused.
- **Usage ledger chain.** An edited amount, a flipped billable flag, a deleted record and a re-chained record are
  each detected, with their code and sequence number.
- **Usage levels.** 79/80/99/100/101%, small limits, no allowance, values near `Long.MAX_VALUE`. Overage is recorded
  and nothing is ever blocked.
- **Usage export.** Verified on its own and as a run. Detected: a missing file, a first file not starting from
  genesis, a gap in months, a gap in sequence numbers, an edited record (its index is reported), an edited header,
  a removed last record, a removed first record, and another tenant's file. A broken ledger is stated in the export
  header. No price or document fields appear in the file.
- **Issuing command, end to end.** The issued licence verifies. Records, run log and console output hold no key or
  signature. Every run is logged. A version 2 keeps the tenant and chosen slots. Install and production share a
  tenant; non-production never does. Install licences are capped at 60 days. Perpetual options are checked. Each
  refusal code has a test. An invalid stored key is reported without echoing it. An unreachable secret store is
  reported. 60 licences issued, 240 IDs, all unique. A replayed random seed is stepped past, and a stuck random
  source ends with `ISSUE_ID_COLLISION`.

The byte-flip test found one gap during the work. An edit to the `payloadSha256` digest alone was accepted, because
the digest was only consulted after a signature failure. The digest is now always compared.

## Decisions I had to make

1. **Two modules**, as for notifications. The ledger and export behaviour tests live in the testkit module, because
   they use the in-memory ledger.
2. **One JSON file with the signature block inside it**, rather than a separate signature file: one database row,
   one file to send. The signature is detached from the bytes: it covers the canonical payload, not the file text.
3. **Three codes beyond the five in the design:** `LICENCE_MALFORMED`, `LICENCE_UNSUPPORTED_FORMAT` and
   `LICENCE_CLOCK_ROLLBACK`. The design says to report a rollback "as tampering", so it is a refusal (the app does
   not start). It clears once the clock is right.
4. **Dates are UTC calendar dates, and `expiresOn` is the last valid day.** "Expiring" covers 0 to 60 days before.
   Warning days are exactly 60, 30, 7 and 1, and every day of grace. Grace is days 1 to 30 after expiry; intake
   stops on day 31.
5. **`NOT_STARTED` state** before the start date: the app starts, intake is stopped (see question 2).
6. **A subscription covers every release.** "Updates until" exists only on perpetual licences.
7. **An install licence is a subscription of at most 60 days.** This is checked when issuing, not when verifying.
   There is no minimum.
8. **Each allowance names its product** (product + type + amount), so a slot can hold allowances for two products
   without ambiguity.
9. **Units and monthly figures.** Storage is whole GB (10^9 bytes). The monthly figure for pages is the sum of a
   month's records; for storage and both seat types it is the highest reading of the month.
10. **Use without an allowance is recorded** and reported `OVER_LIMIT` (limit 0). An unknown slot or product is
    refused with its own code (`USAGE_UNKNOWN_SLOT`, `USAGE_UNKNOWN_PRODUCT`). That is a wrong ID, not a limit.
11. **Billable** is true when the kind is `PRODUCTION` and the app did not mark the reading free.
12. **Tenants and versions.** Install and production licences share a tenant; non-production gets its own tenant
    and slots. A new version uses `--tenant-id` and keeps a slot with `--slot SLOT_ID@…`. The customer name must
    match the tenant's records.
13. **IDs** are `TEN-`, `ENT-` or `LIC-` plus 16 Crockford base32 characters (80 random bits), checked against all
    IDs of every prefix in the records file.
14. **Our own `SigningKeyResolver`**, not the notifications `SecretResolver`, so licensing does not depend on
    notifications. Until a Vault resolver exists, the default reads one environment variable set for the run.
15. **Clock tolerance** defaults to 1 hour and can be changed.
16. **The export has its own chain** across files; it is not the ledger's chain, which follows append order and
    would not fit "any period". The ledger's chain check is written into the export header, and an export is never
    refused because of it.
17. The verifier logs refusals at WARNING with the **code only**. The command logs IDs and codes.

## Questions

1. **Clock rollback blocks start-up.** Is a rollback of more than 1 hour, which refuses the licence, what you want?
   *Recommendation:* yes, with the tolerance configurable per app. It clears by itself once the clock is fixed.
2. **A licence stored before its start date.** Intake stops until it starts. *Recommendation:* keep that, and have
   the setup and licence commands keep the current licence until the new one's start date, so a renewal stored
   early never stops intake.
3. **One allowance per product and type in a slot.** Is that right? *Recommendation:* yes.
4. **Storage in whole GB.** *Recommendation:* yes. Apps convert from bytes when they record.
5. **Export tamper evidence.** The chain detects missing files, gaps and edits. A determined customer could still
   recompute the newest file from scratch. *Recommendation:* accept this for V1, since sending the file is a
   contract term. Later, sign exports on hosted installations, or keep the final values we received and compare.
6. **Deleting the newest ledger records leaves no broken link.** *Recommendation:* in the Postgres ledger task,
   store the chain head (latest sequence and hash) beside the last-seen time and check it on read.
7. **Signing key custody.** *Recommendation:* use Vault Transit with an Ed25519 key, so the private key never
   leaves Vault. This would replace `SigningKeyResolver` with a `sign(keyId, bytes)` interface before the Platform
   Console is built.
8. **Next tasks.** *Recommendation:* Postgres stores and a migration for the ledger and last-seen time, then
   `platform-setup` (design section 6).
