package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.FieldSpec;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.TenantId;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The app's API for approvals through one-click links.
 *
 * <ol>
 *   <li>{@link #create}: saves the request and sends "approval waiting" with Approve and Decline links to the
 *       holders of the approver role (e-mail: each their own links; Teams and Slack: one set for the channel).</li>
 *   <li>The approver clicks a link; the app's page (a GET, after sign-in) calls {@link #open} with the token from
 *       the path and the signed-in person. <b>Opening never decides anything</b>: the page shows the request and a
 *       confirming button (and a reason field, to decline).</li>
 *   <li>The confirming click (a POST, with the app's CSRF protection) calls {@link #decide}. All checks run again;
 *       the first decision wins; the requester is told the outcome; the app's {@link ApprovalAudit} records it.</li>
 * </ol>
 *
 * <p>Checks, in order: the token's signature (and tenant) → it was sent to this person (e-mail links) → the request
 * exists → it is not the person's own request → the person still holds the approver role (asked of the app) →
 * already decided? (shown, nothing changes) → not expired → for a decline, a reason.
 *
 * <p>Logs ids and codes only, never a token, a key, a name or a reason. Thread-safe.
 */
public final class Approvals {

    private static final System.Logger LOG = System.getLogger(Approvals.class.getName());

    /** The time field a request event may declare to show when the request expires. */
    public static final String EXPIRES_FIELD = "expiresAt";

    private final Map<String, ApprovalType> types;
    private final EventCatalogue catalogue;
    private final ApprovalStore store;
    private final ActionTokens tokens;
    private final Notifier notifier;
    private final ApproverCheck approvers;
    private final ApprovalAudit audit;
    private final Clock clock;

    private Approvals(Builder b) {
        this.catalogue = Objects.requireNonNull(b.catalogue, "catalogue");
        this.store = Objects.requireNonNull(b.store, "store");
        this.tokens = Objects.requireNonNull(b.tokens, "tokens");
        this.notifier = Objects.requireNonNull(b.notifier, "notifier");
        this.approvers = Objects.requireNonNull(b.approvers, "approver check");
        this.audit = Objects.requireNonNull(b.audit, "audit");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        Map<String, ApprovalType> byId = new LinkedHashMap<>();
        for (ApprovalType t : b.types) {
            if (byId.put(t.id(), t) != null) {
                throw new IllegalArgumentException("Request type registered twice: " + t.id());
            }
            for (EventTypeId event : List.of(t.requestEvent(), t.approvedEvent(), t.declinedEvent())) {
                EventType type = catalogue.find(event).orElseThrow(() -> new IllegalArgumentException(
                        "Request type " + t.id() + " names an event type the catalogue does not have: " + event));
                if (type.continuous()) {
                    throw new IllegalArgumentException("Request type " + t.id() + " names a continuous event type: "
                            + event);
                }
            }
        }
        this.types = Map.copyOf(byId);
    }

    /**
     * @param types the app's request types
     */
    public static Builder builder(Collection<ApprovalType> types, EventCatalogue catalogue, ApprovalStore store,
                                  ActionTokens tokens, Notifier notifier) {
        return new Builder(types, catalogue, store, tokens, notifier);
    }

    public Optional<ApprovalType> type(String id) {
        return Optional.ofNullable(types.get(id));
    }

    /**
     * Saves a new request, open for its type's {@link ApprovalType#validFor()} duration, and queues the message with
     * the links. Returns at once, like every publish.
     */
    public Created create(NewApprovalRequest r) {
        ApprovalType type = requireType(r.type());
        Instant now = now();
        ApprovalRequest request = new ApprovalRequest(r.tenant(), UUID.randomUUID(), r.entity(), type.id(),
                r.requester(), r.identifier(), r.link(), now, now.plus(type.validFor()).truncatedTo(ChronoUnit.SECONDS),
                null);
        store.create(request);
        NotificationEvent.Builder event = NotificationEvent.builder(type.requestEvent(), r.tenant())
                .entity(r.entity()).occurredAt(now);
        if (r.identifier() != null) {
            event.identifier(r.identifier());
        }
        if (r.link() != null) {
            event.link(r.link());
        }
        if (catalogue.require(type.requestEvent()).field(EXPIRES_FIELD)
                .filter(f -> f.type() == FieldSpec.Type.TIME).isPresent()) {
            event.time(EXPIRES_FIELD, request.expiresAt());
        }
        List<DeliveryReport> reports = notifier.publishActionRequest(event.build(), request.id().toString(),
                type.approverRole());
        LOG.log(Level.DEBUG, "Approval request {0} of tenant {1} created, type {2}", request.id(), r.tenant(),
                type.id());
        return new Created(request, reports);
    }

    /**
     * For the app's page when a link is opened (a GET). Decides nothing and changes nothing; it is not audited.
     *
     * @return {@link ApprovalResult.Ready}, {@link ApprovalResult.AlreadyDecided} or {@link ApprovalResult.Refused}
     */
    public ApprovalResult open(String token, SignedInPerson person) {
        Instant now = now();
        Checked c = check(token, person, now);
        if (c.refusal != null) {
            return new ApprovalResult.Refused(c.refusal);
        }
        if (c.request.decision() != null) {
            return new ApprovalResult.AlreadyDecided(c.request);
        }
        return new ApprovalResult.Ready(c.request, c.action);
    }

    /**
     * The confirming click on the app's page (a POST): the link's action, by the signed-in person. Every check of
     * {@link #open} runs again. Audited whatever the outcome.
     *
     * @param reason required to decline; optional to approve (null or blank for none)
     * @return {@link ApprovalResult.Decided}, {@link ApprovalResult.AlreadyDecided} or {@link ApprovalResult.Refused}
     */
    public ApprovalResult decide(String token, SignedInPerson person, String reason) {
        Instant now = now();
        Checked c = check(token, person, now);
        return finish(c, person, reason, now);
    }

    /**
     * A decision from the app's own screen (no link), for example its list of waiting requests. The same checks
     * apart from the token's: the request must belong to the person's tenant.
     */
    public ApprovalResult decide(UUID requestId, ApprovalAction action, SignedInPerson person, String reason) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(action, "action");
        Instant now = now();
        Checked c = store.find(person.tenant(), requestId)
                .map(request -> checkRequest(request, action, person, now))
                .orElse(new Checked(null, action, ApprovalRefusal.REQUEST_UNKNOWN));
        return finish(c, person, reason, now);
    }

    /** The request, for the app's screens. */
    public Optional<ApprovalRequest> find(TenantId tenant, UUID id) {
        return store.find(tenant, id);
    }

    /**
     * Deletes requests whose expiry passed before {@code before}, decided or not (the app's audit keeps the
     * decisions). For the app's housekeeping, for example daily with {@code now - 30 days}.
     */
    public int purgeExpiredBefore(Instant before) {
        return store.purgeExpiredBefore(before);
    }

    private ApprovalResult finish(Checked c, SignedInPerson person, String reason, Instant now) {
        String given = reason == null || reason.isBlank() ? null : reason.strip();
        ApprovalRefusal refusal = c.refusal;
        if (refusal == null && c.request.decision() == null) {
            if (c.action == ApprovalAction.DECLINE && given == null) {
                refusal = ApprovalRefusal.REASON_REQUIRED;
            } else if (given != null && !Ids.reasonOk(given)) {
                refusal = ApprovalRefusal.REASON_INVALID;
            }
        }
        if (refusal != null) {
            return refused(c, person, refusal, given, now);
        }
        if (c.request.decision() != null) {
            return lost(c.request, c.action, person, given, now);
        }
        Decision decision = new Decision(c.action, person.userId(), person.displayName(), now, given);
        ApprovalStore.Stored stored = store.decide(person.tenant(), c.request.id(), decision, now);
        return switch (stored.kind()) {
            case DECIDED -> won(stored.request(), person, given, now);
            case ALREADY_DECIDED -> lost(stored.request(), c.action, person, given, now);
            case EXPIRED -> refused(new Checked(stored.request(), c.action, null), person,
                    ApprovalRefusal.REQUEST_EXPIRED, given, now);
            case UNKNOWN -> refused(new Checked(null, c.action, null), person, ApprovalRefusal.REQUEST_UNKNOWN, given,
                    now);
        };
    }

    private ApprovalResult won(ApprovalRequest request, SignedInPerson person, String reason, Instant now) {
        ApprovalType type = types.get(request.type());
        Decision d = request.decision();
        LOG.log(Level.INFO, "Approval request {0} of tenant {1} {2} by user {3}", request.id(), request.tenant(),
                d.action().pastTense(), d.byUserId());
        if (type != null) {
            NotificationEvent.Builder outcome = NotificationEvent.builder(
                            d.action() == ApprovalAction.APPROVE ? type.approvedEvent() : type.declinedEvent(),
                            request.tenant())
                    .entity(request.entity()).occurredAt(d.at());
            if (request.identifier() != null) {
                outcome.identifier(request.identifier());
            }
            if (request.link() != null) {
                outcome.link(request.link());
            }
            try {
                notifier.publishToPerson(outcome.build(), request.requester());
            } catch (RuntimeException notQueued) {
                // The decision stands; the requester sees it in the app.
                LOG.log(Level.WARNING, "Approval request {0} of tenant {1}: the requester''s message could not be "
                        + "queued ({2})", request.id(), request.tenant(), notQueued.getClass().getSimpleName());
            }
        } else {
            LOG.log(Level.WARNING, "Approval request {0} of tenant {1} has a request type no longer registered; the "
                    + "requester is not told", request.id(), request.tenant());
        }
        audit.record(new ApprovalAudit.Entry(request.tenant(), request.id(), request.entity(), request.type(),
                person.userId(), d.action(), ApprovalAudit.Outcome.DECIDED, null, now, reason));
        return new ApprovalResult.Decided(request);
    }

    private ApprovalResult lost(ApprovalRequest request, ApprovalAction action, SignedInPerson person, String reason,
                                Instant now) {
        LOG.log(Level.DEBUG, "Approval request {0} of tenant {1}: user {2} was too late", request.id(),
                request.tenant(), person.userId());
        audit.record(new ApprovalAudit.Entry(request.tenant(), request.id(), request.entity(), request.type(),
                person.userId(), action, ApprovalAudit.Outcome.ALREADY_DECIDED, null, now, reason));
        return new ApprovalResult.AlreadyDecided(request);
    }

    private ApprovalResult refused(Checked c, SignedInPerson person, ApprovalRefusal refusal, String reason,
                                   Instant now) {
        ApprovalRequest r = c.request;
        LOG.log(Level.INFO, "Approval decision refused for user {0} of tenant {1}, request {2}: {3}", person.userId(),
                person.tenant(), r == null ? "-" : r.id(), refusal.name());
        audit.record(new ApprovalAudit.Entry(person.tenant(), r == null ? null : r.id(), r == null ? null : r.entity(),
                r == null ? null : r.type(), person.userId(), c.action, ApprovalAudit.Outcome.REFUSED, refusal, now,
                reason));
        return new ApprovalResult.Refused(refusal);
    }

    /** The link's checks, then the request's. */
    private Checked check(String token, SignedInPerson person, Instant now) {
        Objects.requireNonNull(person, "person");
        Optional<ActionTokens.Claims> claims;
        try {
            claims = tokens.read(person.tenant(), token);
        } catch (ApprovalKeyException noKey) {
            LOG.log(Level.WARNING, "Approval links cannot be checked: {0}", noKey.code().name());
            return new Checked(null, null, ApprovalRefusal.KEY_UNAVAILABLE);
        }
        if (claims.isEmpty()) {
            return new Checked(null, null, ApprovalRefusal.LINK_INVALID);
        }
        ActionTokens.Claims cl = claims.get();
        if (!cl.isFor(person.userId())) {
            return new Checked(null, cl.action(), ApprovalRefusal.LINK_NOT_FOR_YOU);
        }
        Optional<ApprovalRequest> found = store.find(person.tenant(), cl.request());
        if (found.isEmpty()) {
            return new Checked(null, cl.action(), ApprovalRefusal.REQUEST_UNKNOWN);
        }
        Checked c = checkRequest(found.get(), cl.action(), person, now);
        if (c.refusal == null && c.request.decision() == null && cl.isExpired(now)) {
            return new Checked(c.request, c.action, ApprovalRefusal.REQUEST_EXPIRED);
        }
        return c;
    }

    private Checked checkRequest(ApprovalRequest request, ApprovalAction action, SignedInPerson person, Instant now) {
        if (request.requester().equals(person.userId())) {
            return new Checked(request, action, ApprovalRefusal.OWN_REQUEST);
        }
        ApprovalType type = types.get(request.type());
        if (type == null) {
            return new Checked(request, action, ApprovalRefusal.REQUEST_UNKNOWN);
        }
        boolean approver;
        try {
            approver = approvers.holdsRole(request.tenant(), request.entity(), type.approverRole(), person.userId());
        } catch (RuntimeException cannotCheck) {
            LOG.log(Level.WARNING, "Approver role of user {0} of tenant {1} could not be checked ({2})",
                    person.userId(), request.tenant(), cannotCheck.getClass().getSimpleName());
            return new Checked(request, action, ApprovalRefusal.APPROVER_CHECK_UNAVAILABLE);
        }
        if (!approver) {
            return new Checked(request, action, ApprovalRefusal.NOT_AN_APPROVER);
        }
        if (request.isExpired(now)) {
            return new Checked(request, action, ApprovalRefusal.REQUEST_EXPIRED);
        }
        return new Checked(request, action, null);
    }

    private ApprovalType requireType(String id) {
        ApprovalType type = types.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Request type not registered: " + id);
        }
        return type;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** A checked link or request: the request (if found), the action, and the refusal, if any. */
    private record Checked(ApprovalRequest request, ApprovalAction action, ApprovalRefusal refusal) {
    }

    /** The saved request and what happened to its message for each destination. */
    public record Created(ApprovalRequest request, List<DeliveryReport> reports) {
        public Created {
            Objects.requireNonNull(request, "request");
            reports = List.copyOf(reports);
        }
    }

    /** Wires the approvals. The approver check and the audit are required. */
    public static final class Builder {
        private final List<ApprovalType> types;
        private final EventCatalogue catalogue;
        private final ApprovalStore store;
        private final ActionTokens tokens;
        private final Notifier notifier;
        private ApproverCheck approvers;
        private ApprovalAudit audit;
        private Clock clock = Clock.systemUTC();

        private Builder(Collection<ApprovalType> types, EventCatalogue catalogue, ApprovalStore store,
                        ActionTokens tokens, Notifier notifier) {
            this.types = List.copyOf(types);
            this.catalogue = catalogue;
            this.store = store;
            this.tokens = tokens;
            this.notifier = notifier;
        }

        /** Asked at every open and decision whether the person still holds the approver role. */
        public Builder approvers(ApproverCheck approvers) {
            this.approvers = approvers;
            return this;
        }

        /** The app's audit of every decision attempt. */
        public Builder audit(ApprovalAudit audit) {
            this.audit = audit;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Approvals build() {
            return new Approvals(this);
        }
    }
}
