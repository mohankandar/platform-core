# P054 report — platform-core 1.1.0: batch opening of data keys and sensitive values

Date: 2026-11-25
Branch: `feature/batch-unwrap-1.1.0` (then `release/1.1.0`, `release/1.2.0-snapshot`, `docs/p054-report`)
Outcome: DONE

## Result

| Step | PR | CI runs | Commit | Tag | Release run |
|---|---|---|---|---|---|
| Task files, code, docs, version 1.1.0-rc.1 | #84 | 45553805930; main 45554528123 | `6736de4` | `platform-core-v1.1.0-rc.1` | [45555459502](https://github.com/mohankandar/platform-core/actions/runs/45555459502): published; **install proof passed on Linux and Windows** |
| 1.1.0 | #85 | 45557062399; main 45557880869 | `a7d54f1` | `platform-core-v1.1.0` | 45558823936: published |
| Main to `1.2.0-SNAPSHOT` | #86 | 45560384533 | `63a36de` | — | — |

Every check was green the first time; every merge by the stop-on-failed-check line. No re-run. 1.1.0 changes no Java or SQL file
against rc.1. The `release.yml` line P053 changed ran **for the first time** in 45555459502 and worked: it called
`install-proof-run.yml`, both systems.

Local: the four changed modules (205 tests, 0 failures, the ones built along included), then one full `mvn -B verify` (public API change): 0 failures,
11 skipped on this laptop only; CI skips none.

**Key service calls for a page of 25 records: before 25, after 1** (decrypt requests counted in front of the real Vault, and
`InMemoryKeyService.calls()` for `FieldValues`).

## Independence

No other product's name or behaviour in code.

## What was built

| New | Signature |
|---|---|
| `KeyService` | `int MAX_BATCH = 100`; `default List<Unwrapped> unwrapAll(CustomerId, List<String>)` |
| `Unwrapped` (`com.documentplatform.crypto`) | `final class … implements AutoCloseable`: `static opened(byte[])`, `static failed(CryptoException)`, `static isItemError(CryptoError)`, `static closeAll(Iterable<Unwrapped>)`, `ok()`, `error()`, `failure()`, `key()`, `close()` |
| `VaultKeyService`, `InMemoryKeyService` | `unwrapAll`: one Transit call, one counted call |
| `RecordCipher` | `List<OpenedKey> openKeys(CustomerId, List<StoredKey>)`; `record StoredKey(String recordContext, String envelope)`; `final class OpenedKey implements AutoCloseable`: `key()`, `failure()`, `close()` |
| `FieldValues` | `List<SensitiveRead> readSensitive(List<RecordRef>)` |
| `SensitiveRead` (`com.documentplatform.fields.values`) | `final class`: `ref()`, `ok()`, `values()`, `failure()`, `code()` |

`docs/api/public-api-1.txt`: 36 lines added, none changed. No schema, setting or error code changed.

**Vault.** Page used: <https://developer.hashicorp.com/vault/api-docs/v1.21.x/secret/transit>, "Decrypt data":
results keep the input order; a batch with some failed items answers 400 unless `partial_failure_response_code` is given; "If all
values fail an error code is still returned." A real Vault 1.21.4 answered as it says:

| Batch | HTTP | Body |
|---|---|---|
| some items fail, `partial_failure_response_code` 200 | 200 | `data.batch_results`, `error` per failed item |
| every item fails | 400 | the same `data.batch_results`, no `errors` |
| the key does not exist | 400 | `errors` only → whole call, `CRYPTO_KEY_MISSING` |

**Delegates (B4).** No production class wraps or forwards a `KeyService` (crypto-spring, monitoring, erase log and setup only use
one). The one delegate is a test's, in `InMemoryKeyServiceTest`; it forwards now. The design doc states the rule.

## Tests asked for → where

| Asked | Where (all passed) |
|---|---|
| Order for 1, 25, 100; 101 refused; empty list; null item | `UnwrapAllVaultTest.orderIsKeptInOneCall`, `.limitEmptyAndNullCallNothing`; `UnwrapAllTest.orderAndCalls`, `.itemsFailAloneAndTheCallFailsWhole` |
| Mixed list | `UnwrapAllVaultTest.aBadItemFailsAloneAndAsTheSingleUnwrapDoes` (same message as the single call); `UnwrapAllTest` |
| Switched-off version; mixed versions | `UnwrapAllVaultTest.versionsMixAndASwitchedOffOneFailsAlone` |
| Away, time-out, permission; nothing half-returned | `UnwrapAllVaultTest.aFailureOfTheKeyServiceThrows`, `.anAnswerThatIsNotOneResultPerItemThrows`; `UnwrapAllTest.nothingHalfReturned` |
| Real Vault: one call, partial reply, all-failed reply | `UnwrapAllVaultTest` (a relay counts and keeps Vault's own answers), `.whenEveryItemFailsVaultAnswers400WithTheResults`, `.aPageOf25RecordsTakesOneCallInsteadOf25` |
| `readSensitive(list)` as the single read | `ReadSensitiveListTest` (class and message of each failure compared), `PostgresValueStoreTest.aPageIsReadInOneKeyServiceCall` |
| Wiping; no secret in any text or log | `orderIsKeptInOneCall`, `orderAndCalls`, `noKeyOrWrappedValueInAnyText`, `NoSecretInLogsTest`, `ReadSensitiveListTest` |
| Stable names and signatures | `StableNamesTest` (five types added), `StableApiTest` |

## Files changed, commits

`58b653e` (P054.md, P048 note), `6736de4` (code, tests, docs, rc.1), `a7d54f1` (1.1.0: version, notes, README and the sample app's
default version), `63a36de` (1.2.0-SNAPSHOT).

## Decisions I had to make

1. **`RecordCipher.openKeys` is new public API the task does not list.** `FieldValues` is in another module and needs the opened
   record keys; only `RecordCipher` may make them.
2. **`SensitiveRead` holds the exception the single read throws,** not a `FieldValuesError`: the single read also throws
   `CryptoException` (for example a changed envelope). `code()` gives either code.
3. **`Unwrapped` keeps the exception as well as the code,** so the single call's message is not lost.
4. **"One `unwrapAll` per customer" is one per page:** a `FieldValues` serves one customer; another customer's record is
   `VALUES_WRONG_CUSTOMER` for that record, as in the single read.
5. **A text that is no Transit ciphertext is refused before the call,** as by `unwrap`; a list of only such texts calls nothing.
6. **A 400 from Vault without `batch_results`** is about the call: `CRYPTO_KEY_SERVICE_ERROR` unless it names a missing or
   switched-off key.
7. **platform-crypto's tests now run on Vault 1.21.4** (were 1.17.6), the version of the install proof and of this task.
8. **The feature and rc.1's version went in one PR,** to save one CI round.

## Not done, and why

- **The store is still read record by record:** a batch read would add a method to the `ValueStore` SPI, which the task does not
  ask for. A page of 25 is 25 `find` calls on the database and 1 key service call.
- **One local build failed** before the full one passed: class files of platform-erase-log went missing from `target` while the
  build ran. The module was not changed and no cause was proved; another process writing there (an editor's Java build) is my guess.

## Questions

1. **A batch `find` in the store** (one SQL statement per page)? Recommendation: only when an app measures the database part as
   slow; as a `default` method, so it stays additive.
2. **platform-licensing-issue's tests still use Vault 1.17.6.** Recommendation: move them to 1.21.4 in P050.
3. **`readSensitive(null)` no longer compiles** (two overloads); it only ever threw. Recommendation: none needed.
