# platform-core 1.1.0-rc.1

Release candidate 1 for 1.1.0 (P054). **Requires Spring Boot 4.2 and Java 21.** Not for production: wait for 1.1.0. Import
`platform-bom` 1.1.0-rc.1. Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Since 1.0.0

Additive only: batch opening of data keys (`KeyService.unwrapAll`, `RecordCipher.openKeys`) and of sensitive values
(`FieldValues.readSensitive(List)`). What it holds in full: `platform-core-v1.1.0.md`.

## Modules, design docs

Modules as in 1.0.0. `docs/design/crypto.md` section 5 and `docs/design/case-fields.md` describe the batch calls.

## Breaking changes, schemas

None. Schema versions as in 1.0.0.
