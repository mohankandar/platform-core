# platform-core 1.0.0-rc.2

Release candidate 2 for 1.0.0 (P047). **Requires Spring Boot 4.2 and Java 21.** Not for production: wait for 1.0.0. Import
`platform-bom` 1.0.0-rc.2. Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Since 1.0.0-rc.1

- **The key-service check looks at the customer's keys** (found by the install proof of rc.1). Before, it asked the key service's
  health only, so an app whose customer keys were missing started. Now `customer-keys-required=true` refuses to start with
  `CRYPTO_KEY_MISSING`, as the guide says. Without it, missing keys are no problem. New constructor
  `KeyServiceCheck(KeyService, CustomerId, boolean)`; the earlier one checks the key service only, as before.

## Breaking changes, schemas

None. Schema versions as in 1.0.0-rc.1.
