# platform-core 1.0.0

**Requires Spring Boot 4.2 and Java 21.** The integration release: every module, installed from the published artifacts on
Linux and Windows as the install guides say, before this release was made. Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>1.0.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`. How an app switches over:
`docs/install/switch-over.md`.

**No other product's name or behaviour in code.**

## Strict semantic versioning from here on

A breaking change comes only with a new major version. Public API is what `docs/design/public-api.md` says; its signatures are
recorded in `docs/api/public-api-1.txt`, and a build that removes or changes one fails. Formats and schemas that 1.x writes, every
later 1.x reads. `.internal` packages may change in any release.

## Since 0.1.0, by module

| Modules | What they hold |
|---|---|
| `platform-errors` | One code standard for every failure, references, safe texts, the generated catalogue (0.4.0) |
| `platform-identity-core`, `-jdbc`, `-spring` | Sign-in, entity admission, roles; login-connection drafts with a test sign-in and rollback, grant history (0.2.0); platform-wide grants and push callers (0.3.0); machines only as push callers, exact group names (0.7.0) |
| `platform-licensing`, `-spring` | Signed licences, clock rule, usage ledger; keys from the app's build, forward-only clock, version floor (0.7.0) |
| `platform-setup` | The setup command; runs with `java -jar` (0.8.1) |
| `platform-crypto`, `-spring` | Per-customer keys in Vault, envelopes, fingerprints, destruction at exit; set-up policy that cannot destroy (0.7.0) |
| `platform-fields` | Case field definitions and sealed values, erased only through the erase log (0.7.0) |
| `platform-erase-log`, `-spring` | Erase log outside the database, replay after a restore; keyed chain, restore noticed while running (0.7.0) |
| `platform-outbound`, `-spring` | Safe outbound calls: pinning, proxy, private CA (0.3.0) |
| `platform-integration`, `-spring` | API push, folder export, callbacks, polling, watched folders, virus scan (0.3.0); links never followed (0.6.1); callback signature v2, secrets tied to their entity (0.7.0) |
| `platform-notifications`, `-spring` | Mail, Teams, Slack, summaries, approval links |
| `platform-monitoring`, `-spring` | Dependency checks, start-up refusal, readiness, diagnostic bundle (0.4.0); bundle allow-list (0.7.0); the customer's keys looked at (1.0.0) |
| `platform-model-governance`, `-spring` | Register, change gate, drift, re-validation, documentation pack (0.5.0); the gate checks the approval itself (0.7.0) |
| The testkits | In-memory stores and mock systems, test scope only |
| `platform-bom` | All of them at one version |

## Design docs implemented

`docs/design/`: case-fields, crypto, erase-log, error-codes, integration-module, integration-profile-format,
integration-standards, licensing, licensing-file-format, model-governance, monitoring, notifications, platform-baseline,
public-api, setup.

## Since 0.8.1

- A database that cannot be reached, or tables that are not ready, stop the app with the module's code in Spring Boot's failure
  report, for identity, erase log, integration and model governance too.
- The key-service check looks at the customer's two keys, so `customer-keys-required=true` refuses a start without them.
- The error catalogue names the modules of release-candidate jars.
- Guides: the switch-over guide; `baselineOnMigrate` for each schema run; backing up the whole database; the lists of schema
  files; push callers' default claims.

No breaking change against 0.8.1.

## Database schemas

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 5 |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 2 |
| platform-fields | `db/platform/fields` | `fld_schema_history` | 2 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 3 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 3 |
| platform-model-governance | `db/platform/modelgov` | `mg_schema_history` | 5 |
| platform-notifications | `db/platform/notifications` | yours to choose | 1 |

## Security review and advisories

- The independent review P042 found 59 things (`docs/work/security-findings.md`). **Every High and Medium finding is fixed.** Open,
  all Low or Info: SEC-24, 25, 28, 29, 34 (part), 35 to 39, 42, 46, 47, 48 (part), 50, 53, 57, 58, 59. SEC-24 and SEC-42 would
  weigh more on a hosted service; they are before-hosting items.
- GitHub advisory database, 2026-11-24, every resolved third-party artifact in every scope: **no open advisory.**

## Install proof

From the published artifacts, on `ubuntu-latest` and `windows-latest` (no Docker on Windows): set-up of Vault and PostgreSQL, the
schema runs, setup, start with a user that has data rights only, smoke steps, restore and erase replay, both licensing modes,
start-up refusals. Candidate rc.3, whose code is this release's: <https://github.com/mohankandar/platform-core/actions/runs/45367140622>.
