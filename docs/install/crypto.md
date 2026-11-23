# Encryption and keys — install guide for IT

The app encrypts sensitive case field values (and, in apps that also encrypt documents, the documents) with keys that stay in your key service,
HashiCorp Vault (Transit engine). Each customer has its own two keys there; nothing secret is ever stored in the
app's database, files or logs. This page lists what the app needs (module `platform-crypto-spring`).

## 1. Vault

Set up as `docs/design/crypto.md` section 7 says: Transit at `transit/`, the AppRole method at `approle/`, and two
policies:

- `platform-crypto-app` for the **running app**: make data keys, unwrap, rewrap, fingerprints, read key versions.
  It cannot create, rotate, export or delete keys.
- `platform-crypto-setup` for the **setup command** only (`docs/install/setup.md`), which creates the customer's keys.
  It has `create`, `update` and `read` on `transit/keys/customer-*`, **no** `delete`.
- `platform-crypto-offboard` for `crypto destroy-customer-keys` at customer exit only (section 5). It has `delete`
  and nothing else, on the same paths. The offboarding run gets a login with both `platform-crypto-setup` and
  `platform-crypto-offboard`; no other login ever gets the offboard policy.

  ```hcl
  # platform-crypto-app
  path "transit/datakey/plaintext/customer-*" { capabilities = ["update"] }
  path "transit/decrypt/customer-*" { capabilities = ["update"] }
  path "transit/rewrap/customer-*" { capabilities = ["update"] }
  path "transit/hmac/customer-*" { capabilities = ["update"] }
  path "transit/keys/customer-*" { capabilities = ["read"] }
  path "secret/data/platform/*" { capabilities = ["read"] }
  # platform-crypto-setup
  path "transit/keys/customer-*" { capabilities = ["create", "update", "read"] }
  # platform-crypto-offboard
  path "transit/keys/customer-*" { capabilities = ["delete"] }
  ```

  An installation set up before P022 removes `delete` from its `platform-crypto-setup` policy and adds
  `platform-crypto-offboard`.

- **The app's own policy is the guard.** The app's `KeyService` bean has a `destroyCustomerKeys` method and its
  Vault client can read KV secrets, so what the running app may do in Vault is decided by its policy alone. The
  running app's login gets `platform-crypto-app` only: it **must not** allow `delete` on `transit/keys/*` (key
  deletion) or `create` / `update` on `secret/data/*` (KV writes). Never give the running app the set-up or the
  offboard policy, and never a wider one.

## 2. App settings (`platform.crypto.*`)

| Property | Default | |
|---|---|---|
| `platform.crypto.enabled` | `true` | `false` switches the module off |
| `platform.crypto.tenant-id` | `platform.licensing.tenant-id` | the customer ID (`TEN-…`); the setup command writes `platform.licensing.tenant-id` into `platform-tenant.properties`, which this falls back to |
| `platform.crypto.vault.address` | — | `https://vault.internal:8200` |
| `platform.crypto.vault.namespace` | none | Vault Enterprise / HCP namespace |
| `platform.crypto.vault.login` | `APPROLE` | `APPROLE` (servers), `TOKEN_FILE` (Vault Agent), `TOKEN` (local runs only) |
| `platform.crypto.vault.role-id`, `.secret-id` or `.secret-id-file` | — | AppRole login (role with `token_policies = platform-crypto-app`) |
| `platform.crypto.vault.token-file` | — | `login=TOKEN_FILE`: read again at each login |
| `platform.crypto.vault.token` | — | `login=TOKEN` |
| `platform.crypto.vault.approle-mount` | `approle` | |
| `platform.crypto.vault.transit-mount` | `transit` | |
| `platform.crypto.vault.key-prefix` | `customer-` | the same as the setup command's `PLATFORM_SETUP_VAULT_KEY_PREFIX` |
| `platform.crypto.vault.timeout` | `10s` | 1s to 2m, for connecting and for each call |

Give secrets as files (`secret-id-file`, `token-file`) or environment variables, not in a config file under version
control. Spring's environment name of a property is its name in capitals with dots as `_` and dashes removed: for
`platform.crypto.vault.secret-id` that is `PLATFORM_CRYPTO_VAULT_SECRETID`. A wrong or missing setting stops the app at startup with a list of every problem and the property names;
no message shows a token or secret ID.

The app does **not** call Vault at startup, so a Vault outage never stops it from starting: it shows in the health
endpoint, and each operation that needs a key fails with its `CRYPTO_…` code.

## 3. Health

`/actuator/health/keyService`: `UP` or `DOWN`, the key service kind (`vault-transit`) and, when down, the code
(`CRYPTO_KEY_SERVICE_UNREACHABLE`, `CRYPTO_KEY_SERVICE_SEALED`, `CRYPTO_KEY_SERVICE_TLS`, `CRYPTO_LOGIN_REFUSED`,
`CRYPTO_PERMISSION_DENIED`, …) with a short reason. Never an address with credentials, a token or a key.

## 4. Rotation

Rotating a customer's wrapping key (`transit/keys/customer-<hex>/rotate`, with the set-up policy) is safe at any time:
new data uses the new version and every older version still opens. Before switching an old version off
(`min_decryption_version`), the app rewraps what uses it (`FieldValues.rewrap` per record; `ValueCipher.rewrap`,
`StreamCipher.rewrap`). The fingerprint key is normally never rotated; if it is, search keeps finding values
fingerprinted with older versions.

## 5. Customer exit: destroying the customer's keys

When a customer leaves, destroying their two keys makes all their data unreadable everywhere, including every backup
of the app's database. **This cannot be undone.** It is a step of the offboarding runbook; its result is the data for
the offboarding certificate.

1. Agree the date with the customer, and that their data will not be needed again.
2. Get a Vault login with the `platform-crypto-setup` **and** `platform-crypto-offboard` policies, for this run
   only. A login with the set-up policy alone is refused with `CRYPTO_PERMISSION_DENIED` and nothing is changed.
   Set it in the environment, as for the setup command:

   | Variable | |
   |---|---|
   | `PLATFORM_SETUP_VAULT_ADDR` | required, Vault's address |
   | `PLATFORM_SETUP_VAULT_NAMESPACE` | optional (Vault Enterprise / HCP) |
   | `PLATFORM_SETUP_VAULT_TOKEN`, or `PLATFORM_SETUP_VAULT_TOKEN_FILE`, or `PLATFORM_SETUP_VAULT_ROLE_ID` + `PLATFORM_SETUP_VAULT_SECRET_ID` | **exactly one** login; none or more than one is `CRYPTO_SETTINGS_INVALID` |
   | `PLATFORM_SETUP_VAULT_APPROLE_MOUNT` | AppRole only, default `approle` |
   | `PLATFORM_SETUP_VAULT_TRANSIT_MOUNT` | default `transit` |
   | `PLATFORM_SETUP_VAULT_KEY_PREFIX` | default `customer-` (the same as the app's `key-prefix`) |
3. Run, from the product's distribution:

   ```
   crypto destroy-customer-keys --tenant-id TEN-… --operator "Your Name" --backup-retention-days 35
   ```

   `--backup-retention-days` is the installation's backup retention (30 to 90, default 35; the same number as
   `platform.erase-log.backup-retention-days`).

   It asks you to type the tenant ID again. For a run without a console (a script), give it as
   `--confirm TEN-…` instead. If it is missing (`CRYPTO_DESTROY_NOT_CONFIRMED`) or different
   (`CRYPTO_DESTROY_CONFIRMATION_WRONG`), nothing is destroyed. Codes: `docs/errors/platform-error-codes.md`.
4. Keep the result file (`customer-keys-destroyed-<tenant>-<time>.json`; or give `--result-out`) with the offboarding
   records. It lists the customer, the two keys with their versions, the date and time, and who ran it. No secrets.
   It carries the two dates for the certificate: **live data destroyed on** (`liveDataDestroyedOn`) and
   **unreadable everywhere, including backups, from** (`unreadableEverywhereFrom` = that day + the backup
   retention). The command prints both.
5. Revoke the Vault login.
6. **Vault backups:** Vault snapshots taken before the run still hold the keys until they expire. The second date
   holds only if snapshot retention is at most the backup retention (section 6).

The running app's login cannot do this (its policy has only `read` on the keys).

## 6. Key service backups (Vault snapshots)

Keep Vault's snapshots (and any other backup of Vault's storage) for **at most the backup retention** of the app's
database: 35 days by default, the same number as `platform.erase-log.backup-retention-days` and the
`--backup-retention-days` of `crypto destroy-customer-keys`.

- A snapshot taken before a customer's keys were destroyed still holds those keys. Restoring it would make that
  customer's database backups readable again.
- With snapshot retention at most the backup retention, the last snapshot holding the keys expires no later than the
  last database backup taken before the destruction. That day is the certificate's "unreadable everywhere, including
  backups, from" date.
- If snapshots must be kept longer, pass that longer number as `--backup-retention-days` (up to 90), so the date on
  the certificate is true.
