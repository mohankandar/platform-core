package com.documentplatform.licensing.spring.notifications;

import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceState;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.NoticeLog;
import com.documentplatform.licensing.usage.UsageAssessment;
import com.documentplatform.licensing.usage.UsageRecorder;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.ConditionKey;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.TenantId;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.documentplatform.licensing.spring.notifications.LicensingEvents.*;

/**
 * Turns the licence state and usage into licensing notifications. {@link #check()} is run regularly (hourly by
 * default, {@link LicensingNoticeWorker}); each run publishes what is due and nothing twice:
 *
 * <ul>
 *   <li><b>One-off events</b> (licence expiring, usage near / at limit, version not covered) are claimed in the
 *       {@link NoticeLog} before they are published, under an ID that names what they are (for example "expiring,
 *       band 30, licence LIC-… v1, slot ENT-…"). A restart or a second app instance finds the claim and publishes
 *       nothing. A publish that fails gives the claim back, so the next run tries again.</li>
 *   <li><b>Continuous conditions</b> (grace, intake stopped, clock behind, over limit, product not covered) are
 *       reported with
 *       {@link Notifier#conditionFailing} on every run while they hold and {@link Notifier#conditionCleared} once they
 *       stop. The notifier keeps their state in its own store, so they alert once, repeat, and send Recovered — also
 *       across restarts. When grace ends, the grace condition is ended without Recovered
 *       ({@link Notifier#conditionSuperseded}) and "intake stopped" takes over.</li>
 * </ul>
 *
 * <p>Business events are published per activated entity slot ({@link ActivatedSlots}); the clock, version and
 * product-not-covered events are technical and per tenant. Only event types registered in the app's catalogue are published. Licence dates are UTC
 * days, so times are given as the exact moment (for example "Licence ends: 2027-12-01 00:00 Z", in the tenant's zone).
 *
 * <p>Logs IDs, codes and counts only.
 */
public final class LicensingNotices {

    private static final System.Logger LOG = System.getLogger(LicensingNotices.class.getName());
    /** Notice claims are kept this long; longer than any period a notice is about (a year's licence bands). */
    static final Duration KEEP_CLAIMS = Duration.ofDays(400);

    /** What one check did. */
    public record Result(int published, int failing, int cleared, String refusal) {
        static Result refused(String code) {
            return new Result(0, 0, 0, code);
        }
    }

    /**
     * @param link           where messages point in the app (a path such as the licence page), or null
     * @param releaseVersion this app version, shown as the identifier of "version not covered", or null
     * @param releaseDate    this app version's release date, or null to skip the "version not covered" check
     */
    public record Options(AppLink link, RecordIdentifier releaseVersion, LocalDate releaseDate) {
        public static final Options NONE = new Options(null, null, null);
    }

    private final LicenceService licence;
    private final Notifier notifier;
    private final NotificationSettings settings;
    private final NoticeLog log;
    private final ActivatedSlots slots;
    private final Clock clock;
    private final Options options;
    private final Set<EventTypeId> registered;

    public LicensingNotices(LicenceService licence, Notifier notifier, NotificationSettings settings, NoticeLog log,
                            ActivatedSlots slots, Clock clock, Options options) {
        this.licence = Objects.requireNonNull(licence, "licence");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.log = Objects.requireNonNull(log, "log");
        this.slots = Objects.requireNonNull(slots, "slots");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.options = Objects.requireNonNull(options, "options");
        this.registered = registeredIn(settings.catalogue());
    }

    /** The licensing event types the catalogue has. Nothing is published for the others. */
    public static Set<EventTypeId> registeredIn(EventCatalogue catalogue) {
        return ALL.stream().filter(id -> catalogue.find(id).isPresent()).collect(Collectors.toUnmodifiableSet());
    }

    public Set<EventTypeId> registered() {
        return registered;
    }

    /** One run: reads the state and usage, publishes what is due. */
    public Result check() {
        if (registered.isEmpty()) {
            return new Result(0, 0, 0, null);
        }
        LicenceStatus status;
        try {
            status = licence.status();
        } catch (LicenceException e) {
            // The health indicator reports a refused licence; there is nothing to notify about a licence we cannot read.
            LOG.log(Level.WARNING, "Licensing notifications skipped: stored licence refused ({0})", e.code());
            return Result.refused(e.code().name());
        }
        Run run = new Run(status, clock.instant());
        run.licence();
        run.clock();
        run.usage();
        run.release();
        run.product();
        log.purgeBefore(run.now.minus(KEEP_CLAIMS));
        if (run.published + run.failing + run.cleared > 0) {
            LOG.log(Level.INFO, "Licensing notifications for tenant {0}: {1} published, {2} conditions failing,"
                    + " {3} cleared", run.tenant, run.published, run.failing, run.cleared);
        }
        return new Result(run.published, run.failing, run.cleared, null);
    }

    /** One check's state. */
    private final class Run {
        final LicenceStatus status;
        final Licence l;
        final TenantId tenant;
        final Instant now;
        final Map<String, EntityId> active;
        int published;
        int failing;
        int cleared;

        Run(LicenceStatus status, Instant now) {
            this.status = status;
            this.l = status.licence();
            this.tenant = TenantId.of(l.tenantId());
            this.now = now;
            this.active = activeSlots();
        }

        /** Activated slots that are in the licence, in slot order. */
        private Map<String, EntityId> activeSlots() {
            Map<String, String> given = slots.activated(l.tenantId());
            Map<String, EntityId> out = new TreeMap<>();
            if (given != null) {
                given.forEach((slot, entity) -> {
                    if (l.slot(slot).isPresent() && entity != null) {
                        out.put(slot, EntityId.of(entity));
                    }
                });
            }
            return out;
        }

        void licence() {
            LicenceState state = status.state();
            for (Map.Entry<String, EntityId> slot : active.entrySet()) {
                EntityId entity = slot.getValue();
                if (on(LICENCE_EXPIRING) && state == LicenceState.EXPIRING) {
                    int band = status.warningThreshold().getAsInt();
                    once("expiring|" + tenant + "|" + l.licenceId() + "|v" + l.version() + "|" + slot.getKey() + "|"
                            + band, () -> event(LICENCE_EXPIRING, entity).identifier(id(l.licenceId()))
                            .count(DAYS_LEFT, status.daysUntilExpiry().getAsLong())
                            .time(LICENCE_ENDS, endOf(l.expiresOn())).build());
                }
                if (on(LICENCE_IN_GRACE)) {
                    ConditionKey key = new ConditionKey(LICENCE_IN_GRACE, tenant, entity, null);
                    if (state == LicenceState.EXPIRED_IN_GRACE) {
                        failing(event(LICENCE_IN_GRACE, entity)
                                .count(GRACE_DAYS_LEFT, status.graceDaysLeft().getAsLong())
                                .time(LICENCE_ENDS, endOf(l.expiresOn()))
                                .time(GRACE_ENDS, endOf(status.graceEndsOn().orElseThrow())).build());
                    } else if (state == LicenceState.EXPIRED_GRACE_OVER) {
                        notifier.conditionSuperseded(key); // "intake stopped" takes over: no Recovered for grace
                    } else {
                        clear(key);
                    }
                }
                if (on(INTAKE_STOPPED)) {
                    if (state == LicenceState.EXPIRED_GRACE_OVER) {
                        failing(event(INTAKE_STOPPED, entity)
                                .time(LICENCE_ENDS, endOf(l.expiresOn()))
                                .time(INTAKE_STOPPED_AT, endOf(status.graceEndsOn().orElseThrow())).build());
                    } else {
                        clear(new ConditionKey(INTAKE_STOPPED, tenant, entity, null));
                    }
                }
            }
        }

        void clock() {
            if (!on(CLOCK_BEHIND)) {
                return;
            }
            if (status.clockBehind()) {
                failing(NotificationEvent.builder(CLOCK_BEHIND, tenant)
                        .count(MINUTES_BEHIND, status.clock().behind().toMinutes()).link(options.link()).build());
            } else {
                clear(new ConditionKey(CLOCK_BEHIND, tenant, null, null));
            }
        }

        void usage() {
            if (!on(USAGE_NEAR_LIMIT) && !on(USAGE_AT_LIMIT) && !on(USAGE_OVER_LIMIT)) {
                return;
            }
            UsageRecorder usage = licence.usage();
            YearMonth month = usage.currentMonth();
            for (Map.Entry<String, EntityId> slot : active.entrySet()) {
                EntityId entity = slot.getValue();
                long nearAt = on(USAGE_NEAR_LIMIT)
                        ? settings.threshold(tenant, entity, USAGE_NEAR_LIMIT, NEAR_LIMIT_PERCENT) : 80;
                for (UsageAssessment a : usage.assess(slot.getKey(), month)) {
                    if (!a.hasAllowance()) {
                        continue;
                    }
                    String allowance = a.product() + ":" + a.type().name();
                    String base = tenant + "|" + slot.getKey() + "|" + allowance + "|" + month;
                    boolean atOrOver = a.used() >= a.limit();
                    if (on(USAGE_AT_LIMIT) && atOrOver) {
                        once("usage-at|" + base, () -> usageEvent(USAGE_AT_LIMIT, entity, allowance, a));
                    } else if (on(USAGE_NEAR_LIMIT) && !atOrOver && a.percentUsed() >= nearAt) {
                        once("usage-near|" + base, () -> usageEvent(USAGE_NEAR_LIMIT, entity, allowance, a));
                    }
                    if (on(USAGE_OVER_LIMIT)) {
                        if (a.used() > a.limit()) {
                            failing(usageEvent(USAGE_OVER_LIMIT, entity, allowance, a));
                        } else {
                            clear(new ConditionKey(USAGE_OVER_LIMIT, tenant, entity, id(allowance)));
                        }
                    }
                }
            }
        }

        void release() {
            LocalDate released = options.releaseDate();
            if (!on(RELEASE_NOT_COVERED) || released == null || l.coversRelease(released)) {
                return;
            }
            once("release|" + tenant + "|" + l.licenceId() + "|v" + l.version() + "|" + released,
                    () -> NotificationEvent.builder(RELEASE_NOT_COVERED, tenant)
                            .identifier(options.releaseVersion() != null ? options.releaseVersion() : id(l.licenceId()))
                            .time(UPDATES_ENDED, endOf(l.updatesUntil())).link(options.link()).build());
        }

        /** The licence does not list this app's product: technical, per tenant, the product code as identifier. */
        void product() {
            if (!on(PRODUCT_NOT_COVERED) || licence.productCode().isEmpty()) {
                return;
            }
            RecordIdentifier product = id(licence.productCode().get());
            if (!licence.productLicensed(status)) {
                failing(NotificationEvent.builder(PRODUCT_NOT_COVERED, tenant).identifier(product)
                        .link(options.link()).build());
            } else {
                clear(new ConditionKey(PRODUCT_NOT_COVERED, tenant, null, product));
            }
        }

        private NotificationEvent usageEvent(EventTypeId type, EntityId entity, String allowance, UsageAssessment a) {
            return event(type, entity).identifier(id(allowance)).count(USED, a.used()).count(ALLOWED, a.limit())
                    .count(PERCENT_USED, a.percentUsed()).build();
        }

        private NotificationEvent.Builder event(EventTypeId type, EntityId entity) {
            return NotificationEvent.builder(type, tenant).entity(entity).link(options.link());
        }

        private void once(String noticeId, Supplier<NotificationEvent> event) {
            if (!log.claim(noticeId, now)) {
                return;
            }
            try {
                notifier.publish(event.get());
                published++;
            } catch (RuntimeException e) {
                log.release(noticeId); // the next check publishes it
                throw e;
            }
        }

        private void failing(NotificationEvent event) {
            notifier.conditionFailing(event);
            failing++;
        }

        private void clear(ConditionKey key) {
            if (notifier.isConditionActive(key)) {
                notifier.conditionCleared(key);
                cleared++;
            }
        }

        private boolean on(EventTypeId type) {
            return registered.contains(type);
        }
    }

    private static RecordIdentifier id(String value) {
        return RecordIdentifier.of(value);
    }

    /** The moment a UTC licence day ends (the start of the next day, UTC). */
    static Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
