# Demo input files for the setup command

Demo values only. Nothing here belongs to a real customer, login system or product.

| File | What it is |
|---|---|
| `licence-demo.json` | Licence `LIC-DEMO-0001` for customer `TEN-DEMO-0001`, two entity slots, products `PRODUCT_A` and `PRODUCT_B`, kind `NON_PRODUCTION` (the apps show "Test environment"), perpetual. Signed with the **development** key `dev-demo-1`. |
| `development-trusted-keys.json` | The development public key. Only demo and test installs list it. |
| `signin-demo.json` | Sign-in file (`platform-signin/1`), direct mode. No secrets. |
| `role-mapping-product-a.csv`, `role-mapping-product-b.csv` | Role mapping per product, by slot ID. |
| `client-bindings-product-a.csv` | Integration client bindings for `PRODUCT_A` (client ID → entity slot → source; demo client IDs, no secrets). Named in `signin-demo.json` as `clientBindingsFile`, with the product's `sources`. `PRODUCT_B` has none, so it has no machine callers. |
| `product-roles-product-a.json`, `product-roles-product-b.json` | Stand-ins for the `product-roles.json` each product ships. |

Run against a demo database (one run per product database):

```
PLATFORM_SETUP_DB_URL=jdbc:postgresql://localhost/demo_a PLATFORM_SETUP_DB_USER=app_user PLATFORM_SETUP_DB_PASSWORD=...
setup plan  --licence licence-demo.json --signin signin-demo.json --product PRODUCT_A \
            --keys development-trusted-keys.json --product-roles product-roles-product-a.json
setup apply (same options)
```

**Production refuses the demo licence.** A production install verifies licences only with the trusted-keys file
shipped with the release, which lists our production signing keys (exported from Vault) and never `dev-demo-1`.
Setup and the app both stop with `LICENCE_UNKNOWN_KEY_ID` (setup: `SETUP_LICENCE_REFUSED`). Even if a production
keys file held some key under the ID `dev-demo-1`, the demo signature would not match it (`LICENCE_FORGED`); both
cases are tested. The development private key was thrown away after signing; to make new demo files, run
`mvn -pl platform-setup test -Dtest=DemoFilesGenerator -Dsetup.demo.regenerate=true`, which makes a new key.
