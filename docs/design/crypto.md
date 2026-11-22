# platform-crypto — encryption and keys (P018)

Modules `platform-crypto` (package `com.documentplatform.crypto`; plain Java, no Spring, no runtime dependencies) and
`platform-crypto-testkit` (test scope only). Built in P018 by moving Vero's `sdv-crypto` here and making it
product-neutral. Vero removes `sdv-crypto` at its switch-over (section 8). LexaVault uses it with value storage
(sensitive case fields, `docs/design/case-fields.md` rule 6).

## 1. What it does

| Part | Class | What |
|---|---|---|
| Key service plug-in point | `KeyService` | Per-customer keys that never leave the key service: wrap / unwrap / rewrap data keys, rotate, versions, HMAC fingerprints, health. |
| Vault | `vault.VaultClient`, `vault.VaultAuth`, `vault.VaultKeyService` | HashiCorp Vault: Transit (keys) and KV version 2 (secrets); login by token, token file or AppRole. |
| Values | `envelope.ValueCipher` | AES-256-GCM of one small value (≤ 64 KiB), self-contained text `pcv1.…` for one column. |
| Streams | `envelope.StreamCipher`, `envelope.EnvelopeInfo` | AES-256-GCM in 64 KiB chunks for documents of any size (format `platform-stream` version 1); the app stores `EnvelopeInfo`. |
| Fingerprints | `fingerprint.Fingerprinter` | HMAC-SHA256 of a normalised value with the customer's fingerprint key, for exact-match search of sensitive values. |
| Failures | `CryptoError`, `CryptoException` | One stable code per kind of failure (section 6). |
| Tests | `testkit.InMemoryKeyService` | The same behaviour and codes in memory; can be switched "down" or "denied". |

AWS KMS and Azure Key Vault: only the interface for now (section 5).

## 2. Keys per customer

- A **customer** is its tenant ID (`CustomerId`, the UUID of the licence and the identity tables). Every customer
  has its own keys, **also in a hosted installation** where several customers share one Vault.
- Each customer has two keys in the key service, never exportable, never in plaintext backups:
  - the **wrapping key** `customer-<32 hex>`: wraps data keys;
  - the **fingerprint key** `customer-<32 hex>-fp`: computes fingerprints.
  The prefix (`customer-`) is a setting, so one Vault can serve several installations with different prefixes.
- **Data keys:** a new random 256-bit key for every value and every stream, from the key service
  (`datakey/plaintext`); only its wrapped form is stored. The plaintext data key lives in memory for the one
  operation and is wiped after it.
- **Who creates keys:** the set-up step (`KeyService.createKeys`, idempotent) with the set-up policy. The app's runtime
  policy cannot create, rotate, export or delete keys. A customer without keys gives `CRYPTO_KEY_MISSING`.

## 3. Versions and rotation

- `rotate(customer)` adds a version to the wrapping key (set-up policy). New data is wrapped with the latest version;
  **every older version still unwraps**.
- `rewrap` (`ValueCipher.rewrap`, `StreamCipher.rewrap`) wraps a stored data key again with the latest version, in
  the key service, without the data key leaving it and without touching the ciphertext (the wrapped key is not part
  of the associated data). The app stores the result in place of the old one.
- To retire an old version: rewrap everything that uses it, then raise the key's lowest usable version (Vault:
  `min_decryption_version`). Data still on a retired version gives `CRYPTO_KEY_DISABLED`.
- The fingerprint key is normally never rotated (stored fingerprints would have to be computed again from the values).
  If it is, `Fingerprinter.searchTerms` returns one fingerprint per usable version, so search still finds values
  fingerprinted with an older version until they are redone.

## 4. Formats

**Value** (`ValueCipher`): `pcv1.` + Base64url of

```
version 1 (1) | customer UUID (16) | key service id (1 + n) | wrapped data key (2 + n) | nonce (12) | ciphertext + tag (16)
```

Associated data: `platform-crypto/value/1|customer=<uuid>|keys=<key service id>|context=<context>`. The **context**
is the caller's, for example `case:<case id>:field:<field key>`: a value moved to another record or field does not
open. One key service call per `seal` and per `open`.

**Stream** (`StreamCipher`), moved from Vero's streaming envelope with the same framing:

```
header:  "PCSTRM01" | version (int) | chunk size 65536 (int) | nonce prefix (8 random bytes)
chunk:   1 | index (int) | plaintext length (int) | ciphertext + tag
final:   127 | chunk count (int) | total length (long) | tag
```

Nonce = prefix + chunk index. Associated data per record: format, version, customer, record type, index, lengths.
Chunks cannot be reordered, dropped, cut off (the authenticated final record), or moved to another customer.
`decrypt` writes each chunk once it is authenticated; on an exception the caller discards the output.
`EnvelopeInfo` (format, version, key service id, customer, wrapped key, key version, encrypted size, SHA-256 of the
ciphertext) is stored by the app next to the object.

**Fingerprint:** `f<version>.<Base64url of 32 bytes>`, HMAC-SHA256 over
`"platform-crypto/fp/1" NUL context NUL canonical(value)`; canonical = NFC, trimmed, white space runs as one space,
lower case. Give it the field's normalised value (platform-fields) and the field key as context.

## 5. The key service plug-in point

`KeyService`: `id`, `createKeys`, `wrappingKey` / `fingerprintKey` (versions), `newDataKey`, `unwrap`, `rewrap`,
`versionOf`, `rotate`, `fingerprint(customer, version, input)`, `health`. How the later implementations map:

| KeyService | Vault Transit (built) | AWS KMS (later) | Azure Key Vault (later) |
|---|---|---|---|
| wrapping key | `aes256-gcm96` key | symmetric KMS key (alias per customer) | RSA-HSM or AES (Managed HSM) key |
| `newDataKey` | `datakey/plaintext` | `GenerateDataKey` | random key + `wrapKey` |
| `unwrap` | `decrypt` | `Decrypt` | `unwrapKey` |
| `rewrap` | `rewrap` | `ReEncrypt` | `unwrapKey` + `wrapKey` |
| `rotate`, versions | `keys/…/rotate`, `latest_version`, `min_decryption_version` | key rotation (backing keys) | new key version |
| retired version | below `min_decryption_version` | disabled key | disabled version |
| `fingerprint` | `hmac` (sha2-256, explicit version) | HMAC KMS key, `GenerateMac` | no HMAC operation: a per-customer fingerprint key wrapped by Key Vault, HMAC in the app (the "where possible" case) |

## 6. Failure codes

| Code | When |
|---|---|
| `CRYPTO_SETTINGS_INVALID` | address, mount, key prefix, secret path or login setting not valid (the message names the setting) |
| `CRYPTO_KEY_SERVICE_UNREACHABLE` | connection refused, no route, timeout |
| `CRYPTO_KEY_SERVICE_TLS` | TLS handshake failed (Vault's CA not in the Java trust store) |
| `CRYPTO_KEY_SERVICE_SEALED` | Vault sealed or not ready (HTTP 503) |
| `CRYPTO_LOGIN_REFUSED` | AppRole login refused, token file unreadable or empty |
| `CRYPTO_PERMISSION_DENIED` | the token's policy does not allow the call (401/403; an AppRole or token-file login is repeated once first) |
| `CRYPTO_KEY_MISSING` | the customer's key does not exist |
| `CRYPTO_KEY_DISABLED` | the data's key version is switched off (below the lowest usable version) |
| `CRYPTO_KEY_SERVICE_ERROR` | any other unusable answer |
| `CRYPTO_SECRET_MISSING` | no KV secret at that path |
| `CRYPTO_WRONG_CUSTOMER` | the data is another customer's (checked before any key is used) |
| `CRYPTO_KEY_SERVICE_MISMATCH` | the data was made with another key service |
| `CRYPTO_CIPHERTEXT_INVALID` | changed, cut short, reordered, wrong context, or a wrapped key that is not this customer's |
| `CRYPTO_FORMAT_UNKNOWN` | not a known format or version |

**Nothing secret leaves:** no message, log line or `toString()` holds a token, a secret ID, a data key, a KV value, a
plaintext, a fingerprinted value or a wrapped key; only ids (customer, key name and version), the setting's name and
Vault's short error text. `CryptoException` carries no cause. The library logs one DEBUG line (an AppRole login) and
WARNING lines when a login token cannot be revoked. Redirects are never followed.

## 7. Vault set-up

Transit at `transit/`, KV version 2 at `secret/` (both are settings), the AppRole method at `approle/`. The two
policies (the same text is used by the tests, `platform-crypto/src/test/resources/vault/`):

```hcl
# platform-crypto-app: what the app may do at runtime.
path "transit/datakey/plaintext/customer-*" { capabilities = ["update"] }
path "transit/decrypt/customer-*"           { capabilities = ["update"] }
path "transit/rewrap/customer-*"            { capabilities = ["update"] }
path "transit/hmac/customer-*"              { capabilities = ["update"] }
path "transit/keys/customer-*"              { capabilities = ["read"] }
path "secret/data/platform/*"               { capabilities = ["read"] }

# platform-crypto-setup: the set-up step. Creates the customer's keys and rotates the wrapping key.
path "transit/keys/customer-*"              { capabilities = ["create", "update", "read"] }
```

Retiring a version (`transit/keys/<name>/config`, `min_decryption_version`) is an operator action with an admin token.
App login: AppRole (role with `token_policies = platform-crypto-app`) is recommended for servers; a token file kept up
to date by Vault Agent or the orchestrator is the alternative; a plain token for local runs. The app builds:

```java
VaultClient vault = new VaultClient(address, namespace, VaultAuth.appRole("approle", roleId, secretId), timeout);
KeyService keys = new VaultKeyService(vault, "transit", "customer-");
ValueCipher values = new ValueCipher(keys);
StreamCipher streams = new StreamCipher(keys);
Fingerprinter fingerprints = new Fingerprinter(keys);
```

## 8. Vero's switch-over (sdv-crypto removed)

What moved, and what changed on the way:

| sdv-crypto | platform-crypto |
|---|---|
| `SdvEnc001StreamingCrypto` (profile `SDV-ENC-001`, magic `SDVENC01`, AAD with `tenant=`) | `StreamCipher` (format `platform-stream` 1, magic `PCSTRM01`, AAD with `customer=`); same chunking, nonces, final record |
| `KeyProtectionProvider`, `ProtectedDataKey` | `KeyService`, `KeyService.DataKey` (wiped on `close`) |
| `VaultTransitKeyProtectionProvider` (token only, `IOException` with text) | `VaultKeyService` + `VaultClient` (token, token file, AppRole; namespace; specific codes; no redirects) |
| key created on every `generateDataKey` (runtime needed create rights) | keys created once by the set-up step; runtime policy has no create |
| key name `sdv-tenant-<hex>` | `customer-<hex>` (prefix is a setting) |
| provider id `VAULT_TRANSIT`, version text `v7` | key service id `vault-transit`, key version as a number |
| `KeyServiceHealthProbe` (in sdv-application) | `KeyService.health()` |
| `VaultCryptoConfiguration`, `DisabledCryptoConfiguration` (Spring, `sdv.crypto.*`) | not moved: wiring stays in the app (or a later `platform-crypto-spring`) |
| Jackson | none (own small JSON reader) |
| — | new: `ValueCipher`, `Fingerprinter`, KV secrets, rewrap, rotation, retired versions |

What Vero changes:

1. Remove the `sdv-crypto` module (parent `<modules>`, the dependency in `sdv-web`). Add `platform-crypto` (version
   from `platform-bom`) and, in test scope, `platform-crypto-testkit` instead of its own in-memory key provider.
2. Implement `StreamingCryptoPort` with `StreamCipher` (or replace the port by it). `tenantId` becomes
   `CustomerId.of(tenantId)`. Map `CryptoEnvelope` ↔ `EnvelopeInfo`: `cryptoProfile` = `platform-stream`,
   `cryptoVersion` = 1, `keyProviderId` = `vault-transit`, `protectedDataKeyReference` = `wrappedKey`,
   `keyProviderVersion` = the key version, `encryptedSizeBytes`, `ciphertextSha256`. `document_crypto_metadata`
   keeps its columns (the customer is the row's `tenant_id`).
3. Crypto failures are `CryptoException` (unchecked) with a code, not `IOException` text. Map the codes to Vero's:
   `CRYPTO_SETTINGS_INVALID` → SDV-SYS-007; `CRYPTO_KEY_SERVICE_UNREACHABLE`, `_SEALED`, `_TLS` → SDV-KEY-002;
   `CRYPTO_PERMISSION_DENIED`, `CRYPTO_LOGIN_REFUSED` → SDV-KEY-003; `CRYPTO_CIPHERTEXT_INVALID`,
   `CRYPTO_WRONG_CUSTOMER`, `CRYPTO_KEY_DISABLED` → SDV-KEY-004; `CRYPTO_KEY_MISSING` → a set-up problem (new code).
   Stream read/write errors stay `IOException`.
4. Settings: `SDV_VAULT_BASE_URL`, `SDV_VAULT_TOKEN` / `SDV_VAULT_TOKEN_FILE`, mount, key prefix → `VaultClient` +
   `VaultAuth.token` / `tokenFile` (add AppRole role ID + secret ID as the recommended option) + `VaultKeyService`.
   `SDV_KEY_PROVIDER=disabled` and SDV-KEY-001 stay Vero's own.
5. Health: the key service health indicator uses `KeyService.health()`; Vero keeps `NOT_CONFIGURED` for "disabled".
6. Set-up: call `KeyService.createKeys(customer)` for the customer with set-up credentials (Vero's install step, or
   `platform-setup`: see the P018 report's questions) and give the runtime the `platform-crypto-app` policy.
7. Stored data: none exists anywhere (nothing in production or test), so no re-encryption is needed. Any local
   development data encrypted as `SDV-ENC-001` does not open any more: ingest it again.
