package com.documentplatform.licensing.spring.notifications;

import com.documentplatform.notifications.ConditionTiming;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * The licensing event types, for the app to register in its {@link EventCatalogue}:
 *
 * <pre>
 * EventCatalogue.Builder catalogue = EventCatalogue.builder(IT_ADMIN);
 * LicensingEvents.register(catalogue, IT_ADMIN, BUSINESS_ADMIN);   // the app's own role IDs
 * ... the app's own event types ...
 * </pre>
 *
 * Business events go to the Business Admin of each activated entity slot (licensing is per entity); critical ones also
 * to the IT Admin. Technical events go to the IT Admin. The defaults below can be changed by the admins within the
 * notification rules. Continuous conditions (grace, intake stopped, clock behind, over limit) alert once, repeat, and
 * send Recovered when they clear.
 *
 * <p>Messages carry the event, the entity, counts, times and the configured link only (the content rule): the licence
 * ID or the allowance ("PRODUCT_A:PAGES_PER_MONTH") as the identifier, never keys, signatures or prices.
 */
public final class LicensingEvents {

    /** Daily summaries go out at this time in the tenant's time zone, by default. */
    public static final LocalTime DAILY_AT = LocalTime.of(8, 0);

    public static final EventTypeId LICENCE_EXPIRING = EventTypeId.of("licensing.licence-expiring");
    public static final EventTypeId LICENCE_IN_GRACE = EventTypeId.of("licensing.licence-in-grace");
    public static final EventTypeId INTAKE_STOPPED = EventTypeId.of("licensing.intake-stopped");
    public static final EventTypeId CLOCK_BEHIND = EventTypeId.of("licensing.clock-behind");
    public static final EventTypeId USAGE_NEAR_LIMIT = EventTypeId.of("licensing.usage-near-limit");
    public static final EventTypeId USAGE_AT_LIMIT = EventTypeId.of("licensing.usage-at-limit");
    public static final EventTypeId USAGE_OVER_LIMIT = EventTypeId.of("licensing.usage-over-limit");
    public static final EventTypeId RELEASE_NOT_COVERED = EventTypeId.of("licensing.release-not-covered");

    public static final List<EventTypeId> ALL = List.of(LICENCE_EXPIRING, LICENCE_IN_GRACE, INTAKE_STOPPED,
            CLOCK_BEHIND, USAGE_NEAR_LIMIT, USAGE_AT_LIMIT, USAGE_OVER_LIMIT, RELEASE_NOT_COVERED);

    /** Field names (counts and times) the events carry. */
    static final String DAYS_LEFT = "daysLeft";
    static final String LICENCE_ENDS = "licenceEnds";
    static final String GRACE_DAYS_LEFT = "graceDaysLeft";
    static final String GRACE_ENDS = "graceEnds";
    static final String INTAKE_STOPPED_AT = "intakeStopped";
    static final String MINUTES_BEHIND = "minutesBehind";
    static final String USED = "used";
    static final String ALLOWED = "allowed";
    static final String PERCENT_USED = "percentUsed";
    static final String UPDATES_ENDED = "updatesEnded";
    /** The admin-adjustable "near limit" percentage of {@link #USAGE_NEAR_LIMIT}. */
    public static final String NEAR_LIMIT_PERCENT = "nearLimitPercent";

    private LicensingEvents() {
    }

    /**
     * Registers every licensing event type.
     *
     * @param itAdmin       the app's IT Admin role (the one the catalogue was built with)
     * @param businessAdmin the app's per-entity Business Admin role
     */
    public static EventCatalogue.Builder register(EventCatalogue.Builder catalogue, RoleId itAdmin,
                                                  RoleId businessAdmin) {
        types(itAdmin, businessAdmin).forEach(catalogue::register);
        return catalogue;
    }

    /** The licensing event types with their defaults. */
    public static List<EventType> types(RoleId itAdmin, RoleId businessAdmin) {
        Schedule daily = Schedule.onceADay(DAILY_AT);
        ConditionTiming oncePerDay = new ConditionTiming(Duration.ZERO, Duration.ofHours(24));
        return List.of(
                EventType.builder(LICENCE_EXPIRING.value(), "Licence expiring", EventKind.BUSINESS)
                        .schedule(daily).recipients(businessAdmin)
                        .count(DAYS_LEFT, "Days left").time(LICENCE_ENDS, "Licence ends").build(),
                EventType.builder(LICENCE_IN_GRACE.value(), "Licence expired, grace period running", EventKind.BUSINESS)
                        .critical().continuous(oncePerDay).recipients(businessAdmin, itAdmin)
                        .count(GRACE_DAYS_LEFT, "Grace days left").time(LICENCE_ENDS, "Licence ended")
                        .time(GRACE_ENDS, "Grace period ends").build(),
                EventType.builder(INTAKE_STOPPED.value(), "Intake stopped, licence expired", EventKind.BUSINESS)
                        .critical().continuous(oncePerDay).recipients(businessAdmin, itAdmin)
                        .time(LICENCE_ENDS, "Licence ended").time(INTAKE_STOPPED_AT, "Intake stopped").build(),
                EventType.builder(CLOCK_BEHIND.value(), "Server clock behind", EventKind.TECHNICAL)
                        .critical().continuous(new ConditionTiming(Duration.ZERO, Duration.ofHours(4)))
                        .recipients(itAdmin).count(MINUTES_BEHIND, "Minutes behind").build(),
                usage(USAGE_NEAR_LIMIT, "Usage near limit", businessAdmin, daily)
                        .threshold(NEAR_LIMIT_PERCENT, "Warn at percent used", 80, 50, 99).build(),
                usage(USAGE_AT_LIMIT, "Usage at limit", businessAdmin, daily).build(),
                usage(USAGE_OVER_LIMIT, "Usage over limit", businessAdmin, daily).continuous(oncePerDay).build(),
                EventType.builder(RELEASE_NOT_COVERED.value(), "Version not covered by licence updates",
                                EventKind.TECHNICAL)
                        .schedule(daily).recipients(itAdmin).time(UPDATES_ENDED, "Update period ended").build());
    }

    private static EventType.Builder usage(EventTypeId id, String name, RoleId businessAdmin, Schedule daily) {
        return EventType.builder(id.value(), name, EventKind.BUSINESS).schedule(daily).recipients(businessAdmin)
                .count(USED, "Used").count(ALLOWED, "Allowed").count(PERCENT_USED, "Percent used");
    }
}
