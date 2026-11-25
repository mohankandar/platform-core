# platform-core 1.2.0

**Requires Spring Boot 4.2 and Java 21.** A minor release on 1.1.1: **additive only.** Every module has the same version. Import
`platform-bom` 1.2.0. Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## What's new

| Finding | Module | New |
|---|---|---|
| LV-25.1 | `platform-fields` | `FieldValues.read(List<RecordRef>)`; it and `readSensitive(List)` read a page with one query for the records (25 records: 50 statements before, 1 now; plus one for the entity's field definitions) and one key service call. SPI: `ValueStore.findAll` (default: `find` each) |
| LV-25.3 | `platform-fields` | `FieldValuesException.conflictingKeys()` |
| LV-25.4 | `platform-fields` | `FieldDefinitions.change(scope, List<FieldChange>, actor)`: every change or none. SPI: `FieldStore.saveAll` (default: refuses, saves nothing) |
| LV-25.5 | `platform-fields` | `FieldValues.withoutKeyService(…)`; code `VALUES_KEY_SERVICE_REQUIRED` |
| LV-23 | `platform-identity-core`, `-jdbc` | Replacing the ACTIVE login connection needs a test sign-in **with a token** for that version of the draft (`IDN_TEST_SIGN_IN_REQUIRED`); the draft calls with customer and product (`updateDraft`, `validate`, `activate`, `discardDraft`; the calls without them are deprecated and stay through 1.x) |
| LV-23 | `platform-setup` | `setup identity rollback-login-connection`: back to the previous connection with the setup login, no session; code `SETUP_NO_PREVIOUS_CONNECTION` |
| LV-24.2 | `platform-notifications-spring`, `-monitoring-spring` | `platform.notifications.mail-servers.<name>.*` with `tenants` and `destinations`; one monitoring check each |
| LV-24.5 | `platform-notifications` | `Approvals.decidedElsewhere`, `ApprovalAudit.recordElsewhere` (default method) |
| LV-24.4 | `platform-licensing-spring` | The admin's warning days: four thresholds of `licensing.licence-expiring` (defaults 60, 30, 7, 1) |
| LV-24.10 | `platform-licensing` | `LicenceBuild.isDevelopment()`, `isDevelopmentLicence(licence)` |

## What an app must check

- **The login-connection screen.** A draft tested without a token can still become validated, but no longer replaces an ACTIVE
  connection. This cannot break a running installation: the refusal switches nothing, people keep signing in through the ACTIVE
  connection, and the draft stays for a test with a token. The very first connection needs no token. An app whose screen has no
  field for the token must add one before its admins can switch; the setup command can always replace the connection.
- **Drafts open during the upgrade:** kept. One validated before needs its test sign-in once more, with a token, if it replaces an
  ACTIVE connection.
- **The settings screen** shows the four new thresholds of "licence expiring" when the app lists thresholds from the catalogue.

## Database schemas

| Module | Version | New |
|---|---|---|
| identity | **6** | `V6__test_sign_in.sql`: one nullable column, `idn_login_connection.test_sign_in_version`. No new rights. Run the identity schema step before starting the app (`IDN_VERSION_OLDER` otherwise); no order relative to the other modules |
| others | as in 1.1.x | licensing 2, fields 2, erase log 3, integration 3, model governance 5, notifications 1 |

An app of 1.1.x does not start on identity version 6 (`IDN_VERSION_NEWER`): run the step when every instance is stopped.

## Modules, design docs

As in 1.1.x; no module added. Updated: case-fields, setup, notifications, licensing; the install guides and the switch-over guide
("New in 1.2.0").

## Breaking changes

None in signatures, settings' defaults, codes or released SQL (`docs/api/public-api-1.txt`: lines added only). One deliberate
refusal is new: `IDN_TEST_SIGN_IN_REQUIRED` (above).

## Install proof

Candidate rc.1, whose code is this release's, from the published artifacts on `ubuntu-latest` and `windows-latest`:
<https://github.com/mohankandar/platform-core/actions/runs/45688094927>.
