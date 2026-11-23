# platform-notifications — design (locked 2026-11-20)

Source of truth for the functional rules: the owner's Shared capabilities thread (project doc `Shared-Capabilities.md`, section 7). This file is the copy the coding sessions read. Do not change the rules here without the owner.

## 1. What the module is

Shared library code that the document apps include (for example a redaction app and a document vault). Not a server. It knows nothing about any app: each app registers its own event catalogue and supplies recipients. No app names, no app-specific events in this module.

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

Listed so the model covers them. Kind: B = business, T = technical, C = critical. The two lists are examples from two
apps; each app's real catalogue lives in that app.

**A redaction app:** cases waiting for review (summary, B) · case past review target (B) · approval waiting (B) · intake source unreachable (T) · files rejected at intake (summary, T) · repeated processing failures (T) · backlog growing / over time target (T) · delivery to the document vault failing (T, C) · delivery recovered (T) · database / AI model / OCR / key service down (T, C) · disk almost full (T) · recovered (T) · page allowance 80% / 100% (B) · licence expiring 30/7/1 days (B) · grace period (B) · licence expired (B, C) · redaction policy changed (B) · emergency admin used (B).

**A document vault:** print/download request waiting (B, to Business Admins) · request approved / denied (B, to the requester) · source system unreachable or failing (T) · documents rejected (summary, T) · database / storage / key service down (T, C) · recovered (T) · licence expiring (B) · storage or seats near limit (B) · licence expired (B, C) · sensitivity level lowered (B, **off by default**).

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
- **B4. Flyway: in the app's schema step, never at app startup.** The answer first said: a separate Flyway run with
  its own history table `notif_schema_history`. The code follows a later choice: the module names **no history table
  of its own**. `NotificationSchema` gives the location `db/platform/notifications` (or the files in order through
  `scripts()` / `script(name)`), and the app's own schema step runs it with whatever tool and history table the app
  uses. This works because the startup check reads the version from `notif_schema_version`, which the SQL files keep
  up to date, never from a history table (P005 decision 3). The install guide (`docs/install/notifications.md`
  section 4) says the same. The later modules (licensing, identity, fields, integration and the others) do name their
  own history table (`lic_schema_history`, `idn_schema_history`, …); notifications is the one that does not.
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

### Decisions made in P015 (source docs/work/P015-report.md, written here in P040)

1. **`mail.oauth.credential-type` is required in Microsoft 365 mode.** The type (`CERTIFICATE` or `SECRET`) is never
   guessed from the secret's contents, so a wrong item in the secret store shows up as a clear code. A missing type
   stops start-up, naming the key.
2. **Certificate format in the secret store:** one PEM text with the certificate and an unencrypted PKCS#8 private
   key, in either order (what `openssl req -x509 -nodes` writes). RSA only, because Entra certificates for this flow
   are RSA. Anything else is `MAIL_OAUTH_CREDENTIAL_INVALID`.
3. **The client assertion carries `x5t`**, the SHA-1 thumbprint of the certificate, as Entra documents for client
   assertions (`ClientAssertion`).
4. **SMTP AUTH disabled and "not allowed" are told apart by Microsoft's reply.** "SmtpClientAuthentication is
   disabled", or no XOAUTH2 offered, is `MAIL_SMTP_AUTH_DISABLED`; every other refused XOAUTH2 login, and
   `SendAsDenied`, is `MAIL_OAUTH_SEND_AS_NOT_ALLOWED` (`EmailChannel`). This is the only place in the module that
   reads a reply's text, and the text is not kept.
5. **Four codes beyond the four asked for:** `MAIL_OAUTH_APP_UNKNOWN`, `MAIL_OAUTH_CREDENTIAL_INVALID`,
   `MAIL_OAUTH_TOKEN_REFUSED` and `MAIL_OAUTH_UNEXPECTED_REPLY`, each pointing IT to a different step.
6. **Only `MAIL_OAUTH_TOKEN_UNREACHABLE` is retried**, with the existing temporary codes. A proxy page in place of a
   token is permanent.
7. **A refused token is dropped**, so the next send fetches a new one; a permission granted after the alert then works
   without a restart.
8. **`mail.security=NONE` is refused at start-up with `mail.auth=OAUTH2_MICROSOFT`.** The core types still allow it,
   because the tests use a plain local mail server.
9. **The token endpoint uses the mail timeouts:** `mail.connect-timeout` to connect, and connect plus read timeout for
   each token request (`MicrosoftTokens`). There are no separate token settings.
10. **Health shows no client ID and no mailbox**, only the mode, token state, code and times, following the "no
    addresses or logins in health" rule.
11. **A certificate is checked for expiry only**, not for "not yet valid", before Microsoft is asked.

### Owner answers to the P015 questions (2026-11-21)

- **C1. Row-level security gets its own design task** before any hosting work starts (see `docs/design/setup.md`
  section 2a).
- **C2. Certificate rotation: replace the secret's content** once the new certificate is uploaded to Entra; the next
  token fetch picks it up. No second "next" certificate reference.
- **C3. `Add-MailboxPermission` is documented now**; the Exchange RBAC for Applications option is added when a customer
  asks for it.
- **C4. No Graph `sendMail` for now.** SMTP with XOAUTH2 stays.

## 6. Approval links (agreed design, owner 2026-11-21)

- **Approvers act from the e-mail, Teams or Slack message through one-click links** (Approve / Decline), not
  in-chat buttons: Teams and Slack callbacks cannot reach on-premises installs, and incoming webhooks do not support
  them.
- **A link opens the app's small approval page for that one request.** The module provides the shared parts; each app
  builds its page and its request types.
- **Safety:**
  - Opening a link never decides anything: the page needs one more click, a POST (e-mail scanners open links).
  - A link works only for the recipient it was sent to, and only while they still hold the approver role (re-checked
    at decision time through the app).
  - Nobody approves their own request.
  - Links expire with the request: default 24 hours, per request type. A request type's time
    (`ApprovalType.validFor`) must be between 5 minutes and 30 days; anything else is refused when the type is made.
  - Decline requires a reason.
  - Messages still follow the content rule.
- **Group approvals:** the first decision wins. Later clicks see "Already approved/declined by <name> at <time>" and
  change nothing. Of two decisions at the same moment exactly one wins, enforced by the database. The requester is
  notified of the outcome. The app audits every decision.
- **Built (P016):** signed, single-recipient, expiring action tokens (no secrets or document data inside; HMAC with a
  key from the `SecretResolver`); a decision store (Postgres table `notif_approval_request` in the same schema run,
  and in memory in the testkit) with first-decision-wins; an API for the app (create a request and its links, check a
  link for the signed-in person, decide, outcome message to the requester); Teams, Slack and e-mail layouts with the
  two links.

### How it works (P016)

- **`approval.Approvals`** is the app's API: `create` saves the request and publishes the "waiting" message;
  `open(token, person)` is for the page's GET and decides nothing; `decide(token, person, reason)` is for the POST;
  `decide(requestId, action, person, reason)` is for the app's own list screen.
- **Checks, in order:** token signature and tenant → sent to this person (e-mail links) → request exists → not the
  person's own → still holds the approver role (`ApproverCheck`, asked of the app every time) → already decided?
  (shown, nothing changes) → not expired → a reason, to decline. Refusals have fixed codes and texts
  (`ApprovalRefusal`).
- **Tokens (`ActionTokens`):** `base64url(payload).base64url(HMAC-SHA256)`, about 100 characters, the last segment of
  the link path. The payload is a version, the action, person-or-channel, the request id, the expiry and 16 bytes of a
  SHA-256 hash of the recipient's user id (or the channel's destination id). The signature also covers the tenant id.
  The key (at least 32 characters) is read through the `SecretResolver` on every use and never kept.
- **Links are made at send time, never stored.** The outbox row names the request (`action_request`); the sender asks
  `ActionLinks` (`approval.ApprovalLinks`) for each recipient's links. A request decided or expired before the message
  went out is not sent (`ACTION_REQUEST_CLOSED`).
- **Delivery codes for approval messages** (`FailureCode`, all permanent except the secret store):
  - `ACTION_REQUEST_CLOSED`: the request was decided or expired before the message went out; nothing is sent.
  - `ACTION_LINKS_NOT_AVAILABLE`: the app has approval requests, but no maker of approval links is installed (links
    are not switched on).
  - `ACTION_KEY_NOT_FOUND`: the link key is not in the secret store.
  - `ACTION_KEY_INVALID`: the link key is shorter than 32 characters.
  - `SECRET_UNAVAILABLE`: the secret store could not be reached while reading the key; retried like any other
    temporary failure.

  Their causes and fixes are in `docs/errors/platform-error-codes.md`; the install guide (section 8) says what IT
  does for each.
- **First decision wins in the database:** one `UPDATE … WHERE decision IS NULL AND expires_at > now`; check
  constraints refuse a decision by the requester and a decline without a reason.
- **The requester's outcome** is the app's "approved" or "declined" event type, sent to that one person
  (`Notifier.publishToPerson`, address from `RecipientResolver.person`). It carries the event name, the record's
  identifier, the time and the link; never the decider's name or the reason.
- **Spring:** `platform.notifications.approvals.link-key-secret` switches it on; `approvals.link-path` (default
  `/approvals/link`) is the app's page. The app supplies `ApprovalType` beans and an `ApprovalAudit`; an
  `ApproverCheck` is optional (default: the recipient resolver's role holders).

### Decisions made in P016

1. **E-mail links are per person; Teams and Slack links are per channel.** A webhook posts to everyone in the channel,
   so its links name the destination, and the page checks the signed-in person's approver role (and own request) as
   for e-mail. E-mail goes to each approver separately, each with their own links and `Message-ID`.
2. **The approver role decides who gets the e-mail**, not the request event type's recipient roles: only role holders
   could decide anyway. The event type's on/off switch and destinations still apply.
3. **Request and outcome messages go at once**, whatever frequency the admin chose for their event types: a summary
   cannot carry links that end with each request, and the outcome is for one person.
4. **The outcome goes by e-mail only**, to the first e-mail destination of the outcome event type, or else the
   tenant's e-mail destination. Teams and Slack channels are not one person.
5. **Each link carries its action.** The page confirms that action; to choose the other, the approver uses the other
   link (or the app's own screen).
6. **HMAC-SHA256, one key.** Replacing the key ends the links already sent (at most 24 hours by default); the approver
   can still decide in the app. No key ring.
7. **A temporary failure for one approver's e-mail sends the message again to all of them** under the same per-person
   `Message-ID`s: the accepted at-least-once window (A1).
8. **The reason is free text, kept only in `notif_approval_request`** (trimmed, up to 2000 characters, no control
   characters but line breaks and tabs), for the app's page and audit. It is never put in a message or a log, and
   `toString()` leaves it out.
9. **Audit:** `ApprovalAudit.record` is called for every `decide` call (decided, already decided, refused), after the
   decision is stored and the requester's message is queued. Opening a link is not audited.
10. **The SMTP library's own trace is kept off.** Angus Mail logs addresses at FINE and the whole message (now with link
    tokens) at FINEST to java.util.logging, whatever `mail.debug` says. `EmailChannel` sets those two loggers back to
    INFO before each send. The JDBC driver's FINEST trace (the app's database logging) still shows stored values such
    as the reason; tokens are never stored, so never in it.
11. **The table was added to V1**, which has not been released (the tagged releases predate notifications), as in
    P005 (and P011 for the licensing tables). The version stays 1.
12. **Old requests are deleted by the app** (`Approvals.purgeExpiredBefore`); the module does not schedule it.

### Owner answers to the P016 questions (2026-11-21)

- **Q1. Teams and Slack keep their links.** They are not personal (anyone in the channel may click), but the approver
  role check, the own-request check and the confirming click on the app's page still apply.
- **Q2. Yes: the requester is told when a request expires with nobody deciding.** An optional "expired" event type on
  `ApprovalType`, raised by the tick, sent to the requester.
- **Q3. Yes: the Spring module purges old approval requests daily**: those that expired more than the outbox retention
  ago (`platform.notifications.retention`, default 30 days). This replaces P016 decision 12 for apps that use the
  Spring module.
- **Q4. No key ring for now.** One link key; replacing it ends the links already sent (P016 decision 6 stands).

### Added in P017 (owner answers Q2 and Q3)

- **`ApprovalType.whenExpired(eventType)`** names the app's event type for "expired, nobody decided" (for example
  "Print request expired"); without it the requester is not told, as before. The event type must be in the catalogue
  and not continuous, checked at startup like the other three.
- **`Approvals.tellExpired(now)`** claims the requests of those types that expired undecided and were not yet told,
  and sends each requester one e-mail (`Notifier.publishToPerson`, like the approved and declined outcomes: event
  name, record identifier, the expiry time, the link; no names). Each request is told **once**: the store marks it
  (`notif_approval_request.expiry_told_at`, added to V1, still unreleased) in one `UPDATE … FOR UPDATE SKIP LOCKED`,
  so several app instances never tell twice. A request past its expiry cannot be decided any more, so a told request
  stays undecided (check constraint).
- **The Spring tick** (`NotificationWorkers`, every `tick-interval`) calls `tellExpired` when approval links are on,
  and **once a day** (at the first tick after start, then every 24 hours) `purgeExpiredBefore(now − retention)`. The
  purge runs first, so a request that expired longer ago than the retention (after a long outage) is deleted, not
  announced late. A failure is logged once with its neutral reason, like the other workers.
- An app without the Spring module calls `tellExpired` from its own scheduler and purges as before.

## 7. Time zone per entity (P020, requirement 20 of `integration-standards.md`)

- Entities can be separate companies in different places. The tenant settings keep the tenant's zone; the app may
  supply `EntityZones` (tenant, entity → the entity's own zone, if any), set with `Notifier.Builder.entityZones` or as a
  Spring bean.
- An event (or summary bucket, which is per entity) that belongs to an entity with its own zone uses that zone for
  the daily summary time (`Schedule.nextDue`), the summary period and every time shown in its message. Events without
  an entity, and entities without their own zone, keep the tenant's zone.
- A failing `EntityZones` never stops a notification: the tenant's zone is used and a warning with the entity id is
  logged.
- An app on platform-identity passes `idn_entity.time_zone` (`EntityTimeZones.zone(uuid)` → `ownZone()`), not the
  effective zone, so an entity without its own zone follows the same zone as tenant-level events.

### The customer's zone comes from identity (owner answer to P020 question 2, built in P021)

- Identity is the one source of the customer's time zone (`idn_customer.time_zone`). The app may supply
  `CustomerZones` (tenant → the customer's zone in identity, if any), set with `Notifier.Builder.customerZones` or as a
  Spring bean. `platform-notifications-spring` makes one itself when the app has platform-identity's
  `EntityTimeZones` as a bean (`EntityTimeZones.customerZone(tenantId)`), unless the app defines its own.
- When identity has a zone, it replaces the tenant zone of the notification settings (`notif_tenant.zone_id`) for
  every event and summary of the tenant, with or without an entity. The tenant zone of the settings is used only when
  identity has none, or when the lookup fails (a warning naming the tenant is logged).
- An entity's own zone (`EntityZones`) still wins over the customer's. Order: entity's own → customer's (identity) →
  notification settings.
