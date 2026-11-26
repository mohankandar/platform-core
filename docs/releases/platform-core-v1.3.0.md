# platform-core 1.3.0

**Requires Spring Boot 4.2 and Java 21.** A minor release on 1.2.0: **additive only.** Every module has the same version. Import
`platform-bom` 1.3.0. Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## What's new

| Board entry | Module | New |
|---|---|---|
| LV-25.2 | `platform-fields` | The field stores in the app's own transaction: `FieldValues.in(Connection)`, `FieldDefinitions.in(Connection)`, `PostgresValueStore.onConnection`, `PostgresFieldStore.onConnection`; SPI `ValueStore.joined`, `FieldStore.joined` (default: `UnsupportedOperationException`). READ COMMITTED with auto-commit off only (`VALUES_TRANSACTION_UNSUITABLE`, `FIELD_TRANSACTION_UNSUITABLE`); one savepoint per write, so a refused write leaves the app's transaction usable; erasing stays in the erase log's own transaction (`VALUES_ERASE_OUTSIDE_LOG`). No Spring bean: `DataSourceUtils` in the guide |
| LV-24.3 (step 1) | `platform-notifications`, `-notifications-spring` | The Microsoft 365 sign-in token request and the Teams and Slack posts through `platform.outbound.*` (address guard, proxy with sign-in and bypass, private CA, https only), **opt-in**. Port `transport.HttpTransport`; new constructors of `MicrosoftTokens`, `TeamsChannel`, `SlackChannel`; codes `MAIL_OUTBOUND_REFUSED`, `WEBHOOK_OUTBOUND_REFUSED` (not retried). SMTP itself follows in 1.4.0 |
| LV-28 | `platform-fields` | Dropdown values with display names: `FieldSpec.Option(code, name)`, `FieldSpec.namedOptions()`, `displayName(code)`, `Builder.namedOptions`; codes `FIELD_OPTION_NAME_INVALID`, `FIELD_OPTION_NAME_REPEATED`. The code stays what is stored, matched, searched and imported; `options()` still lists the codes |

**Additive only:** every 1.x signature, behaviour, setting default, error code and released SQL file stays; `docs/api/public-api-1.txt` only gains
lines. (The new last component of the `FieldSpec` record changes its record pattern: an app that deconstructs `FieldSpec` with a record
pattern adds one component. Its 21-argument constructor stays.)

## Settings

| Setting | Default | What it does |
|---|---|---|
| `platform.notifications.mail.via-outbound` | `false` | The Microsoft 365 sign-in of `mail.*` through `platform.outbound.*` |
| `platform.notifications.mail-servers.<name>.via-outbound` | `false` | The same for a further mail server |
| `platform.notifications.webhook.via-outbound` | `false` | The Teams and Slack posts through `platform.outbound.*` |

Off for new installations too. Switched on, they need `platform-outbound-spring` and `platform.outbound.installation`; without them, and
for a Microsoft sign-in host the address guard refuses, the app stops at start-up naming each setting. Guide:
`docs/install/notifications.md`, "Through the outbound settings".

## What an app must check

- **Run the fields schema step** (version 3) before starting an app of this release; stop every instance of 1.2.x first (it does not
  start on version 3).
- Apps that want the field values in their own transaction: `docs/install/fields.md` section 6 (READ COMMITTED; locks are held until
  the app commits).
- Apps that show dropdown values: show `spec.displayName(code)`; nothing changes for definitions without names.

## Database schemas

| Module | Version | New |
|---|---|---|
| fields | **3** | `V3__fields_option_names.sql`: one nullable column, `fld_field_definition.option_names`, and its check. No new rights. Run it in the fields schema run (`db/platform/fields`, `fld_schema_history`) after V2; no order relative to the other modules |
| others | as in 1.2.0 | licensing 2, identity 6, erase log 3, integration 3, model governance 5, notifications 1 |

## Modules, design docs

As in 1.2.0; no module added. `platform-notifications-spring` has `platform-outbound` as a new optional dependency. Design:
`docs/design/1.3.0-transactions-and-outbound.md` (P057), `docs/design/case-fields.md` and `docs/design/notifications.md` ("Added in P059").
Updated: the fields and notifications install guides and the switch-over guide ("New in 1.3.0").

## Breaking changes

None in signatures, settings' defaults, codes or released SQL.

## Install proof

Candidate rc.1, whose code is this release's, from the published artifacts on `ubuntu-latest` and `windows-latest`:
<https://github.com/mohankandar/platform-core/actions/runs/45870568230>.
