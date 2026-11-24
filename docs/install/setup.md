# Setup command — install guide for IT

**Needs:** Spring Boot 4.2 and Java 21 in the app (`docs/design/platform-baseline.md`).

The setup command loads your licence, your customer and entity records, your login system and your group-to-role
mapping into one product's database. Run it once per product database (or schema), with the same input files. It
checks everything first, shows you a plan, and changes nothing until you run `apply`. Running it again is safe: what
is already there stays as it is.

This version sets up **direct sign-in**: your people sign in with your own login system (for example Okta).

> **Until the app turns licensing on, the licensing tables and the stored licence exist, but nothing checks the
> allowance. Licensing is not live.**

## 1. Before you start

- The app's schema step has run for this release (licensing and identity tables, `docs/install/licensing.md` and
  `docs/install/identity.md`). Setup never creates tables.
- You have these files:

| File | From | Holds |
|---|---|---|
| Your licence (`licence.json`) | Your account manager | Signed licence: customer, entities, products |
| `product-roles.json` | The product release | The product's role names |
| Sign-in file (`signin.json`) | You fill it in | Your login system and, per product, client ID, API audience and mapping file |
| Role mapping file per product (`.csv`) | You fill it in | Which group gets which role in which entity |

- Database settings, in the environment only (never as options):
  `PLATFORM_SETUP_DB_URL` (`jdbc:postgresql://dbhost/appdb`), `PLATFORM_SETUP_DB_USER`, `PLATFORM_SETUP_DB_PASSWORD`.
  The app's everyday database user is enough.
- Key service settings, in the environment only, for this run only: setup creates your customer's own keys in Vault
  (a wrapping key and a fingerprint key, which protect sensitive case field values). Its login needs the
  `platform-crypto-setup` policy (`docs/design/crypto.md` section 7); the app itself never gets that policy. The
  set-up policy cannot delete keys; never give the setup run the `platform-crypto-offboard` policy, which is for
  customer exit only (`docs/install/crypto.md` section 5).

  | Variable | |
  |---|---|
  | `PLATFORM_SETUP_VAULT_ADDR` | Vault's address, for example `https://vault.internal:8200` |
  | `PLATFORM_SETUP_VAULT_NAMESPACE` | optional (Vault Enterprise / HCP) |
  | `PLATFORM_SETUP_VAULT_TOKEN` | one login: a token with the set-up policy, |
  | `PLATFORM_SETUP_VAULT_TOKEN_FILE` | or a file holding one, |
  | `PLATFORM_SETUP_VAULT_ROLE_ID` + `PLATFORM_SETUP_VAULT_SECRET_ID` | or an AppRole login (`PLATFORM_SETUP_VAULT_APPROLE_MOUNT`, default `approle`) |
  | `PLATFORM_SETUP_VAULT_TRANSIT_MOUNT` | default `transit` |
  | `PLATFORM_SETUP_VAULT_KEY_PREFIX` | default `customer-`; the same as the app's `platform.crypto.vault.key-prefix` |

  An installation that keeps no sensitive values can run setup with `--no-customer-keys` instead.

## 2. The sign-in file

```json
{
  "format": "platform-signin/1",
  "tenantId": "TEN-…",
  "mode": "direct",
  "loginSystem": { "type": "OIDC", "issuer": "https://login.example.com/oauth2/default", "providerId": "okta" },
  "products": {
    "PRODUCT_A": { "browserClientId": "…", "apiAudience": "api://…", "roleMappingFile": "role-mapping-product-a.csv" }
  },
  "timeZone": "America/New_York",
  "entityTimeZones": { "ENT-…": "America/Chicago" }
}
```

- `tenantId` must be the customer ID in your licence.
- `roleMappingFile` is relative to the sign-in file's folder.
- `clientBindingsFile` and `sources` (per product) are optional: only for systems that send to the product's API
  (section 3a).
- `timeZone` (the customer's) and `entityTimeZones` (per entity slot) are optional, as IANA names
  (`America/New_York`, `Europe/London`, `UTC`; not offsets such as `-05:00`). They are **defaults**: setup stores a
  zone only where none is stored yet, and keeps a zone that was already set (for example in the app). The plan shows
  `create` for a zone it will store and `unchanged` for one it keeps, so a rerun changes nothing. An entity without
  its own zone follows the customer's. A name that is not an IANA name refuses the file
  (`SETUP_TIME_ZONE_INVALID`); a zone for a slot the licence does not list refuses the run
  (`SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE`).
- **No secrets.** Direct sign-in needs no client secret in setup. Any field not shown above refuses the file,
  except three optional fields per product that this example leaves out: `clientBindingsFile` and `sources`
  (section 3a) and `integrationProfiles` (section 3b). So a secret pasted in by mistake is never read or stored (the message names the field, never its value).
- **`issuer` is your login system's `https` address,** without a query. One exception, for testing only: a local test
  login server on the same machine may use `http`, when its host is exactly `localhost`, `127.0.0.1` or `[::1]` **and**
  the licence is a demo or test licence signed by a development key (one marked `"development": true` in the
  keys inside the product's development build). A production release lists no development key, so there an `http` issuer is
  always refused (`SETUP_HTTP_ISSUER_NOT_ALLOWED`), as is any `http` address on another machine.

## 3. The role mapping file

```
# Format: platform-role-mapping/1
# Product: PRODUCT_A
entity_id,entity_name,group,role
ENT-…,,Finance Readers,READER
```

- `entity_id` is the entity's slot ID from the licence (`ENT-…`). Leave `entity_name` empty: your Business Admin names
  each entity when activating it. If you do fill it in later, it must match.
- `role` must be one of the product's roles (`product-roles.json`), written exactly, **or one of its aliases**. A
  product may list other names for its roles in `product-roles.json`, for example the names your identity team
  already uses:

  ```json
  "aliases": { "Reviewer": "READER" }
  ```

  An alias is written exactly as listed. Setup turns it into the role when it reads the file and stores the role; the
  plan shows both (`group "Finance Readers" -> Reviewer -> READER`, and one `checked  role alias "Reviewer" -> READER`
  line per alias used). A name that is neither a role nor an alias is refused, as before.
- The file is the complete list of grants for this product. Grants in the database that are not in the file are
  shown in the plan as `not removed` and kept, unless you add `--remove-unlisted-grants`; then the plan shows them as
  `remove` first, and `apply` removes them.
- **Grant history.** Every grant setup adds or removes is written to the grant history (`idn_grant_history`) in the
  same transaction, with the setup run's ID (shown on the first line of the output and in the run log as `run=…`).
  A group whose role changes from one role to another in one run is one `CHANGED` row. The app's audit page shows
  the history per entity; see `docs/install/identity.md` section 7.
- **Roles for your whole organisation: `*` rows.** Some products have platform roles (`platformRoles` in
  `product-roles.json`), for example a platform operations admin who looks after every entity. Such a role is
  granted once, for all entities, on a row with `*` as its `entity_id` (leave `entity_name` empty):

  ```
  *,,Platform Operations,PLATFORM_ADMIN
  ```

  Give platform roles only to the small group of people who run the product for the whole organisation, never to
  a department's group. A `*` row takes only a platform role (`SETUP_ENTITY_ROLE_PLATFORM_WIDE` otherwise), and a
  platform role is never granted on an entity's row (`SETUP_PLATFORM_ROLE_ON_ENTITY`); both refuse the whole file.
  The plan shows `platform grant PRODUCT_A (every entity): group "…" -> PLATFORM_ADMIN`. `*` rows follow the same
  rules as the other rows: a `*` grant no longer in the file is kept unless you add `--remove-unlisted-grants`, and
  every change is in the grant history (scope `PLATFORM`) with the run's ID. Needs identity schema version 4.

## 3a. The client-binding file (only if other systems send to the product)

Other systems that send documents to the product's API (for example your loan system) sign in with their own client
ID from your login system. This file says which client may send to which entity, and as which system. Without the
file, the product accepts no such system. Leave it out if no system sends to the product.

**1. List the sending systems in the sign-in file**, under the product:

```json
"PRODUCT_A": {
  "browserClientId": "…", "apiAudience": "api://…", "roleMappingFile": "role-mapping-product-a.csv",
  "clientBindingsFile": "client-bindings-product-a.csv",
  "sources": [ { "key": "loan-system", "name": "Loan servicing system" } ]
}
```

- `key`: a short name for the system, lower case: letters, digits, `.`, `_`, `-` (for example `loan-system`).
- `name`: a name people recognise.
- `clientBindingsFile` is relative to the sign-in file's folder. Both fields are optional; sign-in files without them
  work as before.

**2. Write the file**, one line per client, with this first line exactly:

```
client_id,entity_slot,source,description
0oa1b2c3d4E5f6G7h8i9,ENT-…,loan-system,Loan system for Finance
```

- `client_id`: the client ID your login system shows for that system's app registration (for example the Okta client
  ID), copied exactly. Capitals matter. No spaces, commas or quotes.
- `entity_slot`: the entity's slot ID from the licence (`ENT-…`). The entity need not be activated yet; the client is
  accepted only once your Business Admin has activated it.
- `source`: one of the `key`s you listed in the sign-in file.
- `description`: optional, for people (up to 200 characters). Never shown to the sending system.
- Save as "CSV UTF-8". A spreadsheet's quotes around a description with a comma are fine.

**Never put a secret in the file.** Only the client ID goes here; the client's secret or certificate stays in your
login system and in the sending system. A value that looks like a secret (a token, a key, `password=…`) refuses the
file.

**Rules setup checks** (the run is refused with `SETUP_CLIENT_BINDINGS_INVALID`, and each problem is listed with its
line number, for example `line 4: SOURCE_UNKNOWN`; nothing is written):

| Code | Means |
|---|---|
| `HEADER_WRONG` | The first line is not exactly `client_id,entity_slot,source,description` |
| `WRONG_COLUMN_COUNT`, `BAD_QUOTES`, `NOT_UTF8` | The line is not four comma-separated values, or the file is not UTF-8 |
| `CLIENT_ID_INVALID`, `SLOT_INVALID`, `SOURCE_INVALID`, `DESCRIPTION_INVALID` | A value is not written as described above |
| `SECRET_LIKE` | A value looks like a secret |
| `DUPLICATE_CLIENT_ID` | The client ID is on an earlier line too: each client sends to exactly one entity, as one system |
| `SOURCE_UNKNOWN` | The source is not listed under the product's `sources` in the sign-in file |
| `SLOT_UNKNOWN` | The entity slot is not in your licence |
| `CLIENT_BOUND_ELSEWHERE` | The client is already set up for another entity or system |

**Systems that sign in with a client certificate.** A system that calls the product with a client certificate
(mutual TLS) instead of a token from your login system gets a line too. Its `client_id` is `cert-sha256:` followed
by the certificate's SHA-256 fingerprint: 64 characters, digits and lower-case `a`–`f`, no colons. It is not a
secret: the fingerprint is public, the certificate's private key never leaves the sending system. Get it from the
certificate file (`.cer`, `.crt` or `.pem`; not the private key):

- **Windows** (PowerShell):

  ```powershell
  $c = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 'C:\certs\loan-system.cer'
  'cert-sha256:' + (([System.Security.Cryptography.SHA256]::Create().ComputeHash($c.RawData) |
      ForEach-Object { $_.ToString('x2') }) -join '')
  ```

- **Linux:**

  ```sh
  echo "cert-sha256:$(openssl x509 -in loan-system.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')"
  ```

```
cert-sha256:9664be3e90949f0310cf8428d15808bcc134b3f52c85cbfa55af328a3c19a5ab,ENT-…,loan-system,Loan system certificate 2026
```

The plan marks these lines `(client certificate)`. A `cert-sha256:` value that is not 64 lower-case hex characters is
refused (`CLIENT_ID_INVALID`).

**Renewing a certificate without a break.** Before the old certificate ends, add a second line with the new
certificate's fingerprint, for the same entity and source, and run setup. Both certificates are accepted. Once the
sending system uses the new one, remove the old line and run setup again: the old certificate is switched off
(within the product's cache time, 30 seconds by default).

**Changes.** The file is the complete list for the product. A new line is created; a line you remove switches that
client off (it is kept as INACTIVE, never deleted, and the plan shows `switch off`); a line you add back switches it
on again. Removing `clientBindingsFile` from the sign-in file switches every client of the product off. To move a
client to another entity or system: remove its line and run setup, then add the new line and run setup again. When a
newer licence drops an entity slot, remove that slot's lines too. Every change is in the plan, the run log and the
binding history (`idn_machine_binding_history`).

## 3b. Integration profiles (only if the product's integrations use one)

An integration profile describes another system's published API. Its owner publishes the profile with a release
tag. If the product's integrations use profiles, name each one in the sign-in file under the product:

```json
"integrationProfiles": [
  { "name": "acme-archive-v1", "file": "acme-archive-v1.profile.json", "tag": "acme-archive-profile-v1.2.0" }
]
```

- `name`: the profile's name, as written in the file.
- `file`: the profile file you received, relative to the sign-in file's folder. Keep it unchanged.
- `tag`: the release tag the profile came from. It ends with the profile's version (here `1.2.0`).

Setup checks each file when it reads its input files, before the database or the key service is touched: the format, every rule of the format, that the name is the one you
wrote, and that the tag names the file's version. The plan lists each profile as `checked`, and so does the run
log. **Setup stores nothing for profiles**: the product's integration screens read the file when IT sets up the
integration.

Refusals:

- `SETUP_PROFILE_TAG_MISSING`: no tag.
- `SETUP_PROFILE_INVALID`: the file is not valid, or is not the profile or version named. Each problem names the
  file and the place in it, for example `acme-archive-v1.profile.json INT_PROFILE_SCHEMA at /direction`.

Sign-in files without `integrationProfiles` work as before.

## 4. Running it

```
setup plan  --licence licence.json --signin signin.json --product PRODUCT_A \
            --product-roles product-roles.json
setup apply (the same options)
```

Optional: `--config-out FILE` (default `platform-tenant.properties`), `--run-log FILE` (default
`platform-setup-runs.log`), `--remove-unlisted-grants`, `--replace-connection`, `--no-customer-keys`.

The plan lists every item as `create`, `unchanged`, `update`, `conflict`, `remove`, `not removed`, `switch off` or `checked`:

```
  create      licence LIC-… v1 (first licence)
  create      customer TEN-…
  create      entity slot ENT-… (NOT_ACTIVATED)
  create      login connection PRODUCT_A: provider okta, issuer https://…, browser client ID …, API audience api://…
  create      grant ENT-… PRODUCT_A: group "Finance Readers" -> READER
  create      client binding PRODUCT_A: client "0oa1b2c3d4E5f6G7h8i9" -> ENT-…, source loan-system
  create      config platform-tenant.properties (platform.licensing.tenant-id=TEN-…)
Customer keys:
  create      customer keys TEN-… (<customer UUID>) in key service vault-transit
```

**Customer keys.** `plan` only reads whether your customer's keys exist. `apply` creates the missing ones just before
the database part is written; keys that exist are never changed or replaced (a rerun, or the run for the second
product, shows `unchanged`). The key names are `customer-<32 hex>` and `customer-<32 hex>-fp`, where the hex is the
customer UUID shown, derived from your tenant ID. A key service problem refuses the run before anything is written
(`SETUP_KEY_SERVICE_FAILED`, with the `CRYPTO_…` code naming the cause).

`apply` then writes, in one database transaction: the licence, your customer, your entities (an entity that a newer
licence drops is kept as `NOT_IN_LICENCE`, never deleted), the login connection, the grants and the client
bindings. Then it writes
`platform-tenant.properties` with `platform.licensing.tenant-id`; import it in the app's config
(`spring.config.import=optional:file:./platform-tenant.properties`).

**Login connection changed in the app.** If someone changed the login connection in the app since the last setup
(for example your IT Admin switched to a tested draft, `docs/install/identity.md` section 6), and it now differs from
the sign-in file, the plan shows `conflict` and setup leaves it as it is. Add `--replace-connection` to replace the
**active** connection's values with the sign-in file's. A draft the IT Admin is preparing in the app is never touched
by setup, with or without the flag.

**Customer keys required.** A product that keeps sensitive values says so in its `product-roles.json`
(`"customerKeys": "required"`). For such a product, `--no-customer-keys` is refused (`SETUP_CUSTOMER_KEYS_REQUIRED`),
whatever the licence. Without the setting (or with `"optional"`), the flag works as before. A customer set up once
with `--no-customer-keys` gets both keys from the next run without the flag; nothing else changes.

## 5. Results

| Exit code | Means |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Failed. If the database part failed, nothing was changed; run again. If only the config file could not be written, the database part is done; fix the folder and run again |
| 2 | Refused: nothing was changed. The message starts with a `SETUP_…` code and says what to do |
| 3 | Done, except items marked `conflict` or `not removed`, which need a flag |

Refusal codes: `SETUP_BAD_OPTION`, `SETUP_FILE_UNREADABLE`, `SETUP_KEYS_INVALID`, `SETUP_LICENCE_REFUSED` (with the
licence code, for example `LICENCE_UNKNOWN_KEY_ID`), `SETUP_SIGNIN_INVALID`, `SETUP_MODE_NOT_SUPPORTED`,
`SETUP_HTTP_ISSUER_NOT_ALLOWED`, `SETUP_TIME_ZONE_INVALID` and `SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE` (section 2),
`SETUP_WRONG_TENANT`, `SETUP_PRODUCT_NOT_IN_LICENCE`,
`SETUP_PRODUCT_NOT_IN_SIGNIN`, `SETUP_PRODUCT_ROLES_INVALID` (also a bad alias or `customerKeys` value),
`SETUP_MAPPING_INVALID` (with every bad line), `SETUP_PLATFORM_ROLE_ON_ENTITY` and `SETUP_ENTITY_ROLE_PLATFORM_WIDE`
(a role of the wrong kind on an entity or a `*` row, section 3), `SETUP_CLIENT_BINDINGS_INVALID` (with every bad line,
section 3a), `SETUP_PROFILE_INVALID` and `SETUP_PROFILE_TAG_MISSING` (section 3b),
`SETUP_DB_SETTINGS_MISSING`, `SETUP_DB_UNREACHABLE`,
`SETUP_TABLES_NOT_READY`, `SETUP_OTHER_CUSTOMER_INSTALLED`, `SETUP_LICENCE_OLDER`, `SETUP_LICENCE_VERSION_CONFLICT`,
`SETUP_CONFIG_OTHER_TENANT`, `SETUP_KEY_SERVICE_SETTINGS_MISSING`, `SETUP_KEY_SERVICE_FAILED`,
`SETUP_CUSTOMER_KEYS_REQUIRED` (section 4). Failures: `SETUP_FAILED`, `SETUP_CONFIG_NOT_WRITTEN`. Each code's cause
and fix are in `docs/errors/platform-error-codes.md`.

Two setup runs on the same database at once never mix: the second waits for the first.

The run log gets one line per run with the time, the run ID (`run=…`, also in the grant history), product, customer and licence IDs, the counts per action and the
code, and what happened to the customer keys (`keys=created`, `present`, `missing` after a plan, `skipped`). It
never holds group names, secrets, the database password or the key service login.

## 6. Demo files

`platform-setup/demo/` holds demo input files (demo values only), signed with a separate **development** key. A
production installation never trusts that key (only a development build does), so it refuses the demo licence
(`SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID`). See `platform-setup/demo/README.md`.
