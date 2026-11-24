# Licensing file formats (platform-licensing, P009)

The formats the `platform-licensing` module reads, and `platform-licensing-issue` (our own issuing tool, since 0.6.0) writes. The Platform Console must produce **the same bytes**
when it issues licences later. The rules in `licensing.md` apply; this file only describes formats. No prices
appear in any of them.

## 1. JSON rules (all files)

A strict subset of JSON, so each value has exactly one canonical form:

- Values: objects, arrays, strings, whole numbers (64-bit signed), `true`/`false`.
- Not used: `null` (a field is present or absent), fractions, exponents, leading zeros, `-0`, duplicate keys.
  Nesting at most 32 deep. Text is UTF-8.
- **Canonical form** (RFC 8785 / JCS for this subset): keys sorted by UTF-16 code units, no whitespace, strings
  escaped only where JSON requires (`\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`; other characters below U+0020 as
  `\u00xx` with lower-case hex); every other character written as itself in UTF-8.
- Files are written "pretty" (same keys, sorted, two-space indent, LF line ends) for people to read. Whitespace is
  not signed: the signature covers the canonical form of the payload.
- Dates: `YYYY-MM-DD` (UTC calendar date). Months: `YYYY-MM`. Times: ISO-8601 UTC as Java `Instant.toString()`
  (`2026-11-21T10:00:00Z`).
- Hashes: SHA-256, lower-case hex. Keys and signatures: standard Base64 with padding.

## 2. Licence file — `platform-licence/1`

```
{ "format": "platform-licence/1",
  "licence":   { ...payload... },
  "signature": { "algorithm": "Ed25519", "payloadSha256": "<hex>", "value": "<Base64, 64 bytes>" } }
```

### Payload fields (all required unless marked)

| Field | Value |
|---|---|
| `licenceId` | `LIC-XXXX-XXXX-XXXX-XXXX`, new for every licence and version |
| `version` | 1, 2, 3 … per tenant; a change of slots or allowances is a new version |
| `customer` | customer name, 1–200 printable characters |
| `tenantId` | `TEN-XXXX-XXXX-XXXX-XXXX`; kept across versions; never shared between production/install and non-production |
| `products` | product codes (`[A-Z][A-Z0-9_]{1,31}`), no repeats |
| `entitySlots` | `[{ "slotId": "ENT-…", "allowances": [{ "product", "type", "amount" }] }]` |
| `type` | `SUBSCRIPTION` or `PERPETUAL` |
| `kind` | `INSTALL`, `PRODUCTION` or `NON_PRODUCTION` |
| `startsOn` | date |
| `expiresOn` | SUBSCRIPTION only: last valid day (inclusive, UTC) |
| `updatesUntil` | PERPETUAL only: last product release date covered |
| `deployment` | `HOSTED` or `ON_PREM`; information only, not enforced |
| `keyId` | signing key ID: a name `[a-z0-9][a-z0-9._-]{0,63}`, optionally `:VERSION` (Vault Transit key name and version, for example `lic-2026:1`; since P011) |
| `issuedAt` | time |

Allowance `type` and its unit: `PAGES_PER_MONTH` (pages), `STORAGE` (GB, 10^9 bytes), `CONCURRENT_SEATS` (seats),
`NAMED_SEATS` (seats). `amount` ≥ 1. Every allowance's `product` is one of `products`; one allowance per
product and type within a slot. An `INSTALL` licence is a `SUBSCRIPTION` of at most 60 days (enforced when issuing).
Unknown fields are refused.

IDs are 16 random Crockford base32 characters (80 bits) after the prefix, in groups of four.

### Signing

- Signed message = UTF-8 bytes of `"platform-licence/1"` + LF + `canonical(payload)`.
- `value` = Ed25519 signature of that message (64 bytes, Base64).
- `payloadSha256` = SHA-256 of the same message. It is not the security control (the signature is); it lets the
  verifier tell an edited file (`LICENCE_TAMPERED`) from a signature not made by the named key (`LICENCE_FORGED`).
- Ed25519 is deterministic: the same payload and key give the same file.

### Verification order and codes

| Check | Code |
|---|---|
| nothing stored (null, empty, blank) | `LICENCE_MISSING` |
| not UTF-8, not JSON, `format` missing or not a string | `LICENCE_MALFORMED` |
| `format` not `platform-licence/1` | `LICENCE_UNSUPPORTED_FORMAT` |
| missing/extra envelope fields, signature not 64 bytes of Base64 | `LICENCE_MALFORMED` |
| `algorithm` not `Ed25519` | `LICENCE_UNSUPPORTED_FORMAT` |
| `keyId` not among the app's trusted keys | `LICENCE_UNKNOWN_KEY_ID` |
| SHA-256 of the signed message ≠ `payloadSha256` (payload or digest edited) | `LICENCE_TAMPERED` |
| signature does not verify with the named key | `LICENCE_FORGED` |
| signed payload breaks the model (for example perpetual with an expiry) | `LICENCE_MALFORMED` |
| `tenantId` ≠ the installation's tenant (when known) | `LICENCE_WRONG_CUSTOMER` |
| storing only: older version than one stored / same version, different licence | `LICENCE_OLDER_VERSION` / `LICENCE_VERSION_CONFLICT` |

`format` is compared before the envelope's field set is checked (`SignedDocument.open`), so a file of another format
with extra fields gives `LICENCE_UNSUPPORTED_FORMAT`, not `LICENCE_MALFORMED`. The same order applies to the
last-seen reset (section 7).

Every message is `CODE: meaning. exact cause.` with IDs and dates only — never signatures, keys or other content.

A server clock behind is **not** a refusal (owner decision O1): dates use the later of the server clock and the
latest time seen, and a clock behind by more than the tolerance is reported as a flag with the exact difference.

### Sample (throwaway test key, generated for this document; not a real key)

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

(Short arrays are shown on one line here for space; the command writes one element per line. Either verifies:
whitespace is not signed.) Its public key, as a trusted-keys file:

```json
{ "format": "platform-licence-keys/1",
  "keys": [ { "algorithm": "Ed25519", "keyId": "test-sample-01",
              "publicKey": "MCowBQYDK2VwAyEAGAK4ENFFDSCloKEjiehEskki1ND+E9kBdPxlPov5/aQ=" } ] }
```

## 3. Trusted keys file — `platform-licence-keys/1`

What apps ship: `{"format":"platform-licence-keys/1","keys":[{"algorithm":"Ed25519","keyId":"…","publicKey":"…"}]}`.
`publicKey` is Base64 of the X.509 SubjectPublicKeyInfo encoding (44 bytes for Ed25519). A rotation adds a key;
old keys stay until no licence signed with them is in use.

A key may also carry the optional field `"development": true` (P031): a **development key**, for demo and local
test licences only, which no production release lists. A few local test features (an `http` login issuer on this
machine, P031) are allowed only for a licence signed by such a key (`TrustedKeys.isDevelopmentKey`). The field is
`true` or `false`; any other value is refused, and a file without it loads as before (every key a normal key). Other
fields in a key are refused.

```json
{ "format": "platform-licence-keys/1",
  "keys": [ { "algorithm": "Ed25519", "development": true, "keyId": "dev-local-01", "publicKey": "…" } ] }
```

## 4. Issuing command files (our side only)

- **Issued records** (`--records`, JSON Lines, append only): one canonical object per licence with `licenceId`,
  `version`, `tenantId`, `customer`, `kind`, `type`, `products`, `slotIds`, `startsOn`, `expiresOn` or
  `updatesUntil`, `deployment`, `keyId`, `issuedAt`, `payloadSha256`, `licenceFile`. No key material, no signature.
  New IDs are checked against every ID in this file.
- **Run log** (`--run-log`, default `<records>.runs.log`, JSON Lines): one line per run, issued or refused:
  `at`, `outcome` (`ISSUED`, `RESET_ISSUED`, `KEYS_EXPORTED`, `REFUSED`, `FAILED`), `code`, `licenceId`, `version`,
  `tenantId`, `kind`, `resetId`, `resetTo`, `notAfter` (a last-seen reset, section 7), `keyId` (the key that signed,
  with its version), `keyIds` (an export), `out` (file name),
  `signer` (`vault-transit`), `signerAuditRefs` (the Vault request IDs of this run's signatures, which match Vault's
  audit log). Never a token, a key or a signature.
- **Signing** (since P011): through the `Signer` interface, `sign(keyId, bytes)`. The command uses
  `VaultTransitSigner`: the message goes to Vault Transit (`POST /v1/transit/sign/<name>`) and only the signature comes
  back; the private key never leaves Vault. Each signature is checked against the key's public key before the file is
  written. Set-up and the Vault policy: `docs/install/licence-signing.md`.

## 5. Usage ledger record (hash chain)

The chain head (newest `sequence` and `hash`) is stored apart from the records and moved in the same transaction as
every append; a ledger whose newest record is not the head gives `USAGE_CHAIN_HEAD_MISMATCH` (newest records removed).

Each record: `sequence` (1, 2, …), `slotId`, `product`, `type`, `period` (month), `amount`, `unit` (from the type),
`billable`, `recordedAt`, `previousHash` (64 zeros for the first), `hash`.

`hash = SHA-256(canonical({every field except hash}))`. Checks, with codes: sequence not 1, 2, 3 …
(`USAGE_CHAIN_SEQUENCE_GAP`), `previousHash` ≠ hash of the record before (`USAGE_CHAIN_LINK_BROKEN`), content ≠ its
hash (`USAGE_CHAIN_RECORD_EDITED`).

Monthly usage: `PAGES_PER_MONTH` is the sum of a month's amounts; `STORAGE`, `CONCURRENT_SEATS`, `NAMED_SEATS` the
highest reading. `billable` = licence kind is `PRODUCTION` and the app did not mark the reading free.

## 6. Usage export file — `platform-usage-export/1`

```
{ "format": "platform-usage-export/1",
  "header":  { tenantId, licenceId, sequence, periodFrom, periodTo, startChainValue, exportedAt,
               ledgerChain: { intact, recordCount, [problem, brokenAtSequence] } },
  "records": [ { ledgerSequence, slotId, product, type, period, amount, unit, billable, recordedAt, chain } ],
  "footer":  { headerChainValue, recordCount, finalChainValue } }
```

- `startChainValue` = the previous export's `finalChainValue` (64 zeros for the first export); `sequence` = previous
  + 1; `periodFrom` should be the month after the previous `periodTo`.
- `headerChainValue = SHA-256(startChainValue + LF + canonical(header))`.
- Each record: `chain = SHA-256(previous chain value + LF + canonical(record without "chain"))`, starting from
  `headerChainValue`. `finalChainValue` = the last record's `chain` (or `headerChainValue` when there are none).
- The ledger's own chain check is written into the header; an export is never refused because of it.
- Holds no document data and no prices: only the fields above.

Verifier codes: `EXPORT_MALFORMED`, `EXPORT_UNSUPPORTED_FORMAT`, `EXPORT_HEADER_EDITED`, `EXPORT_RECORD_EDITED`
(with the record index; also a record removed or inserted before the end), `EXPORT_RECORD_OUTSIDE_PERIOD`,
`EXPORT_FOOTER_MISMATCH` (records removed at the end, footer edited), and across files `EXPORT_START_MISMATCH`
(file missing or replaced), `EXPORT_SEQUENCE_GAP`, `EXPORT_PERIOD_GAP`, `EXPORT_TENANT_MISMATCH`.

## 7. Last-seen reset — `platform-last-seen-reset/1`

Issued by us when a wrong future server clock poisoned an installation's latest time seen. Same envelope and signing
rules as the licence (section 2), with the body named `reset`:

```
{ "format": "platform-last-seen-reset/1",
  "reset": { "resetId": "RST-…", "tenantId": "TEN-…", "resetTo": "<time>", "notAfter": "<time>",
             "issuedAt": "<time>", "keyId": "…" },
  "signature": { "algorithm": "Ed25519", "payloadSha256": "<hex>", "value": "<Base64>" } }
```

Signed message: `"platform-last-seen-reset/1"` + LF + `canonical(reset)`. Applied with the licence command
(`apply-reset --file …`): the latest time seen is set to `resetTo`, and `resetId` is recorded so the reset works once.
It is refused when the server clock is past `notAfter`. Codes: `RESET_MISSING`, `RESET_MALFORMED`,
`RESET_UNSUPPORTED_FORMAT`, `RESET_UNKNOWN_KEY_ID`, `RESET_TAMPERED`, `RESET_FORGED`, `RESET_WRONG_CUSTOMER`,
`RESET_EXPIRED`. Outcomes: `APPLIED`, `ALREADY_APPLIED`, `NOT_NEEDED` (the latest time seen was not ahead).

## 8. Tables (`db/platform/licensing`, version 1)

Run by the app's schema step as a separate Flyway run with history table `lic_schema_history`; startup only checks.

| Table | Holds |
|---|---|
| `lic_schema_version` | the version of the files run (1) |
| `lic_licence` | `CURRENT` and `WAITING` licence files exactly as received, with copies of ID, version, tenant, start date (never trusted) |
| `lic_last_seen` | one row: the latest time seen |
| `lic_clock_reset` | IDs of applied last-seen resets |
| `lic_usage` | the usage ledger (section 5) |
| `lic_chain_head` | one row: newest sequence and hash |
| `lic_notice_sent` | IDs of licensing notifications already published (event, tenant, slot, licence or month, band), so a restart never publishes one twice (P011) |

No secrets, key material, prices or document data in any table. The runtime user needs SELECT, INSERT, UPDATE and
DELETE on the `lic_` tables and SELECT on `lic_schema_version`.
