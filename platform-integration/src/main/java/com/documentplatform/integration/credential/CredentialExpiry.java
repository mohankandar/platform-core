package com.documentplatform.integration.credential;

import com.documentplatform.outbound.CertificateInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Expiry warnings for the client certificates in use (CURRENT and NEXT): which reach 30, 7 and 1 days before their end
 * (the installation can change these), and which have expired. The app turns them into IT notices through its own
 * notifications (and remembers which threshold it already told, per certificate); platform-core sends nothing itself.
 * An expired CURRENT certificate also refuses every call ({@code INT_CREDENTIAL_EXPIRED}, SIGN_IN_FAILED).
 */
public final class CredentialExpiry {

    /** 30, 7 and 1 days. */
    public static final List<Integer> DEFAULT_DAYS = List.of(30, 7, 1);

    /**
     * One warning.
     *
     * @param threshold the smallest threshold (in days) the certificate has reached, or 0 once it has expired
     * @param daysLeft  whole days left (0 on the last day; negative once expired)
     */
    public record Warning(UUID integrationId, UUID entity, String product, UUID credentialId, CredentialState state,
                          CertificateInfo info, int threshold, long daysLeft, boolean expired) {
    }

    private final CredentialStore store;
    private final List<Integer> days;

    public CredentialExpiry(CredentialStore store) {
        this(store, DEFAULT_DAYS);
    }

    /** @param days the thresholds in days before the end, each 1 to 365 */
    public CredentialExpiry(CredentialStore store, List<Integer> days) {
        this.store = Objects.requireNonNull(store, "store");
        if (days == null || days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 365)) {
            throw new IllegalArgumentException("Expiry warning days are 1 to 365");
        }
        this.days = List.copyOf(new TreeSet<>(days)); // smallest first
    }

    public List<Integer> days() {
        return days;
    }

    /** The certificates in use that have reached a threshold or expired at {@code now}, soonest end first. */
    public List<Warning> check(Instant now) {
        List<Warning> out = new ArrayList<>();
        for (StoredCredential c : store.active()) {
            Instant end = c.info().validTo();
            if (now.isAfter(end)) {
                out.add(new Warning(c.integrationId(), c.entity(), c.product(), c.id(), c.state(), c.info(), 0,
                        -Duration.between(end, now).toDays() - 1, true));
                continue;
            }
            Duration left = Duration.between(now, end);
            for (int d : days) {
                if (left.compareTo(Duration.ofDays(d)) <= 0) {
                    out.add(new Warning(c.integrationId(), c.entity(), c.product(), c.id(), c.state(), c.info(), d,
                            left.toDays(), false));
                    break;
                }
            }
        }
        out.sort((a, b) -> a.info().validTo().compareTo(b.info().validTo()));
        return out;
    }
}
