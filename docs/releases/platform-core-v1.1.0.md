# platform-core 1.1.0

**Requires Spring Boot 4.2 and Java 21.** A minor release: **additive only, no change for callers of 1.0.0.** Every module has
the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>1.1.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## What's new

A results page with sensitive values takes one key service call instead of one per record (measured for 25 records: 25 before,
1 now).

| Module | New |
|---|---|
| `platform-crypto` | `KeyService.unwrapAll(customer, wrapped)`: up to `KeyService.MAX_BATCH` (100) data keys, one `Unwrapped` per item in the order given. An item that was changed, is another customer's or has a switched-off key version fails alone; key service failures throw. Vault: one Transit `decrypt` call. `RecordCipher.openKeys` for record envelopes |
| `platform-crypto-testkit` | `InMemoryKeyService.unwrapAll` is one counted call |
| `platform-fields` | `FieldValues.readSensitive(List<RecordRef>)`: up to 100 records, one `SensitiveRead` per record with exactly the single read's values or exception, one batched key service call |

A `KeyService` of your own keeps working: `unwrapAll` has a default that calls `unwrap` per item. If it wraps another
`KeyService`, forward `unwrapAll` to get the batch.

No new permission: the app's Vault policy already allows `decrypt`.

## Modules

As in 1.0.0; no module added.

## Design docs implemented

As in 1.0.0; `docs/design/crypto.md` section 5 and `docs/design/case-fields.md` have the batch calls.

## Breaking changes

None. No signature, behaviour, error code, schema or setting of 1.0.0 changed (`docs/api/public-api-1.txt`: lines added only).

## Database schemas

As in 1.0.0: identity 5, licensing 2, fields 2, erase log 3, integration 3, model governance 5, notifications 1.
