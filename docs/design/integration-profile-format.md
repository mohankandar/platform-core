# The integration profile format: `platform-integration-profile/1`

A profile describes **one published API** well enough that any app on platform-core can talk to it by
configuration alone. The API's owner writes it and publishes it next to the API's OpenAPI file, with a release tag.
platform-core ships no profiles: it reads the file an installation gives it.

The machine-readable definition is the JSON Schema
`platform-integration/src/main/resources/schema/platform-integration-profile-1.schema.json`. The checks are
`com.documentplatform.integration.profile.ProfileValidator`, and the loader is `ProfileLoader.load(Path)`.

## What a profile holds

| Field | What it says |
|---|---|
| `format` | Always `platform-integration-profile/1`. |
| `name`, `version` | The profile's name (`acme-archive-v1`: lower case, digits, `.`, `_`, `-`) and its own version (`1.2.0`). |
| `description` | One paragraph for people. |
| `api` | The API it describes: `title` and `version`, as in the OpenAPI file. |
| `direction`, `kind` | `OUTBOUND` with `API_PUSH`, or (since P027) `INBOUND` with `API_POLL`. |
| `steps` **or** `packaging` | For `API_PUSH`, exactly one of them. The calls, in order: method, path, header and body templates, which values to read from the answer, the integrity check, rules per step. Or a packaging preset (`TWO_STEP`, `MULTIPART`, `FILE_PER_CALL`) with its parts; a preset expands to ordinary steps. |
| `signIn` | The sign-in types the API accepts (`NONE`, `BASIC`, `API_KEY`, `BEARER_FIXED`, `OAUTH2_CLIENT_CREDENTIALS`, and since P026 `MUTUAL_TLS`: the API requires a client certificate, listed together with the type used with it, if any). The certificate itself is the installation's, never in a profile. Also `installationSupplies`, the words IT recognises for what it must supply (`tokenAddress`, `clientId`, `credential`, `scope`, `apiKey`), and `apiKeyHeader` for an API key. |
| `health` | The readiness call (`GET` / `HEAD`, path, with or without sign-in) and its own rules. For example, a 503 with a busy code or with Retry-After is BUSY, not down. |
| `rules` | How answers are classified: status, the API's error code (exact, a prefix such as `DMS-*`, or `-` for none), optionally one field of the answer or the presence of Retry-After. The outcome is SENT, ALREADY_RECEIVED, REFUSED, SIGN_IN_FAILED, BUSY, OUTAGE, … It can also give a platform code and say whether an attempt is used. |
| `errorCodePointer` | Where the API puts its error code in an error answer (a JSON pointer, `/code`, `/error/code`). |
| `limits` | Defaults: timeouts, largest file, most documents, largest answer read, largest package. |
| `values` | The app values the templates use as `{value.<name>}`, each marked required or not. |
| `fields` | Where mapped case fields go: one object of a step's JSON body, written `"<object>":{}` in its template. Also the field names the API expects (required or not), and whether other names may be mapped. |
| `documentAttributes` | Per-document values (`{doc.attr.<name>}`), with their allowed values and default (for example a classification). |
| `settings` | Choices the installation makes, each with a default. Each value stands for engine settings. Today there is one engine setting: `token.requiredClaims`, the claims the OAuth 2.0 token must carry. |
| `notes` | Notes for IT, in plain words. |
| `testMode` | What the Test action runs: `FULL` (every step with fake data) or `CHECK_ONLY` (health and sign-in). |
| `steps[].accepted` | Since P026, optional, last step only (run once): "accepted, check later". The API may answer **202 Accepted**. `referencePointer` (where the 202 answer holds the status reference; without it, the `Location` header), `statusMethod` (`GET` or `POST`) and `statusPath` (with `{status.reference}`; only without `Location`), `statePointer` and the state values `done`, `failed` and `working`, `defaultWaitSeconds` (when Retry-After is missing, default 30) and `read` (values from the "done" answer). The attempt ends ACCEPTED; the app asks for the status later, one call each time. |
| `poll` | Since P027, for `API_POLL` only, in place of `steps` and `packaging` (and without `fields` and `documentAttributes`): how the API's waiting documents are fetched. `list` (the list call: `method` GET or POST, `path`, optional `headers`, `bodyTemplate`, `rules`), `itemsPointer` (where the list answer holds the items; empty when the answer is the list), `idPointer` (each item's stable ID, relative to the item), `values` (name → pointer relative to the item, handed to the app), `fileNameValue`, `paging` (`mode` NONE, CURSOR, NEXT_LINK or PAGE_NUMBER, with `pointer`, `param`, `sizeParam`, `pageSize`, `firstPage`, `maxPagesPerPass`, `resume` CONTINUE or FROM_START), `download` (its answer is the document) and the optional `acknowledge` (made only after the app took the item). The per-item calls may use `{item.id}` and `{item.<name>}`; the list call only `{correlationId}`. The engine adds the paging parameters to the list call's query string. |

Templates are the same fixed building blocks as in any configuration, with no expressions or scripts:

- `{package.*}`, `{doc.*}`, `{doc.attr.*}`, `{value.*}`, `{step.<step>.<value>}`, `{correlationId}`; for polling
  `{item.id}` and `{item.<name>}`;
- in JSON only, the one list construct `{#each doc}…{/each}`;
- in JSON only, "omit when missing": `{name?}` as the whole value of an object member leaves the member out when it
  has no value (P025; refused anywhere else with `INT_CONFIG_OMIT_PLACE`, reported here as `INT_PROFILE_TEMPLATE`).

## What a profile never holds

- an address;
- a secret;
- a client ID;
- an entity;
- a case field key;
- any customer data.

The installation adds those when it completes the profile (`IntegrationProfile.complete`): entity, product, address,
sign-in, field mapping and settings. The completed configuration then goes through the normal save checks.

The validator refuses an address (`scheme://`) or anything that looks like a secret anywhere in the file, and a
`{field:<key>}` placeholder in a template. Each of these gives `INT_PROFILE_INSTALLATION_DATA`.

## Checks and codes

Every problem has a code and a JSON pointer into the profile. It never repeats a value.

| Code | Means |
|---|---|
| `INT_PROFILE_UNREADABLE` | The file cannot be read, is not UTF-8, or is larger than 2 MiB |
| `INT_PROFILE_NOT_JSON` | Not one JSON object |
| `INT_PROFILE_FORMAT_UNKNOWN` | A `format` this version does not know |
| `INT_PROFILE_SCHEMA` | Does not fit the JSON Schema (missing, unknown, wrong type or shape) |
| `INT_PROFILE_STEPS` | Neither steps nor a preset (or both), a preset missing its parts, steps that cannot run; for `API_POLL`: no `poll`, `poll` with steps, a preset, fields or document attributes, not `INBOUND`, or poll calls or paging that cannot run |
| `INT_PROFILE_TEMPLATE` | An unknown or forbidden placeholder, one in a query string, a body that is not JSON |
| `INT_PROFILE_HEADER` | A header only sign-in or HTTP may set (Authorization, Host, …), or a header name that is not valid |
| `INT_PROFILE_RULE` | A rule that is not valid |
| `INT_PROFILE_VALUE_UNDECLARED` | `{value.<name>}` not declared in `values` |
| `INT_PROFILE_ATTRIBUTE` | `{doc.attr.<name>}` not declared, or a default that is not among its values |
| `INT_PROFILE_FIELDS` | The fields target names no step, or its body has no `"<object>":{}` |
| `INT_PROFILE_SETTING` | A default that is not one of its values, or an engine setting that does not exist |
| `INT_PROFILE_SIGN_IN` | An API key without its header name |
| `INT_PROFILE_INSTALLATION_DATA` | An address, something that looks like a secret, or a case field key |

## Versioning

- `/1` changes **only by additions that old profiles still pass**. A new optional field is allowed; so is a new
  value of an enumeration. Nothing becomes required, and nothing that was allowed becomes refused.
- Any other change is a new format, `platform-integration-profile/2`, with its own schema file. The validator refuses
  any format it does not know (`INT_PROFILE_FORMAT_UNKNOWN`); it never guesses.
- A profile's own `version` is the API owner's. Setup checks that the release tag named for a profile in the sign-in
  file ends with that version.

## For the API's owner: the contract kit

`platform-integration-testkit` has `ProfileContract`, for the owner's own build:

- `checkAgainstSpec(profile, openapi)`. It checks that:
  - every operation the profile uses exists, with the same method and path;
  - required headers are sent, and no header is sent that the spec does not know;
  - request bodies (JSON, a multipart JSON part) validate against the request schemas;
  - the values the profile reads are in the answers;
  - every error code the spec lists is named by a rule. The spec lists each answer's codes in the response
    extension `x-error-codes`, a list of strings.
- `checkAgainstServer(profile, baseUrl, signIn, secrets, installation)`: a live check, with no real data. The health
  check must be healthy or busy, sign-in must work, and one empty request must be refused with a code that a rule
  names.

Both report every problem. `MockSource.forProfile` runs a mock of a polling profile's source (its paging, download
and acknowledge calls, with switches). `ProfileMock` runs a mock of any profile, optionally following the OpenAPI file. Its
switches come from the profile's rules: busy, down, refused, already received, sign-in refused, health busy and health
down.
