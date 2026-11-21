package com.documentplatform.licensing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A licence and its state on {@code today} (UTC).
 *
 * <p>Day counting, with {@code expiresOn} the last valid day:
 * <ul>
 *   <li>{@code daysUntilExpiry = expiresOn - today}: 0 on the last valid day.</li>
 *   <li>{@link LicenceState#EXPIRING} when that is 0 to 60. {@link #warningThreshold()} is the band (60, 30, 7 or 1)
 *       the day falls in; {@link #warningDay()} is true on exactly 60, 30, 7 and 1 days before.</li>
 *   <li>Grace: days 1 to 30 after {@code expiresOn} ({@link #graceEndsOn()} = expiresOn + 30, the last grace day).
 *       From expiresOn + 31 intake stops.</li>
 * </ul>
 * A perpetual licence is {@link LicenceState#VALID} from its start date on, for ever.
 *
 * <p>{@code today} is the UTC date of the clock's <b>effective</b> time (the later of the server clock and the latest
 * time seen), so a clock turned back never extends a licence.
 *
 * @param clock   the clock reading the state was worked out from
 * @param waiting a renewal stored early, in force from its start date; null when there is none
 */
public record LicenceStatus(Licence licence, LocalDate today, LicenceState state, ClockReading clock,
                            Licence waiting) {

    /** Days of grace after the expiry date. */
    public static final int GRACE_DAYS = 30;
    /** The days before expiry on which a warning is due. */
    public static final List<Integer> WARNING_DAYS = List.of(60, 30, 7, 1);

    public LicenceStatus {
        Objects.requireNonNull(licence, "licence");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(clock, "clock");
    }

    /** The status of {@code licence} at {@code now} (a clock with nothing seen before). */
    public static LicenceStatus at(Licence licence, Instant now) {
        return at(licence, ClockReading.exact(now), null);
    }

    /** The status of {@code licence} at the effective time of {@code clock}. */
    public static LicenceStatus at(Licence licence, ClockReading clock, Licence waiting) {
        LocalDate today = LocalDate.ofInstant(clock.effective(), ZoneOffset.UTC);
        return new LicenceStatus(licence, today, stateOn(licence, today), clock, waiting);
    }

    /** The alert flag: the server clock is behind the latest time seen by more than the tolerance. */
    public boolean clockBehind() {
        return clock.clockBehind();
    }

    public java.util.Optional<Licence> renewalWaiting() {
        return java.util.Optional.ofNullable(waiting);
    }

    static LicenceState stateOn(Licence licence, LocalDate today) {
        if (today.isBefore(licence.startsOn())) {
            return LicenceState.NOT_STARTED;
        }
        if (licence.type() == LicenceType.PERPETUAL) {
            return LicenceState.VALID;
        }
        long days = ChronoUnit.DAYS.between(today, licence.expiresOn());
        if (days > WARNING_DAYS.get(0)) {
            return LicenceState.VALID;
        }
        if (days >= 0) {
            return LicenceState.EXPIRING;
        }
        return -days <= GRACE_DAYS ? LicenceState.EXPIRED_IN_GRACE : LicenceState.EXPIRED_GRACE_OVER;
    }

    public boolean intakeAllowed() {
        return state.intakeAllowed();
    }

    /** Days from today to the expiry date (negative once expired); empty for a perpetual licence. */
    public OptionalLong daysUntilExpiry() {
        return licence.expiresOn() == null ? OptionalLong.empty()
                : OptionalLong.of(ChronoUnit.DAYS.between(today, licence.expiresOn()));
    }

    /** While {@link LicenceState#EXPIRING}: the warning band (60, 30, 7 or 1) today falls in. */
    public OptionalInt warningThreshold() {
        if (state != LicenceState.EXPIRING) {
            return OptionalInt.empty();
        }
        long days = daysUntilExpiry().getAsLong();
        int band = WARNING_DAYS.get(0);
        for (int w : WARNING_DAYS) {
            if (days <= w) {
                band = w;
            }
        }
        return OptionalInt.of(band);
    }

    /** True on the days a warning is due: 60, 30, 7 and 1 days before expiry, and every day of grace (critical). */
    public boolean warningDay() {
        return switch (state) {
            case EXPIRING -> WARNING_DAYS.contains((int) daysUntilExpiry().getAsLong());
            case EXPIRED_IN_GRACE -> true;
            default -> false;
        };
    }

    /** The last day of grace (expiresOn + 30); empty for a perpetual licence. */
    public java.util.Optional<LocalDate> graceEndsOn() {
        return licence.expiry().map(d -> d.plusDays(GRACE_DAYS));
    }

    /** While in grace: days of grace left after today (0 on the last grace day). */
    public OptionalLong graceDaysLeft() {
        if (state != LicenceState.EXPIRED_IN_GRACE) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ChronoUnit.DAYS.between(today, graceEndsOn().orElseThrow()));
    }

    /** A sentence for the app to show (admin banner, log line). Contains dates and IDs only. */
    public String message() {
        String base = stateMessage();
        if (waiting != null) {
            base += " The renewed licence " + waiting.licenceId() + " v" + waiting.version()
                    + " is stored and takes over on " + waiting.startsOn() + ".";
        }
        return base;
    }

    private String stateMessage() {
        Licence l = licence;
        return switch (state) {
            case NOT_STARTED -> "The licence " + l.licenceId() + " starts on " + l.startsOn()
                    + ". Intake is stopped until then; viewing and export work.";
            case VALID -> l.type() == LicenceType.PERPETUAL
                    ? "The licence is valid (perpetual; updates until " + l.updatesUntil() + ")."
                    : "The licence is valid until " + l.expiresOn() + ".";
            case EXPIRING -> {
                long d = daysUntilExpiry().getAsLong();
                yield d == 0 ? "The licence expires at the end of today (" + l.expiresOn() + "). Renew it to avoid the grace period."
                        : "The licence expires in " + d + (d == 1 ? " day" : " days") + " (on " + l.expiresOn()
                        + "). Renew it to avoid the grace period.";
            }
            case EXPIRED_IN_GRACE -> "The licence expired on " + l.expiresOn() + ". Intake continues during the "
                    + GRACE_DAYS + "-day grace period, which ends on " + graceEndsOn().orElseThrow() + " ("
                    + graceDaysLeft().getAsLong() + " days left after today). Renew the licence now.";
            case EXPIRED_GRACE_OVER -> "The licence expired on " + l.expiresOn() + " and the grace period ended on "
                    + graceEndsOn().orElseThrow() + ". Intake is stopped; viewing and export still work."
                    + " Install a renewed licence to resume intake.";
        };
    }
}
