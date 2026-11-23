# Tick-off: crypto (docs/design/crypto.md)

Modules: `platform-crypto`, `platform-crypto-testkit`, `platform-crypto-spring` (plus `SetupKeys` in `platform-setup`
and the record-envelope call counts in `platform-fields`, where the design points there). Install guide:
`docs/install/crypto.md`. Read-only check on branch `docs/p035-tick-off`; verified by reading code and tests.

Paths below are shortened as follows:
- `C/` = `platform-crypto/src/main/java/com/documentplatform/crypto/`
- `CT/` = `platform-crypto/src/test/java/com/documentplatform/crypto/vault/` (real Vault through Testcontainers, `VaultDev`)
- `K/` = `platform-crypto-testkit/src/main/java/com/documentplatform/crypto/testkit/`
- `KT/` = `platform-crypto-testkit/src/test/java/com/documentplatform/crypto/testkit/`
- `S/` = `platform-crypto-spring/src/main/java/com/documentplatform/crypto/spring/`
- `ST/` = `platform-crypto-spring/src/test/java/com/documentplatform/crypto/spring/`

Counts: DONE 72 · UNTESTED 16 · PARTLY 12 · MISSING 0 · DIFFERENT 6 · APP-SIDE 5 · DOC GAP 4   (115 rules)

Gaps: A 0 · B 8 · C 34 (38 from the doc, 4 from the install guide)

## crypto — section 1 What it does

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-1 | platform-crypto is plain Java with no runtime dependencies | DIFFERENT | `platform-crypto/pom.xml` (depends on `platform-errors`) | — | Since P032 `CryptoError` / `CryptoException` implement `PlatformCode` / `HasPlatformCode`, so the module depends on platform-errors (as CLAUDE.md says). The design text still says "no runtime dependencies". Doc fix only. |
| crypto-2 | The testkit is test scope only | APP-SIDE | `K/InMemoryKeyService.java` | — | The apps declare it in test scope. |
| crypto-3 | `ValueCipher`: AES-256-GCM of one value of at most 64 KiB, text `pcv1.…` | DONE | `C/envelope/ValueCipher.java` `seal`, `MAX_VALUE_BYTES` | `CT/VaultCryptoTest.java` `valuesRoundTrip`; `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals` (64 KiB + 1 refused) | |
| crypto-4 | `RecordCipher`: all sensitive values of a record under one data key; opening a record is one key service call | DONE | `C/envelope/RecordCipher.java` `newKey`, `openKey` | `CT/RecordEnvelopeVaultTest.java` `manyValuesOneKeyAndTheyOpenOnlyInTheirPlace`; `platform-fields/.../jdbc/PostgresValueStoreTest.java` `openingARecordIsOneKeyServiceCallHoweverManySensitiveValues` | |
| crypto-5 | `StreamCipher`: 64 KiB chunks, format `platform-stream` 1; the app stores `EnvelopeInfo` | DONE | `C/envelope/StreamCipher.java`, `C/envelope/EnvelopeInfo.java` | `KT/InMemoryKeyServiceTest.java` `streamFormatRefusesReorderingDroppingAndTrailingBytes` (64 KiB record offsets); `CT/VaultCryptoTest.java` `streamsRoundTrip` | |
| crypto-6 | `InMemoryKeyService`: the same behaviour and codes, can be switched down or denied | DONE | `K/InMemoryKeyService.java` `down`, `denied` | `KT/InMemoryKeyServiceTest.java` `outagesHaveTheirCodes` | |
| crypto-7 | AWS KMS and Azure Key Vault: only the interface for now | DONE | `C/KeyService.java` (only `VaultKeyService` implements it) | n/a: nothing is built, so nothing to test | Decided absence. |

## crypto — section 2 Keys per customer

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-8 | `CustomerId.fromTenantId`: SHA-256 of `platform-customer\|<tenant ID>`, version 5 and variant bits set; the prefix never changes | DONE | `C/CustomerId.java` `fromTenantId`, `NAME_PREFIX` | `CT/RecordEnvelopeVaultTest.java` `aTenantIdAlwaysGivesTheSameCustomer` (pinned UUID) | |
| crypto-9 | A tenant ID is `TEN-…`; anything else is refused | DONE | `C/CustomerId.java` `TENANT_ID` | `CT/RecordEnvelopeVaultTest.java` `aTenantIdAlwaysGivesTheSameCustomer` (bad values) | |
| crypto-10 | Every customer has its own keys, also when several customers share one Vault | DONE | `C/vault/VaultKeyService.java` `wrappingKeyName` | `CT/VaultCryptoTest.java` `anotherCustomersDataIsRefused` (relabelled data: B's key cannot unwrap A's data key), `fingerprintsAreStablePerCustomerAndDifferAcrossCustomers` | |
| crypto-11 | Both keys are never exportable and never in plaintext backups | UNTESTED | `C/vault/VaultKeyService.java` `createKeys` (`exportable=false`, `allow_plaintext_backup=false`) | — | No test reads the created key's config back from Vault. |
| crypto-12 | Key names `customer-<32 hex>` and `customer-<32 hex>-fp` | DONE | `C/vault/VaultKeyService.java` `wrappingKeyName`, `fingerprintKeyName` | `CT/KeyDestructionVaultTest.java` `theCommandNeedsTheTenantIdTypedBackAndWritesAResultWithoutKeyMaterial`; `CT/RecordEnvelopeVaultTest.java` `aTenantIdAlwaysGivesTheSameCustomer` | |
| crypto-13 | The prefix `customer-` is a setting (checked) | DONE | `C/vault/VaultKeyService.java` constructor `PREFIX` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` ("Bad Prefix" refused); `ST/CryptoWiringTest.java` `appRoleWithASecretIdFile` (`install2-`) | |
| crypto-14 | A new random 256-bit data key for every value, record envelope and stream, from `datakey/plaintext`; only the wrapped form is stored | PARTLY | `C/envelope/ValueCipher.java` `seal`, `StreamCipher.encrypt`, `RecordCipher.newKey` (each calls `newDataKey`); `C/KeyService.java` `DataKey` (refuses non-256-bit) | `CT/VaultCryptoTest.java` `valuesRoundTrip` ("a new data key every time") | That assertion also passes with one data key and a fresh nonce. No test compares the wrapped keys of two seals or counts the calls per seal. |
| crypto-15 | The plaintext data key lives only for the one operation and is wiped after it | PARTLY | `C/KeyService.java` `DataKey.close`; `ValueCipher.open` / `StreamCipher.decrypt` (`Arrays.fill` in `finally`) | `KT/InMemoryKeyServiceTest.java` `dataKeysAreWipedAndHidden` | Only `DataKey.close` is proven. The unwrapped arrays in `open` / `decrypt` and the `RecordKey` wipe (crypto-32) are untested. |
| crypto-16 | Keys are created by the setup command (`setup apply`, `SetupKeys` → `KeyService.createKeys`) with a set-up login given to that run only | DONE | `platform-setup/.../SetupKeys.java` `create`, `vault` | `platform-setup/.../SetupCommandTest.java` `applyCreatesTheCustomersKeysAndARerunLeavesThem` | |
| crypto-17 | The app's runtime policy cannot create, rotate, export or delete keys | DONE | `platform-crypto/src/test/resources/vault/crypto-app.hcl` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` (create, rotate); `CT/KeyDestructionVaultTest.java` `theRuntimePolicyCannotDestroyKeys` | There is no export call at all (see crypto-11). |
| crypto-18 | A customer without keys gives `CRYPTO_KEY_MISSING` | DONE | `C/vault/VaultKeyService.java` `missing` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes`; `KT/InMemoryKeyServiceTest.java` `keysExistOnlyAfterCreation` | |

## crypto — section 3 Versions and rotation

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-19 | `rotate` adds a version (set-up policy); new data uses the latest version; every older version still unwraps | DONE | `C/vault/VaultKeyService.java` `rotate` | `CT/VaultCryptoTest.java` `rotationKeepsOldVersionsDecryptingAndRewrapMovesData` | |
| crypto-20 | `rewrap` (value, stream, record) wraps the data key again in the key service without touching the ciphertext; the wrapped key is not in the associated data | DONE | `ValueCipher.rewrap`, `StreamCipher.rewrap`, `RecordCipher.rewrap` | `CT/VaultCryptoTest.java` `rotationKeepsOldVersionsDecryptingAndRewrapMovesData` (same SHA-256); `CT/RecordEnvelopeVaultTest.java` `rotationAndRewrapLeaveTheItemsAsTheyAre` | |
| crypto-21 | Retiring a version = raising `min_decryption_version`; data still on it gives `CRYPTO_KEY_DISABLED` | DONE | `C/vault/VaultKeyService.java` `check`, `versions` | `CT/VaultCryptoTest.java` `rotationKeepsOldVersionsDecryptingAndRewrapMovesData`; `KT/InMemoryKeyServiceTest.java` `rotationLowestUsableAndRewrap` | |
| crypto-22 | If the fingerprint key is rotated, `searchTerms` gives one fingerprint per usable version, so old fingerprints still match | DONE | `C/fingerprint/Fingerprinter.java` `searchTerms` | `CT/VaultCryptoTest.java` `fingerprintsAreStablePerCustomerAndDifferAcrossCustomers`; `KT/InMemoryKeyServiceTest.java` `fingerprints` | |

## crypto — section 4 Formats

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-23 | Value layout `pcv1.` + version 1 \| customer UUID 16 \| key service id 1+n \| wrapped key 2+n \| nonce 12 \| ciphertext + tag, and the AAD text `platform-crypto/value/1\|customer=…\|keys=…\|context=…` | PARTLY | `C/envelope/ValueCipher.java` `encode`, `decode`, `aad` | `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals` (version byte); `CT/VaultCryptoTest.java` `anotherCustomersDataIsRefused` (customer at offset 1) | No known-answer vector pins the rest of the layout or the AAD text. A change would make stored values unreadable, and the round-trip tests would still pass. |
| crypto-24 | The context is bound: a value moved to another record or field does not open | DONE | `ValueCipher.aad` | `CT/VaultCryptoTest.java` `tamperedCiphertextIsRefused` ("moved to another record"); `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals` | |
| crypto-25 | A context is 1 to 200 of `A-Z a-z 0-9 _ . : / @ -` | DONE | `ValueCipher.checkContext` | `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals` ("bad context!") | |
| crypto-26 | One key service call per `seal` and per `open` | PARTLY | `ValueCipher.seal`, `open` | `KT/InMemoryKeyServiceTest.java` `outagesHaveTheirCodes` (open counted) | `seal` is not counted. |
| crypto-27 | Damage codes: unknown prefix or version → `CRYPTO_FORMAT_UNKNOWN`; cut short, flipped or bad Base64 → `CRYPTO_CIPHERTEXT_INVALID` | DONE | `ValueCipher.decode`, `gcm` | `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals`; `CT/VaultCryptoTest.java` `tamperedCiphertextIsRefused` | |
| crypto-28 | Record envelope `pck1.` + version \| customer \| key service id \| wrapped key, and item `pci1.` + version \| nonce \| ciphertext + tag | PARTLY | `C/envelope/RecordCipher.java` `encodeEnvelope`, `RecordKey.seal` | `CT/RecordEnvelopeVaultTest.java` `manyValuesOneKeyAndTheyOpenOnlyInTheirPlace` (prefixes only) | No known-answer vector, as in crypto-23. The tampered-envelope assertion does not check the code. |
| crypto-29 | Item AAD `platform-crypto/record/1\|customer\|keys\|record\|item`: an item cannot move to another customer, record or item | DONE | `RecordKey.aad`, `RecordCipher.check` | `CT/RecordEnvelopeVaultTest.java` `manyValuesOneKeyAndTheyOpenOnlyInTheirPlace` (moved field, moved record, `CRYPTO_WRONG_CUSTOMER`) | |
| crypto-30 | platform-fields uses record context `fld/<SHA-256 of entity, product, record type, record ID>` and item context `<field key>/<position>` | UNTESTED | `platform-fields/.../values/FieldValues.java` (`"fld/"` + hash, `itemContext`) | — | Built in platform-fields. This check found no test that pins the context form or moves a sealed value between records. Cross-check with the case-fields tick-off. |
| crypto-31 | `newKey` and `openKey` are one key service call each, whatever the number of items; no plaintext key is cached | DONE | `RecordCipher.newKey`, `openKey` | `platform-fields/.../jdbc/PostgresValueStoreTest.java` `openingARecordIsOneKeyServiceCallHoweverManySensitiveValues`, `keyRotationKeepsReadingAndSearchWorking` (a read right after the write still costs 1 call, so nothing is cached); `platform-fields-testkit/.../FieldValuesInMemoryTest.java` | |
| crypto-32 | `RecordKey.close()` wipes the plaintext key; a closed key cannot be used | UNTESTED | `RecordKey.close`, `live` | — | |
| crypto-33 | `RecordCipher.rewrap` changes only the envelope | DONE | `RecordCipher.rewrap` | `CT/RecordEnvelopeVaultTest.java` `rotationAndRewrapLeaveTheItemsAsTheyAre` | |
| crypto-34 | A random 96-bit nonce per item | UNTESTED | `RecordKey.seal` | — | No test checks that two seals of the same value give different items. |
| crypto-35 | Stream framing: header `PCSTRM01` \| version \| chunk size 65536 \| 8-byte nonce prefix; chunk 1 \| index \| length \| ct+tag; final 127 \| count \| total \| tag | DONE | `StreamCipher.encrypt`, `decrypt` | `KT/InMemoryKeyServiceTest.java` `streamFormatRefusesReorderingDroppingAndTrailingBytes` (fixed header 24 and record 65561 offsets; magic changed → `CRYPTO_FORMAT_UNKNOWN`) | |
| crypto-36 | Nonce = prefix + index; AAD binds format, version, customer, type, index and lengths: no reorder, drop, cut-off, trailing bytes or move to another customer | DONE | `StreamCipher.nonce`, `aad`, `check` | `KT/InMemoryKeyServiceTest.java` `streamFormatRefusesReorderingDroppingAndTrailingBytes`; `CT/VaultCryptoTest.java` `tamperedCiphertextIsRefused`, `anotherCustomersDataIsRefused` | |
| crypto-37 | `decrypt` writes each chunk only once it is authenticated | UNTESTED | `StreamCipher.decrypt` | — | Discarding the output on an exception is the app's job (APP-SIDE). No test checks that a damaged second chunk leaves only chunk 1 in the output. |
| crypto-38 | `EnvelopeInfo` holds format, version, key service id, customer, wrapped key, key version, encrypted size and SHA-256 of the ciphertext; `toString` leaves out the wrapped key | PARTLY | `C/envelope/EnvelopeInfo.java` | `CT/VaultCryptoTest.java` `streamsRoundTrip` (size, key service, customer); `KT/InMemoryKeyServiceTest.java` (toString) | `ciphertextSha256` is never compared with the real SHA-256 of the output. |
| crypto-39 | Fingerprint text `f<version>.<Base64url of 32 bytes>`; `parse` reads it | DONE | `Fingerprinter.Fingerprint.text`, `parse` | `CT/VaultCryptoTest.java` `fingerprintsAreStablePerCustomerAndDifferAcrossCustomers` (regex, parse) | |
| crypto-40 | HMAC-SHA256 over `"platform-crypto/fp/1" NUL context NUL canonical(value)`; the context separates fields | PARTLY | `Fingerprinter.input` | `CT/VaultCryptoTest.java` `fingerprintsAreStablePerCustomerAndDifferAcrossCustomers` ("per context") | The exact input is not pinned (no known-answer test against a known HMAC key). A change would silently break search over stored fingerprints. |
| crypto-41 | canonical = NFC, trimmed, white-space runs as one space, lower case | DONE | `Fingerprinter.canonical` | `KT/InMemoryKeyServiceTest.java` `fingerprints` (real composed vs decomposed "été", `canonical`); `CT/VaultCryptoTest.java` | |
| crypto-42 | `fingerprintAll` / `ofAll` fingerprint many values in **one** key service call (`batch_input`) | DIFFERENT | `Fingerprinter.fingerprintAll`, `ofAll`; `VaultKeyService.fingerprints` | `CT/RecordEnvelopeVaultTest.java` `batchFingerprintsEqualSingleOnesInOneCall` (equality only); `PostgresValueStoreTest` (3 calls per new record) | `ofAll` is one call. `fingerprintAll` is two (the key's versions, then one batch), as its javadoc and P019 decision 5 say. Doc fix. |

## crypto — section 5 The key service plug-in point

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-43 | `KeyService`: `id`, `createKeys`, `wrappingKey` / `fingerprintKey`, `newDataKey`, `unwrap`, `rewrap`, `versionOf`, `rotate`, `fingerprint`, `health` | DONE | `C/KeyService.java` | `KT/InMemoryKeyServiceTest.java` (OtherId implements each; all exercised); `CT/VaultCryptoTest.java` | |
| crypto-44 | Vault mapping: `aes256-gcm96`, `datakey/plaintext`, `decrypt`, `rewrap`, `keys/…/rotate`, `latest_version`, `min_decryption_version`, `hmac` sha2-256 with an explicit version | DONE | `C/vault/VaultKeyService.java` | `CT/VaultCryptoTest.java` (all through a real Vault) | |
| crypto-45 | (DOC GAP) Section 5's method list leaves out `fingerprints` (batch, P019 decision 5) and `destroyCustomerKeys` (P021/P022) | DOC GAP | `C/KeyService.java` `fingerprints`, `destroyCustomerKeys` | — | Add both to the list, and a Vault mapping row for destroy. |

## crypto — section 6 Failure codes

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-46 | `CRYPTO_SETTINGS_INVALID` names the setting | DONE | `VaultClient.checkAddress`, `VaultAuth`, `VaultKeyService` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` (ftp address, prefix, blank token), `kvSecretsAreReadAndHidden` (`../sys`) | |
| crypto-47 | `CRYPTO_KEY_SERVICE_UNREACHABLE`: refused, no route, timeout | DONE | `VaultClient.send`, `unreachable` | `CT/VaultCryptoTest.java` `vaultDownGivesItsCode` | Only the refused case is tested. |
| crypto-48 | `CRYPTO_KEY_SERVICE_TLS`: TLS handshake failed | UNTESTED | `VaultClient.tls` | — | |
| crypto-49 | `CRYPTO_KEY_SERVICE_SEALED`: sealed or not ready (503) | UNTESTED | `VaultClient.failure`, `health` | — | |
| crypto-50 | `CRYPTO_LOGIN_REFUSED`: AppRole refused, token file unreadable or empty | DONE | `VaultClient.login`, `readTokenFile` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` (wrong secret ID, blank file) | The unreadable file is not tested. |
| crypto-51 | `CRYPTO_PERMISSION_DENIED` on 401/403; an AppRole or token-file login is repeated once first | PARTLY | `VaultClient.call`, `failure` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` (denied) | The repeated login is untested; a long-running app relies on it once the token TTL ends. The code repeats only on 403, not on 401 as the doc says (Vault answers 403 for a bad token, so this is a doc detail). |
| crypto-52 | `CRYPTO_KEY_MISSING`: the customer's key does not exist | DONE | `VaultKeyService.missing` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` | |
| crypto-53 | `CRYPTO_KEY_DISABLED`: below the lowest usable version | DONE | `VaultKeyService.check` | `CT/VaultCryptoTest.java` `rotationKeepsOldVersionsDecryptingAndRewrapMovesData` | |
| crypto-54 | `CRYPTO_KEY_SERVICE_ERROR`: any other unusable answer | UNTESTED | `VaultClient.failure`, `VaultKeyService` reply checks | — | |
| crypto-55 | `CRYPTO_SECRET_MISSING`: no KV secret at that path | DONE | `VaultClient.readSecret` | `CT/VaultCryptoTest.java` `kvSecretsAreReadAndHidden` | |
| crypto-56 | `CRYPTO_WRONG_CUSTOMER`, checked before any key is used | DONE | `ValueCipher.check`, `RecordCipher.check`, `StreamCipher.check` | `CT/VaultCryptoTest.java` `anotherCustomersDataIsRefused` (with Vault, an unwrap first would give `CRYPTO_CIPHERTEXT_INVALID`, so the order is proven); `CT/RecordEnvelopeVaultTest.java` | |
| crypto-57 | `CRYPTO_KEY_SERVICE_MISMATCH`: made with another key service | DONE | `ValueCipher.check` (also record, stream) | `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals` (OtherId) | Record and stream variants untested. |
| crypto-58 | `CRYPTO_CIPHERTEXT_INVALID`: changed, cut short, reordered, wrong context, a wrapped key that is not this customer's | DONE | `ValueCipher.gcm`, `VaultKeyService.check` | `CT/VaultCryptoTest.java` `tamperedCiphertextIsRefused`, `anotherCustomersDataIsRefused` | |
| crypto-59 | `CRYPTO_FORMAT_UNKNOWN`: not a known format or version | DONE | `decode` methods | `KT/InMemoryKeyServiceTest.java` `valueFormatRefusals`, `streamFormatRefuses…` | |
| crypto-60 | Nothing secret leaves: no message, log line or `toString()` holds a token, secret ID, data key, KV value, plaintext, fingerprinted value or wrapped key | PARTLY | all classes | `CT/NoSecretInLogsTest.java` `secretsNeverLeave` (client, auth, data key, value, stream, fingerprint, KV, failures); `ST/CryptoWiringTest.java`; `CT/KeyDestructionVaultTest.java` (command output) | `RecordCipher` / `RecordKey` paths (seal, open, damaged item, wrong customer, `toString`) and `Fingerprinter.Input.toString` are not in the no-secret test. |
| crypto-61 | `CryptoException` carries no cause | UNTESTED | `C/CryptoException.java` (cause `null`) | — | No test asserts `getCause() == null`. |
| crypto-62 | The library logs only one DEBUG line (AppRole login) and WARNING lines when a token cannot be revoked | UNTESTED | `VaultClient.login`, `close` | — | The content is safe (crypto-60). The set of lines is not checked. |
| crypto-63 | Redirects are never followed (a token never goes to another address) | UNTESTED | `VaultClient` (`HttpClient.Redirect.NEVER`, 3xx message) | — | No test with a server that answers 3xx. |

## crypto — section 7 Vault set-up

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-64 | Transit at `transit/`, KV v2 at `secret/` (both settings), AppRole at `approle/` | DONE | `VaultKeyService(…, mount, …)`, `VaultClient.readSecret(mount, …)`, `VaultAuth.appRole(mount, …)` | `CT/VaultCryptoTest.java`; `ST/CryptoWiringTest.java` `aWrongConfigStopsStartup…` (transit-mount checked) | |
| crypto-65 | The design's three policy texts are the ones the tests use (`src/test/resources/vault/`) | PARTLY | `platform-crypto/src/test/resources/vault/crypto-app.hcl`, `crypto-setup.hcl`, `crypto-offboard.hcl` | `CT/VaultDev.java` loads them; behaviour tested in crypto-66..68 | They match today, but no test checks that the design and install-guide text is the same as the resources (licensing has such a test, P011 decision 17). |
| crypto-66 | `platform-crypto-app`: datakey, decrypt, rewrap, hmac (update); keys (read); `secret/data/platform/*` (read) | DONE | `crypto-app.hcl` | `CT/VaultCryptoTest.java` (every app operation runs with an AppRole on this policy; `writeSecret` refused) | |
| crypto-67 | `platform-crypto-setup`: create, update, read on `transit/keys/customer-*`; no delete | DONE | `crypto-setup.hcl` | `CT/KeyDestructionVaultTest.java` `theSetupLoginAloneCannotDestroyAndChangesNothing` | |
| crypto-68 | `platform-crypto-offboard`: delete only; given with the set-up policy to the offboarding run only | DONE | `crypto-offboard.hcl` | `CT/KeyDestructionVaultTest.java` `destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable` | Who gets the login is operator-side. |
| crypto-69 | Retiring a version (`min_decryption_version`) is an operator action with an admin token | APP-SIDE | — | — | Operator runbook (install guide section 4). |
| crypto-70 | App login: AppRole (recommended), token file (Vault Agent), plain token for local runs | DONE | `C/vault/VaultAuth.java`, `VaultClient.token` | `CT/VaultCryptoTest.java` `permissionMissingKeyAndLoginHaveTheirCodes` (token file), `start` (AppRole), `vaultDownGivesItsCode` (token) | |
| crypto-71 | Vault Enterprise / HCP namespace sent with each call | UNTESTED | `VaultClient.send` (`X-Vault-Namespace`) | — | `ST/CryptoWiringTest.java` sets a namespace but never checks the header. |
| crypto-72 | The code sample's constructors (`VaultClient(address, namespace, auth, timeout)`, `VaultKeyService(vault, "transit", "customer-")`, the ciphers) exist | DONE | `VaultClient`, `VaultKeyService`, ciphers | `CT/VaultCryptoTest.java` `start` (same constructors) | |

## crypto — section 8 Switch-over of the app that had its own crypto module

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-73 | The new stream format: magic `PCSTRM01`, AAD with `customer=`; same chunking, nonces and final record | DONE | `StreamCipher` | see crypto-35, crypto-36 | |
| crypto-74 | `KeyService.DataKey` is wiped on `close`; key service id `vault-transit`; key version as a number | DONE | `KeyService.DataKey`, `VaultKeyService.ID` | `KT/InMemoryKeyServiceTest.java` `dataKeysAreWipedAndHidden`; `CT/VaultCryptoTest.java` `streamsRoundTrip` | |
| crypto-75 | No Jackson: its own small JSON reader | DONE | `C/vault/VaultJson.java`; `platform-crypto/pom.xml` | every Vault test runs through `VaultJson` | |
| crypto-76 | The app's switch-over steps 1–7 (remove its old module, use `StreamCipher`, map `EnvelopeInfo` and codes, settings, health "not configured", the setup run, re-ingest local data) | APP-SIDE | — | — | The app that moved its crypto module here. |
| crypto-77 | Step 2 says the tenant becomes `CustomerId.of(tenantId)` | DIFFERENT | `C/CustomerId.java` | — | `CustomerId.of(String)` parses a UUID. A tenant ID goes through `CustomerId.fromTenantId` (P019 decision 1). Doc fix. |

## crypto — section 9 Owner answers to P018

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-78 | `setup apply` creates the keys with `PLATFORM_SETUP_VAULT_*` (set-up policy); `setup plan` only reads; existing keys left alone; `--no-customer-keys` skips | DONE | `platform-setup/.../SetupKeys.java` `check`, `create`, `vault` (on `VaultEnvironment`) | `platform-setup/.../SetupCommandTest.java` `applyCreatesTheCustomersKeysAndARerunLeavesThem`, `aKeyServiceProblemRefusesTheRunBeforeTheDatabaseChanges`, `theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted` | Answers 2 and 3 are covered by crypto-31 and section 10. Answer 4 (the licence signer) belongs to licensing. |

## crypto — section 10 platform-crypto-spring

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-79 | `platform.crypto.*` properties: `enabled`, `tenant-id`, `vault.address`, `namespace`, `login` (APPROLE default, TOKEN_FILE, TOKEN), `role-id`, `secret-id` / `secret-id-file`, `token-file`, `token`, `approle-mount`, `transit-mount`, `key-prefix`, `timeout` | DONE | `S/CryptoProperties.java` | `ST/CryptoWiringTest.java` (all tests) | Defaults checked in the install-guide section. |
| crypto-80 | `tenant-id` defaults to `platform.licensing.tenant-id` | DONE | `S/CryptoConfig.java` `from`; `S/CryptoAutoConfiguration.java` `cryptoCustomer` | `ST/CryptoWiringTest.java` `vaultIsWiredFromThePropertiesWithoutCallingItAtStartup` | |
| crypto-81 | `CryptoConfig.from` stops startup listing every problem (`CryptoConfigException`, `CryptoFailureAnalyzer`); no token or secret ID in any message | DONE | `S/CryptoConfig.java`, `S/CryptoFailureAnalyzer.java` | `ST/CryptoWiringTest.java` `aWrongConfigStopsStartupListingEveryProblemAndNoSecret` | `*_SETTINGS_INVALID` is an exception text here, not a `PlatformCode` (P035 cross-check B2.2). |
| crypto-82 | The `VaultClient` bean is closed on shutdown, which revokes an AppRole token | PARTLY | `S/CryptoAutoConfiguration.java` `@Bean(destroyMethod = "close")`; `VaultClient.close` | `CT/VaultCryptoTest.java` `stop` calls `close` (nothing asserted) | No test checks the revoke or the shutdown hook. |
| crypto-83 | Beans `KeyService` (Vault), `ValueCipher`, `RecordCipher`, `StreamCipher`, `Fingerprinter`; `CustomerId` only when a tenant ID is configured | DONE | `S/CryptoAutoConfiguration.java` | `ST/CryptoWiringTest.java` `vaultIsWiredFromThePropertiesWithoutCallingItAtStartup`, `appRoleWithASecretIdFile` (no customer bean) | |
| crypto-84 | Every bean backs off for the app's own; an app-defined `KeyService` means no Vault beans | PARTLY | `@ConditionalOnMissingBean` on each bean | `ST/CryptoWiringTest.java` `theAppsOwnKeyServiceWinsAndHealthFollowsIt` | Only the `KeyService` back-off is tested. The ciphers, `Fingerprinter`, `CustomerId` and the health indicator are not. |
| crypto-85 | Vault is not called at startup; an outage never stops the app | DONE | `S/CryptoAutoConfiguration.java` | `ST/CryptoWiringTest.java` `vaultIsWiredFromThePropertiesWithoutCallingItAtStartup` | |
| crypto-86 | `KeyServiceHealthIndicator` (`keyService`): UP / DOWN, the key service kind, on DOWN the code and a short reason; no secrets | DONE | `S/KeyServiceHealthIndicator.java` | `ST/CryptoWiringTest.java` `vaultIsWiredFromThePropertiesWithoutCallingItAtStartup`, `theAppsOwnKeyServiceWinsAndHealthFollowsIt` | The "only with Actuator" condition is untested (minor). |
| crypto-87 | `platform.crypto.enabled=false` switches the module off | DONE | `@ConditionalOnProperty` | `ST/CryptoWiringTest.java` `switchedOffMeansNoBeans` | |
| crypto-88 | (DOC GAP) The `CustomerId` bean is one customer per installation; a hosted installation that serves several customers derives each `CustomerId` itself (P026 decision 12, for integration credentials) | DOC GAP | `S/CryptoAutoConfiguration.java` `cryptoCustomer` | — | Section 2 says several customers may share one Vault, but section 10 does not say what the Spring wiring does then. |

## crypto — section 11 Customer key destruction

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-89 | `destroyCustomerKeys` destroys both keys with every version; Vault: read versions, `config deletion_allowed=true`, `DELETE`, check 404 | DONE | `VaultKeyService.destroyCustomerKeys`, `destroy` | `CT/KeyDestructionVaultTest.java` `destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable` | |
| crypto-90 | It returns a `KeyDestruction`: customer, key service, per key the role, name, latest version and DESTROYED / ABSENT, the time; names and numbers only | DONE | `C/KeyDestruction.java` | same test; `KT/KeyDestructionInMemoryTest.java` `destroyedKeysRefuseDecryptAndFingerprintWithTheirCode` (no key material) | |
| crypto-91 | A key already gone is ABSENT, so a rerun finishes the job | DONE | `VaultKeyService.destroy` | `CT/KeyDestructionVaultTest.java` `destroyingTheCustomersKeys…` (rerun all ABSENT) | The half-done case (one key gone) is not tested. |
| crypto-92 | The interface's default `destroyCustomerKeys` refuses | UNTESTED | `C/KeyService.java` default (`CRYPTO_KEY_SERVICE_ERROR`) | — | |
| crypto-93 | Afterwards unwrap, rewrap, new data keys and fingerprints give `CRYPTO_KEY_MISSING` (value, record, stream, fingerprint); other customers untouched | DONE | `VaultKeyService` | `CT/KeyDestructionVaultTest.java` `destroyingTheCustomersKeysMakesEverythingOfThatCustomerUnreadable`; `KT/KeyDestructionInMemoryTest.java` | |
| crypto-94 | Creating keys again for the customer gives **new** keys: nothing old opens with them | UNTESTED | Vault behaviour; `InMemoryKeyService.createKeys` | — | The offboarding certificate depends on this. No test re-creates the keys and tries the old ciphertext. |
| crypto-95 | Only the offboarding login (set-up + offboard) may destroy; `sys/capabilities-self` is asked first; without `delete` → `CRYPTO_PERMISSION_DENIED` naming the offboard policy, nothing changed, not even `deletion_allowed` | DONE | `VaultKeyService.requireDelete` | `CT/KeyDestructionVaultTest.java` `theSetupLoginAloneCannotDestroyAndChangesNothing`, `theRuntimePolicyCannotDestroyKeys`, `theCommandWithTheSetupLoginAloneFailsAndDestroysNothing` | Follows P022 decisions 3 and 4, which replace P021 decision 11. |
| crypto-96 | When Vault cannot answer the capability question, the steps run and Vault refuses the one the login may not do | UNTESTED | `VaultKeyService.requireDelete` (`!r.ok()` → return) | — | |
| crypto-97 | Command `crypto destroy-customer-keys --tenant-id --operator [--confirm] [--result-out] [--backup-retention-days]`; unknown options refused | DONE | `C/command/CryptoCommand.java` `run`, `parse` | `KT/KeyDestructionInMemoryTest.java` `confirmationMissingOrWrongIsRefusedBeforeTheKeyServiceIsCalled` (usage) | |
| crypto-98 | Login from the environment only (`PLATFORM_SETUP_VAULT_*`, `VaultEnvironment`, shared with `SetupKeys`) | DONE | `C/vault/VaultEnvironment.java`; `CryptoCommand.setupLogin` | `CT/KeyDestructionVaultTest.java` `theCommandNeedsTheTenantIdTypedBack…`; `SetupCommandTest.theVaultSettingsComeFromTheEnvironmentAndAreNeverPrinted` (none / two logins) | |
| crypto-99 | `--backup-retention-days` 30 to 90, default 35; outside → `CRYPTO_COMMAND_USAGE` before the key service is called | DONE | `CryptoCommand.retentionDays` | `KT/KeyDestructionInMemoryTest.java` `aBackupRetentionOutsideThirtyToNinetyDaysIsRefusedBeforeTheKeyServiceIsCalled`, `theCertificateDataCarriesBothDates…` | |
| crypto-100 | The tenant ID must be typed back (`--confirm` or the console); missing → `CRYPTO_DESTROY_NOT_CONFIRMED`; different, other case or the UUID → `CRYPTO_DESTROY_CONFIRMATION_WRONG`; both before the key service; nothing destroyed | DONE | `CryptoCommand.run` | `KT/KeyDestructionInMemoryTest.java` `confirmationMissingOrWrongIsRefusedBeforeTheKeyServiceIsCalled`; `CT/KeyDestructionVaultTest.java` | |
| crypto-101 | The result file is created before anything is destroyed and never overwritten (`CRYPTO_DESTROY_RESULT_FILE`); removed again when nothing was destroyed | DONE | `CryptoCommand.createResultFile`, `deleteQuietly` | `KT/KeyDestructionInMemoryTest.java` `anExistingResultFileIsNeverOverwrittenAndNothingIsDestroyed`, `aFailingKeyServiceIsReportedAndTheEmptyResultFileRemoved` | |
| crypto-102 | Default file name `customer-keys-destroyed-<tenant>-<yyyyMMddTHHmmssZ>.json` | UNTESTED | `CryptoCommand.run` (`STAMP`) | — | `theResultHoldsTheCertificateDataAndTheDefaultFileNameHasTheTenantAndTime` passes that name as `--result-out`, so the default is never used. |
| crypto-103 | Neither key found → `CRYPTO_DESTROY_NO_KEYS`, exit 2 | DONE | `CryptoCommand.run` | `CT/KeyDestructionVaultTest.java` `theCommandNeedsTheTenantIdTypedBack…` (rerun) | |
| crypto-104 | Exit codes 0 destroyed, 1 failed (rerun), 2 refused | DONE | `CryptoCommand.EXIT_*` | `KT/KeyDestructionInMemoryTest.java` (all three); `CT/KeyDestructionVaultTest.java` | |
| crypto-105 | Result `platform-key-destruction/1` with tenantId, customer, keyService, keys (role, name, versions `1-n`, state), destroyedAt, operator; written and printed | DONE | `CryptoCommand.json` | `KT/KeyDestructionInMemoryTest.java` `theResultHoldsTheCertificateData…`; `CT/KeyDestructionVaultTest.java` | |
| crypto-106 | No key material, wrapped key, token or value in the result or output | DONE | `CryptoCommand.json` | `CT/KeyDestructionVaultTest.java` `theCommandNeedsTheTenantIdTypedBackAndWritesAResultWithoutKeyMaterial` | |
| crypto-107 | Two dates (P022): `liveDataDestroyedOn` = UTC day of `destroyedAt`; `unreadableEverywhereFrom` = that day + retention; both printed | DONE | `CryptoCommand.liveDataDestroyedOn`, `unreadableEverywhereFrom` | `KT/KeyDestructionInMemoryTest.java` `theSecondDateIsTheDestructionDayPlusTheBackupRetention`, `theCertificateDataCarriesBothDatesWithTheDefaultOrTheGivenRetention` | |
| crypto-108 | The format name stays `/1` (fields only added) | DONE | `CryptoCommand.FORMAT` | `KT/KeyDestructionInMemoryTest.java` (format string asserted) | |
| crypto-109 | The key service's snapshot retention must be at most the backup retention | APP-SIDE | — | — | Operator (install guide section 6). |
| crypto-110 | Customer exit is run with a login given to that run only; the login is revoked afterwards | APP-SIDE | `CryptoCommand` calls `Connection.close` (AppRole revoke) | `KT/KeyDestructionInMemoryTest.java` (`closed` == 1) | Issuing and revoking a token login is the operator's job. The command ends its own AppRole login. |

## crypto — decisions in reports not in the design body, and stale texts

| ID | Rule | Status | Code | Test | Note |
|---|---|---|---|---|---|
| crypto-111 | (DOC GAP, P032) `CryptoError` and `CryptoCommand.Code` are `PlatformCode`s with message, cause and fix in `*.codes.properties`; the command's five codes are not in the section 6 table | DONE (P037, c3fa330) | `C/CryptoError.java`, `C/command/CryptoCommand.java` `Code`, both `.codes.properties` | — | Add a line on P032 and list the `CRYPTO_COMMAND_USAGE` / `CRYPTO_DESTROY_*` codes in section 6. |
| crypto-112 | (DOC GAP, P021 decision 10) Destroyed keys give `CRYPTO_KEY_MISSING` because Vault cannot tell "destroyed" from "never created" | DOC GAP | `CryptoError.codes.properties` `CRYPTO_KEY_MISSING.fix` | — | The fix text says "Run the setup command". After a customer exit that would create new keys, and nothing old would open with them. The design and the fix text should say so. |
| crypto-113 | `KeyService.destroyCustomerKeys` javadoc says the command calls it "with the set-up login" | DIFFERENT | `C/KeyService.java` | — | Out of date since P022: it needs the set-up and offboard login. Javadoc fix. |
| crypto-114 | The texts in `CryptoCommand.Code.codes.properties` name the options correctly | DONE (P037, 77e6652) | `platform-crypto/src/main/resources/.../command/CryptoCommand.Code.codes.properties` `*.fix` | — | The text says `--tenant`; the option is `--tenant-id`. |
| crypto-115 | The fix texts in `CryptoError.codes.properties` match the codes | DONE (P037, 77e6652) | `CryptoError.codes.properties` | — | `CRYPTO_SECRET_MISSING.fix` speaks of a "login secret (token file or AppRole secret ID)", but the code means a missing KV secret. `CRYPTO_PERMISSION_DENIED.fix` says "encrypt, decrypt and hmac"; the runtime policy is datakey, decrypt, rewrap, hmac and read on keys. |

## Install guide check: docs/install/crypto.md

| Item in guide | Kind | In code? | Difference |
|---|---|---|---|
| install-crypto-1: section 5 step 2 lists `PLATFORM_SETUP_VAULT_ADDR`, `_TOKEN`, `_TOKEN_FILE`, `_ROLE_ID` + `_SECRET_ID`, `_KEY_PREFIX` | property (env) | yes (`VaultEnvironment`) | The guide leaves out `PLATFORM_SETUP_VAULT_NAMESPACE`, `_TRANSIT_MOUNT` and `_APPROLE_MOUNT`, which the command reads. It also does not say that exactly one login may be set. |
| install-crypto-2: section 5 step 3 command line | option | yes | `--confirm TEN-…` (non-interactive confirmation) is not mentioned. `--result-out` appears only in step 4. |
| install-crypto-3: `platform.crypto.tenant-id` "the setup command writes it into `platform-tenant.properties`" | default | yes (`ConfigFragment`) | Setup writes `platform.licensing.tenant-id`, which crypto falls back to. It never writes `platform.crypto.tenant-id`. Wording only. |
| install-crypto-4: `PLATFORM_CRYPTO_VAULT_SECRET_ID` as the environment form of `platform.crypto.vault.secret-id` | property (env) | through Spring relaxed binding (legacy dash-to-underscore form) | It works, but no test covers it. Spring's documented form is `PLATFORM_CRYPTO_VAULT_SECRETID`. |

All other items match: 33 checked (13 `platform.crypto.*` properties with defaults `true`, `APPROLE`, `approle`,
`transit`, `customer-`, `10s`, the 1s–2m range; the health path `keyService` and the 5 codes listed; the 3 policies and
their capabilities; `--backup-retention-days` 30–90 default 35; the result file name pattern; `liveDataDestroyedOn` /
`unreadableEverywhereFrom`; refusal of the set-up-only login with `CRYPTO_PERMISSION_DENIED`; no Vault call at
startup; the startup problem list; `FieldValues.rewrap`, `ValueCipher.rewrap`, `StreamCipher.rewrap`).

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| crypto-11 | Keys never exportable, no plaintext backup | No test | B | In `VaultCryptoTest`, read `transit/keys/<name>` after `createKeys` and assert `exportable=false`, `allow_plaintext_backup=false` for both keys | S |
| crypto-23 | Value layout and AAD fixed | No known-answer vector | B | Known-answer test: fixed key service (fixed data key and wrapped text), fixed nonce, expected `pcv1.` text | S |
| crypto-28 | Record envelope / item layout fixed | No known-answer vector; tamper code not asserted | B | Known-answer test for `pck1.` / `pci1.`; assert `CRYPTO_CIPHERTEXT_INVALID` on a tampered envelope | S |
| crypto-40 | Fingerprint input fixed | No known-answer HMAC | B | Known-answer test with the in-memory key service's known fingerprint key, against an HMAC computed outside Java | S |
| crypto-51 | Login repeated once on a refused token | Retry untested; 401 not retried as the doc says | B | Vault test with an AppRole `token_ttl` of a few seconds: a call after expiry succeeds; align the doc (403) or the code (401 too) | S |
| crypto-60 | Nothing secret leaves | Record envelope paths and `Fingerprinter.Input` not in the no-secret test | B | Add `RecordCipher` seal / open / damaged / wrong-customer and `RecordKey` / `Input` `toString` to `NoSecretInLogsTest` | S |
| crypto-63 | Redirects never followed | No test | B | Test with a local server that answers 307 to another port: assert `CRYPTO_KEY_SERVICE_ERROR` and that the second server got no token | S |
| crypto-94 | Re-created keys are new | No test | B | In `KeyDestructionVaultTest`, `createKeys` again after destruction and assert old value / envelope / stream still fail | S |
| crypto-1 | No runtime dependencies | Design text out of date (platform-errors since P032) | C | Design section 1: "depends only on platform-errors" | S |
| crypto-14 | New data key per value | Test cannot tell a reused data key | C | Assert the wrapped keys of two seals differ, or count `newDataKey` calls per seal | S |
| crypto-15 | Data key wiped after use | Only `DataKey.close` proven | C | Test through a recording key service that keeps the returned arrays and checks they are zeroed after `open` / `decrypt` | S |
| crypto-26 | One call per seal | `seal` not counted | C | Add a call count around `seal` in `outagesHaveTheirCodes` | S |
| crypto-30 | platform-fields contexts | Context form not pinned | C | Fields test: a sealed item copied to another record does not open (`CRYPTO_CIPHERTEXT_INVALID`) | S |
| crypto-32 | `RecordKey.close` wipes, closed key unusable | No test | C | Test: after `close`, `seal` / `open` throw `IllegalStateException` | S |
| crypto-34 | Random nonce per item | No test | C | Assert two seals of the same item value differ | S |
| crypto-37 | Chunks written only once authenticated | No test | C | Damage chunk 2 of 3: assert the output holds exactly chunk 1 | S |
| crypto-38 | `EnvelopeInfo.ciphertextSha256` correct | Never compared | C | Assert it equals the SHA-256 of the encrypted bytes in `streamsRoundTrip` | S |
| crypto-42 | `fingerprintAll` one call | Doc says one call; it is two | C | Design section 4: "`ofAll` one call; `fingerprintAll` two (versions + one batch)" | S |
| crypto-45 | KeyService method list | `fingerprints`, `destroyCustomerKeys` missing from section 5 | C | Add both and a Vault mapping row for destroy | S |
| crypto-48 | `CRYPTO_KEY_SERVICE_TLS` | No test | C | Test against a local TLS server with an untrusted certificate | S |
| crypto-49 | `CRYPTO_KEY_SERVICE_SEALED` | No test | C | Local stub answering 503 to a call and to `sys/health` | S |
| crypto-54 | `CRYPTO_KEY_SERVICE_ERROR` | No test | C | Local stub answering 500 / non-JSON 200 | S |
| crypto-61 | No cause on `CryptoException` | No test | C | Assert `getCause() == null` in an existing failure test | S |
| crypto-62 | Only the stated log lines | No test | C | In `NoSecretInLogsTest`, assert the captured `com.documentplatform.crypto` records are the AppRole DEBUG line only | S |
| crypto-65 | Policy texts in docs = test resources | No guard | C | Test that the design and install guide contain each `.hcl` resource's `path` lines verbatim (as licensing does) | S |
| crypto-71 | Namespace header | No test | C | Stub server records `X-Vault-Namespace` | S |
| crypto-77 | `CustomerId.of(tenantId)` in section 8 | Doc names the wrong method | C | Design section 8 step 2: `CustomerId.fromTenantId(tenantId)` | S |
| crypto-82 | `VaultClient` closed on shutdown, AppRole token revoked | No test | C | Spring test: close the context and check `revoke-self` reached a stub | S |
| crypto-84 | Every bean backs off | Only `KeyService` tested | C | `CryptoWiringTest`: app-defined `ValueCipher` / `RecordCipher` / `StreamCipher` / `Fingerprinter` / `CustomerId` / health indicator win | S |
| crypto-88 | One customer per installation in Spring | Not in the design | C | Design section 10: the `CustomerId` bean is the installation's only customer; multi-customer apps derive their own | S |
| crypto-92 | Default destroy refuses | No test | C | Assert `CRYPTO_KEY_SERVICE_ERROR` from a `KeyService` without the override (for example OtherId) | S |
| crypto-96 | Capability check unanswerable | No test | C | Vault test with a token without the default policy (`no_default_policy`): steps run, Vault refuses the delete | S |
| crypto-102 | Default result file name | Untested | C | Run without `--result-out` in a temp working folder (or make the folder injectable) and assert the name | S |
| crypto-111 | P032 codes in design | Not stated; command codes not in the table | C · **FIXED in P037 (c3fa330)** | Design section 6: note `PlatformCode` + `.codes.properties`; add the five command codes | S |
| crypto-112 | Destroyed keys look like never created | Not stated; fix text suggests running setup | C | Design section 11 + `CRYPTO_KEY_MISSING.fix`: "after a customer exit this is expected; do not re-create" | S |
| crypto-113 | `destroyCustomerKeys` javadoc | Says set-up login | C | Javadoc: "with the set-up and offboard login" | S |
| crypto-114 | Command code texts | `--tenant` instead of `--tenant-id` | C · **FIXED in P037 (77e6652)** | Fix `CryptoCommand.Code.codes.properties` and regenerate the catalogue | S |
| crypto-115 | Crypto code fix texts | `CRYPTO_SECRET_MISSING` and `CRYPTO_PERMISSION_DENIED` fixes describe other things | C · **FIXED in P037 (77e6652)** | Rewrite both fix texts and regenerate the catalogue | S |
| install-crypto-1 | Set-up env variables | Three variables and the one-login rule missing | C | Add `_NAMESPACE`, `_TRANSIT_MOUNT`, `_APPROLE_MOUNT` and "exactly one login" to section 5 | S |
| install-crypto-2 | `--confirm` | Not mentioned | C | Mention `--confirm TEN-…` for runs without a console | S |
| install-crypto-3 | tenant-id wording | Setup writes the licensing key | C | "setup writes `platform.licensing.tenant-id`, which this falls back to" | S |
| install-crypto-4 | `PLATFORM_CRYPTO_VAULT_SECRET_ID` | Untested binding | C | Spring test binding the secret ID from that environment name, or document `…SECRETID` | S |
