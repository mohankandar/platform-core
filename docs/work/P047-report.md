# P047 report — install proof on Linux and Windows, switch-over guide, release 1.0.0

Date: 2026-11-24
Branch: `release/install-proof` (then `release/1.0.0-rc.1` to `-rc.3`, `release/1.0.0`, `release/1.1.0`, `docs/p047-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Commit | Tag | Release run |
|---|---|---|---|---|---|
| Sample app, proof, switch-over guide, start-up reports | #74 | 45348357946 | `c876de0` | — | — |
| Candidate 1 | #75 | 45349383755 **failed** (finding 4), 45352425478; main 45353504973 | `bd91820` | `platform-core-v1.0.0-rc.1` | 45354498808: published; **proof failed** (finding 5) |
| Candidate 2 | #76 | 45358768068; main 45359859104 | `eed05b9` | `platform-core-v1.0.0-rc.2` | 45360791836: attempt 1 publish failed (below), attempt 2 published; **proof passed** |
| Candidate 3 | #77 | 45364790585; main 45365997500 | `b9ab443` | `platform-core-v1.0.0-rc.3` | 45367140622: published; **proof passed** |
| 1.0.0 | #78 | 45369953009; main 45371222409 | `67eac09` | `platform-core-v1.0.0` | 45372362500 |
| Main to `1.1.0-SNAPSHOT` | #79 | 45374530618 | `9b42fa0` | — | — |

1.0.0 changes no Java or SQL file against rc.3: version, notes, README and the sample app's default version only.

**Re-run:** the publish job of 45360791836 failed before building anything: Maven Central answered 429 (too many requests) for
Boot's dependency POM. Nothing had been published. The failed job was run again once; that is attempt 2.

## Install-proof runs

Every step of the guides on both systems; no Docker on either; PostgreSQL is the operating system's own (the runner image's
package or installer), Vault is the official binary, checked against its published checksum.

| Version | Linux | Windows | Run |
|---|---|---|---|
| 0.8.1 (while the proof was written) | steps 1–10 passed, 11 failed | same | 45344173167 and later branch runs |
| 1.0.0-rc.1 | failed at the last refusal check | same | 45354498808 |
| 1.0.0-rc.2 | passed | passed | 45360791836 |
| 1.0.0-rc.3 | **passed** | **passed** | [45367140622](https://github.com/mohankandar/platform-core/actions/runs/45367140622) |
| 1.0.0 | **passed** | **passed** | [45375802820](https://github.com/mohankandar/platform-core/actions/runs/45375802820) |

## Independence

No other product's name or behaviour in code. The sample app is made up and named only "sample app".

## Findings of the proof

| # | What differed | Guide or code | Fix |
|---|---|---|---|
| 1 | With the database stopped, the app showed a stack trace without its documented code | code: identity, erase log, integration, model governance had no failure report for their tables | failure analyzers, `FailureReportsTest`; `*_CHECK_FAILED` texts say "could not be reached, or refused"; monitoring guide section 3 |
| 2 | A dump of one schema cannot be restored (`pg_trgm` is left out) | guide | fields guide section 4: back up the whole database |
| 3 | Lists of schema files without the newest (identity V5, integration V3, fields V2, erase log V3); `sub` still named as a default claim | guides | corrected |
| 4 | The error catalogue counted a module of a `-rc.1` jar as another module (found by CI of candidate 1's PR) | code | `Catalogue.moduleOf` |
| 5 | With the customer's keys deleted and `customer-keys-required=true`, the app **started**; the guide says the check looks at them | code: `KeyServiceCheck` asked the key service's health only | it uses the customer's two keys; `BuiltInChecksTest.customerKeysMissingOrUnusable` |
| 6 | Each schema run needs `baselineOnMigrate` in a schema with other tables; my script did it, no guide said so | guides | seven guides |

Finding 6 needed no code change but a new candidate by the task's rule, hence rc.3.

## What was built

- `examples/sample-app`: `app` (every module as its guide says), `mocks` (login system, destination and mail server in a process of
  their own, from the testkits), `install/install-proof.sh`, `install/grants.sql`. Not in the reactor, not in the BOM, not published.
- `.github/workflows/install-proof.yml`: by hand with a version, and by the release workflow for every `-rc` tag. A candidate's
  GitHub release is marked as a pre-release.
- `docs/install/switch-over.md`.
- `StableApiTest` with `docs/api/public-api-1.txt` (7,832 signatures): every public type outside `.internal` packages of every
  published module. A line that goes or changes fails the build.

## B5

| Check | Result |
|---|---|
| Findings | no open Critical, High or Medium (15 such rows, all FIXED) |
| `gaps.md` | 3 A and 85 B rows, all FIXED |
| Advisories | 104 third-party artifacts, every scope: none open; control with old versions found 10 |
| Name guard, public API, catalogue | green in main's CI 45365997500 |
| Full signatures recorded | yes |

## Tests

Local `mvn verify` of the last code change: 0 failures; 11 skipped on this laptop only; CI skips none. New: `StableApiTest`,
`FailureReportsTest`, `customerKeysMissingOrUnusable`.

## Decisions I had to make

1. **The proof was first developed against 0.8.1**, to find errors without spending release candidates. Every proof that counts
   ran against a published candidate.
2. **Development licence:** the demo licence and its development public key, built into the sample app's development build.
3. **The customer's keys missing is no problem when they are not required** (`--no-customer-keys` is allowed then).
4. **The snapshot also holds the testkits,** because apps' tests use them.

## Not done, and why

- **The licence command and `crypto destroy-customer-keys` are not run** by the proof; setup stores the licence. The task does not
  list them.
- **`docs/install/integration.md`** is proved for one folder export and one API push, as the task says; polling, watched folders,
  callbacks and mutual TLS are covered by the modules' own tests only.

## Questions

1. **`main`'s branch protection refused one merge for a moment after CI was green** (#77). It merged by the normal line when tried
   again. Recommendation: none needed; noted.
2. **Should the install proof also run monthly against the newest release,** so a change of the runner images or of Vault is noticed?
   Recommendation: yes, a scheduled trigger in a later task.
