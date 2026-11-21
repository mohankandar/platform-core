# P011 report — licensing: notification events and Vault signing

Merged into `main` (fast-forward) and pushed. The feature branch is deleted locally and on origin.

## What was built

### Owner answers to P010

Q1–Q5 are recorded in `docs/design/licensing.md`, section 8, "Owner answers to the P010 questions". Nothing in the
code changed for them: Q1, Q3, Q4 and Q5 confirm what was built, and Q2 belongs to the setup task.

### A. Licensing notifications (`platform-licensing-spring`, package `…licensing.spring.notifications`)

`platform-notifications` is an **optional** dependency. If the app does not have it, the new auto-configuration is
not even loaded. Licensing works as before and publishes nothing; a test runs the app with the notifications classes
hidden to prove it.

- **`LicensingEvents.register(catalogue, itAdmin, businessAdmin)`** registers the eight event types (table below).
  The app calls it while it builds its `EventCatalogue`, passing its own role IDs.
- **`ActivatedSlots`** (supplied by the app) maps each activated slot ID to the app's entity ID. Business events go
  out once per activated slot; a slot that is not activated gets nothing. Clock and version events are technical and
  go out per tenant. If business types are registered and the app has no `ActivatedSlots` bean, the app does not
  start, with a message naming the bean.
- **`LicensingNotices.check()`** reads the licence state and this month's usage and publishes what is due. It runs on
  its own daemon thread (`LicensingNoticeWorker`): first one minute after start, then every
  `platform.licensing.notifications.check-interval` (default 1 hour).
  - **One-off events** (expiring, near limit, at limit, version not covered) are claimed first in the new table
    `lic_notice_sent` (`PostgresNoticeLog`), under an ID that says what the event is, then published. A restart or a
    second instance finds the claim and publishes nothing. A publish that fails gives the claim back, so the next
    check tries again.
  - **Conditions** (grace, intake stopped, clock behind, over limit) are reported with `conditionFailing` on each
    check while they hold, and with `conditionCleared` once they stop. The notifier keeps their state in its own
    store, so they alert once, repeat, and send Recovered, also across restarts.
  - **When grace ends**, the grace condition is closed without a Recovered message and "intake stopped" takes over.
    This uses a new `Notifier.conditionSuperseded(key)` in `platform-notifications`.
- **Content:** a message holds the event name, the entity, counts and times, an identifier and the configured link.
  The identifier is the licence ID, or the allowance (`PRODUCT_A:PAGES_PER_MONTH`) for usage events. It never holds
  keys, signatures, key IDs, the customer name or prices; a test checks every message for these.
- **Properties** (`platform.licensing.notifications.*`): `enabled`, `check-interval` (1 min to 24 h), `link` (a path
  in the app), `release-version`, `release-date`. Each wrong value is reported by its key, and the value is not echoed.

#### The events and their defaults

"Daily" means once a day at 08:00 in the tenant's time zone. Critical events cannot be sent as a daily summary, so for
them "daily" means the alert repeats every 24 hours.

| Event type ID | Name | Kind | Defaults | Recipients | Fields |
|---|---|---|---|---|---|
| `licensing.licence-expiring` | Licence expiring | business, per slot | once per band (60, 30, 7, 1 days), daily summary | Business Admin | days left, licence ends |
| `licensing.licence-in-grace` | Licence expired, grace period running | business, per slot, **critical** | condition: alert at once, repeat every 24 h, Recovered on renewal | Business Admin, IT Admin | grace days left, licence ended, grace period ends |
| `licensing.intake-stopped` | Intake stopped, licence expired | business, per slot, **critical** | condition: alert at once, repeat every 24 h, Recovered on renewal | Business Admin, IT Admin | licence ended, intake stopped |
| `licensing.clock-behind` | Server clock behind | technical, **critical** | condition: alert at once, repeat every 4 h, Recovered when within tolerance | IT Admin | minutes behind |
| `licensing.usage-near-limit` | Usage near limit | business, per slot | once per allowance and month at **80%** (admin threshold `nearLimitPercent`, 50–99), daily summary | Business Admin | used, allowed, percent used |
| `licensing.usage-at-limit` | Usage at limit | business, per slot | once per allowance and month at 100%, daily summary | Business Admin | used, allowed, percent used |
| `licensing.usage-over-limit` | Usage over limit | business, per slot | condition: alert at once, repeat every 24 h, daily summary, Recovered at the new month | Business Admin | used, allowed, percent used |
| `licensing.release-not-covered` | Version not covered by licence updates | technical | once per licence version and release, daily summary | IT Admin | update period ended |

### B. Vault Transit signing (`platform-licensing`, package `…licensing.issue`)

- **`Signer`** replaces `SigningKeyResolver`. It has `sign(keyId, bytes)`, plus `resolveKeyId`, `publicKeys`,
  `auditReferences` and `close`. `LicenceSigner` and `LastSeenReset` now sign through a function, so no private key
  ever enters the process.
- **`VaultTransitSigner`** uses the JDK `HttpClient`:
  - It signs with `POST /v1/<mount>/sign/<name>` (`key_version` given) and checks every returned signature against
    the key's public key before the file is written.
  - It refuses a key that is not `ed25519`, and a key that allows export or plaintext backup.
  - Key ID = Transit key name + version (`lic-2026:1`). A bare name (`--key-id lic-2026`) means the latest version,
    and that version is written into the licence.
  - Credentials: `VAULT_TOKEN`, or `--vault-login userpass --vault-user NAME`. The login asks for the password without
    echo, and the token it gets is revoked when the run ends. The command also takes `--vault-addr` / `VAULT_ADDR`,
    `--vault-mount` and `VAULT_NAMESPACE`.
  - Redirects are not followed, so a token is never sent to another address. The JVM trust store and proxy settings
    are used.
  - The token and password are never logged, printed, written to the records or the run log, or put in an exception
    message.
- **`IssueLicenceCommand.main` signs with Vault only.** The environment-variable signer moved to test sources
  (`EnvironmentSigner`). The testkit's `InMemorySigningKeyResolver` became `InMemorySigner`.
- **New option `--export-trusted-keys lic-2026[,lic-2027] --out trusted-keys.json`** writes the public keys of every
  version in the `platform-licence-keys/1` format and prints the install-config lines.
- **Run log:** `keyId` is now the key that signed, with its version. It also records `signer: vault-transit` and
  `signerAuditRefs` (the Vault request IDs, which match Vault's audit log). New outcome: `KEYS_EXPORTED`.
- **New refusal codes:** `ISSUE_VAULT_NO_TOKEN`, `ISSUE_VAULT_LOGIN_REFUSED`, `ISSUE_VAULT_PERMISSION_DENIED`,
  `ISSUE_VAULT_UNREACHABLE`, `ISSUE_VAULT_TLS`, `ISSUE_VAULT_SEALED`, `ISSUE_VAULT_ERROR`, `ISSUE_KEY_EXPORTABLE`,
  `ISSUE_SIGNATURE_MISMATCH`. `ISSUE_KEY_NOT_FOUND` now also covers a missing key version.
- **`docs/install/licence-signing.md`** covers:
  - the audit device and the Transit key;
  - the `licensing-signer` policy: sign and read public keys of `lic-*` only, with export and backup explicitly denied;
  - the owner and backup userpass accounts, whose names live only in Vault;
  - signing, export, rotation, what is recorded where, and every refusal code.

### Docs

- `docs/design/licensing.md`: owner answers, P011 decisions and pointers.
- `docs/design/licensing-file-format.md`: key-ID rule, run-log fields, signing, and the `lic_notice_sent` table.
- `docs/install/licensing.md`: key IDs in brackets, and a new section 6 on notifications.
- `docs/design/notifications.md`: `conditionSuperseded`.
- `CLAUDE.md`: module rows updated.

## Tests

`mvn -o clean verify` and then `mvn -o install`: **504 tests, 0 failures, 0 errors** (473 before, 31 new).

| Module | Tests |
|---|---|
| identity-core, identity-spring, notifications, notifications-spring | 73, 15, 65, 29 (unchanged) |
| notifications-testkit | 102 (was 101): superseded condition |
| platform-licensing | 141 (was 129): 11 Vault, 1 notice log on Postgres |
| platform-licensing-testkit | 47 (unchanged) |
| platform-licensing-spring | 32 (was 14): 11 notices, 7 wiring |

All 473 existing tests pass. **One existing test file changed:** `IssueLicenceCommandTest` constructed a
`SigningKeyResolver`, which no longer exists. Three lines were changed (the class it builds and the "store down"
stub). Its test methods and assertions are unchanged.

**Vault** runs as Testcontainers `hashicorp/vault:1.17.6` in dev mode. The tests set it up as the install guide
describes and cover:

- Sign in Vault, export the public key, verify. The key ID `lic-2026:1` is in the licence, the records and the run
  log, and the run log has the Vault request ID.
- A reset is signed in Vault too.
- **Rotation:** a new licence gets `lic-rot:2`. The old `lic-rot:1` licence still verifies, and pinning `:1` still
  works. An app that only trusts `:1` refuses `:2` with `LICENCE_UNKNOWN_KEY_ID`.
- **A token without the policy** gives `ISSUE_VAULT_PERMISSION_DENIED`, and nothing is written or recorded.
- **The policy** allows key reads but refuses export, backup, rotate, config changes, new keys and other keys (all
  HTTP 403).
- **Vault unreachable** gives `ISSUE_VAULT_UNREACHABLE`. With no token at all, the code is `ISSUE_VAULT_NO_TOKEN`.
- **Userpass login** works, and the login token is revoked after the run (the token count is back to its earlier
  value). A wrong password gives `ISSUE_VAULT_LOGIN_REFUSED`.
- An exportable key, a non-ed25519 key, a missing key and a missing version are each refused with their own code.
- **No secrets in any output.** Stdout, stderr, run log, records, Java log records and every file written were
  checked: none contains the signer token, the root token, the password, any `hvs.` token or the signature (only the
  licence file carries the signature).
- The install guide shows exactly the policy that is tested.

**Notifications** (in memory, controlled clock, restarts simulated on the same stores):

- Expiring goes out once per band and only for the activated slot. A missed day 30 is still sent on day 25.
- Grace alerts at once to the Business Admin and the IT Admin and repeats daily. There is no duplicate after a
  restart. Intake stopped takes over with no Recovered for grace, and a renewal sends Recovered for intake stopped.
- A renewal during grace sends Recovered for grace.
- Clock behind alerts the IT Admin once and recovers.
- Near, at and over limit go out per slot; the slot that is not activated gets nothing. The admin threshold is
  honoured. Over limit recovers at the new month.
- Version not covered goes out once, to the IT Admin.
- A failed publish is retried on the next check.
- The content rule holds in every message.
- With no licensing types in the catalogue, nothing is published. A refused licence is reported by its code.

**Wiring:**

- Without notifications on the classpath, licensing works and the notification beans are absent.
- With a notifier, the check is wired and running.
- With business events and no `ActivatedSlots`, the app does not start, with a clear message.
- A catalogue without licensing types starts fine.
- Wrong properties are listed by key, without their values.
- `enabled=false` switches licensing notifications off.
- `trusted-keys[lic-2026:1]` binds with the `:` kept.

## Merge result

- `main` was at `c6a4784` (P008), so the merge brought P009, P010 and P011.
- It was a fast-forward, `c6a4784..e72358a`, pushed to `origin/main`.
- `feature/licensing-core` is deleted on origin and locally.
- This report and `docs/work/P011.md` are committed on `main` and pushed after the merge.

## Decisions I had to make

1. **What "daily" means.** Non-critical events go in a daily summary at 08:00 in the tenant's time zone. Critical
   events may only be Immediately or Every hour, so for grace "daily" means the alert repeats every 24 hours.
2. **Condition timing.** Every licensing condition alerts at once (threshold 0), because the check is hourly and each
   state is a fact. Intake stopped and over limit repeat every 24 hours, like grace. Clock behind repeats every
   4 hours, the notifications default.
3. **Expiring is sent once per band (60, 30, 7, 1), not only on the exact day.** If the app is down on day 30, the
   warning still goes out on the next check. Band 1 includes the last valid day.
4. **Grace hands over to intake stopped without a Recovered message.** For this I added
   `Notifier.conditionSuperseded` to platform-notifications. A renewal sends Recovered for whichever of the two was
   active.
5. **Licence conditions are keyed by event, tenant and entity, with no licence ID.** A renewal has a new licence ID,
   so a key that included it could never be cleared. Usage conditions use `PRODUCT:TYPE` as their identifier.
6. **The usage levels:**
   - near limit: at or above the admin's percentage (default 80, range 50–99, a notifications threshold);
   - at limit: used is at or above the allowance. If usage jumps straight past near, only "at limit" is sent;
   - over limit: used is above the allowance, as a condition.

   Usage of a product the slot has no allowance for is not notified.
7. **A new table, `lic_notice_sent` (`notice_id`, `recorded_at`), added to V1.** V1 was never released; notifications
   did the same in P005, decision 2. The column-name guard forbids "key", hence `notice_id`. Claims are purged after
   400 days.
8. **Notification tenant = the licence's tenant ID; entity = what `ActivatedSlots` maps the slot to.**
9. **Only event types in the catalogue are published.** A catalogue with none switches the check off, logged once
   at start.
10. **Licence dates are shown as the exact moment the UTC day ends** ("Licence ends: 2027-12-01 00:00" in the
    tenant's zone), so a date is never shown a day off.
11. **Version not covered needs `release-date`** (and optionally `release-version`) in the properties. It is sent
    once per licence version and release.
12. **Signer lifecycle.** The signer is opened only after the options have been checked, so wrong options never
    reach Vault. It is closed at the end of the run, which revokes a login token.
13. **The key-ID rule now allows an optional `:VERSION`.** Older key IDs (`lic-2026-01`) stay valid. In Spring config
    the ID must be written in brackets (`"[lic-2026:1]"`); the export prints it that way, and a test proves it binds.
14. **Vault safety checks.** Keys that allow export or plaintext backup are refused, and every signature from Vault is
    checked against its public key. Neither was asked for; both are cheap protection.
15. **Logins.** The command supports `VAULT_TOKEN` and userpass. For login MFA or single sign-on (OIDC), you log in
    with the Vault CLI and pass `VAULT_TOKEN`; the command does not drive those flows.
16. **Signing with a raw key is gone from production code.** The env-variable signer is test-only, and `main` signs
    with Vault.
17. **No new Maven dependency for the Vault tests.** They use Testcontainers' `GenericContainer` with the Vault image,
    not the Vault module. The policy is a test resource, and a test checks the install guide shows it verbatim.
18. **A small lenient JSON reader for Vault replies (`VaultJson`).** Vault's replies contain `null`, which the strict
    licence reader refuses on purpose.

## Questions

1. **Intake stopped repeats every 24 hours.** Is that right for a critical event? *Recommendation:* yes. Renewal takes
   days, and every 4 hours would train people to ignore it.
2. **Product not in the licence** (your Q1: intake stops) has no notification. Add one ("Licence does not cover this
   product", technical, critical, IT Admin)? *Recommendation:* yes, in the next licensing task. It is small.
3. **A slot deactivated while one of its conditions is active** (grace, over limit) keeps that condition open until
   the next renewal. *Recommendation:* accept for now, and clear its conditions when the app's slot-activation screen
   is built.
4. **Key IDs in config need brackets.** Should apps instead point to the shipped file, with
   `platform.licensing.trusted-keys-file: trusted-keys.json`? *Recommendation:* yes. IT never types key IDs, and the
   file is exactly what the export writes.
5. **Login MFA and single sign-on in the command itself.** *Recommendation:* no. Use the Vault CLI and `VAULT_TOKEN`
   for those; the command stays simple.
6. **Next tasks.** *Recommendation:* `platform-setup` (with `tenant-id` required, Q2), then each app's switch-over
   task: register the licensing events, supply `ActivatedSlots`, set the link and the release date.
