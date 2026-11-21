# Setup command — install guide for IT

The setup command loads your licence, your customer and entity records, your login system and your group-to-role
mapping into one product's database. Run it once per product database (or schema), with the same input files. It
checks everything first, shows you a plan, and changes nothing until you run `apply`. Running it again is safe: what
is already there stays as it is.

This version sets up **direct sign-in**: your people sign in with your own login system (for example Okta).

## 1. Before you start

- The app's schema step has run for this release (licensing and identity tables, `docs/install/licensing.md` and
  `docs/install/identity.md`). Setup never creates tables.
- You have these files:

| File | From | Holds |
|---|---|---|
| Your licence (`licence.json`) | Your account manager | Signed licence: customer, entities, products |
| `trusted-keys.json` | The product release | Our public signing keys |
| `product-roles.json` | The product release | The product's role names |
| Sign-in file (`signin.json`) | You fill it in | Your login system and, per product, client ID, API audience and mapping file |
| Role mapping file per product (`.csv`) | You fill it in | Which group gets which role in which entity |

- Database settings, in the environment only (never as options):
  `PLATFORM_SETUP_DB_URL` (`jdbc:postgresql://dbhost/appdb`), `PLATFORM_SETUP_DB_USER`, `PLATFORM_SETUP_DB_PASSWORD`.
  The app's everyday database user is enough.

## 2. The sign-in file

```json
{
  "format": "platform-signin/1",
  "tenantId": "TEN-…",
  "mode": "direct",
  "loginSystem": { "type": "OIDC", "issuer": "https://login.example.com/oauth2/default", "providerId": "okta" },
  "products": {
    "PRODUCT_A": { "browserClientId": "…", "apiAudience": "api://…", "roleMappingFile": "role-mapping-product-a.csv" }
  }
}
```

- `tenantId` must be the customer ID in your licence.
- `roleMappingFile` is relative to the sign-in file's folder.
- **No secrets.** Direct sign-in needs no client secret in setup. Any field not shown above refuses the file, so a
  secret pasted in by mistake is never read or stored (the message names the field, never its value).

## 3. The role mapping file

```
# Format: platform-role-mapping/1
# Product: PRODUCT_A
entity_id,entity_name,group,role
ENT-…,,Finance Readers,READER
```

- `entity_id` is the entity's slot ID from the licence (`ENT-…`). Leave `entity_name` empty: your Business Admin names
  each entity when activating it. If you do fill it in later, it must match.
- `role` must be one of the product's roles (`product-roles.json`), written exactly.
- The file is the complete list of grants for this product. Grants in the database that are not in the file are
  shown in the plan as `not removed` and kept, unless you add `--remove-unlisted-grants`; then the plan shows them as
  `remove` first, and `apply` removes them.

## 4. Running it

```
setup plan  --licence licence.json --signin signin.json --product PRODUCT_A \
            --keys trusted-keys.json --product-roles product-roles.json
setup apply (the same options)
```

Optional: `--config-out FILE` (default `platform-tenant.properties`), `--run-log FILE` (default
`platform-setup-runs.log`), `--remove-unlisted-grants`, `--replace-connection`.

The plan lists every item as `create`, `unchanged`, `update`, `conflict`, `remove` or `not removed`:

```
  create      licence LIC-… v1 (first licence)
  create      customer TEN-…
  create      entity slot ENT-… (NOT_ACTIVATED)
  create      login connection PRODUCT_A: provider okta, issuer https://…, browser client ID …, API audience api://…
  create      grant ENT-… PRODUCT_A: group "Finance Readers" -> READER
  create      config platform-tenant.properties (platform.licensing.tenant-id=TEN-…)
```

`apply` then writes, in one database transaction: the licence, your customer, your entities (an entity that a newer
licence drops is kept as `NOT_IN_LICENCE`, never deleted), the login connection, and the grants. Then it writes
`platform-tenant.properties` with `platform.licensing.tenant-id`; import it in the app's config
(`spring.config.import=optional:file:./platform-tenant.properties`).

**Login connection changed in the app.** If someone changed the login connection in the app since the last setup,
and it now differs from the sign-in file, the plan shows `conflict` and setup leaves it as it is. Add
`--replace-connection` to replace it with the sign-in file's values.

## 5. Results

| Exit code | Means |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Failed. If the database part failed, nothing was changed; run again. If only the config file could not be written, the database part is done; fix the folder and run again |
| 2 | Refused: nothing was changed. The message starts with a `SETUP_…` code and says what to do |
| 3 | Done, except items marked `conflict` or `not removed`, which need a flag |

Refusal codes: `SETUP_BAD_OPTION`, `SETUP_FILE_UNREADABLE`, `SETUP_KEYS_INVALID`, `SETUP_LICENCE_REFUSED` (with the
licence code, for example `LICENCE_UNKNOWN_KEY_ID`), `SETUP_SIGNIN_INVALID`, `SETUP_MODE_NOT_SUPPORTED`,
`SETUP_WRONG_TENANT`, `SETUP_PRODUCT_NOT_IN_LICENCE`, `SETUP_PRODUCT_NOT_IN_SIGNIN`, `SETUP_PRODUCT_ROLES_INVALID`,
`SETUP_MAPPING_INVALID` (with every bad line), `SETUP_DB_SETTINGS_MISSING`, `SETUP_DB_UNREACHABLE`,
`SETUP_TABLES_NOT_READY`, `SETUP_OTHER_CUSTOMER_INSTALLED`, `SETUP_LICENCE_OLDER`, `SETUP_LICENCE_VERSION_CONFLICT`,
`SETUP_CONFIG_OTHER_TENANT`. Failures: `SETUP_FAILED`, `SETUP_CONFIG_NOT_WRITTEN`.

Two setup runs on the same database at once never mix: the second waits for the first.

The run log gets one line per run with the time, product, customer and licence IDs, the counts per action and the
code. It never holds group names, secrets or the database password.

## 6. Demo files

`platform-setup/demo/` holds demo input files (demo values only), signed with a separate **development** key. A
production installation never lists that key in its `trusted-keys.json`, so it refuses the demo licence
(`SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID`). See `platform-setup/demo/README.md`.
