package com.documentplatform.integration.callback;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.RetryPolicy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed callbacks out (requirement 12): an app tells a configured receiver (an integration of kind {@code CALLBACK})
 * that a case is done or failed. {@link #enqueue} puts the callback in the outbox; {@link #deliverDue} (run by the
 * app's or the Spring wiring's scheduler) sends what is due, one signed POST per attempt through the call engine, with
 * the same outbound safety rules as every call.
 *
 * <ul>
 *   <li><b>Retries</b> with back-off ({@link #DEFAULT_RETRY}: 1 minute, doubling up to 6 hours, given up 24 hours after
 *       it was created or resent; changeable); a receiver's Retry-After replaces the back-off (up to the longest
 *       delay).</li>
 *   <li><b>At most once at a time:</b> a sender claims a callback with a lease; it survives restarts.</li>
 *   <li><b>Classified like an API push:</b> SENT and ALREADY_RECEIVED are done; REFUSED (the receiver said no) gives
 *       up at once; every other result is retried until the window ends.</li>
 *   <li><b>GAVE_UP</b> callbacks are listed for the app ({@link #gaveUp}), which can {@link #resend} them (same ID,
 *       a new window).</li>
 * </ul>
 */
public final class Callbacks {

    /** A refusal of {@link #enqueue} or {@link #resend}. */
    public static final class Refused extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {
        private final IntegrationCode code;

        public Refused(IntegrationCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        public IntegrationCode code() {
            return code;
        }

        /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
            return code;
        }
    }

    /** 1 minute, doubling up to 6 hours, given up after 24 hours. */
    public static final RetryPolicy DEFAULT_RETRY =
            new RetryPolicy(Duration.ofMinutes(1), Duration.ofHours(6), Duration.ofHours(24));
    /** The largest body. */
    public static final int MAX_BODY_BYTES = 64 * 1024;
    /** How long a sender holds a callback it claimed. */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);

    private static final System.Logger LOG = System.getLogger(Callbacks.class.getName());

    private final CallbackOutbox outbox;
    private final IntegrationStore integrations;
    private final CallEngine engine;
    private final RetryPolicy retry;
    private final Duration lease;
    private final Clock clock;

    public Callbacks(CallbackOutbox outbox, IntegrationStore integrations, CallEngine engine, Clock clock) {
        this(outbox, integrations, engine, DEFAULT_RETRY, DEFAULT_LEASE, clock);
    }

    public Callbacks(CallbackOutbox outbox, IntegrationStore integrations, CallEngine engine, RetryPolicy retry,
                     Duration lease, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.integrations = Objects.requireNonNull(integrations, "integrations");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lease.compareTo(Duration.ofSeconds(5)) < 0) throw new IllegalArgumentException("A lease is at least 5 s");
    }

    /**
     * Puts a callback in the outbox, due now.
     *
     * @param body one JSON object the app gives (the case's ID and state, for example); never document content
     * @return the callback's ID, sent as {@code Platform-Callback-Id}
     */
    public UUID enqueue(UUID integrationId, String body) {
        StoredIntegration s = integrations.current(integrationId)
                .orElseThrow(() -> new Refused(IntegrationCode.INT_CONFIG_NOT_FOUND));
        IntegrationConfig c = s.config();
        if (c.kind() != Kind.CALLBACK) throw new Refused(IntegrationCode.INT_KIND_NOT_SUPPORTED);
        checkBody(body);
        UUID id = UUID.randomUUID();
        outbox.enqueue(CallbackEntry.pending(id, c.id(), c.entity(), c.product(), body, now()));
        LOG.log(System.Logger.Level.INFO, "Callback {0} for integration {1} queued", id, c.id());
        return id;
    }

    static void checkBody(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new Refused(IntegrationCode.INT_CALLBACK_BODY_INVALID);
        }
        try {
            if (!(Json.parse(body) instanceof Map<?, ?>)) throw new Refused(IntegrationCode.INT_CALLBACK_BODY_INVALID);
        } catch (Json.Invalid e) {
            throw new Refused(IntegrationCode.INT_CALLBACK_BODY_INVALID);
        }
    }

    /** Sends up to {@code max} due callbacks, one attempt each. Returns how many were tried. */
    public int deliverDue(int max) {
        Instant now = now();
        List<CallbackEntry> due = outbox.claimDue(now, now.plus(lease), max);
        for (CallbackEntry e : due) {
            deliver(e);
        }
        return due.size();
    }

    private void deliver(CallbackEntry e) {
        int attempts = e.attempts() + 1;
        Optional<StoredIntegration> s = integrations.current(e.integrationId());
        AttemptResult r = s.isEmpty() ? null : engine.sendCallback(s.get(), e.id(), e.body());
        Instant now = now();
        if (r == null) {
            outbox.markGaveUp(e.id(), attempts, Outcome.NOT_CONFIGURED.name(),
                    IntegrationCode.INT_CONFIG_NOT_FOUND.name(), 0, now);
            return;
        }
        String outcome = r.outcome().name();
        String code = r.code().name();
        if (r.delivered()) {
            outbox.markSent(e.id(), attempts, outcome, code, r.httpStatus(), now);
            return;
        }
        if (r.outcome() == Outcome.REFUSED) {
            outbox.markGaveUp(e.id(), attempts, outcome, code, r.httpStatus(), now);
            LOG.log(System.Logger.Level.WARNING, "Callback {0} refused by the receiver ({1}): given up", e.id(), code);
            return;
        }
        Duration after = r.retryAfter() == null ? null
                : r.retryAfter().compareTo(retry.maxDelay()) > 0 ? retry.maxDelay() : r.retryAfter();
        Optional<Instant> next = retry.next(e.windowFrom(), attempts, after, now);
        if (next.isEmpty()) {
            outbox.markGaveUp(e.id(), attempts, outcome, code, r.httpStatus(), now);
            LOG.log(System.Logger.Level.WARNING, "Callback {0}: retries ran out ({1}): given up", e.id(), code);
        } else {
            outbox.markRetry(e.id(), attempts, outcome, code, r.httpStatus(), next.get());
        }
    }

    public Optional<CallbackEntry> find(UUID id) {
        return outbox.find(id);
    }

    /** The callbacks given up, of an entity in a product, newest first. */
    public List<CallbackEntry> gaveUp(UUID entity, String product, int limit) {
        return outbox.gaveUp(entity, product, limit);
    }

    /** A GAVE_UP callback goes out again (same ID, due now, a new give-up window). */
    public void resend(UUID id) {
        if (!outbox.resend(id, now())) throw new Refused(IntegrationCode.INT_CALLBACK_NOT_FOUND);
        LOG.log(System.Logger.Level.INFO, "Callback {0} resent", id);
    }

    /** Deletes SENT and GAVE_UP callbacks finished longer than {@code keep} ago. */
    public int purge(Duration keep) {
        return outbox.purgeFinishedBefore(now().minus(keep));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
