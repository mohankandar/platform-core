/**
 * Approvals through one-click links in notifications.
 *
 * <p>An approver acts from the e-mail, Teams or Slack message through two links, Approve and Decline. A link opens
 * the app's own small page for that one request; the page shows the request and needs one more click (a POST) to
 * decide, because opening a link must never decide anything (mail scanners open links). This package provides the
 * shared parts; each app builds its page and registers its request types ({@link
 * com.documentplatform.notifications.approval.ApprovalType}).
 *
 * <ul>
 *   <li>{@link com.documentplatform.notifications.approval.ActionTokens}: signed (HMAC-SHA256), single-recipient,
 *       expiring tokens. They carry no secret and no document data: a request id, the action, the expiry and a hash of
 *       the recipient. The key is read through the {@code SecretResolver}.</li>
 *   <li>{@link com.documentplatform.notifications.approval.ApprovalStore}: the requests and their decision, first
 *       decision wins (Postgres: {@code jdbc.PostgresApprovalStore}; in memory in the testkit).</li>
 *   <li>{@link com.documentplatform.notifications.approval.Approvals}: the app's API: create a request (which sends
 *       the message with the links), check a link for the signed-in person, decide, tell the requester.</li>
 *   <li>{@link com.documentplatform.notifications.approval.ApprovalLinks}: makes each recipient's links at send
 *       time, for the {@code Notifier}.</li>
 * </ul>
 */
package com.documentplatform.notifications.approval;
