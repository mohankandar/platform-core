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
| Record envelopes | `envelope.RecordCipher` | All sensitive values of one record under **one** data key (P019): envelope `pck1.…` once per record, items `pci1.…` per value; opening a record is one key service call. |
| Streams | `envelope.StreamCipher`, `envelope.EnvelopeInfo` | AES-256-GCM in 64 KiB chunks for documents of any size (format `platform-stream` version 1); the app stores `EnvelopeInfo`. |
| Fingerprints | `fingerprint.Fingerprinter` | HMAC-SHA256 of a normalised value with the customer's fingerprint key, for exact-match search of sensitive values. |
| Failures | `CryptoError`, `CryptoException` | One stable code per kind of failure (section 6). |
| Tests | `testkit.InMemoryKeyService` | The same behaviour and codes in memory; can be switched "down" or "denied". |
| Spring | `platform-crypto-spring`: `spring.CryptoAutoConfiguration` | `platform.crypto.*` properties, Vault login, the key service and ciphers as beans, health indicator (P019, section 10). |

AWS KMS and Azure Key Vault: only the interface for now (section 5).

## 2. Keys per customer

- A **customer** is identified by its tenant ID (`TEN-…`, as in the licence and the identity tables). `CustomerId`
  is a UUID derived from it, the same way everywhere: `CustomerId.fromTenantId(tenantId)`, a name-based UUID
  (SHA-256 of `platform-customer|<tenant ID>`, version 5 and variant bits set, like the entity UUID of an entity
  slot). So the setup command and the app always arrive at the same key names without storing a mapping. The prefix
  `platform-customer|` never changes. Every customer has its own keys, **also in a hosted installation** where several
  customers share one Vault.
- Each customer has two keys in the key service, never exportable, never in plaintext backups:
  - the **wrapping key** `customer-<32 hex>`: wraps data keys;
  - the **fingerprint key** `customer-<32 hex>-fp`: computes fingerprints.
  The prefix (`customer-`) is a setting, so one Vault can serve several installations with different prefixes.
- **Data keys:** a new random 256-bit key for every value, every record envelope and every stream, from the key service
  (`datakey/plaintext`); only its wrapped form is stored. The plaintext data key lives in memory for the one
  operation and is wiped after it.
- **Who creates keys:** the setup command (`setup apply`, `platform-setup`'s `SetupKeys`, calling
  `KeyService.createKeys`) with a login that has the set-up policy and is given to that run only. The app's runtime
  policy cannot create, rotate, export or delete keys. A customer without keys gives `CRYPTO_KEY_MISSING`. Destroying
  them at customer exit: section 11.

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

**Record envelope** (`RecordCipher`, P019): all sensitive values of one record under one data key.

```
envelope (once per record):  "pck1." + Base64url( version 1 (1) | customer UUID (16) | key service id (1 + n) | wrapped data key (2 + n) )
item (once per value):       "pci1." + Base64url( version 1 (1) | nonce (12, random per item) | ciphertext + tag (16) )
```

Associated data per item:
`platform-crypto/record/1|customer=<uuid>|keys=<key service id>|record=<record context>|item=<item context>`, so an
item cannot be moved to another customer, record or item. platform-fields uses `fld/<SHA-256 of entity, product,
record type and record ID>` as the record context and `<field key>/<position>` as the item context. `newKey` (new
record) and `openKey` (read, or add items under the same key) are **one key service call each**, whatever the number
of items; the `RecordKey` holds the plaintext key only for that operation and `close()` wipes it. No plaintext key is
cached. `rewrap` changes only the envelope. Random 96-bit nonces under one key are safe far beyond the at most
50 values per field of one record.

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
lower case. Give it the field's normalised value (platform-fields) and the field key as context. `fingerprintAll` /
`ofAll` fingerprint many values in one key service call (Vault Transit `batch_input`; `KeyService.fingerprints`).

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

# platform-crypto-setup: the set-up step. Creates the customer's keys and rotates the wrapping key. No delete.
path "transit/keys/customer-*"              { capabilities = ["create", "update", "read"] }

# platform-crypto-offboard: customer exit only (section 11). Delete, nothing else, on the same paths. Given together
# with platform-crypto-setup to the offboarding run only; never to the set-up step or the app.
path "transit/keys/customer-*"              { capabilities = ["delete"] }
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
| `VaultCryptoConfiguration`, `DisabledCryptoConfiguration` (Spring, `sdv.crypto.*`) | replaced by `platform-crypto-spring` (`platform.crypto.*`, P019) |
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
6. Set-up: the setup command creates the customer's keys (`setup apply`, owner answer P018-1); Vero's install runs it
   with the set-up login and gives the runtime the `platform-crypto-app` policy.
7. Stored data: none exists anywhere (nothing in production or test), so no re-encryption is needed. Any local
   development data encrypted as `SDV-ENC-001` does not open any more: ingest it again.

## 9. Owner answers to P018 (2026-11-22, task P019)

1. **Keys are created by the setup command.** `setup apply` (`platform-setup`, `SetupKeys`) calls
   `KeyService.createKeys` for the customer, with a Vault login given to that run only (settings
   `PLATFORM_SETUP_VAULT_*` from the environment, `platform-crypto-setup` policy). `setup plan` only reads whether the
   keys exist. Existing keys are left alone, so a rerun (or the run for the second product database) is safe. The
   running app's policy has no create or rotate rights. `--no-customer-keys` skips the step for an installation
   without sensitive values. Install guide: `docs/install/setup.md`.
2. **Case envelope:** all sensitive values of one record are sealed under one data key (`RecordCipher`, section 4),
   so opening a record is one key service call. No plaintext key is cached in memory.
3. **`platform-crypto-spring`:** built (section 10).
4. licensing's `VaultTransitSigner` stays as it is.

## 10. platform-crypto-spring

Spring Boot wiring, the same layout as the other `-spring` modules. Install guide: `docs/install/crypto.md`.

- `CryptoProperties` (`platform.crypto.*`): `enabled`, `tenant-id` (default `platform.licensing.tenant-id`),
  `vault.address`, `vault.namespace`, `vault.login` (`APPROLE` default, `TOKEN_FILE`, `TOKEN`), `vault.role-id`,
  `vault.secret-id` / `vault.secret-id-file`, `vault.token-file`, `vault.token`, `vault.approle-mount`,
  `vault.transit-mount`, `vault.key-prefix`, `vault.timeout`.
- `CryptoConfig.from` checks them at startup and stops the app with every problem listed (`CryptoConfigException`,
  `CryptoFailureAnalyzer`); no message holds a token or secret ID.
- Beans: `VaultClient` (closed on shutdown, which revokes an AppRole token), `KeyService` (`VaultKeyService`),
  `ValueCipher`, `RecordCipher`, `StreamCipher`, `Fingerprinter`, and `CustomerId` when a tenant ID is configured.
  Every bean backs off when the app defines its own; an app-defined `KeyService` (for example the in-memory one in
  tests) means no Vault beans at all.
- Vault is not called at startup: an outage never stops the app.
- `KeyServiceHealthIndicator` (`/actuator/health/keyService`, when Actuator is present): `UP` / `DOWN`, the key
  service kind, and on `DOWN` the `CRYPTO_*` code and a short reason. No secrets.

## 11. Customer key destruction (customer exit, P021)

Decision (b) + (c) of `docs/design/erase-log.md`: at customer exit, destroying the customer's keys makes **every**
record, value, document and backup of that customer unreadable. The offboarding certificate rests on this.

- **`KeyService.destroyCustomerKeys(customer)`** destroys the wrapping key and the fingerprint key, with every version.
  Vault Transit: for each key, read its versions, `POST transit/keys/<name>/config` with `deletion_allowed=true`, then
  `DELETE transit/keys/<name>`, then check it is gone (404). It returns a `KeyDestruction`: customer, key service,
  per key its role, name, latest version and `DESTROYED` / `ABSENT`, and the time. Names and version numbers only.
  A key that is already gone is `ABSENT`, so a rerun after a partial failure finishes the job. The default method of
  the interface refuses (a key service that cannot destroy says so).
- **Afterwards:** `unwrap`, `rewrap`, `newDataKey` and fingerprints give `CRYPTO_KEY_MISSING`, so `ValueCipher.open`,
  `RecordCipher.openKey`, `StreamCipher.decrypt` and `Fingerprinter` fail with that code for this customer. Other
  customers are untouched. Creating keys again for the customer makes **new** keys: nothing old opens with them.
- **Who may (P022):** only the offboarding run's login, which has **both** `platform-crypto-setup` (its `read` gives
  the versions, its `update` covers `config` `deletion_allowed`) and `platform-crypto-offboard` (`delete` only, the
  same paths). The set-up step's login no longer has `delete`, so no set-up run can destroy keys.
  - Before changing a key, `VaultKeyService` asks Vault what the login may do on it (`sys/capabilities-self`, allowed
    by Vault's default policy). Without `delete` (the set-up policy alone, the runtime policy) it refuses with
    `CRYPTO_PERMISSION_DENIED`, naming the offboard policy, and nothing is changed: the key is not even marked
    `deletion_allowed`.
  - When Vault cannot answer that question (a login without the default policy), the steps run as before and Vault
    refuses the one the login may not do.
- **The command** (`command.CryptoCommand`, `crypto destroy-customer-keys`):

  ```
  crypto destroy-customer-keys --tenant-id TEN-… --operator "<who runs it>" [--confirm TEN-…] [--result-out <file>]
                               [--backup-retention-days 35]
  ```

  - Key service login from the environment only, the same variables as the setup command
    (`PLATFORM_SETUP_VAULT_*`, `vault.VaultEnvironment`, which `SetupKeys` now uses too), but a login with the
    set-up **and** offboard policies, given to this run only.
  - `--backup-retention-days`: the backup retention of the installation, 30 to 90, default 35 (the erase log's).
    Outside that range → `CRYPTO_COMMAND_USAGE`, before the key service is called.
  - The tenant ID must be **typed back**: `--confirm`, or on the console when it is not given. Missing →
    `CRYPTO_DESTROY_NOT_CONFIRMED`, different (also a different case, or the customer UUID instead) →
    `CRYPTO_DESTROY_CONFIRMATION_WRONG`. Both before the key service is called; nothing destroyed.
  - The result file is created **before** anything is destroyed and never overwritten (`CRYPTO_DESTROY_RESULT_FILE`);
    default name `customer-keys-destroyed-<tenant>-<yyyyMMddTHHmmssZ>.json`.
  - Neither key found → `CRYPTO_DESTROY_NO_KEYS` (wrong tenant ID or prefix, or already destroyed), exit 2.
  - Exit codes: 0 destroyed, 1 failed (key service; rerun), 2 refused.
- **Result** (`platform-key-destruction/1`, written to the file and printed), the data for the offboarding certificate:

  ```json
  {
    "format": "platform-key-destruction/1",
    "tenantId": "TEN-…",
    "customer": "<customer UUID>",
    "keyService": "vault-transit",
    "keys": [
      {"role": "WRAPPING", "name": "customer-<hex>", "versions": "1-3", "state": "DESTROYED"},
      {"role": "FINGERPRINT", "name": "customer-<hex>-fp", "versions": "1-1", "state": "DESTROYED"}
    ],
    "destroyedAt": "2026-11-22T10:15:30Z",
    "liveDataDestroyedOn": "2026-11-22",
    "backupRetentionDays": 35,
    "unreadableEverywhereFrom": "2026-12-27",
    "operator": "<who ran it>"
  }
  ```

  No key material, wrapped key, token or value. **Two dates** (P022) for the certificate:
  - `liveDataDestroyedOn`: the day (UTC) of `destroyedAt`. From then the live data is unreadable.
  - `unreadableEverywhereFrom`: `liveDataDestroyedOn` + `backupRetentionDays`. From then it is unreadable everywhere,
    backups included: every database backup and every key service snapshot taken before the destruction has expired.
    This holds only if the key service's snapshot retention is **at most** the backup retention (below).

  The fields were added to `platform-key-destruction/1` (nothing removed or renamed), so the format name stays.
- **Key service backups.** Vault snapshots (and any backup of Vault's storage) still hold the keys until they expire.
  A restored snapshot would bring the keys back. So the key service's snapshot retention must be **at most the backup
  retention** (35 days by default; `docs/install/crypto.md` section 6), and the offboarding certificate gives both
  dates: live data destroyed on `liveDataDestroyedOn`, unreadable everywhere from `unreadableEverywhereFrom`. AWS KMS (`ScheduleKeyDeletion`, 7–30 days) and Azure Key
  Vault (soft delete, then purge) have the same shape when they are added.
