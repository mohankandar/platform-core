package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.ActionAudience;
import com.documentplatform.notifications.ActionLink;
import com.documentplatform.notifications.ActionLinks;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.TenantId;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes the Approve and Decline links of a request message at send time, for the {@code Notifier}
 * ({@code Notifier.Builder.actionLinks}). Each link is {@code <app address><page path>/<token>}, for example
 * {@code https://app.customer.example/approvals/link/AQEB...}; the app's page at that path reads the token.
 *
 * <p>No links (and no message) once the request is decided or expired; none for the requester, who cannot decide.
 */
public final class ApprovalLinks implements ActionLinks {

    private final ApprovalStore store;
    private final ActionTokens tokens;
    private final AppLink pagePath;

    /**
     * @param pagePath the path of the app's approval page, for example {@code /approvals/link}; the token is added as
     *                 the last path segment
     */
    public ApprovalLinks(ApprovalStore store, ActionTokens tokens, AppLink pagePath) {
        this.store = Objects.requireNonNull(store, "store");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.pagePath = Objects.requireNonNull(pagePath, "pagePath");
    }

    @Override
    public Result linksFor(TenantId tenant, String requestId, ActionAudience audience, URI appBaseUrl, Instant now) {
        UUID id;
        try {
            id = UUID.fromString(requestId);
        } catch (IllegalArgumentException notOurs) {
            return Result.failed(FailureCode.ACTION_REQUEST_CLOSED);
        }
        Optional<ApprovalRequest> found = store.find(tenant, id);
        if (found.isEmpty() || !found.get().isOpen(now)) {
            return Result.failed(FailureCode.ACTION_REQUEST_CLOSED);
        }
        ApprovalRequest request = found.get();
        if (audience instanceof ActionAudience.Person p && p.userId().equals(request.requester())) {
            return Result.none();
        }
        List<ActionLink> links = new ArrayList<>();
        try {
            for (ApprovalAction action : ApprovalAction.values()) {
                String token = tokens.mint(tenant, id, action, audience, request.expiresAt());
                links.add(new ActionLink(action.label(), URI.create(pagePath.resolve(appBaseUrl) + "/" + token)));
            }
        } catch (ApprovalKeyException noKey) {
            return Result.failed(noKey.code());
        }
        return Result.of(links);
    }

    /** The path of the app's approval page. */
    public AppLink pagePath() {
        return pagePath;
    }
}
