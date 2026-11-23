# Error codes: the common standard (P032)

**The rule (owner):** whenever something fails, say exactly what failed, why, and what to do. Never "an error
occurred". The person sees a safe, plain message with a reference; IT and the log get the exact cause; every code has a
runbook entry (its fix). Never in a message, log, metric or bundle: passwords, keys, tokens, secret values, document
content, or field values (sensitive or not). Names and addresses of things are fine: a secret path, a key alias, a
server address, a document reference.

## 1. The shape: `PlatformCode` (module `platform-errors`)

| Part | What it is |
|---|---|
| `code()` | The stable name. Platform codes keep their names (`IDN_*`, `INT_*`, `OUT_*`, `SETUP_*`, `LICENCE_*`, `MON_*`, …); nothing is renamed. |
| `kind()` | `REFUSED` (the request is not allowed), `FAILED` (we or a dependency failed), `NEEDS_PERSON` (not a failure: sent to review), `INFO` (a result). |
| `userMessage()` | Plain and safe; says what the person does; nothing about accounts or internals. |
| `cause()` | The exact cause for IT and the log, with `{name}` placeholders for safe details only. |
| `fix()` | What IT does (the runbook entry). |
| `supportNote()` | What support tells the customer. |
| `httpStatus()` | Optional. |

Every platform code enum implements it. The texts are data next to each enum: `<Enum>.codes.properties` (nested
enums `Outer.Inner.codes.properties`) with keys `<CODE>.kind`, `.message`, `.cause`, `.fix`, `.support`, `.http`;
defaults for a whole enum under `*.` and for a prefix under `PREFIX_*.` (the longest prefix wins, the code's own text
wins over both). An enum may override a method instead (for example `LicenceRefusal.fix()` is its `action()`).

**The catalogue** `docs/errors/platform-error-codes.md` and `.json` (format `platform-error-codes/1`: module, family,
code, kind, message, cause, fix, support, http) is generated from the enums. The build check
(`platform-error-catalogue`, run on CI) fails when a code enum does not implement `PlatformCode`, when a code lacks a
message, cause or fix, when a code name is used in two modules, or when the generated files are stale. Regenerate
with `mvn -pl platform-error-catalogue -am verify -Dplatform.errors.write=true` and commit the two files.
`platform-error-catalogue` is a build check only: it is never deployed and is not in the BOM, so no app depends on it.
Since P037 the check also refuses a text with a gap where a name was dropped (`Set , and`, `()`) or a `%s` slot, a
user message that names internals (SQL, an exception, a stack trace, a driver), an exception that carries a code
without `HasPlatformCode`, and a code name that disappears (`docs/errors/code-names.txt` lists every name ever
released; the write mode only adds to it).

## 2. Failures: `PlatformError`, `ReferenceId`, `FailureLog`

- `ReferenceId.next()`: `R-` plus 12 characters of Crockford base32, time-ordered (tenths of a second) and
  unguessable (random bits), never the same twice in one process (within a tenth the random part steps on by a
  random amount). Inside a request handled with platform-monitoring-spring's filter, the request's
  reference (`ReferenceId.current()`, from `X-Request-Id`) is used.
- `PlatformError`: code, the user message with `(reference R-…)`, the cause with its safe details filled in, the
  reference, the time. A detail named like a secret (`password`, `secret`, `token`, `key`, `credential`, …) loses its
  value; so does any value that looks like a JWT, a PEM block, a `password=…` pair, a Bearer or Basic value, or a long
  key-like run (`SafeText`). Secret references (`…-secret`, `…-ref`) keep their name.
- `FailureLog.record(code, safeDetails)` writes one log line `code=… ref=… cause="…"` (ERROR for FAILED, WARNING for
  REFUSED and NEEDS_PERSON) and returns the reference for the person's message, the IT Admin's screen and a support
  ticket. The last 200 are kept in memory for the Support screen and the diagnostic bundle.
- Exceptions that carry a code implement `HasPlatformCode` (the schema exceptions, `LicenceException`,
  `CryptoException`), so a caller reports the code, never the exception's text.

## 3. An app's own codes

Apps keep their names (for example `LV-…` or `AREA-NNN`). Either:

1. **implement `PlatformCode`** on the app's own enum and put the texts next to it, exactly as the platform does
   (and build the app's catalogue with `Catalogue.of(List.of(MyCode.class))`); or
2. **map to it**: a small adapter that returns the app's code name and texts.

```java
public enum IntakeCode implements PlatformCode {   // texts in IntakeCode.codes.properties
    AREA_001_FILE_TOO_LARGE,
    AREA_002_UNKNOWN_SENDER
}

String ref = FailureLog.record(IntakeCode.AREA_001_FILE_TOO_LARGE, Map.of("limit", "250 MB"));
ui.show(IntakeCode.AREA_001_FILE_TOO_LARGE.userMessage() + " (reference " + ref + ")");
```

Show the person `userMessage()` with the reference; log the cause (the `FailureLog` line); give IT `fix()`; the
support team reads `supportNote()`.

## 4. The API error format (integration standards requirement 16)

Every error answer of an app's API:

```json
{"code": "IDN_PUSH_NOT_BOUND", "message": "The calling system is not allowed to send to this API.",
 "reference": "R-0J5K2Q7M9X4D", "details": {"field": "loanId"}}
```

- `code`: the code; `message`: its `userMessage()`; `reference`: the failure's reference; `details` (optional):
  safe values only (names of fields, limits), never a value the caller sent.
- The HTTP status is the code's `httpStatus()` where it has one; else 4xx for REFUSED, 5xx for FAILED.
- The `X-Request-Id` request header is echoed back; when it is missing (or not a short safe token) one is made, and
  it is the failure's reference (platform-monitoring-spring's `RequestReferenceFilter`).

**Versioning of an app's API:** the version is in the path (`/v1/…`). Within a version only additions (new
operations, new optional fields, new codes). Anything else is a new version (`/v2/…`), and an old version ends only
after at least one release of notice.
