# Integration profiles — install guide for IT

A profile is a ready-made set-up for one published API: which calls to make, what the answers mean, and how to check
that the destination is ready. The product loads it and you complete it with what only your installation knows: the
destination's address, the sign-in, which case fields go where, and the profile's settings. Profiles are data shipped
with the release; you do not edit them.

## sdv-ingestion-v1 (the document vault's ingestion API, version 1.0.0)

What it does, for each package:

1. **Create a submission** (`POST /api/v1/ingestion/submissions`): the package's ID, how many documents, the list of
   documents, and the case fields you map (for example `contractNumber` from the field `contract_number`).
2. **Upload each PDF** (`POST …/submissions/{id}/documents`), one call per document, with its file name, its ID and
   its sensitivity level (`STANDARD` unless the product says otherwise).
3. **Check each upload:** the vault's checksum and size must match what was sent; otherwise the delivery stops as
   "unclear" (`INT_INTEGRITY_MISMATCH`) and is not counted as delivered.

Before sending, the product asks the vault whether it is ready (`GET /actuator/health/readiness`, no sign-in):

| The vault answers | Means | The product |
|---|---|---|
| 200 | Ready | Sends |
| 503 with code `SDV-SYS-018`, or 503 with `Retry-After` | **Busy** | Waits (the seconds in `Retry-After`) and sends fewer at once. Not paused, no attempt counted |
| 503 without the busy code and without `Retry-After`, no answer, a timeout | **Down** | Pauses and checks again |

### What you set up

1. **The destination's address**, for example `https://vault.yourcompany.local` (https; plain http only on an
   internal network on premises).
2. **A client in your login system** for this sending system: OAuth 2.0 client credentials. Store its secret (or its
   certificate) in the secret store; the product holds only the reference. The token's audience must be the one the
   vault expects.
3. **The setting `senderIdentity`:**

   | Value | Use it when | What your login system must do |
   |---|---|---|
   | `CLAIMS` (default) | The vault identifies the sender by claims in the token (vault releases before the client-ID lookup) | Add two claims to this client's tokens: `sdv_tenant_id` (the vault's tenant ID for you) and `sdv_source_id` (the vault's ID of this sending system), both as text. The vault gives you both values. Without them the product stops before sending (`INT_TOKEN_CLAIMS_MISSING`) |
   | `LOOKUP` | The vault finds you and the sending system from the client ID (it has the client registered) | Nothing extra: a standard token is enough |

   The product sends the same requests either way; the setting only decides what it checks in the token.
4. **The field mapping:** for each field the vault expects (Tenant IT set them up there), the case field it comes
   from. A sensitive field (for example a person's name) is sent only if you allow it for this destination; allowing
   it is recorded in the configuration's history.

### What the answers mean

Every answer code of the API has its own rule (the full table is in `docs/work/P024-report.md`). In short:

- **Sent / already received** (a retry the vault recognises, or a document it already had): done.
- **Busy** (503 `SDV-SYS-018`): wait the `Retry-After` seconds and send the same request again; no attempt counted.
- **Down** (503 with another code, for example `SDV-STO-004` storage not available, or no code): the product waits
  and checks readiness. The vault's code is kept, so you can see why.
- **Sign-in** (`SDV-AUT-*`, also 503 `SDV-AUT-004`, the sign-in server's keys can't be fetched): fix the sign-in set-up.
- **Refused** (400, 404, 409, 413, 415 with a code): the delivery or its data must be corrected.

If a delivery stops half-way, the next attempt carries on from where it stopped: the submission is not created again
and documents already uploaded are not sent again.
