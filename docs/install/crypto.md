# Encryption and keys — install guide for IT

The app encrypts sensitive case field values (and, in Vero, documents) with keys that stay in your key service,
HashiCorp Vault (Transit engine). Each customer has its own two keys there; nothing secret is ever stored in the
app's database, files or logs. This page lists what the app needs (module `platform-crypto-spring`).

## 1. Vault

Set up as `docs/design/crypto.md` section 7 says: Transit at `transit/`, the AppRole method at `approle/`, and two
policies:

- `platform-crypto-app` for the **running app**: make data keys, unwrap, rewrap, fingerprints, read key versions.
  It cannot create, rotate, export or delete keys.
- `platform-crypto-setup` for the **setup command** only (`docs/install/setup.md`), which creates the customer's keys.

## 2. App settings (`platform.crypto.*`)

| Property | Default | |
|---|---|---|
| `platform.crypto.enabled` | `true` | `false` switches the module off |
| `platform.crypto.tenant-id` | `platform.licensing.tenant-id` | the customer ID (`TEN-…`); the setup command writes it into `platform-tenant.properties` |
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

Give secrets as files or environment variables (`PLATFORM_CRYPTO_VAULT_SECRET_ID`), not in a config file under version
control. A wrong or missing setting stops the app at startup with a list of every problem and the property names;
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
