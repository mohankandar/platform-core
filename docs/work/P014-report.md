# P014 report — setup command (direct mode) and follow-ups

Built on `feature/identity-storage`, merged into `main` and pushed. Built per `docs/design/setup.md` and
`docs/work/P012-report.md` sections 5.2–5.3.

## Result

- `mvn verify`: **563 tests, 0 failures**. That is the 542 existing tests, all still passing, plus 21 new ones.
  `mvn install` ran with the same result.
- New tests by module:
  - platform-setup: 15 (Postgres 16 in Testcontainers)
  - identity-jdbc: +3
  - identity-testkit: +1
  - licensing-spring: +2
- Existing tests I had to change. No check was weakened.
  - `LicensingWiringTest` and `LicensingNotificationsWiringTest`: the test apps now set
    `platform.licensing.tenant-id`, which is now required. The "no config at all" case now expects 3 problems
    instead of 2; the new one is the missing tenant ID.
  - `SlotActivationTest`: `storeLicence()` now calls a new `storeLicence(starts, expires)`. It stores the same
    licence as before.
- **Merge:**
  - `main` was fast-forwarded from `19c8f64` to `bad3c0b` and pushed.
  - `feature/identity-storage` was deleted locally and on origin. Locally this needed `git branch -D`: git's `-d`
    refuses when the branch's remote copy lacks the last commit, even though `main` has it. I checked that `main`
    contains the branch tip before deleting.
  - This task file and this report were then committed on `main` and pushed.

## What was built

### A. Owner answers to P013

The answers are recorded in `docs/design/setup.md` under "4. Decisions from P013", together with my P013 decisions
1–11.

1. **Suspend and resume.**
   - `SlotActivation.suspend(slotId, actor)` and `resume(slotId, actor)`, backed by the new store methods
     `EntityStore.suspend` and `resume` (Postgres and in-memory).
   - New results: `SUSPENDED`, `RESUMED`, `ALREADY_SUSPENDED`, `NOT_SUSPENDED`. The caller audits every call.
2. **Activation follows the intake rule.**
   - `LicensedSlots.Current` now carries a `Standing`: `IN_FORCE`, `NOT_STARTED` or `GRACE_OVER`.
   - `LicenceSlots` fills it from the licence state.
   - Activation is allowed while the licence is valid, expiring or in grace. Two new refusal codes:
     - after grace: `LICENCE_EXPIRED`;
     - before the licence's start date: `LICENCE_NOT_STARTED`.
   - Resume follows the same rule. Suspend is always allowed.
3. **Organization claim for hub mode:** nothing to build now.
4. **A suspended slot comes back suspended.**
   - New column `idn_entity.status_before_removal` (`ACTIVE`/`SUSPENDED`), added by editing `V1__identity.sql`.
   - Database checks: the column is set only while the slot is `NOT_IN_LICENCE`, and only for an activated slot.
   - When the slot returns, it gets that status back.
   - Tests: Postgres `aSuspendedSlotDroppedFromTheLicenceComesBackSuspendedNeverActive`, and in-memory
     `suspendedSlotComesBackSuspended`.

### B. Licensing follow-ups

- **New event `licensing.product-not-covered`, "Licence does not cover this product".**
  - Technical and critical; goes to the IT Admin.
  - It is a condition: an alert at once, repeated daily, and Recovered once a licence that lists the product is
    stored.
  - One per customer, with the product code as the identifier. It is in `LicensingEvents.ALL`.
  - Test: `aLicenceWithoutThisProductAlertsTheItAdminAndRecovers`.
- **`platform.licensing.trusted-keys-file`.**
  - Takes a file path or `classpath:…`, and reads the shipped `platform-licence-keys/1` file.
  - The inline `trusted-keys.<id>` form still works and can be combined with the file. A key ID given in both with
    different keys stops the start.
  - A missing or unreadable file stops the start, naming the setting.
- **`platform.licensing.tenant-id` is required.** Without it the start stops with "platform.licensing.tenant-id is
  missing: give this installation's customer ID (TEN-...) as in the licence; the setup command writes it into the
  config fragment it creates (platform-tenant.properties)".

### C. New module `platform-setup`

The module is in the root `<modules>` list and in `platform-bom`. Package `com.documentplatform.setup`; plain Java,
no Spring. The PostgreSQL driver comes from the product's distribution, as for the licence command.

```
setup plan|apply --licence licence.json --signin signin.json --product PRODUCT_A
                 --keys trusted-keys.json --product-roles product-roles.json
                 [--config-out platform-tenant.properties] [--run-log platform-setup-runs.log]
                 [--remove-unlisted-grants] [--replace-connection]
environment: PLATFORM_SETUP_DB_URL, PLATFORM_SETUP_DB_USER, PLATFORM_SETUP_DB_PASSWORD
```

1. **Checks on the files (no database):**
   - The trusted-keys file is valid, and the licence verifies with it.
   - The sign-in file is valid, in direct mode, and has only known fields.
   - The sign-in file and the licence name the same customer.
   - The licence lists the product, and the sign-in file has an entry for it.
   - `product-roles.json` is for that product.
   - The mapping file can be read.
2. **Checks against the database, still before any write:**
   - The `lic_` and `idn_` tables are at this release's version. Setup never creates tables.
   - The database holds no other customer.
   - The licence is not older than the stored one.
   - Every mapping row names a slot in the licence and a role the product knows. The whole file is checked and every
     bad line is listed.
   - The config fragment does not name another customer.
3. **Plan.** Every item is listed as `create`, `unchanged`, `update`, `conflict`, `remove` or `not removed`. `plan`
   runs in a transaction that is rolled back, so it changes nothing.
4. **Apply.** The plan is worked out again and written in **one transaction**, under `pg_advisory_xact_lock`, in this
   order:
   - the licence, through `LicenceService.store`;
   - the customer;
   - the slots. Setup never deletes a slot; one the licence dropped becomes `NOT_IN_LICENCE`.
   - the login connection. If it was changed in the app since the last setup, that is a `conflict`; setup replaces it
     only with `--replace-connection`.
   - the grants. Missing ones are added. Grants not in the file are removed only with `--remove-unlisted-grants`.

   After the commit, setup writes `platform-tenant.properties` (`platform.licensing.tenant-id=TEN-…`) in one step.
5. **Run log:** one line per run, with the time, action, product, customer, licence ID and version, outcome, exit
   code, counts per action, and the code.

Exit codes:

| Code | Means |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Failed: either the database part was rolled back, or only the config file could not be written. Run again in both cases |
| 2 | Refused; nothing was changed |
| 3 | Done, except items that need a flag |

**Demo files** are in `platform-setup/demo/`, with a README:

- the licence `LIC-DEMO-0001` for `TEN-DEMO-0001`: perpetual, `NON_PRODUCTION`, two slots, products `PRODUCT_A` and
  `PRODUCT_B`;
- `development-trusted-keys.json`;
- the sign-in file;
- two mapping files and two `product-roles` stand-ins.

The licence is signed with the development key `dev-demo-1`. Its private key was thrown away after signing.
`DemoFilesGenerator` makes new demo files with a new key, and runs only on request.

**How production refuses the demo licence:**

- A production install trusts only the keys in its release's `trusted-keys.json`, and that file never lists
  `dev-demo-1`. Setup and the app stop with `LICENCE_UNKNOWN_KEY_ID`; in setup that is
  `SETUP_LICENCE_REFUSED: LICENCE_UNKNOWN_KEY_ID`.
- A keys file that lists some other key under the ID `dev-demo-1` fails with `LICENCE_FORGED`.
- Both cases are tested.

**Docs:**

- new: `docs/install/setup.md`;
- updated: `docs/install/licensing.md` (keys file, required tenant ID, new event), `docs/install/identity.md`
  (activate, suspend, resume), `docs/design/setup.md`, `docs/design/licensing.md` (note on Q2) and the CLAUDE.md
  module table.

## Sample input files (demo values only)

`signin-demo.json`:

```json
{
  "format": "platform-signin/1",
  "tenantId": "TEN-DEMO-0001",
  "mode": "direct",
  "loginSystem": { "type": "OIDC", "issuer": "https://login.demo.example/oauth2/default", "providerId": "demo-okta" },
  "products": {
    "PRODUCT_A": { "browserClientId": "demo-product-a-web", "apiAudience": "api://demo-product-a",
                   "roleMappingFile": "role-mapping-product-a.csv" },
    "PRODUCT_B": { "browserClientId": "demo-product-b-web", "apiAudience": "api://demo-product-b",
                   "roleMappingFile": "role-mapping-product-b.csv" }
  }
}
```

`role-mapping-product-a.csv`:

```
# Format: platform-role-mapping/1
# Product: PRODUCT_A
# Demo values only. entity_name stays empty: the Business Admin names each entity when activating it.
entity_id,entity_name,group,role
ENT-DEMO-0001,,Demo Finance Readers,READER
ENT-DEMO-0001,,Demo Finance Editors,EDITOR
ENT-DEMO-0002,,Demo Legal Readers,READER
ENT-DEMO-0002,,Demo Auditors,AUDITOR
```

`product-roles-product-a.json` (each product ships its own):

```json
{ "format": "platform-product-roles/1", "productCode": "PRODUCT_A",
  "roles": ["READER", "EDITOR", "AUDITOR"], "platformRoles": ["PLATFORM_ADMIN"] }
```

The first `apply` on an empty product database (real output):

```
Setup apply for product PRODUCT_A, customer TEN-DEMO-0001, licence LIC-DEMO-0001 v1 (NON_PRODUCTION), sign-in mode direct.
Plan:
  create      licence LIC-DEMO-0001 v1 (first licence)
  create      customer TEN-DEMO-0001
  create      entity slot ENT-DEMO-0001 (NOT_ACTIVATED)
  create      entity slot ENT-DEMO-0002 (NOT_ACTIVATED)
  create      login connection PRODUCT_A: provider demo-okta, issuer https://login.demo.example/oauth2/default, browser client ID demo-product-a-web, API audience api://demo-product-a
  create      grant ENT-DEMO-0001 PRODUCT_A: group "Demo Finance Editors" -> EDITOR
  create      grant ENT-DEMO-0001 PRODUCT_A: group "Demo Finance Readers" -> READER
  create      grant ENT-DEMO-0002 PRODUCT_A: group "Demo Auditors" -> AUDITOR
  create      grant ENT-DEMO-0002 PRODUCT_A: group "Demo Legal Readers" -> READER
  create      config platform-tenant.properties (platform.licensing.tenant-id=TEN-DEMO-0001)
Config fragment platform-tenant.properties: platform.licensing.tenant-id=TEN-DEMO-0001. The app imports it (spring.config.import).
Setup done (10 created, 0 updated, 0 removed, 0 unchanged).
```

A second run lists every item as `unchanged` and ends "Setup done (0 created, 0 updated, 0 removed, 8 unchanged)".
The run log line:

```
2026-11-21T10:00:00Z setup apply product=PRODUCT_A tenant=TEN-DEMO-0001 licence=LIC-DEMO-0001/v1 result=DONE exit=0 create=10 unchanged=0 update=0 conflict=0 remove=0 not_removed=0
```

## Tests asked for → where

All tests are in `SetupCommandTest`. They connect as the DML-only user unless the row says otherwise.

| Asked for | Test |
|---|---|
| Idempotent rerun | `theDemoFilesSetUpBothProductDatabasesAndARerunChangesNothing`. The second `apply` changes no row and adds no connection version. The same files also set up a second product's database |
| Rerun after a mid-run failure | `aRerunAfterAMidRunFailureFinishesWhatWasMissing`. A trigger makes the grants step (the last step) fail. Everything is rolled back, licence and last-seen time included, and the rerun does it all. In a second database the config file write fails after the commit, and the rerun writes only the file |
| Plan shows removals before they happen; removal only with the flag | `thePlanShowsRemovalsFirstAndGrantsAreRemovedOnlyWithTheFlag` |
| Conflict on an app-changed connection | `aConnectionChangedInTheAppIsAConflictUntilReplaceConnectionIsGiven`. Also: a change setup made itself is updated without the flag |
| Wrong-tenant sign-in file refused | `aSignInFileOfAnotherCustomerIsRefused` |
| Unknown role refused | `anUnknownRoleRefusesTheWholeFileAndNothingIsWritten`. Also covered: a slot not in the licence, a platform role, wrong case |
| Development-key licence refused by production trusted keys | `theDevelopmentKeyLicenceIsRefusedByProductionTrustedKeys` (`LICENCE_UNKNOWN_KEY_ID` and `LICENCE_FORGED`) |
| DML-only user | `theDmlOnlyUserRunsSetupAndSetupNeverCreatesTables`. On an empty schema, even as the owner, setup creates nothing |
| No secrets in any output | `noSecretInAnyOutput`. Checks stdout, stderr, the run log, the config file and the stored connection for: the database password (on success, a wrong password, and a password put in the URL) and a client secret pasted into the sign-in file |

There are also tests for:

- slot dropped → `NOT_IN_LICENCE`, never deleted;
- an older licence refused;
- a database or config file of another customer refused;
- two runs at once: the second waits for the advisory lock;
- wrong options and hub mode refused;
- missing database settings named;
- an activated slot and its name left alone by a rerun.

## Decisions I had to make

1. **`--keys` and `--product-roles` are required options.**
   - Setup needs the release's trusted keys to verify the licence, and the product's role list.
   - The task's option list did not name them. Both files ship with the product.
2. **All three database settings come from the environment:** `PLATFORM_SETUP_DB_URL`, `_USER` and `_PASSWORD`.
   There is no `--db` option. A database error is shown by its SQL state only, never the driver's message or the
   URL.
3. **Exit code 1 means "failed, run again".** It covers a rolled-back database part, and a config file that could not
   be written after the commit. The task's list (0, 2, 3) has no code for a failure.
4. **Items that need a flag do not block the rest.**
   - `apply` writes everything else and exits 3.
   - A grant not in the file, when the flag is not given, also gives exit 3, not 0. A customer who deleted a row
     should not be told "done" while the access stays.
5. **Grants not in the file:** every grant of the customer for this product and the sign-in file's issuer, including
   slots the file does not name, counts as not in the file. The file is the customer's complete list per product.
   Grants under an older issuer are not touched.
6. **Slots stored = the licence in force plus a waiting renewal**, so new slots of an early renewal are stored at
   once. Activation still allows only slots of the licence in force.
7. **What counts as changed in the app:**
   - The connection differs from the sign-in file, and its last history row is not `SETUP`.
   - Equal values are `unchanged` even if the app made them.
   - A connection the app disabled is also a conflict. Setup looks at the latest connection, not only an ACTIVE one.
8. **One installation, one customer.** Setup refuses with `SETUP_OTHER_CUSTOMER_INSTALLED` if `idn_customer` or
   `lic_licence` holds another customer. Without this, a wrong database setting would let the licence store replace
   the other customer's licence, which it treats as unreadable.
9. **The licensing stores join the setup's transaction** through a connection wrapper that ignores their own
   commit, rollback and close. Licensing itself did not change. The failure test shows the licence is rolled back.
10. **Config file:**
    - `platform-tenant.properties`, written in one step, after the commit.
    - An existing file that names another customer is refused before anything is written.
    - An existing file without the key gets the line added.
11. **Format names:**
    - Mapping files: `platform-role-mapping/1`.
    - Role list: `platform-product-roles/1`, with `roles` and optional `platformRoles`.
    - A role in the wrong case is refused, with its correct spelling.
12. **Sign-in file:** the issuer must be an `https` address without query or user info. Any unknown field refuses
    the file; the message names the field, never its value.
13. **Activation:**
    - Two codes: `LICENCE_EXPIRED` (after grace) and `LICENCE_NOT_STARTED`. "The same rule as intake" also stops
      intake before the start date.
    - Suspending a `NOT_IN_LICENCE` slot is refused (see question 2).
    - `LicensedSlots.Current` kept its two-argument constructor, which means "in force".
14. **Product not covered:** one condition per customer, with the product code as the identifier, repeated once a
    day like the grace alert.
15. **Demo licence:**
    - It is perpetual and `NON_PRODUCTION`, so it never needs signing again, and the apps show "Test environment".
    - The development private key is not kept anywhere.
16. **The run log is not written when the first word or the options cannot be parsed.** Setup then does not know
    which file to use. The refusal is still printed.

## Questions

1. **Where do `trusted-keys.json` and `product-roles.json` come from?** *Recommendation:* each product ships a small
   `setup` wrapper script that passes both, so IT types only licence, sign-in file and product.
2. **Suspend a slot that is `NOT_IN_LICENCE`?** This would make it come back suspended. Today it is refused.
   *Recommendation:* leave it refused unless the app's screen needs it.
3. **Exit code 3 when grants are not removed** (no flag): is that right, or should it be 0 with a note?
   *Recommendation:* keep 3.
4. **Grants under an older issuer**, after a customer changes login system: should setup list them as "not in the
   file" too? *Recommendation:* yes, in the hub-mode task, which changes issuers anyway.
5. **One customer per database:** this check must be loosened for shared hosting tables later. *Recommendation:* keep
   it now; revisit with hosting.
6. **Exit code 1 for failures:** confirm. *Recommendation:* keep it.
