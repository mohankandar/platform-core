package com.documentplatform.licensing;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Throwaway keys and licences for this module's tests. */
public final class Fixtures {

    public static final KeyPair KEY_1 = com.documentplatform.licensing.issue.SigningKeys.generate();
    public static final KeyPair KEY_2 = com.documentplatform.licensing.issue.SigningKeys.generate();
    public static final String KEY_ID_1 = "test-2026-01";
    public static final String KEY_ID_2 = "test-2027-01";

    private Fixtures() {
    }

    public static TrustedKeys trustKey1() {
        return TrustedKeys.of(Map.of(KEY_ID_1, KEY_1.getPublic()));
    }

    public static TrustedKeys trustBoth() {
        return TrustedKeys.of(Map.of(KEY_ID_1, KEY_1.getPublic(), KEY_ID_2, KEY_2.getPublic()));
    }

    public static Licence subscription(LocalDate starts, LocalDate expires) {
        return subscription(LicenceKind.PRODUCTION, starts, expires, KEY_ID_1);
    }

    public static Licence subscription(LicenceKind kind, LocalDate starts, LocalDate expires, String keyId) {
        return new Licence("LIC-0000-0000-0000-0001", 1, "Example Customer Ltd", "TEN-0000-0000-0000-0001",
                List.of("PRODUCT_A", "PRODUCT_B"),
                List.of(new EntitySlot("ENT-0000-0000-0000-0001", List.of(
                                new Allowance("PRODUCT_A", AllowanceType.PAGES_PER_MONTH, 10000),
                                new Allowance("PRODUCT_A", AllowanceType.NAMED_SEATS, 25))),
                        new EntitySlot("ENT-0000-0000-0000-0002", List.of(
                                new Allowance("PRODUCT_B", AllowanceType.STORAGE, 500),
                                new Allowance("PRODUCT_B", AllowanceType.CONCURRENT_SEATS, 5)))),
                LicenceType.SUBSCRIPTION, kind, starts, expires, null, Deployment.ON_PREM, keyId,
                Instant.parse("2026-11-21T10:00:00Z"));
    }

    public static Licence perpetual(LocalDate starts, LocalDate updatesUntil) {
        return new Licence("LIC-0000-0000-0000-0002", 1, "Internal", "TEN-0000-0000-0000-0002",
                List.of("PRODUCT_A"),
                List.of(new EntitySlot("ENT-0000-0000-0000-0003", List.of(
                        new Allowance("PRODUCT_A", AllowanceType.PAGES_PER_MONTH, 10000)))),
                LicenceType.PERPETUAL, LicenceKind.PRODUCTION, starts, null, updatesUntil, Deployment.HOSTED,
                KEY_ID_1, Instant.parse("2026-11-21T10:00:00Z"));
    }

    public static String signed(Licence licence) {
        return com.documentplatform.licensing.issue.LicenceSigner.sign(licence, (licence.keyId().equals(KEY_ID_2) ? KEY_2 : KEY_1).getPrivate());
    }

    public static Instant at(String date) {
        return LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(12 * 3600);
    }

    /** A clock tests move by hand. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant now) {
            this.now = now;
        }

        public void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
