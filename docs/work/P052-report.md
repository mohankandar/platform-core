# P052 report — faster CI

Date: 2026-11-24
Branch: `build/faster-ci` (then `docs/p052-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Merged as |
|---|---|---|---|
| Workflow, script, Dependabot | #72 | 45341686627 (one run, full build) | `94f5c8c` (task file), `9c38f8a` |
| Main after the merge | — | @MAIN@ | — |
| This report (documentation only) | this PR | @DOCS@ | — |

No release.

## CI times

| | Before | After |
|---|---|---|
| Pull request, full | two runs of 5 to 7½ min (#68: 45332671753 7:29, 45332679361 5:23) | one run, 6:48 (#72) |
| Pull request, documentation only | two full runs | one short run, @DOCSTIME@ |
| Main | 5½ to 7 min (45334531950: 5½) | full build, @MAINTIME@ |

## Independence

No other product's name or behaviour in code.

## What was built

- **`ci.yml`:** on `pull_request`, and on `push` to main only. One job `verify`, so the required check's name is unchanged. A newer
  push to a PR stops the older run; runs on main are never stopped.
- **Documentation only** is decided from all files of the pull request (`git diff --no-renames base...head`) by
  `scripts/ci-docs-only.sh`: `docs/**`, and `*.md` / `README*` outside any `src/` folder. No file, or any other file, is a full
  build. Checked by hand with seven inputs (docs, mixed, Markdown in `src/`, none, no final line end, workflow, Dependabot).
- **The short run** executes the product-name guard and every test that reads documentation: `CatalogueTest`, `PublicApiTest`,
  `ReleasedSqlFilesTest`, `PolicyTextInDocsTest`, `VaultTransitSignerTest` (signing guide), `ModelGovSchemaTest` (model governance
  guide). "No test skipped" runs on it too.
- **Dependabot** no longer proposes version updates of what Boot manages. With `update-types`, security updates still come.
- The routine in `docs/work/README.md` is unchanged.

## `mvn -T 1C`: not kept

| Local run (32 threads) | Result | Tests | Time |
|---|---|---|---|
| `-T 1C`, 1 | **failed**: `BuiltInChecksTest.loginSystem`, `MON_CHECK_TIMEOUT` after 10 s | 1524 | 284 s |
| `-T 1C`, 2 | green | 1524 | 329 s |
| `-T 1C`, 3 | green | 1524 | 299 s |
| ordinary | green | 1524 | 570 s |

The rule asks for three green in a row and no new intermittent failure; the first run already broke it, so the five CI runs
were not spent. The test has a real 10-second limit that a fully loaded machine can miss.

## Decisions I had to make

1. **Two of the tests in the short run need containers** (Vault, PostgreSQL). They stay, because they check that a guide's text
   works.

## Not done, and why

- `mvn -T 1C`: see above.

## Questions

1. **Try `-T 2` later?** Recommendation: only if CI time hurts again; it would need the timing-sensitive tests run alone.
