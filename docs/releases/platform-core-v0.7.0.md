# platform-core 0.7.0

Security fixes from the independent review P042 (`docs/work/security-findings.md`), tasks P044 and P045. **Not a
drop-in update:** six new schema versions, a new callback signature, and licence keys that now come from the product's
build. Read "What apps do" and "What installations do". Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.7.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Findings fixed

| Finding | Severity | What changed |
|---|---|---|
| SEC-2 | Medium | The set-up Vault policy can no longer destroy key versions |
| SEC-3 | Medium | The erase-log chain is keyed with the customer's key service key; forged entries are never acted on |
| SEC-4 | Medium | A database restored while the app runs is noticed, and work stops until the replay |
| SEC-5 | Medium | Field values are erased only through the erase log |
| SEC-6 | Medium | The model gate checks approval, use and entity itself; the database keeps live approved |
| SEC-7 | Medium | A person's token is never a push caller |
| SEC-8 | Medium | Callback signature v2 covers the callback ID |
| SEC-10 | Medium | Integration secrets are tied to their entity (`int/<entity>/<name>`) |
| SEC-11 | Medium | The diagnostic bundle reads an allow-list of sources and property names |
| SEC-12 | Medium | Licence keys come only from the app's build |
| SEC-13 | Medium | The everyday database user cannot turn the licence clock back or bring back an older licence |
| SEC-16, SEC-17, SEC-18 | Low | Certificate bindings only by certificate; grants of a replaced login system are listed by setup; more secret shapes found |
| SEC-19 to SEC-23 | Low | Stronger `SafeText`; one-line details; the caller no longer chooses the reference; references not guessable from each other; readiness shows state and code only |
| SEC-26, SEC-27 | Low | Unpaired surrogates refused in signed files; the usage chain's claim corrected, with checks against kept anchors |
| SEC-30 | Low | Plain http to Vault only on this machine |
| SEC-31, SEC-32, SEC-33 | Low | Approver and validator are different people; role spelling normalised; history tables insert only |
| SEC-34 | Low | Fixed `search_path` for every function of every schema. Not done: a warning when the app's user holds DELETE on `mg_measure` |
| SEC-43, SEC-44, SEC-45, SEC-49, SEC-51, SEC-54, SEC-55, SEC-56 | Info | Development keys only in development builds; exact group names; text-direction characters refused; erase-log hardening; file size limits; `Verdict` text; reference filter before Spring Security; no driver text in store failures |
| SEC-48 | Info | Partly: the fingerprint canonical form is unchanged |

## What apps do

1. **Licence keys in the build (breaking).** Put the public keys in the app's jar as
   `META-INF/platform/licence-keys.json`, and write `META-INF/platform/licence-build.properties` with `built-at=<time>`
   in the build. A development build, and only that, adds `development-keys=true`. `platform.licensing.trusted-keys` and
   `trusted-keys-file` are gone and stop the app. The setup and licence commands lost `--keys`: ship them in a wrapper
   with the same two files on the classpath exactly once. Tests give a `LicenceBuild` bean (`TestLicences.licenceBuild()`).
2. **API changes.** `LicenceStore.highestVersion` is new and must be implemented by an app's own store.
   `LicenceCommand.jdbc` / `service` take a `LicenceBuild`. `LicensingProperties` lost its trusted-key settings.
   `SetupCommand` has a constructor with a `LicenceBuild`.
3. **Callbacks.** Receivers verify `v2` (`CallbackVerifier`). `v1` only with
   `platform.integration.callbacks.accept-v1-signatures=true`, off by default. Senders send `v2` only, so update receivers
   first.
4. **Integration secrets** are named `int/<entity UUID or slot ID>/<name>`; others are refused
   (`INT_CONFIG_SECRET_SCOPE`).
5. **Push callers.** `sub` is no longer a default client-ID claim, and tokens with user claims are refused
   (`platform.identity.push.user-claims`, `.machine-claim`).
6. **Group names** are compared exactly; a text claim is one group.
7. **Model governance.** The approver may not be the person who ran the validation (`MOD_APPROVER_SAME_PERSON`).
8. **Reference.** `X-Request-Id` in the answer is always made by the platform; the caller's own is in the MDC as
   `requestId`. References sort by tenth of a second.
9. **Diagnostic bundle.** Add the app's own property prefixes with `platform.monitoring.bundle-properties`.

## What installations do

1. Run every schema step (below) as the schema owner, and adjust the grants as the install guides list
   (identity, model governance, licensing).
2. **Erase log:** with the key service, the app takes no work until IT has run `--erase-log-seal` once
   (`docs/install/erase-log.md`).
3. **Vault:** replace the set-up and offboard policies (`docs/install/crypto.md`).
4. **Licensing:** `grant select on lic_version_floor`, `revoke delete on lic_last_seen` and
   `revoke update, delete on lic_clock_reset` for the app's user; remove the trusted-key settings. `licence apply-reset`
   is run with the schema owner's user.
5. **Identity:** check that group names match the role mapping character by character
   (`docs/install/identity.md`, "Upgrading to 0.7.0").
6. **Health:** `management.endpoint.health.show-details=when-authorized`.

## Database schemas

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 4 → **5** |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 → **2** |
| platform-fields | `db/platform/fields` | `fld_schema_history` | 1 → **2** |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 → **3** |
| platform-integration | `db/platform/integration` | `int_schema_history` | 2 → **3** |
| platform-model-governance | `db/platform/modelgov` | `mg_schema_history` | 4 → **5** |
| platform-notifications | `db/platform/notifications` | as before | unchanged |

## New codes

`ERASE_LOG_NOT_SEALED`, `ERASE_LOG_KEY_UNAVAILABLE`, `INT_CONFIG_SECRET_SCOPE`, `IDN_PUSH_NOT_MACHINE`,
`MOD_APPROVER_SAME_PERSON`, `RESET_NEEDS_OWNER`. No code renamed or removed. Texts:
`docs/errors/platform-error-codes.md`.
