# Pinned specification: sdv-ingestion-v1

The `sdv-ingestion-v1` profile (`profile.json`) and its contract tests are built from this copy of the published API
specification. It is changed only deliberately, with a release of the API that changes it.

| | |
|---|---|
| File | `sdv-ingestion-v1.openapi.json` (exact copy, byte for byte; `-text` in `.gitattributes`, so no line-end change) |
| Source repository | `secure-document-vault`, `docs/api/sdv-ingestion-v1.openapi.json` |
| Branch | `docs/ingestion-contract` |
| Commit | `76307d61e8c8f4029f5fdcf3161425a826c6d5d1` (`76307d6`, task 0040 part 2: busy answer 503 SDV-SYS-018 with Retry-After, upload SDV-STO-004) |
| Commit date | 2026-11-22 |
| Pinned on | 2026-11-22 (platform-core task P024) |
| Spec `info.version` | 1.0.0 |
| SHA-256 of the file | `858b0f07793ddacbbabf2c5d32a89f0e57b6fa425142c8f1e0c219f7cc1feed2` |
| Next step | Re-pin to the tag `sdv-ingestion-v1.0.0` when it exists (a later, separate step) |

The build checks the SHA-256 above against the file (`PinnedSpecTest`).

## What the copy does not show yet

- **Readiness busy.** The profile's health rules treat a readiness answer of **503 with `SDV-SYS-018`, or 503 with
  `Retry-After`,** as busy (wait, send fewer at once, no pause). The pinned readiness operation
  (`/actuator/health/readiness`) still documents only `200` and `503` with `{"status": …}`, without the busy code or
  Retry-After: that comes in a separate commit of the API. The contract tests therefore do not require the
  readiness-busy answer from the spec; the rules are tested against the mock. Until the API's commit lands, a readiness
  503 without either is treated as down, as before.
- **Answer examples.** The spec has no examples of its 2xx answers; the mock builds answers from the response schemas.
