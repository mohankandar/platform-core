# P029 report — setup loads integration client bindings

Date: 2026-11-22
Branch: feature/client-bindings (merged into main, deleted), then main (this report)
Outcome: DONE

## Result (tests per module, merge and push results)

- `mvn clean verify`: **BUILD SUCCESS, 911 tests, 0 failures, 3 skipped.** That is the 897 tests of P023, all
  passing, plus 14 new ones. The 3 skipped are P023's end-to-end TLS tests, which Norton blocks on this laptop. Then
  `mvn install` (with `-DskipTests`, straight after the verify): BUILD SUCCESS.

| Module | Tests |
|---|---|
| platform-identity-core | 90 (was 86) |
| platform-identity-spring | 15 |
| platform-notifications | 81 |
| platform-notifications-testkit | 132 |
| platform-identity-testkit | 7 (was 6) |
| platform-notifications-spring | 41 |
| platform-licensing | 141 |
| platform-licensing-testkit | 47 |
| platform-licensing-spring | 34 |
| platform-identity-jdbc | 32 (was 29) |
| platform-crypto | 20 |
| platform-crypto-testkit | 16 |
| platform-setup | 26 (was 20) |
| platform-fields | 91 |
| platform-fields-testkit | 18 |
| platform-crypto-spring | 5 |
| platform-erase-log | 16 |
| platform-erase-log-testkit | 9 |
| platform-erase-log-spring | 5 |
| platform-integration | 14 |
| platform-integration-testkit | 71 (3 skipped) |

- **Start (A):** four files were committed as they were (021f0f7): `docs/design/setup.md` (section 6.1),
  `docs/design/model-governance.md`, `docs/design/integration-module.md` (the P023 decisions section) and
  `docs/work/P029.md`. Then `feature/client-bindings` was branched from main.
- **Merge:** fast-forward, `main` e1d29d3 → dd58c3f, pushed.
- **Branch:** deleted locally. It was never pushed, so there was nothing to delete on origin.
- **`feature/case-fields`:** still on origin, so the local branch stays.
- **This report:** the next commit on main, pushed.

## What was built

Everything follows `docs/design/setup.md` 6.1. The file format is unchanged.

### Identity storage

**Table.** `idn_machine_binding` is added by a **new file, `V2__machine_binding.sql`**, in the identity Flyway run
(`db/platform/identity`, history table `idn_schema_history`). V1 was not edited (decision 1). `IdentitySchema.VERSION`
is now 2.

- Columns:
  - customer, product, client ID;
  - slot ID and entity UUID, both foreign keys to `idn_entity`;
  - source key and description;
  - status ACTIVE or INACTIVE;
  - version and times.
- The table refuses a client ID with a space, comma, quote or control character, and checks the shape of the source
  key and the description length.
- A unique index allows one binding per customer, product and client ID, so at most one can be ACTIVE (decision 2).
- A partial index on ACTIVE rows serves the lookup at sign-in.

**History.** `idn_machine_binding_history` holds every version of each binding, with its status, entity, source,
change source (SETUP, APP or MIGRATION), who and when. Rows are only ever inserted, as in
`idn_login_connection_history`.

**Database rights.** The runtime user needs the same as before: SELECT, INSERT, UPDATE and DELETE on the `idn_`
tables. The two new tables are in `IdentitySchema.TABLES`, so the startup check covers them. No code deletes a
binding or a history row.

**Store (identity-core, `com.documentplatform.identity.store`):**

- `MachineBinding` is the record; it checks every value.
- `MachineBindingStatus` is ACTIVE or INACTIVE.
- `MachineBindingChange` is one history row.
- `MachineBindingStore` has `bindings`, `activeBinding`, `insertBinding`, `updateBinding` (with the expected
  version) and `bindingHistory`.
- Postgres: `jdbc.PostgresMachineBindingStore`, on a DataSource, or on the caller's connection (`onConnection`, for
  setup's single transaction).
- In memory: `InMemoryIdentityStore` implements the same store.

**Built-in `MachineClients`.** `jdbc.PostgresMachineClients(ds, product)` returns the entity of the client's ACTIVE
binding.

- `new PostgresMachineEntityBindings(ds, product)` now uses it by default. An app can still supply its own
  `MachineClients` with the 3-argument constructor.
- `PostgresMachineEntityBindings` keeps a bound entity only while it is ACTIVE and the customer's login connection for
  the key is ACTIVE.
- If the table cannot be read, the result is `null`, so the library refuses the call.
- `PostgresMachineClients.activeBinding(client)` also gives the binding's source key. `MachineEntityBindings` returns
  only the entity.
- In memory: `InMemoryIdentityStore.machineBindings(product)` applies the same rules.

### Sign-in file

`platform-signin/1` gets two optional fields per product:

- `"clientBindingsFile"`: relative to the sign-in file's folder.
- `"sources"`: a list of `{ "key", "name" }`. Keys must match `[a-z0-9][a-z0-9._-]{0,63}` and be unique; there are
  at most 200 sources.

Older files without them load as before; this is tested.

### Setup

**Preflight** (`SetupInputs`, before the database) reads the file with `identity.mapping.ClientBindingsFile`, which
is in identity-core next to the role-mapping reader. It checks:

- the exact header `client_id,entity_slot,source,description`;
- four columns, and quotes (spreadsheet quoting, a byte-order mark, CRLF and empty lines are accepted);
- the shape of the client ID, slot ID, source and description;
- that the source is listed in the sign-in file's `sources`;
- that each client ID appears only once;
- that no value looks like a secret.

Every problem has a code and its line number. The run is refused with `SETUP_CLIENT_BINDINGS_INVALID` and every bad
line, for example `line 4: SOURCE_UNKNOWN: …`. A value that looks like a secret is never repeated in the message.

**Plan** (`SetupRun.planClientBindings`), in the locked transaction:

- **Refused:**
  - `SLOT_UNKNOWN`: a slot that the licences in force do not list, checked against the same set as the time zones;
  - `CLIENT_BOUND_ELSEWHERE`: a client that is ACTIVE for another entity or source.
  - Both are reported with their line numbers.
- **What the plan shows:**
  - `create` for a new line;
  - `unchanged`;
  - `update` with "(description)" when only the description changed;
  - `update` with "(switched on again)" when an INACTIVE binding comes back, also for another entity;
  - **`switch off`** for a stored ACTIVE binding that is not in the file. If the sign-in file names no file, every
    binding of the product is switched off. The binding is kept as INACTIVE and never deleted.

**Apply** writes the bindings in the same transaction as the rest, after the slots, as `SETUP` / `setup`. Each change
writes a history row. A binding changed in the app meanwhile gives `SETUP_FAILED`, and everything is rolled back.

**Rerun:** a rerun changes nothing. The plan shows `unchanged`, and no history row is written.

**Run log:** a new count `switch_off=N`. The closing summary also says "N switched off".

**Demo files:**

- `platform-setup/demo/client-bindings-product-a.csv`: two demo client IDs, one for each entity slot.
- `signin-demo.json`: PRODUCT_A now names that file and two demo `sources`. PRODUCT_B has neither, so it has no
  machine callers.
- `demo/README.md`: lists the new file.

**Docs:**

- `docs/install/setup.md`: new section 3a, the customer-IT steps in plain words (list the systems, write the file,
  never a secret, the codes, how changes work). Also the sign-in example, the plan actions, the apply sentence and
  the refusal codes.
- `docs/install/identity.md`: the two tables and V2.
- `CLAUDE.md`: the rows for identity-jdbc, identity-testkit and setup.

## Tests asked for → where

| Asked for | Where |
|---|---|
| Load | `ClientBindingsSetupTest.loadsTheDemoFileAndARerunChangesNothing`: plan writes nothing; apply stores both bindings ACTIVE v1 with the derived entity UUID and SETUP history |
| Rerun unchanged | the same test: `unchanged`, no new history, `switch_off=0` in the run log; the PRODUCT_B run (no file) does not touch PRODUCT_A |
| Removed line → INACTIVE | `aRemovedLineSwitchesTheBindingOffAndAReAddedOneSwitchesItOn`: `switch off`, INACTIVE v2, `switch_off=1`; a line added back is switched on again (also for another entity) with history 1/2/3; a description change is an update; no file at all switches everything off and deletes nothing |
| Duplicate client ID refused | `fileProblemsRefuseTheRunWithLineNumbersAndWriteNothing` (`line 3: DUPLICATE_CLIENT_ID`); `ClientBindingsFileTest.everyProblemHasItsLineAndCode` |
| Client bound to another entity refused | `aClientBoundToAnotherEntityOrSourceIsRefused`: `line 2` and `line 3: CLIENT_BOUND_ELSEWHERE`, nothing changed |
| Unknown slot / source refused with line numbers | `fileProblemsRefuse…`: `line 4: SOURCE_UNKNOWN`, and `line 3: SLOT_UNKNOWN … is not a slot of this customer's licence` with the whole database part rolled back; `ClientBindingsFileTest` |
| Header wrong refused | `fileProblemsRefuse…` (`line 1: HEADER_WRONG`); `ClientBindingsFileTest.theHeaderIsExact` |
| Secrets-looking values refused | `fileProblemsRefuse…` (`SECRET_LIKE`, the value never repeated); `ClientBindingsFileTest.secretsAreRefusedAndNeverRepeated` (JWT, PEM, `password=`, `client_secret:`, a long mixed key; ordinary Okta, Entra and Auth0 IDs pass) |
| An older sign-in file without the new fields still works | `anOlderSignInFileWithoutTheNewFieldsStillWorks` |
| `PostgresMachineEntityBindings` returns the entity only when binding, entity and connection are ACTIVE | identity-jdbc `MachineBindingsTest.theBindingCountsOnlyWhileBindingEntityAndConnectionAreActive`: no binding, another product, entity suspended, connection disabled, binding switched off, slot not yet activated, table unreadable → `null`. Setup `theLoadedBindingAdmitsTheClientOnlyOnceTheEntityIsActive`, end to end from the demo file. In memory: `InMemoryMachineBindingsTest` |
| DML-only user | every setup test runs as the DML-only app user; `MachineBindingsTest.storeVersionsAndHistoryWithTheDmlOnlyUser`: store, versions and history through that user, which cannot alter or create tables; the table refuses a duplicate client and a bad client ID even without the code |
| (also) | `MachineBindingsTest.aV1DatabaseIsRefusedUntilV2IsRun`; `IdentitySchemaTest` updated to version 2 |

## Files changed, commits

- 021f0f7 `docs: P029 task; client-binding file format (setup 6.1); model governance design draft; P023 decisions`
- dd58c3f `feat(identity,setup): setup loads integration client bindings (P029)`:
  - identity-core: `store/MachineBinding`, `MachineBindingStatus`, `MachineBindingChange`, `MachineBindingStore`;
    `mapping/ClientBindingsFile`; test `ClientBindingsFileTest`.
  - identity-jdbc: `V2__machine_binding.sql`, `PostgresMachineBindingStore`, `PostgresMachineClients`;
    `PostgresMachineEntityBindings` (2-argument constructor), `MachineClients` (doc), `IdentitySchema` (version 2,
    tables); tests `MachineBindingsTest`, `IdentitySchemaTest` (version numbers), `Pg` (`exec(DataSource, …)` now
    package-visible).
  - identity-testkit: `InMemoryIdentityStore` implements `MachineBindingStore` and has `machineBindings(product)`;
    test `InMemoryMachineBindingsTest`.
  - setup: `SigninFile`, `SetupInputs`, `SetupRun`, `Plan` (`SWITCH_OFF`), `SetupRefusal`
    (`SETUP_CLIENT_BINDINGS_INVALID`), `SetupCommand` (summary); demo files; tests `ClientBindingsSetupTest` (new) and
    `SetupCommandTest` (two changes, decision 9).
  - docs: `docs/install/setup.md`, `docs/install/identity.md`, `CLAUDE.md`.
- This report: the next commit on main.

## Decisions I had to make

1. **V2, not an edit of V1.** No tag contains V1 (`v0.2.1` and `v0.3.0` came before it), so the task allowed editing
   it, as P014 and P020 did. I added V2 instead: LexaVault's switch-over may already have run V1 in its databases, and
   editing V1 would break their Flyway checksums. A database that ran only V1 is refused with `IDN_VERSION_OLDER`
   until V2 is run.
2. **One binding per customer, product and client ID**, not only one ACTIVE one. This is stronger than the task asks.
   A client is switched off and on in place, and the history keeps every version.
3. **"Bound to another entity or source" is refused only while the binding is ACTIVE.** An INACTIVE client may come
   back for another entity, with history. So moving a client takes two runs: remove the line and run setup, then add
   the new line and run setup again.
4. **No `clientBindingsFile` means every binding of the product is switched off.** This follows 6.1: "no file = no
   machine callers". The plan shows each `switch off` before `apply`.
5. **Choices the format leaves open:**
   - Both `"` and `'` count as quotes in a client ID.
   - There are no comment lines. A `#` at the start of a line is a client ID, because the role-mapping reader's
     comment rule would silently drop such a client.
   - Spreadsheet quoting is accepted for the description.
6. **The rule for "looks like a secret":** a JWT; a PEM block; `password=` or `secret:` (and similar words followed by
   `=` or `:`); or a run of 40 or more key-like characters mixing upper case, lower case and digits. Okta, Entra (GUID),
   Auth0 (32 characters) and Keycloak-style client IDs pass.
7. **Which checks run when.**
   - In preflight: format, shapes, sources, duplicates and secrets.
   - In the plan: slots against the licences in force (as the time zones are checked), and clients bound elsewhere
     against the database.
   - Both refuse with the same code and line numbers.
8. **The file reader is in identity-core** (`mapping.ClientBindingsFile`), so Vero can use the same reader when its
   loader moves to platform-core.
9. **Two existing setup tests were changed**, because the demo files now include two bindings:
   - the run-log create count goes from 10 to 12;
   - the test where a newer licence drops `ENT-DEMO-0002` also removes that slot's binding line. Otherwise it would be
     refused with `SLOT_UNKNOWN`, which is the intended behaviour, and the install guide says so.

## Not done, and why

Nothing in the task is left. The IT Admin's integration page, which will edit the same table, is a later task.

## Questions (numbered, each with a recommendation)

1. **Keep Vero 0041's loader identical.** Decisions 5 and 6 (both kinds of quote refused, no comment lines, the rule
   for what looks like a secret) are not written in 6.1. *Recommendation:* add them to `docs/design/setup.md` 6.1 and
   tell the SDV thread, so a file that works in one loader works in the other.
2. **Removing `clientBindingsFile` switches every client of the product off** (decision 4). *Recommendation:* keep it.
   The plan lists every `switch off` before `apply`, and nothing is deleted.
3. **Moving a client takes two runs** (decision 3). *Recommendation:* keep it for now: the move is explicit and
   audited. If customers find it clumsy, add a `--move-bound-clients` flag later, in the style of
   `--replace-connection`.
