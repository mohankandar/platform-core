# Cross-check B2.5: product independence

The guard is `platform-identity-core/src/test/java/com/documentplatform/identity/NoProductNamesGuardTest.java`.

## Coverage of modules

The guard does not list modules. `noProductNameInCodeDataOrInstallGuides` walks the whole repository from the root
(`Path.of("").getParent()`, checked to hold `pom.xml`). It skips:
- `.git/`, `target/` and `node_modules/`;
- `docs/work/` and `docs/design/`, which are history and examples.

So every module is covered, including the ones added after P030:
- `platform-outbound(-spring)`;
- `platform-errors` and `platform-error-catalogue`;
- `platform-monitoring(-spring, -testkit)`;
- `platform-model-governance(-spring, -testkit)`.

Any module added later is covered without a change. The test runs in every `mvn verify` and in CI, because it is
part of `platform-identity-core`'s tests. `theGuardFindsWordsAndIdentifierParts` proves the matcher (whole words,
`x_y`, `X-Y`, camelCase parts, old package names) and checks that it does not flag ordinary words.

## File types

The guard checks:
- `.java`, `.sql`, `.json`, `.xml`, `.yml`, `.yaml` and `.properties` files;
- `CLAUDE.md` and `README*`;
- everything under `docs/install/`.

Tracked files outside `docs/work/` and `docs/design/` that it does **not** read:

| Kind | Files |
|---|---|
| Markdown | `INTEGRATION-NOTES.md`, `SCHEMA_CONTRACT.md`, `docs/errors/platform-error-codes.md` (generated; its `.json` twin is checked), `docs/releases/*.md` (5) |
| CSV | `platform-setup/demo/*.csv` (3) |
| Vault policies | `platform-crypto/src/test/resources/vault/*.hcl` (3), `platform-licensing/src/test/resources/vault/licensing-signer.hcl` |
| Scripts | `scripts/build-release.ps1`, `scripts/build-release.sh`, `scripts/ci-no-skipped-tests.sh` |
| Spring metadata | `META-INF/spring.factories` (6), `...AutoConfiguration.imports` (9) |
| Git | `.gitattributes`, `.gitignore` |

A text search of all these files for the product words finds two with hits:
- **`INTEGRATION-NOTES.md`** (19 lines, from the P013 era). It names two apps and their roles (line 5).
- **`SCHEMA_CONTRACT.md`** (54 lines, from the 0.3.0 identity release). It has a column describing one app's schema
  choices by name, for example dropping a named unique constraint, plus its refusal rules (lines 10–41).

That is a product's name **and behaviour** in the repo, outside the guard's reach. Neither file is linked from
CLAUDE.md, the install guides or the code. All other unread files are clean.

## Read check of code

No product name or product-specific behaviour was found in the code (`.java`, SQL, resources). The guard passes on
main. The tick-off agents were told to flag any they met; none did (see their files).

## Gaps

| ID | Rule | What is missing | Sev | Proposed fix (one line) | Size |
|---|---|---|---|---|---|
| xc-independence-1 | platform-core never names or assumes a product | Two root notes name apps and describe one app's schema behaviour; the guard skips them | B | Move their generic content into `docs/install/identity.md` (the identity schema contract) and delete both files | S |
| xc-independence-2 | The guard covers every text file a reader or app sees | The guard skips `*.md` outside CLAUDE.md, README and `docs/install/`, and skips `.csv`, `.hcl`, `.sh`, `.ps1` | C | Check every text file outside `docs/work/` and `docs/design/`, with an allow-list of binary types; a test plants a name in each kind | S |
