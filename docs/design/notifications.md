# platform-notifications — design (locked 2026-11-20)

Source of truth for the functional rules: the owner's Shared capabilities thread (project doc `Shared-Capabilities.md`, section 7). This file is the copy the coding sessions read. Do not change the rules here without the owner.

## 1. What the module is

Shared library code that LexaVault and Vero include. Not a server. It knows nothing about either app: each app registers its own event catalogue and supplies recipients. No app names, no app-specific events in this module.

## 2. Functional rules (owner decisions)

1. **Event catalogue.** Each app registers a list of eligible event types. The customer switches on the ones they want. Events are added or removed in later releases.
2. **Recipients are roles**, not named people (e.g. "Reviewers of entity X"). Email goes to the people holding the role; Teams/Slack go to the channel webhook the admin assigned.
3. **Who configures.** The customer's IT Admin connects email, Teams and Slack once (tenant level). Technical events (system health, intake, delivery) are chosen and routed by the IT Admin. Business events (work, approvals, licence/usage, security) are chosen per entity by the Business Admin.
4. **Channels:** email (customer's mail server), Microsoft Teams and Slack by **incoming webhook link**. No bot apps.
5. **Content rule.** A message carries only: the event's name, the record's primary identifier (e.g. contract number), counts/times where relevant, and a link into the app. **Never** document content, a redacted value, a case field marked "sensitive", passwords, keys, tokens or webhook URLs. Enforced by the API shape and by tests.
6. **No personal opt-out in V1.** Only admins choose.
7. **Frequency per event:** Immediately · Every hour · Every 4 hours · Once a day (at an admin-chosen time). Anything except Immediately is a **summary** of everything since the last one.
8. **Critical events** (something is down: delivery failing, database / AI model / OCR / key service / storage down, licence expired) go **Immediately to the IT Admin** and may only be set to Immediately or Every hour.
9. **Defaults:** critical Immediately; work events every 4 hours; licence and usage once a day. Each event type carries its default on/off state, frequency, recipient roles and thresholds.
10. **Continuous problems alert once, not per failure:** an alert fires when a condition has lasted past a threshold (default 15 minutes), repeats while it continues (default every 4 hours), and a **Recovered** message follows when it clears.
11. **Thresholds are defaults the admin can adjust**, within the critical-event limit above.
12. **No generic errors:** when a channel fails, the cause is specific ("Mail server refused the login", "Teams webhook address no longer valid"), with a stable code, and nothing secret in the message or log.

## 3. Event catalogues (registered by each app, not built into this module)

Listed so the model covers them. Kind: B = business, T = technical, C = critical.

**LexaVault:** cases waiting for review (summary, B) · case past review target (B) · approval waiting (B) · intake source unreachable (T) · files rejected at intake (summary, T) · repeated processing failures (T) · backlog growing / over time target (T) · delivery to Vero failing (T, C) · delivery recovered (T) · database / AI model / OCR / key service down (T, C) · disk almost full (T) · recovered (T) · page allowance 80% / 100% (B) · licence expiring 30/7/1 days (B) · grace period (B) · licence expired (B, C) · redaction policy changed (B) · emergency admin used (B).

**Vero:** print/download request waiting (B, to Business Admins) · request approved / denied (B, to the requester) · source system unreachable or failing (T) · documents rejected (summary, T) · database / storage / key service down (T, C) · recovered (T) · licence expiring (B) · storage or seats near limit (B) · licence expired (B, C) · sensitivity level lowered (B, **off by default**).

## 4. Technical shape (built in steps)

- **P003 — core logic, no I/O:** event-type model and catalogue registration with validation (critical frequency rule, defaults); settings model (tenant-level channels; per-entity business settings; tenant-level technical settings; thresholds); the publish API (structured fields only, no free text); routing (event → enabled? → which channels → which roles); summary buckets for hourly / 4-hourly / daily; continuous-condition tracker (start, threshold, repeat, recovered); message building that obeys the content rule; a `Clock` and a `tick(now)` entry point so apps schedule it and tests control time; channel and recipient-resolver **interfaces** with in-memory fakes. Full unit tests.
- **P004 — real channels and storage:** SMTP email, Teams and Slack webhook senders with retries and specific failure reasons; an outbox so a crash or restart loses nothing and nothing is sent twice; storage behind interfaces with a Postgres implementation and SQL migration files the apps run in their own schema step; webhook URLs and mail passwords read from the secret store, never logged.
- **P005 — Spring wiring:** module `platform-notifications-spring`: auto-configuration, scheduling of `tick` and of the background sender, configuration properties (mail server and webhook secret references come from the install config file), the startup check of the `notif_` tables, a health indicator per destination. Install guide: `docs/install/notifications.md`.
- **Later, in each app:** the admin settings screens, the app's catalogue registration and the recipient resolver (role → people), when that app switches to platform-core.

## 5. Decisions

### Owner answers to the P003 questions (2026-11-20)

- **O1. Licensing is per entity.** Licence and page-allowance events are published once per entity. There are no
  tenant-level business settings: every business event carries an entity.
- **O2. Kept as built:** critical events are always on; the condition timing limits of D3 stand; one summary per
  entity. Entity separation is a legal requirement: a message never mixes entities.
- **O3. Fakes out of production code.** The in-memory fakes live in the test-support module
  `platform-notifications-testkit` (in the build and the BOM). The main jar does not contain them.
- **O4. Record identifiers may contain single spaces** (for example `LN 12345`), with no leading, trailing or double
  spaces. This replaces the "no spaces" part of D9.

### Decisions made in P003, as confirmed or changed by the answers above

1. **Critical events cannot be switched off.** Their recipient roles always include the IT Admin role, which the app
   names when it registers its catalogue. Confirmed (O2).
2. **The IT Admin role is looked up at tenant level**, even for a per-entity event such as "licence expired".
3. **Condition timing limits:** threshold 0–24 h, at most 1 h for critical events; repeat 15 min–24 h. Confirmed (O2).
4. **Recovered** uses the condition's own event type and settings. It is sent only if an alert went out; a condition
   that clears before its threshold sends nothing.
5. **When the admin chose no destination**, events go to the tenant's e-mail destination. Teams and Slack receive
   only the events assigned to them, without role-based recipients.
6. **One summary per destination, schedule, entity and set of recipient roles.** Up to 10 items per event type, then
   "… and N more", with totals for counts. Confirmed (O2: a message never mixes entities).
7. **Saved settings that break a rule** after a catalogue change fall back to the event type's defaults.
8. **Business events must carry an entity**; technical events may carry one but are set per tenant. Confirmed and
   extended (O1): there are no tenant-level business settings.
9. **Identifier and link shapes.** Identifier: at most 64 characters of `A–Z a–z 0–9 . _ : / # -`, with single spaces
   inside (O4), no `//`. Link: a path only, no query string or fragment; the host comes from the tenant's app
   address. The real protection is that the publish API has no free-text field.
10. **"Immediately" goes to the outbox at once.** Replaced by P004 answer A3: `publish` only saves to the outbox and
    a background sender sends Immediately messages at once. `publish` never waits on a channel.
11. **Own `TenantId` and `EntityId` types**, so the module does not depend on identity-core.
12. **Numeric thresholds** are declared per event type with a default and a range, adjustable by the admin within the
    range, and read by the app with `NotificationSettings.threshold(...)`. The module does not compare them itself.

### Owner answers to the P004 questions (2026-11-20)

- **A1. The at-least-once window is accepted** (P004 decision 3): a rare duplicate is better than a lost alert.
- **A2. OAuth 2.0 for Microsoft 365 / Google mail:** not answered yet; still open.
- **A3. `publish` only saves to the outbox.** A background worker sends Immediately messages at once. `publish` must
  never wait on a channel.
- **A4. Retry defaults accepted** (P004 decision 4), and they are configuration properties.
- **A5. No authenticating proxy for now.** The plain JVM proxy settings are enough.

### Database rule (owner, fixed for both products, 2026-11-20)

- **D1. Database setup is a separate schema step the customer runs.** App startup never creates tables or inserts
  rows. The `notif_` SQL files (`db/platform/notifications`) are run only by the app's schema step; they are
  documented, not executed at startup.
- **D2. At startup the app checks** that the `notif_` tables exist at the expected version (table
  `notif_schema_version`) and that its database user may use them. If not, it stops with a specific message, for
  example "Notifications tables at version 0, expected version 1: run the schema step …". No generic errors.
- **D3. At runtime the app's database user needs only SELECT, INSERT, UPDATE and DELETE** on the `notif_` tables
  (SELECT on `notif_schema_version`), no DDL.

### Decisions made in P004, as confirmed or changed by the answers above

1. **The P003 tests live in the testkit module**, with the fakes they use: the main module's tests cannot depend on
   the testkit (a Maven cycle). The main module has the I/O tests.
2. **Idempotency keys name what the message is**, not when it was made: an event id and destination; a condition's
   start and alert number; a summary bucket and its due time. A due bucket takes no new items, so rebuilding it gives
   the same message. Times are kept in whole milliseconds, so keys survive the database round trip.
3. **At least once, in one window:** the receiver took the message and the process stopped before the outbox recorded
   it. After the lease the message goes again under the same key (e-mail keeps its `Message-ID`). Every other crash
   point sends exactly once. Accepted (A1).
4. **Retry policy:** first retry after 1 minute, doubling up to 30 minutes, given up after 24 hours; a receiver's
   `Retry-After` replaces the backoff for that attempt. 5-minute lease, up to 100 messages per send round, finished
   messages kept 30 days. Accepted (A4); all are `platform.notifications.*` properties since P005.
5. **Which failures are retried:** temporary ones (unreachable, timeout, 4xx mail reply, 429, 5xx, secret store down,
   recipient lookup down, unexpected sender fault). A TLS failure is permanent: a certificate or configuration problem
   does not fix itself.
6. **One refused recipient:** the others still get the message; the refusal is reported and not retried.
7. **Recipients are looked up again on every attempt.** The outbox stores roles, never e-mail addresses.
8. **Mail server and webhook settings come from the install config**, not the database. The database keeps only
   destination ids and their channel. Since P005 one mail server serves every e-mail destination.
9. **https only for webhooks**, no redirects followed, JVM proxy settings used. Plain http only for the module's own
   tests. No authenticating proxy (A5).
10. **Logging through `System.Logger`**, ids, codes and fixed reasons only. Jakarta Mail's debug output is off.
11. **Plain-text e-mail only**; Slack uses plain-text blocks only, so nothing in a message is read as formatting.
12. **A summary whose event types were all removed** from the catalogue in a later release is dropped silently.
13. **`WEBHOOK_REJECTED` reads "… refused the webhook request (not authorised)"**, matching 401/403.

### Owner answers to the P005 questions (2026-11-21)

- **B1. OAuth 2.0 (XOAUTH2) for Microsoft 365 / Google mail:** still open (P004 A2). Until it is decided, the
  install guide points to a relay connector.
- **B2. Health states accepted:** "Not used yet" is a fourth state, and the component status is `DEGRADED`, not
  `DOWN`, when a destination has a problem.
- **B3. Health stays per app instance** for now; cluster-wide health read from the outbox may be revisited if a
  customer's monitoring needs one view.
- **B4. Flyway: separate run, own history table.** Each app runs `db/platform/notifications` as a separate Flyway run
  with its own history table `notif_schema_history`, in its schema step, never at app startup. The platform files
  never share the app's history table (both start at V1). Each app's switch-over task records this.
- **B5. One mail server for all e-mail destinations** is enough for V1. Per-destination servers can be added later
  without breaking the config (`mail.*` stays the default).

### Decisions made in P005, as confirmed or changed by the answers above

1. **The core API changed for answer A3.** Added: `sendDue`, `queueDue`, `batchSize()` and `Builder.whenQueued`.
   `publish` now reports `PENDING` where it used to report `SENT`. `tick` keeps its old meaning (queue, then send),
   so plain-Java callers and the existing tests still work.
2. **V1 was edited instead of adding V2**, for the version table and the outbox `seq` column. V1 was never released.
   Once a release ships, its files are never edited again.
3. **The version comes from `notif_schema_version`**, which the SQL files keep up to date, not from Flyway's history:
   each app uses its own tool and history table (see B4).
4. **Startup also checks the user's rights**, with `has_table_privilege`, so a missing grant is named at startup and
   not at the first send. No sequence rights are needed.
5. **One mail server serves every e-mail destination.** E-mail destination ids live in the tenant settings
   (database), not in the config. Confirmed (B5).
6. **Teams and Slack ids are config map keys**, unique across both channels. Ports default from the security
   setting. Security defaults to STARTTLS.
7. **Extra limits:** the lease must be longer than the longest send (connect + read timeout); otherwise a slow send
   looks like a stopped one and is sent twice. `tick-interval` and `sender.poll-interval` must be 1 s – 5 min.
   `sender.batch-size` must be 1 – 1000.
8. **Validation is the module's own code, not Bean Validation**, to give one specific message per key and never echo
   a possible secret.
9. **The module runs its own daemon threads** and does not use `@EnableScheduling`, so it imposes nothing on the
   app's scheduler. One tick thread and one sender thread per instance.
10. **Several instances need no cluster lock.** Claims use `SKIP LOCKED`, and alerts and summaries raised twice get
    the same key.
11. **Shutdown waits up to 10 s for a send in progress.** A send cut off there is sent again after its lease: the same
    accepted window as A1.
12. **Health is per app instance and in memory**, from the last outcome per destination. Confirmed (B2, B3).
    - A fourth state, **Not used yet**, covers the time before the first send.
    - Temporary failures count as **Down** after 15 minutes without a success.
    - Refusals that concern one message (recipient refused, message rejected, no recipients) count as **Having
      problems**, because the destination itself answers.
    - Configured Teams and Slack destinations are listed from the start. E-mail destinations appear once used.
13. **The component status is `DEGRADED`, not `DOWN`**, when a destination has a problem, so a broken webhook does
    not get the app restarted or taken out of a load balancer. Confirmed (B2).
14. **Missing app beans give a specific startup message.** `SecretResolver` is required only when a channel is
    configured. The table check runs only when the module's Postgres stores are used.
15. **Built against Spring Boot 3.3.2**, the lowest line the products run; the suite also passes on 3.5.16.
    `spring-boot-autoconfigure` is compile scope and `spring-boot-actuator` is optional. The app's own Boot version
    wins.
16. **New dependencies, all Apache-2.0:** `spring-boot-autoconfigure` (compile), `spring-boot-actuator` (optional),
    and `spring-boot-test` and `assertj-core` (test). AssertJ's `byte-buddy` is excluded.

### Added in P011 (licensing notifications)

- **`Notifier.conditionSuperseded(key)`** ends a condition without a Recovered message, for when a more serious
  condition about the same thing takes over (licence grace ends and "intake stopped" starts). Nothing is queued.
- The licensing event types are registered by the app from `platform-licensing-spring` (`LicensingEvents.register`);
  this module still has no event types of its own.

### Added in P015 (Microsoft 365 sign-in)

Owner, 2026-11-21: the first customer's mail is Microsoft 365, the second runs its own Exchange server; both are
supported. Password login and the internal relay stay as they are.

- **`mail.auth`**: `PASSWORD` (default, unchanged) or `OAUTH2_MICROSOFT`. Microsoft 365 uses SMTP `AUTH XOAUTH2` on
  `smtp.office365.com:587` with STARTTLS, as the sender mailbox, with a token from the OAuth 2.0 client-credentials
  flow (Entra app registration, scope `https://outlook.office365.com/.default`, application permission
  `SMTP.SendAsApp`).
- **App credential**: a certificate with its private key (preferred; sent as a signed RS256 client assertion) or a
  client secret, read only through `SecretResolver` from a reference. Never in config.
- **Tokens** (`MicrosoftTokens`): fetched with the JDK `HttpClient`, held until 5 minutes before expiry (or half the
  lifetime if shorter), dropped when the mail server refuses them (a newly granted permission is only in a new token).
  Never logged, never in results, health or exceptions.
- **New failure codes**: `MAIL_OAUTH_TOKEN_UNREACHABLE` (retried), `MAIL_OAUTH_CREDENTIAL_REFUSED`,
  `MAIL_OAUTH_APP_UNKNOWN`, `MAIL_OAUTH_CREDENTIAL_INVALID`, `MAIL_OAUTH_TOKEN_REFUSED`, `MAIL_OAUTH_UNEXPECTED_REPLY`,
  `MAIL_OAUTH_SEND_AS_NOT_ALLOWED`, `MAIL_SMTP_AUTH_DISABLED` (all permanent). The existing mail codes still apply.
- **Health** shows the sign-in mode, and for Microsoft 365 the token state, code, last fetch and expiry time.
