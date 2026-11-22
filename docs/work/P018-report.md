# P018 report — merge case fields, P017 follow-ups, then platform-crypto

Date: 2026-11-22
Branch: feature/case-fields (A, B), then feature/crypto (C); both merged into main
Outcome: PARTLY DONE (all work done, merged and pushed; the remote branch `feature/case-fields` and the local
branch `feature/case-fields` are not deleted, see "Not done")

## Result (tests per module, merge and push results)

- `mvn verify` after B: **695 tests, 0 failures** (the 685 existing, all passing, plus 10 new in platform-fields).
- `mvn verify` and `mvn install` after C: **712 tests, 0 failures** (695 plus 17 new). Per module:

| Module | Tests |
|---|---|
| platform-identity-core | 86 |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 128 |
| platform-notifications-spring | 39 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 24 |
| platform-identity-testkit | 5 |
| platform-setup | 15 |
| platform-fields | 70 (was 60) |
| platform-fields-testkit | 10 |
| **platform-crypto** (new) | 10 (Vault in Testcontainers) |
| **platform-crypto-testkit** (new) | 7 |

- **Merges:** both were fast-forwards. `main` afcce24 → d7a8d0d (case fields, pushed), d7a8d0d → afe8f41 (crypto,
  pushed). This report is the next commit on main, pushed.
- **Branches:** `feature/crypto` deleted locally (it was never pushed). `feature/case-fields`: **not deleted**,
  locally or on origin. The tool's permission check refused `git push origin --delete feature/case-fields`. Git
  refuses `git branch -d` while origin's copy of the branch is behind; `-D` would get past that, and I did not force
  it. Everything on it is in main.

## What was built

### A. Uncommitted files

`docs/design/integration-module.md`, `docs/design/setup.md` (section 6), `docs/work/P018.md`, and also
`docs/work/README.md` (untracked as well; decision 1). Committed exactly as they were, first on the branch (641e66c).

### B. Owner answers to P017 (platform-fields)

1. **DECIMAL**: digits with an optional minus and decimal part (`-4.125`); no thousands commas, no exponent, no `+`.
   Normalised without trailing zeros (`4.50` → `4.5`, `100.0` → `100`). Takes `minValue` / `maxValue` with any
   decimals; no length, pattern or normalisation. New code `VALUE_NOT_A_DECIMAL`.
   **DATE_TIME**: ISO 8601 with its offset (`2026-11-21T10:15:30Z`, `…10:15:30.5-05:00`; seconds and a fraction of
   up to 9 digits optional), calendar checked. Display = as given; normalised = the same moment in UTC with
   milliseconds (`2026-11-21T15:15:30.500Z`), so equal moments match whatever their offset. New code
   `VALUE_NOT_A_DATE_TIME`. Both types are in the file format, the delivery, the SQL check and the mapping table.
2. One list per entity and product in Vero too: recorded. Nothing to build (`putExtension` holds "which fields a
   source sends").
3. Several primary fields: as built in P017; recorded.
4. Import keeps the existing field: as built in P017; recorded.
5. **ADDRESS `street2`**: optional `FieldValue.Address.street2` (new 6-part record; the 5-part constructor and
   `FieldValue.address(...)` stay); tidied like the other parts, blank means none, counts towards the 512
   characters, the first street line stays required; in the delivery as member `"street2"`, left out when there is
   none.

All five answers are in `docs/design/case-fields.md` (types line, mapping rows, new section "Owner answers to
P017"); CLAUDE.md's platform-fields row is updated.

### C. platform-crypto (new) and platform-crypto-testkit (new)

Package `com.documentplatform.crypto`, plain Java, no runtime dependencies; both modules are in the root
`<modules>` and in `platform-bom`. Design, including what Vero changes at its switch-over:
`docs/design/crypto.md`.

- **`KeyService`**: the plug-in point. Per customer a wrapping key and a fingerprint key, neither leaving the key
  service: `createKeys`, versions, `newDataKey`, `unwrap`, `rewrap`, `versionOf`, `rotate`,
  `fingerprint(customer, version, input)`, `health`. AWS KMS and Azure Key Vault: the interface only, plus a mapping
  table in the design.
- **Vault:** `VaultClient` with login by token, token file (read at each login) or AppRole (login again once when
  Vault refuses the token; revoke on `close`), namespace, no redirects, KV v2 `readSecret` / `writeSecret` (values
  hidden in `toString`), health. `VaultKeyService` on Transit: keys `customer-<hex>` and `customer-<hex>-fp`,
  `aes256-gcm96`, not exportable.
- **Envelopes:** `ValueCipher`: one value, new data key, AES-256-GCM, bound to the customer, the key service and the
  caller's context, text `pcv1.…`, `rewrap`. `StreamCipher`: Vero's streaming format moved (64 KiB chunks, nonce
  prefix + index, authenticated final record), returns `EnvelopeInfo` for the app to store, `rewrap`.
- **Fingerprints:** `Fingerprinter`: HMAC-SHA256 computed in Vault (`transit/hmac`, explicit key version) over
  domain + context + canonical value (NFC, trimmed, single spaces, lower case); `searchTerms` covers every usable
  fingerprint key version.
- **Codes:** 14 `CRYPTO_*` codes (section 6 of the design): unreachable, TLS, sealed, login refused, permission
  denied, key missing, key disabled (version below `min_decryption_version`), wrong customer, ciphertext invalid, and
  others. No message, log line or `toString()` holds a token, secret ID, key, value or wrapped key.
- **Testkit:** `InMemoryKeyService`: the same behaviour and codes, plus `down`, `denied`, `setLowestUsable`,
  `rotateFingerprintKey`, `createKeysOnDemand`, a call counter.

**Moved from sdv-crypto, and what changed** (full table in the design, section 8): the streaming envelope (same
framing; magic, profile and associated data renamed and `tenant` → `customer`); the Transit key provider (now with
specific codes instead of `IOException` text, AppRole and token-file login, namespace, no redirects); data keys
still 256-bit and wiped. Changed: keys are created by the set-up step, not on every data key (Vero's runtime needed
create rights). Key names `sdv-tenant-<hex>` → `customer-<hex>`. Provider id `VAULT_TRANSIT` → `vault-transit`. Not
moved: the Spring configuration classes (wiring stays in the app). Jackson dropped. New: values, fingerprints, KV,
rewrap, rotation, retired versions.

I read only Vero's `backend/sdv-crypto`, its ports in sdv-application, its crypto table, and its Vault and
error-code docs. Nothing in Vero was changed.

## Tests asked for → where

| Asked | Where |
|---|---|
| DECIMAL, DATE_TIME, street2 | `platform-fields/.../NewTypesTest` (6), `LegacyTypeMappingTest` (the Vero DECIMAL and DATETIME lines now use the new types), `FieldListFileTest.newKindsRoundTrip`, `PostgresFieldStoreTest.decimalAndDateTimeFieldsAreStoredAndReadBack`, `FieldDeliveryTest.anAddressTravelsWithItsSecondStreetLine`, `.decimalAndDateTimeTravelAsStrings` |
| encrypt/decrypt (Vault) | `VaultCryptoTest.valuesRoundTrip`, `.streamsRoundTrip` (0 B, 1 B, 64 KiB, 64 KiB + 1, 200 KiB) |
| rotation, old versions still decrypt | `VaultCryptoTest.rotationKeepsOldVersionsDecryptingAndRewrapMovesData` (also rewrap and `min_decryption_version` → `CRYPTO_KEY_DISABLED`); `InMemoryKeyServiceTest.rotationLowestUsableAndRewrap` |
| wrong customer key refused | `VaultCryptoTest.anotherCustomersDataIsRefused` (relabelled value and forged envelope as well) |
| tampered ciphertext refused | `VaultCryptoTest.tamperedCiphertextIsRefused`; `InMemoryKeyServiceTest.streamFormatRefusesReorderingDroppingAndTrailingBytes`, `.valueFormatRefusals` |
| fingerprint stable, different per customer | `VaultCryptoTest.fingerprintsAreStablePerCustomerAndDifferAcrossCustomers` (also per context, after rotation); `InMemoryKeyServiceTest.fingerprints` |
| Vault down gives a specific code | `VaultCryptoTest.vaultDownGivesItsCode` (`CRYPTO_KEY_SERVICE_UNREACHABLE`, health DOWN) |
| specific codes: permission, key missing, login | `VaultCryptoTest.permissionMissingKeyAndLoginHaveTheirCodes` |
| KV | `VaultCryptoTest.kvSecretsAreReadAndHidden` |
| no key or plaintext in logs | `NoSecretInLogsTest` (every log level, exception messages and `toString()`; good and failing paths) |
| testkit | `InMemoryKeyServiceTest` (7) |
| 685 existing still pass | 695 after B, 712 after C, 0 failures |

## Files changed, commits

- 641e66c docs: integration module design, setup client bindings, P018 task, work README (A)
- d7a8d0d feat(fields): DECIMAL and DATE_TIME types, ADDRESS street2; record owner answers to P017 (P018 B):
  `FieldType`, `FieldSpec`, `FieldValue`, `FieldValidator`, `FieldSet`, `ValueError`, `FieldDelivery`,
  `V1__fields.sql`, tests, `docs/design/case-fields.md`, CLAUDE.md
- afe8f41 feat(crypto): platform-crypto and testkit (P018 C): new modules, root POM, BOM, `docs/design/crypto.md`,
  CLAUDE.md
- this report (on main)

## Decisions I had to make

1. **`docs/work/README.md`** was untracked too; I committed it with the files of A, unchanged.
2. **Fields SQL changed in V1, not a new V2.** `V1__fields.sql` was never on main or in a release, so the type
   check now lists DECIMAL and DATE_TIME. P017 did the same with the unreleased notifications V1.
3. **DECIMAL format:** plain digits and one point, no `+`, no commas, no exponent. Vero's `new BigDecimal` took
   `1e3` and `+4`; neither looks like something a person types into a decimal field. No "number of decimals" rule
   (not asked).
4. **DATE_TIME needs an offset**, as Vero's `OffsetDateTime.parse` did. It is stored normalised as UTC with
   milliseconds (nanoseconds only when given).
5. **Crypto failures are unchecked** `CryptoException`s with a code; stream I/O stays `IOException`.
6. **Keys are created by the set-up step only.** The runtime policy has no create or rotate. Vero used to create the
   key on every data key.
7. **Two keys per customer**, so the wrapping key can rotate without changing fingerprints. Fingerprints carry their
   key version, and search uses every usable version.
8. **"Key disabled" in Vault** means a version below `min_decryption_version`. Transit has no on/off switch for a
   key.
9. **Value format is self-contained** (`pcv1.`), so one column is enough. The stream keeps Vero's model: the app
   stores `EnvelopeInfo`.
10. **Associated data does not include the wrapped key**, so rewrap never touches ciphertext.
11. **The fingerprint context** (for example the field key) goes into the HMAC, so equal values in different fields
    do not match.
12. **No Spring module** (not asked). Vero's Spring configuration stays in Vero until a `platform-crypto-spring` is
    asked for.
13. **VaultJson is copied** from licensing (with nested objects for KV), so crypto does not depend on licensing.

## Not done, and why

- **Deleting `feature/case-fields`** locally and on origin. The permission check of this session refused the remote
  delete, and the local `-d` needs that first. Its work is all in main. To finish:
  `git push origin --delete feature/case-fields` then `git branch -d feature/case-fields`.

## Questions (numbered, each with a recommendation)

1. **Who creates a customer's keys?** `KeyService.createKeys` needs set-up credentials. *Recommendation:* add it to
   `platform-setup`'s `setup apply`, with the set-up policy's token given to that run only.
2. **One key service call per value.** Opening 50 sensitive values is 50 Vault calls. *Recommendation:* when value
   storage is built, seal all sensitive values of one case under one data key (a "case envelope") instead of caching
   plaintext keys in memory.
3. **A `platform-crypto-spring` module** (properties, health indicator, wiring), as for notifications and licensing?
   *Recommendation:* yes, with value storage, so both apps wire it the same way.
4. **licensing's `VaultTransitSigner`** has its own Vault client. *Recommendation:* leave it as it is. It is a
   command-line tool with an owner login, and moving it would make licensing depend on crypto. Revisit only if a
   third Vault user appears.
