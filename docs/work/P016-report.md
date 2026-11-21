# P016 report — approval links in notifications

Built on `feature/approval-links` (from `main`), merged into `main` and pushed.

## Result

- `mvn verify`: **608 tests, 0 failures**. That is the 579 existing tests, all still passing, plus 29 new ones.
  `mvn install` ran with the same result.
- New tests by module:
  - platform-notifications-testkit: 21 (`ApprovalsTest`, in memory)
  - platform-notifications: 5 (`ApprovalLinksEndToEndTest`: Postgres, GreenMail, local Teams/Slack webhook server)
  - platform-notifications-spring: 3 (`ApprovalWiringTest`)
- **One existing assertion changed:** `PostgresStoresTest.migrationCreatesOnlyNotifTables` lists the tables the
  migration makes; it now includes `notif_approval_request`. No other existing test was changed.
- **Commits:** the first commit on the branch is your `docs/design/setup.md` fix, exactly as it was in the working
  tree (section 2a rewritten, section 4 restored).
- **Merge:**
  - `main` was fast-forwarded from `a367aa7` to `430183e` (two commits: the setup.md fix, then the feature) and
    pushed.
  - `feature/approval-links` was deleted locally. It never existed on origin (nothing asked for it to be pushed
    before the merge), so there was nothing to delete there; checked with `git ls-remote`.
  - This task file and this report were then committed on `main` and pushed.
- **Not committed:** `docs/design/case-fields.md`, an untracked file that appeared in the working tree during the
    session. It is not part of this task, so I left it as it is.

## What was built

### platform-notifications, core (`com.documentplatform.notifications`)

- **`Message`** gains `actionRequest` (the request a message asks a decision on) and `actions` (one-click links:
  `ActionLink`, label + URL). The 4-argument constructor still works. `ActionLink.toString()` shows the label only.
  `text()` adds `Approve: <url>` and `Decline: <url>` lines when there are links; otherwise it is unchanged.
- **`ActionLinks`** (interface): makes the links for one audience at send time. **`ActionAudience`**: a person
  (`Person(userId)`, e-mail) or a channel (`Channel(destination)`, Teams/Slack).
- **`Notifier`**:
  - `publishActionRequest(event, requestId, approverRole)`: goes at once to the event type's destinations. E-mail
    goes to the holders of the approver role, **one e-mail per person with their own links** and their own
    `Message-ID`; the requester gets none. Teams and Slack get one set of links for the channel.
  - `publishToPerson(event, userId)`: an e-mail to one person (the requester's outcome); address from the new
    `RecipientResolver.person(tenant, userId)` (default: nobody, so `NO_RECIPIENTS`).
  - `Builder.actionLinks(...)`.
  - **Links are made at send time and never stored.** The outbox stores the request id (`action_request`) and, for a
    message to one person, their user id (`recipient_user`), never an address.
- **`OutboxEntry`** gains `recipientUser` (old constructor kept); `OutboxEntry.toPerson(...)`.
- **New failure codes:** `ACTION_REQUEST_CLOSED` (decided or expired before the message went out; nothing sent),
  `ACTION_LINKS_NOT_AVAILABLE`, `ACTION_KEY_NOT_FOUND`, `ACTION_KEY_INVALID` (all permanent). A secret store that is
  down gives `SECRET_UNAVAILABLE` (retried).
- **Layouts:** e-mail plain text with the two link lines; Teams `Action.OpenUrl` buttons Approve, Decline, Open in the
  app; Slack link buttons. Nothing calls back into Teams or Slack.

### platform-notifications, approvals (`com.documentplatform.notifications.approval`)

- **`ActionTokens`**: `base64url(payload).base64url(HMAC-SHA256)`, about 100 characters. Payload: version, action,
  person-or-channel, request id, expiry, 16 bytes of SHA-256 of the recipient's user id (or destination id). The
  signature also covers the tenant id. No secret, name, address or document data. Constant-time comparison. Key (at
  least 32 characters) read through `SecretResolver` on every use, never kept.
- **`ApprovalLinks`** (implements `ActionLinks`): `<app address><page path>/<token>`; no links for the requester; no
  message once the request is decided or expired.
- **`Approvals`** (the app's API): `create`, `open` (GET, decides nothing, not audited), `decide(token, person,
  reason)` (POST), `decide(requestId, action, person, reason)` (the app's own screen), `find`, `purgeExpiredBefore`.
- **`ApprovalResult`**: `Ready`, `Decided`, `AlreadyDecided` (with `ApprovalRequest.outcomeText(zone)` = "Already
  approved by Cy Lee at 2026-11-20 10:05 Z"), `Refused` (`ApprovalRefusal` with a fixed text).
- **`ApprovalType`**: id, the app's three event types (waiting, approved, declined), approver role, `validFor`
  (default 24 hours, 5 minutes to 30 days).
- **App interfaces:** `ApproverCheck` (asked at every open and decision; `ApproverCheck.from(recipientResolver)` as a
  default), `ApprovalAudit` (every decision attempt; `Entry.toString()` leaves the reason out).
- **`ApprovalStore`** with **`jdbc.PostgresApprovalStore`**: table `notif_approval_request` added to `V1` (not yet
  released). First decision wins with one `UPDATE … WHERE decision IS NULL AND expires_at > now`; check constraints
  also refuse a decision by the requester and a decline without a reason. `NotificationSchema.TABLES` includes it, so
  the startup check and rights check cover it.

### platform-notifications-testkit

- `InMemoryApprovalStore` (same first-decision-wins rule).
- `InMemoryRecipientResolver`: `person(...)`, `know(...)` and `revoke(...)`.

### platform-notifications-spring

- `platform.notifications.approvals.link-key-secret` switches approvals on; `approvals.link-path` (default
  `/approvals/link`). Both checked at startup by key; a pasted value is never repeated.
- Beans (each backs off for the app's own): `PostgresApprovalStore`, `ActionTokens`, `ApprovalLinks` (wired into the
  `Notifier`), `Approvals`. The app supplies `ApprovalType` beans and an `ApprovalAudit` (startup names each one that
  is missing); `ApproverCheck` is optional.

### Docs

- `docs/design/notifications.md`: owner answers to P015 (C1–C4); new section 6 "Approval links" with how it works and
  the P016 decisions.
- `docs/install/notifications.md`: section 8 (switching approval links on, key, replacing it, codes); table and grant
  list; settings rows.
- CLAUDE.md: the module table.

## How an app uses it (minimal example)

```java
// 1. Catalogue: three event types of the app's own (names are the app's).
catalogue.register(EventType.builder("approval.print-waiting", "Print request waiting", EventKind.BUSINESS)
                .recipients(PRINT_APPROVER).time("expiresAt", "Expires").build())
         .register(EventType.builder("approval.print-approved", "Print request approved", EventKind.BUSINESS).build())
         .register(EventType.builder("approval.print-declined", "Print request declined", EventKind.BUSINESS).build());

// 2. Beans (Spring; with platform.notifications.approvals.link-key-secret=notifications/approval-link-key).
@Bean ApprovalType printRequest() {
    return ApprovalType.of("print", PRINT_WAITING, PRINT_APPROVED, PRINT_DECLINED, PRINT_APPROVER); // 24 h
}
@Bean ApprovalAudit approvalAudit(AuditLog log) { return entry -> log.write(entry); }
// The RecipientResolver also implements person(tenant, userId), so the requester can be told the outcome.

// 3. Ask.
approvals.create(new NewApprovalRequest("print", tenant, entity, requester.userId(),
        RecordIdentifier.of("PR-1001"), AppLink.of("/print-requests/PR-1001")));

// 4. The page: GET /approvals/link/{token} (after sign-in) shows, POST decides.
@GetMapping("/approvals/link/{token}")
String show(@PathVariable String token, Model model) {
    ApprovalResult r = approvals.open(token, signedIn());          // decides nothing
    model.addAttribute("result", r);                               // Ready → confirm button (+ reason to decline)
    return "approval";                                             // AlreadyDecided → outcomeText; Refused → text()
}
@PostMapping("/approvals/link/{token}")                            // with the app's CSRF protection
String confirm(@PathVariable String token, @RequestParam(required = false) String reason, Model model) {
    model.addAttribute("result", approvals.decide(token, signedIn(), reason));
    return "approval";
}
private SignedInPerson signedIn() {                                // from the app's login
    return new SignedInPerson(currentTenant(), currentUserId(), currentDisplayName());
}
```

## Tests asked for → where

| Asked for | Test |
|---|---|
| Link opened without POST decides nothing | `openingALinkDecidesNothing` (opened 3 times, both links: nothing stored, nothing audited, requester not told); `approveFromTheEmailAndTheRequesterIsTold` (Postgres) |
| Wrong person refused | `aLinkWorksOnlyForThePersonItWasSentTo` (another approver and a non-approver) |
| Role removed refused | `aPersonWhoNoLongerHoldsTheApproverRoleIsRefused` (link and app screen); `anApproverCheckThatFailsRefuses` |
| Own request refused | `nobodyDecidesOnTheirOwnRequest` (no e-mail links for the requester; channel link and app screen refused); database check in `theTableRefusesWhatTheRulesForbid` |
| Expired refused | `linksExpireWithTheRequest` (one second before / at 24 h), `eachRequestTypeHasItsOwnExpiry`, `aMessageNotSentBeforeTheRequestClosedIsNotSentAtAll` |
| Decline without reason refused | `declineNeedsAReason` (null, blank, too long, control character; then a reason works and never reaches a message) |
| First decision wins with concurrent decisions | `theFirstDecisionWinsWhenSeveralComeAtOnce` (16 threads, in memory); `theDatabaseLetsExactlyOneOfManyConcurrentDecisionsWin` (24 threads over two "app instances", and 24 straight at the store, Postgres) |
| Later click sees the outcome | `aLaterClickSeesTheOutcomeAndChangesNothing` ("Already approved by Cy Lee at …", also after expiry) |
| Tampered token refused | `aTamperedOrForeignTokenIsRefused` (flipped characters across payload and signature, swapped signature, truncated, extended, malformed, other tenant, other key) |
| No token or secret in logs | `noTokenKeyReasonOrNameInLogsReportsOrOutbox`: all java.util.logging records at every level, results, audit entries and every table, across sends, refusals, a tampered link and a missing key |
| Layouts | `eachApproverGetsTheirOwnLinksAtOnceAndTheRequesterNone`, `theRealMessagesCarryTheTwoLinks` (GreenMail, Teams and Slack payloads) |
| Requester told, audit | `theRequesterIsToldTheOutcome`, `everyDecisionAttemptIsAudited` |

**One fault the tests found.** Angus Mail (the SMTP library) traces the whole SMTP conversation, message text
included, to java.util.logging at FINEST, and recipients' addresses at FINE, even with `mail.debug=false`. With links
in the text, a "log everything" setting would have put tokens in the log. `EmailChannel` now sets those two loggers
back to INFO before each send (decision 10).

## Decisions I had to make

1. **E-mail links are per person; Teams and Slack links are per channel.** A webhook posts to everyone in a channel,
   so "only the recipient it was sent to" cannot be checked there. The channel's links name the destination, and the
   page still checks the signed-in person's approver role and own request. See question 1.
2. **Who gets the e-mail is the approver role**, not the event type's recipient roles, since only role holders could
   decide. The admin's on/off switch and destinations for the event type still apply.
3. **Request and outcome messages go at once**, whatever frequency the admin set: a summary cannot carry links that
   end with each request.
4. **The outcome goes to the requester by e-mail only** (the event type's first e-mail destination, else the tenant's).
   It carries the event name, record identifier, time and link; never the decider's name or the reason.
5. **Each link carries its action**; the page confirms that action. To choose the other, use the other link or the
   app's screen (`decide(requestId, …)`).
6. **HMAC-SHA256 with one key**, read on every use. Replacing the key ends the links already sent; no key ring.
7. **One approver's temporary e-mail failure re-sends to all approvers** with the same per-person `Message-ID`s (the
   accepted at-least-once window, A1).
8. **Reason:** trimmed, up to 2000 characters, line breaks and tabs allowed, other control characters not. Optional
   when approving. Stored only in `notif_approval_request`.
9. **Audit runs for every `decide` call** (decided, already decided, refused), after the decision is stored and the
   requester's message queued. An audit exception reaches the app; the stored decision stands. Opening is not
   audited.
10. **The SMTP library's trace is kept off** (see the fault above). The JDBC driver's own FINEST trace still shows
    values it writes, such as the reason; that is the app's database logging and I left it alone. Tokens and the key
    are never written, so they are not in it.
11. **The table went into V1** (version stays 1): V1 is not released; the tags v0.2.1 and v0.3.0 predate
    notifications.
12. **Old requests are not purged automatically.** `Approvals.purgeExpiredBefore` is there for the app. See question 3.
13. **An approver check that fails refuses** (`APPROVER_CHECK_UNAVAILABLE`), and a secret store that is down refuses a
    link for now (`KEY_UNAVAILABLE`): both fail closed.

## Questions

1. **Teams and Slack links are not personal** (decision 1): anyone in the channel who holds the approver role can use
   them. Is that acceptable, or should Teams/Slack messages carry only "Open in the app" and leave links to e-mail?
   *Recommendation:* keep it; the role check and the confirming click still apply, and channels are where group
   approvals happen.
2. **Requester told when a request expires undecided?** Not asked, not built. *Recommendation:* yes, as an optional
   third event type on `ApprovalType`, raised by the hourly tick.
3. **Purging old requests** (decision 12): should the Spring module run `purgeExpiredBefore(now − 30 days)` daily?
   *Recommendation:* yes, with the same `retention` setting as the outbox; the app's audit keeps the decisions.
4. **Key rotation without breaking sent links** (decision 6): add an optional "previous key" reference accepted for
   checking only? *Recommendation:* not now; links live 24 hours and the app's screen still works.
