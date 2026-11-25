package com.documentplatform.notifications;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static com.documentplatform.notifications.NotificationException.Reason.ENTITY_REQUIRED;
import static com.documentplatform.notifications.NotificationException.Reason.IS_A_CONDITION;
import static com.documentplatform.notifications.NotificationException.Reason.NOT_A_CONDITION;
import static com.documentplatform.notifications.NotificationException.Reason.UNKNOWN_FIELD;

/**
 * The entry point. Apps publish events and report conditions. The app's scheduler calls {@link #tick(Instant)}
 * regularly (every minute is enough) to raise condition alerts, put due summaries in the outbox and clear out old
 * messages, and a background sender calls {@link #sendDue(Instant)} to deliver what is in the outbox. The Spring module
 * {@code platform-notifications-spring} schedules both.
 *
 * <p>Routing, for each event: is the event type switched on for this tenant (technical) or entity (business)? →
 * which connected destinations does it go to? → for e-mail, which people hold its recipient roles (asked of the app's
 * {@link RecipientResolver} at send time)? Immediately events go into the outbox at once; the others wait for their
 * summary.
 *
 * <p>{@link #publish}, {@link #conditionFailing} and {@link #conditionCleared} never wait on a channel: they only save
 * to the outbox and wake the sender ({@link Builder#whenQueued(Runnable)}). Sending happens in
 * {@link #sendDue(Instant)}, which does not hold the notifier's lock, so a slow mail server never holds up a publish.
 *
 * <p>Reliability: every message is saved in the {@link OutboxStore} before it is sent, under an idempotency key that
 * names what the message is. A restart loses nothing (saved messages are sent by the next sender run) and re-creating a
 * message gives the saved one, not a second. Temporary failures are retried with backoff ({@link RetryPolicy});
 * permanent ones stop at once and are reported. Summary buckets and condition state are stored too.
 *
 * <p>Logs ids, codes and fixed reasons only (to the {@code System.Logger} of this class), never message content,
 * addresses or secrets. Thread-safe: routing and housekeeping are serialised per instance, sending runs alongside them.
 * Several instances (one per app instance) may share the stores. The {@link RecipientResolver} and the channels are
 * called from the sending thread and must be thread-safe.
 */
public final class Notifier {

    private static final System.Logger LOG = System.getLogger(Notifier.class.getName());

    private final NotificationSettings settings;
    private final EventCatalogue catalogue;
    private final RecipientResolver recipients;
    private final Map<ChannelKind, Channel> channels;
    private final Clock clock;
    private final OutboxStore outbox;
    private final SummaryBuckets buckets;
    private final ConditionTracker conditions;
    private final RetryPolicy retry;
    private final Duration lease;
    private final Duration keepFinishedFor;
    private final int batchSize;
    private final Runnable whenQueued;
    private final ActionLinks actionLinks;
    private final EntityZones entityZones;
    private final CustomerZones customerZones;

    private Notifier(Builder b) {
        this.settings = Objects.requireNonNull(b.settings, "settings");
        this.catalogue = settings.catalogue();
        this.recipients = Objects.requireNonNull(b.recipients, "recipients");
        this.channels = new EnumMap<>(ChannelKind.class);
        b.channels.forEach(c -> {
            if (this.channels.put(c.kind(), c) != null) {
                throw new IllegalArgumentException("One channel per kind");
            }
        });
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.outbox = Objects.requireNonNull(b.outbox, "outbox store");
        this.buckets = new SummaryBuckets(Objects.requireNonNull(b.summaries, "summary store"));
        this.conditions = new ConditionTracker(Objects.requireNonNull(b.conditions, "condition store"), catalogue);
        this.retry = Objects.requireNonNull(b.retry, "retry policy");
        this.lease = b.lease;
        this.keepFinishedFor = b.keepFinishedFor;
        this.batchSize = b.batchSize;
        this.whenQueued = b.whenQueued;
        this.actionLinks = b.actionLinks;
        this.entityZones = b.entityZones;
        this.customerZones = b.customerZones;
    }

    public static Builder builder(NotificationSettings settings, RecipientResolver recipients) {
        return new Builder(settings, recipients);
    }

    /**
     * Publishes a one-off event and returns at once. The event is saved in the outbox for each destination
     * ({@link DeliveryStatus#PENDING}) or added to its summary ({@link DeliveryStatus#QUEUED_FOR_SUMMARY}). It never
     * waits on a channel.
     */
    public List<DeliveryReport> publish(NotificationEvent event) {
        EventType type = check(event);
        if (type.continuous()) {
            throw new NotificationException(IS_A_CONDITION, "Continuous event types are reported as conditions");
        }
        List<Routed> routed;
        synchronized (this) {
            Instant now = now();
            Instant at = event.occurredAt() == null ? now : event.occurredAt();
            routed = route(new Occurrence(Keys.newEvent(), event, type, MessageKind.EVENT, at, null, 0), now);
        }
        return queued(routed);
    }

    /**
     * Publishes a message that asks for a decision on one request (for example "Approval waiting: LN 12345"), with
     * one-click links made at send time by the {@link ActionLinks} ({@link Builder#actionLinks}). It goes at once,
     * whatever frequency the event type has (a summary cannot carry links that end with each request), to the
     * destinations its settings chose. E-mail goes to the holders of {@code approverRole}, each with their own links;
     * Teams and Slack get one set of links for the channel. Publishing the same request again finds the saved message.
     *
     * @param requestId the request (letters, digits, {@code . _ -}); the links name it
     */
    public List<DeliveryReport> publishActionRequest(NotificationEvent event, String requestId, RoleId approverRole) {
        EventType type = check(event);
        Text.require(requestId, Text.ID, "Request id");
        Objects.requireNonNull(approverRole, "approverRole");
        if (type.continuous()) {
            throw new NotificationException(IS_A_CONDITION, "Continuous event types are reported as conditions");
        }
        List<Routed> reports = new ArrayList<>();
        synchronized (this) {
            Instant now = now();
            Instant at = event.occurredAt() == null ? now : event.occurredAt();
            Occurrence o = new Occurrence(Keys.actionRequest(event.tenant(), requestId), event, type, MessageKind.EVENT,
                    at, null, 0);
            EventSettings s = settings.effective(event.tenant(), event.entity(), event.type());
            Optional<Routed> stop = notRouted(event, s);
            if (stop.isPresent()) {
                return List.of(stop.get().report());
            }
            TenantSettings t = zoned(settings.tenant(event.tenant()).orElseThrow(), event.tenant(), event.entity());
            Message message = MessageBuilder.single(o, t).forRequest(requestId);
            for (DestinationId destination : t.targetsFor(s.destinations())) {
                ChannelKind kind = t.kindOf(destination);
                Set<RoleId> roles = kind == ChannelKind.EMAIL ? Set.of(approverRole) : Set.of();
                reports.add(enqueue(OutboxEntry.pending(Keys.forDestination(o.key(), destination), event.tenant(),
                        destination, kind, event.entity(), roles, Set.of(event.type()), message, now)));
            }
        }
        return queued(reports);
    }

    /**
     * Publishes an event to one person by e-mail (for example the requester, told that their request was approved).
     * It goes at once, if the event type is switched on, to the first e-mail destination its settings chose, or else
     * the tenant's e-mail destination; Teams and Slack are left out, because their channels are not one person. The
     * person's address is asked of {@link RecipientResolver#person} at send time.
     */
    public List<DeliveryReport> publishToPerson(NotificationEvent event, String userId) {
        EventType type = check(event);
        Text.require(userId, Text.ID, "User id");
        if (type.continuous()) {
            throw new NotificationException(IS_A_CONDITION, "Continuous event types are reported as conditions");
        }
        Routed routed;
        synchronized (this) {
            Instant now = now();
            Instant at = event.occurredAt() == null ? now : event.occurredAt();
            EventSettings s = settings.effective(event.tenant(), event.entity(), event.type());
            Optional<Routed> stop = notRouted(event, s);
            if (stop.isPresent()) {
                return List.of(stop.get().report());
            }
            TenantSettings t = zoned(settings.tenant(event.tenant()).orElseThrow(), event.tenant(), event.entity());
            Optional<DestinationId> mail = t.targetsFor(s.destinations()).stream()
                    .filter(d -> t.kindOf(d) == ChannelKind.EMAIL).findFirst()
                    .or(() -> t.targetsFor(Set.of()).stream().findFirst());
            if (mail.isEmpty()) {
                return List.of(new DeliveryReport(event.tenant(), Set.of(event.type()), null, MessageKind.EVENT,
                        DeliveryStatus.NO_DESTINATION, null));
            }
            Occurrence o = new Occurrence(Keys.newEvent(), event, type, MessageKind.EVENT, at, null, 0);
            routed = enqueue(OutboxEntry.toPerson(Keys.forDestination(o.key(), mail.get()), event.tenant(),
                    mail.get(), event.entity(), userId, Set.of(event.type()), MessageBuilder.single(o, t), now));
        }
        return queued(List.of(routed));
    }

    /** DISABLED or NO_DESTINATION, when the event goes nowhere. */
    private Optional<Routed> notRouted(NotificationEvent e, EventSettings s) {
        Set<EventTypeId> types = Set.of(e.type());
        if (!s.enabled()) {
            return Optional.of(Routed.of(new DeliveryReport(e.tenant(), types, null, MessageKind.EVENT,
                    DeliveryStatus.DISABLED, null)));
        }
        Optional<TenantSettings> tenant = settings.tenant(e.tenant());
        if (tenant.map(t -> t.targetsFor(s.destinations())).orElse(List.of()).isEmpty()) {
            return Optional.of(Routed.of(new DeliveryReport(e.tenant(), types, null, MessageKind.EVENT,
                    DeliveryStatus.NO_DESTINATION, null)));
        }
        return Optional.empty();
    }

    /**
     * Reports that a continuous condition is failing (call it on every failure; repeats are counted, not alerted).
     * The event's type, tenant, entity and identifier name the condition. A due alert is queued, never sent here.
     */
    public List<DeliveryReport> conditionFailing(NotificationEvent event) {
        EventType type = check(event);
        if (!type.continuous()) {
            throw new NotificationException(NOT_A_CONDITION, "Only continuous event types are reported as conditions");
        }
        List<Routed> routed;
        synchronized (this) {
            Instant now = now();
            conditions.failing(event, now);
            routed = raiseDueAlerts(now);
        }
        return queued(routed);
    }

    /**
     * Reports that a condition has cleared. Queues Recovered once, and only if the condition had alerted. Never waits
     * on a channel.
     */
    public List<DeliveryReport> conditionCleared(ConditionKey key) {
        List<Routed> routed;
        synchronized (this) {
            Instant now = now();
            Optional<Occurrence> recovered = conditions.recovered(key, now);
            if (recovered.isEmpty()) {
                return List.of();
            }
            routed = route(recovered.get(), now);
            conditions.remove(key);
        }
        return queued(routed);
    }

    /**
     * Ends a condition without a Recovered message, because a more serious condition about the same thing takes over
     * (for example "licence in grace" when grace ends and "intake stopped" starts). Nothing is queued; a second call
     * finds nothing.
     */
    public synchronized void conditionSuperseded(ConditionKey key) {
        conditions.remove(key);
    }

    /**
     * Raises due condition alerts, puts due summaries in the outbox and clears out finished messages older than the
     * retention. Then it sends what is due ({@link #sendDue(Instant)}), including retries and messages a stopped
     * process left behind. Called by the app's scheduler. Returns what happened to the messages it raised and sent.
     */
    public List<DeliveryReport> tick(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MILLIS);
        List<Routed> raised = housekeeping(at);
        Map<String, DeliveryReport> sent = attemptDue(at);
        List<DeliveryReport> reports = new ArrayList<>();
        for (Routed r : raised) {
            DeliveryReport outcome = r.key() == null ? null : sent.remove(r.key());
            reports.add(outcome == null ? r.report() : outcome);
        }
        reports.addAll(sent.values());
        return reports;
    }

    /**
     * The first half of {@link #tick(Instant)}: raises due condition alerts, puts due summaries in the outbox, clears
     * out old messages and wakes the sender. Never sends, so a scheduler can run it on time while a slow channel holds
     * up the sender.
     */
    public List<DeliveryReport> queueDue(Instant now) {
        return queued(housekeeping(now.truncatedTo(ChronoUnit.MILLIS)));
    }

    private synchronized List<Routed> housekeeping(Instant at) {
        List<Routed> raised = new ArrayList<>(raiseDueAlerts(at));
        for (SummaryBuckets.Due due : buckets.due(at)) {
            raised.addAll(queueSummary(due, at));
        }
        outbox.purgeFinishedBefore(at.minus(keepFinishedFor));
        return raised;
    }

    /**
     * Sends up to {@link #batchSize()} due messages from the outbox: new ones, retries whose time has come, and any
     * whose worker stopped (lapsed lease). Does not hold the notifier's lock, so publishing goes on meanwhile. Several
     * senders, also in other app instances, may run at once: each message is claimed by one of them.
     *
     * @return one report per message attempted; as many as the batch size means more may be due
     */
    public List<DeliveryReport> sendDue(Instant now) {
        return List.copyOf(attemptDue(now.truncatedTo(ChronoUnit.MILLIS)).values());
    }

    /** How many messages one {@link #sendDue(Instant)} attempts at most. */
    public int batchSize() {
        return batchSize;
    }

    /** Whether the condition is currently being tracked (started and not cleared). */
    public synchronized boolean isConditionActive(ConditionKey key) {
        return conditions.isActive(key);
    }

    /** Occurrences waiting for a summary. */
    public synchronized int pendingSummaryItems() {
        return buckets.pending();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private EventType check(NotificationEvent event) {
        EventType type = catalogue.require(event.type());
        if (type.kind() == EventKind.BUSINESS && event.entity() == null) {
            throw new NotificationException(ENTITY_REQUIRED, "Business events belong to an entity");
        }
        event.counts().keySet().forEach(name -> declared(type, name, FieldSpec.Type.COUNT));
        event.times().keySet().forEach(name -> declared(type, name, FieldSpec.Type.TIME));
        return type;
    }

    private static void declared(EventType type, String name, FieldSpec.Type kind) {
        if (type.field(name).filter(f -> f.type() == kind).isEmpty()) {
            throw new NotificationException(UNKNOWN_FIELD, "The event type does not declare this field");
        }
    }

    private ConditionTiming timing(NotificationEvent event) {
        return settings.effective(event.tenant(), event.entity(), event.type()).conditionTiming();
    }

    private Map<String, DeliveryReport> attemptDue(Instant now) {
        Map<String, DeliveryReport> reports = new LinkedHashMap<>();
        for (OutboxEntry entry : outbox.claimDue(now, now.plus(lease), batchSize)) {
            reports.put(entry.key(), attempt(entry, now));
        }
        return reports;
    }

    /** Wakes the sender if anything went into the outbox, and hands back the reports. */
    private List<DeliveryReport> queued(List<Routed> routed) {
        if (routed.stream().anyMatch(r -> r.key() != null)) {
            try {
                whenQueued.run();
            } catch (RuntimeException ignored) {
                // The message is saved; the sender's next regular run picks it up.
            }
        }
        return routed.stream().map(Routed::report).toList();
    }

    private List<Routed> raiseDueAlerts(Instant now) {
        List<Routed> reports = new ArrayList<>();
        for (ConditionTracker.Due due : conditions.due(now, this::timing)) {
            reports.addAll(route(due.occurrence(), now));
            conditions.markAlerted(due, now);
        }
        return reports;
    }

    private List<Routed> route(Occurrence o, Instant now) {
        NotificationEvent e = o.event();
        Set<EventTypeId> types = Set.of(e.type());
        EventSettings s = settings.effective(e.tenant(), e.entity(), e.type());
        if (!s.enabled()) {
            return List.of(Routed.of(new DeliveryReport(e.tenant(), types, null, o.kind(), DeliveryStatus.DISABLED,
                    null)));
        }
        Optional<TenantSettings> tenant = settings.tenant(e.tenant());
        List<DestinationId> targets = tenant.map(t -> t.targetsFor(s.destinations())).orElse(List.of());
        if (targets.isEmpty()) {
            return List.of(Routed.of(new DeliveryReport(e.tenant(), types, null, o.kind(),
                    DeliveryStatus.NO_DESTINATION, null)));
        }
        TenantSettings t = zoned(tenant.get(), e.tenant(), e.entity());
        List<Routed> reports = new ArrayList<>();
        for (DestinationId destination : targets) {
            ChannelKind kind = t.kindOf(destination);
            Set<RoleId> roles = kind == ChannelKind.EMAIL ? s.recipientRoles() : Set.of();
            if (s.schedule().isSummary()) {
                buckets.add(e.tenant(), destination, s.schedule(), e.entity(), roles, o, now, t.zone());
                reports.add(Routed.of(new DeliveryReport(e.tenant(), types, destination, o.kind(),
                        DeliveryStatus.QUEUED_FOR_SUMMARY, null)));
            } else {
                reports.add(enqueue(OutboxEntry.pending(Keys.forDestination(o.key(), destination), e.tenant(),
                        destination, kind, e.entity(), roles, types, MessageBuilder.single(o, t), now)));
            }
        }
        return reports;
    }

    /**
     * The tenant's settings with the zone that applies: the entity's own zone, when the event belongs to an entity
     * that has one; else the customer's zone from identity ({@link CustomerZones}), when identity has one; else the
     * tenant zone of the notification settings. A failing lookup falls back to the next one, with a warning.
     */
    private TenantSettings zoned(TenantSettings t, TenantId tenant, EntityId entity) {
        TenantSettings customer = t;
        try {
            customer = customerZones.zoneOf(tenant).map(t::withZone).orElse(t);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Time zone of tenant {0} not available from identity ({1}); the tenant zone of the "
                    + "notification settings is used", tenant.value(), e.getClass().getSimpleName());
        }
        if (entity == null) {
            return customer;
        }
        try {
            return entityZones.zoneOf(tenant, entity).map(t::withZone).orElse(customer);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Time zone of entity {0} of tenant {1} not available ({2}); the customer''s zone is "
                    + "used", entity.value(), tenant.value(), e.getClass().getSimpleName());
            return customer;
        }
    }

    private List<Routed> queueSummary(SummaryBuckets.Due due, Instant now) {
        SummaryBucket bucket = due.bucket();
        Set<EventTypeId> types = new LinkedHashSet<>();
        List<Occurrence> items = new ArrayList<>();
        for (SummaryItem item : due.items()) {
            // A type removed from the catalogue in a later release has nothing left to say.
            catalogue.find(item.event().type()).ifPresent(type -> {
                types.add(type.id());
                items.add(new Occurrence(item.key(), item.event(), type, item.kind(), item.at(), item.since(),
                        item.failures()));
            });
        }
        if (items.isEmpty()) {
            buckets.remove(due);
            return List.of();
        }
        Optional<TenantSettings> tenant = settings.tenant(bucket.tenant());
        if (tenant.isEmpty() || !tenant.get().isConnected(bucket.destination())) {
            buckets.remove(due);
            return List.of(Routed.of(failedReport(bucket.tenant(), types, bucket.destination(), null,
                    MessageKind.SUMMARY, FailureCode.DESTINATION_NOT_CONNECTED, "summary dropped")));
        }
        TenantSettings t = zoned(tenant.get(), bucket.tenant(), bucket.entity());
        OutboxEntry entry = OutboxEntry.pending(Keys.summary(bucket), bucket.tenant(), bucket.destination(),
                t.kindOf(bucket.destination()), bucket.entity(), bucket.roles(), types,
                MessageBuilder.summary(items, t), now);
        // Saved first, then the bucket emptied: stopping in between rebuilds the same summary under the same key.
        Routed saved = enqueue(entry);
        buckets.remove(due);
        return List.of(saved);
    }

    /** Saves a message for the sender. Reports PENDING, or the saved state if the same message was saved before. */
    private Routed enqueue(OutboxEntry entry) {
        if (outbox.enqueue(entry)) {
            return new Routed(entry.key(), new DeliveryReport(entry.tenant(), entry.eventTypes(), entry.destination(),
                    entry.message().kind(), DeliveryStatus.PENDING, null));
        }
        // Already saved before (re-created after a restart): sent, given up, waiting, or in a worker's hands.
        OutboxEntry saved = outbox.find(entry.key()).orElse(entry);
        DeliveryStatus status = switch (saved.state()) {
            case SENT -> DeliveryStatus.SENT;
            case FAILED -> DeliveryStatus.FAILED;
            case PENDING, SENDING -> saved.lastFailure() == null ? DeliveryStatus.PENDING : DeliveryStatus.RETRY_SCHEDULED;
        };
        FailureCode code = status == DeliveryStatus.FAILED || status == DeliveryStatus.RETRY_SCHEDULED
                ? saved.lastFailure() : null;
        return new Routed(status == DeliveryStatus.PENDING ? saved.key() : null, new DeliveryReport(saved.tenant(),
                saved.eventTypes(), saved.destination(), saved.message().kind(), status, code));
    }

    /** One attempt at a claimed message, and its outcome recorded in the outbox. */
    private DeliveryReport attempt(OutboxEntry entry, Instant now) {
        int attempts = entry.attempts() + 1;
        SendResult result = send(entry, now);
        MessageKind kind = entry.message().kind();
        if (result.sent()) {
            outbox.markSent(entry.key(), attempts, now);
            LOG.log(Level.DEBUG, "Notification {0} sent to destination {1} of tenant {2}, attempt {3}",
                    entry.key(), entry.destination(), entry.tenant(), attempts);
            return new DeliveryReport(entry.tenant(), entry.eventTypes(), entry.destination(), kind,
                    DeliveryStatus.SENT, null);
        }
        FailureCode code = result.failure();
        Optional<Instant> next = code.retryable()
                ? retry.next(entry.createdAt(), attempts, result.retryAfter(), now) : Optional.empty();
        if (next.isPresent()) {
            outbox.markRetry(entry.key(), attempts, next.get(), code);
            LOG.log(Level.INFO, "Notification {0} to destination {1} of tenant {2} not delivered: {3} ({4}); "
                            + "attempt {5}, next attempt at {6}", entry.key(), entry.destination(), entry.tenant(),
                    code.describe(entry.channel()), code.name(), attempts, next.get());
            return new DeliveryReport(entry.tenant(), entry.eventTypes(), entry.destination(), kind,
                    DeliveryStatus.RETRY_SCHEDULED, code);
        }
        outbox.markFailed(entry.key(), attempts, code, now);
        return failedReport(entry.tenant(), entry.eventTypes(), entry.destination(), entry.channel(), kind, code,
                "notification " + entry.key()
                        + (code.retryable() ? ", retries ran out after attempt " : ", stopped at attempt ") + attempts);
    }

    /** @param channel the destination's channel, or null when the destination is gone */
    private static DeliveryReport failedReport(TenantId tenant, Set<EventTypeId> types, DestinationId destination,
                                               ChannelKind channel, MessageKind kind, FailureCode code, String what) {
        LOG.log(Level.WARNING, "Notification not delivered to destination {0} of tenant {1}: {2} ({3}); {4}",
                destination, tenant, channel == null ? code.name() : code.describe(channel), code.name(), what);
        return new DeliveryReport(tenant, types, destination, kind, DeliveryStatus.FAILED, code);
    }

    /** Failures that concern one recipient's e-mail, not the mail server or its configuration. */
    private static final Set<FailureCode> ONE_RECIPIENT = Set.of(FailureCode.MAIL_RECIPIENT_REFUSED,
            FailureCode.MAIL_TEMPORARILY_REFUSED, FailureCode.MAIL_MESSAGE_REJECTED, FailureCode.SENDER_FAILED);

    private SendResult send(OutboxEntry entry, Instant now) {
        Optional<TenantSettings> tenant = settings.tenant(entry.tenant());
        if (tenant.isEmpty() || tenant.get().kindOf(entry.destination()) != entry.channel()) {
            return SendResult.failed(FailureCode.DESTINATION_NOT_CONNECTED);
        }
        Channel channel = channels.get(entry.channel());
        if (channel == null) {
            return SendResult.failed(FailureCode.CHANNEL_NOT_AVAILABLE);
        }
        Set<Recipient> people = new LinkedHashSet<>();
        if (entry.channel() == ChannelKind.EMAIL) {
            try {
                if (entry.recipientUser() != null) {
                    recipients.person(entry.tenant(), entry.recipientUser()).ifPresent(people::add);
                }
                // The IT Admin is a tenant-level role: asked without the entity, even for a per-entity event.
                entry.recipientRoles().stream().sorted((a, b) -> a.value().compareTo(b.value()))
                        .forEach(role -> people.addAll(recipients.resolve(entry.tenant(),
                                role.equals(catalogue.itAdminRole()) ? null : entry.entity(), role)));
            } catch (RuntimeException lookupFailed) {
                return SendResult.failed(FailureCode.RECIPIENTS_UNAVAILABLE);
            }
            if (people.isEmpty()) {
                return SendResult.failed(FailureCode.NO_RECIPIENTS);
            }
        }
        String request = entry.message().actionRequest();
        if (request != null) {
            return sendWithLinks(entry, channel, people, request, tenant.get().appBaseUrl(), now);
        }
        if (entry.channel() == ChannelKind.EMAIL && entry.recipientUser() == null) {
            return sendToEach(entry, channel, people, now);
        }
        return deliver(channel, new OutgoingMessage(entry.tenant(), entry.destination(), entry.channel(), people,
                entry.message(), entry.key()));
    }

    /**
     * An e-mail to the holders of the recipient roles: one message per person, so nobody sees who else was told (P055).
     * What became final for one person (sent, or refused for good) is recorded in the outbox under that person's key, in
     * its final state, so a retry of the message for the others leaves them out. A failure that is not about one
     * recipient ends the attempt: the rest would fail alike.
     */
    private SendResult sendToEach(OutboxEntry entry, Channel channel, Set<Recipient> people, Instant now) {
        Map<String, Set<Recipient>> persons = new TreeMap<>();
        people.forEach(r -> persons.computeIfAbsent(r.userId(), id -> new LinkedHashSet<>()).add(r));
        SendResult temporary = null;
        SendResult permanent = null;
        for (Map.Entry<String, Set<Recipient>> person : persons.entrySet()) {
            String key = Keys.forPerson(entry.key(), person.getKey());
            Optional<OutboxEntry> before = outbox.find(key);
            if (before.isPresent()) {
                if (before.get().state() == OutboxState.FAILED && permanent == null) {
                    permanent = SendResult.failed(before.get().lastFailure());
                }
                continue;
            }
            SendResult r = deliver(channel, new OutgoingMessage(entry.tenant(), entry.destination(), entry.channel(),
                    person.getValue(), entry.message(), persons.size() == 1 ? entry.key() : key));
            if (!r.sent() && !ONE_RECIPIENT.contains(r.failure())) {
                return r;
            }
            if (!r.sent() && r.failure().retryable()) {
                temporary = temporary == null ? r : temporary;
                continue;
            }
            if (!r.sent() && permanent == null) {
                permanent = r;
            }
            if (persons.size() > 1) {
                outbox.enqueue(new OutboxEntry(key, entry.tenant(), entry.destination(), entry.channel(),
                        entry.entity(), entry.recipientRoles(), entry.eventTypes(), entry.message(),
                        r.sent() ? OutboxState.SENT : OutboxState.FAILED, entry.attempts() + 1, entry.createdAt(), now,
                        r.failure(), now));
            }
        }
        return temporary != null ? temporary : permanent != null ? permanent : SendResult.ok();
    }

    /**
     * A message asking for a decision: e-mail goes to each person on their own with their own links (and not to the
     * requester); a channel gets one set of links. A temporary failure for one person sends the whole message again
     * later, under the same per-person keys (e-mail {@code Message-ID}s), so the others may get it twice: the accepted
     * at-least-once window.
     */
    private SendResult sendWithLinks(OutboxEntry entry, Channel channel, Set<Recipient> people, String request,
                                     URI appBaseUrl, Instant now) {
        if (actionLinks == null) {
            return SendResult.failed(FailureCode.ACTION_LINKS_NOT_AVAILABLE);
        }
        if (entry.channel() != ChannelKind.EMAIL) {
            ActionLinks.Result links = links(entry, request, new ActionAudience.Channel(entry.destination()),
                    appBaseUrl, now);
            if (links.failed()) {
                return SendResult.failed(links.failure());
            }
            return deliver(channel, new OutgoingMessage(entry.tenant(), entry.destination(), entry.channel(), Set.of(),
                    entry.message().withActions(links.links()), entry.key()));
        }
        SendResult temporary = null;
        SendResult permanent = null;
        int sent = 0;
        for (Recipient person : people.stream().sorted((a, b) -> a.userId().compareTo(b.userId())).toList()) {
            ActionLinks.Result links = links(entry, request, new ActionAudience.Person(person.userId()), appBaseUrl,
                    now);
            if (links.failed()) {
                return SendResult.failed(links.failure()); // closed request or no key: nobody gets it
            }
            if (links.links().isEmpty()) {
                continue; // for example the requester
            }
            SendResult r = deliver(channel, new OutgoingMessage(entry.tenant(), entry.destination(), entry.channel(),
                    Set.of(person), entry.message().withActions(links.links()),
                    Keys.forPerson(entry.key(), person.userId())));
            if (r.sent()) {
                sent++;
            } else if (r.failure().retryable()) {
                temporary = temporary == null ? r : temporary;
            } else {
                permanent = permanent == null ? r : permanent;
            }
        }
        if (temporary != null) {
            return temporary;
        }
        if (permanent != null) {
            return permanent;
        }
        return sent == 0 ? SendResult.failed(FailureCode.NO_RECIPIENTS) : SendResult.ok();
    }

    private ActionLinks.Result links(OutboxEntry entry, String request, ActionAudience audience, URI base,
                                     Instant now) {
        try {
            return actionLinks.linksFor(entry.tenant(), request, audience, base, now);
        } catch (RuntimeException fault) {
            return ActionLinks.Result.failed(FailureCode.SENDER_FAILED);
        }
    }

    private static SendResult deliver(Channel channel, OutgoingMessage message) {
        try {
            return channel.send(message);
        } catch (RuntimeException senderFault) {
            // The exception text may hold a server reply or an address: it is not kept.
            return SendResult.failed(FailureCode.SENDER_FAILED);
        }
    }

    /** A report, and the outbox key when a message was saved and waits for the sender. */
    private record Routed(String key, DeliveryReport report) {
        static Routed of(DeliveryReport report) {
            return new Routed(null, report);
        }
    }

    /** Wires a notifier. Settings, recipients and the three stores are required. */
    public static final class Builder {
        private final NotificationSettings settings;
        private final RecipientResolver recipients;
        private final List<Channel> channels = new ArrayList<>();
        private Clock clock = Clock.systemUTC();
        private OutboxStore outbox;
        private SummaryStore summaries;
        private ConditionStore conditions;
        private RetryPolicy retry = RetryPolicy.DEFAULT;
        private Duration lease = Duration.ofMinutes(5);
        private Duration keepFinishedFor = Duration.ofDays(30);
        private int batchSize = 100;
        private Runnable whenQueued = () -> { };
        private ActionLinks actionLinks;
        private EntityZones entityZones = EntityZones.NONE;
        private CustomerZones customerZones = CustomerZones.NONE;

        private Builder(NotificationSettings settings, RecipientResolver recipients) {
            this.settings = settings;
            this.recipients = recipients;
        }

        public Builder channels(Collection<? extends Channel> channels) {
            this.channels.addAll(channels);
            return this;
        }

        public Builder channel(Channel channel) {
            this.channels.add(Objects.requireNonNull(channel, "channel"));
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder outbox(OutboxStore outbox) {
            this.outbox = outbox;
            return this;
        }

        public Builder summaries(SummaryStore summaries) {
            this.summaries = summaries;
            return this;
        }

        public Builder conditions(ConditionStore conditions) {
            this.conditions = conditions;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retry) {
            this.retry = retry;
            return this;
        }

        /**
         * How long a worker holds a message it is sending (default 5 minutes). Must be longer than a channel's
         * timeouts: a lease that runs out means the worker stopped, and the message is sent again.
         */
        public Builder lease(Duration lease) {
            if (lease.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("The lease must be at least a second");
            }
            this.lease = lease;
            return this;
        }

        /** How long sent and failed messages stay in the outbox (default 30 days). */
        public Builder keepFinishedFor(Duration keepFinishedFor) {
            if (keepFinishedFor.isNegative()) {
                throw new IllegalArgumentException("Retention cannot be negative");
            }
            this.keepFinishedFor = keepFinishedFor;
            return this;
        }

        /** Messages sent per {@link Notifier#sendDue(Instant)} (default 100). */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("Batch size must be positive");
            }
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Called after a message was saved in the outbox, to wake the background sender so Immediately messages go
         * out at once. Runs on the publishing thread: it must only signal, never send.
         */
        public Builder whenQueued(Runnable wake) {
            this.whenQueued = Objects.requireNonNull(wake, "wake");
            return this;
        }

        /**
         * Makes the one-click links of messages that ask for a decision ({@link Notifier#publishActionRequest}).
         * Without it such messages fail with {@link FailureCode#ACTION_LINKS_NOT_AVAILABLE}.
         */
        public Builder actionLinks(ActionLinks links) {
            this.actionLinks = Objects.requireNonNull(links, "links");
            return this;
        }

        /**
         * The entities' own time zones: an event of an entity with its own zone gets that zone for its daily summary
         * time, its summary period and the times in its messages. Without it, the tenant's zone everywhere.
         */
        public Builder entityZones(EntityZones zones) {
            this.entityZones = Objects.requireNonNull(zones, "zones");
            return this;
        }

        /**
         * The customer's time zone from identity, the one source of it: when it gives a zone, that zone replaces the
         * tenant zone of the notification settings for every event and summary of the tenant (an entity's own zone
         * still wins). Without it, or when it gives none, the tenant zone of the notification settings.
         */
        public Builder customerZones(CustomerZones zones) {
            this.customerZones = Objects.requireNonNull(zones, "zones");
            return this;
        }

        public Notifier build() {
            return new Notifier(this);
        }
    }
}
