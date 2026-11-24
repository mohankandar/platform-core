package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.TrustedKeys;

import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Throwaway keys and signed licences for the apps' tests. A new key pair is generated for every instance; nothing
 * here is a real key.
 */
public final class TestLicences {

    public static final String KEY_ID = "test-key-1";
    public static final String TENANT = "TEN-TEST-0001";
    public static final String SLOT = "ENT-TEST-0001";
    public static final String PRODUCT = "PRODUCT_A";

    private final KeyPair keys = com.documentplatform.licensing.issue.SigningKeys.generate();
    private final String keyId;

    public TestLicences() {
        this(KEY_ID);
    }

    public TestLicences(String keyId) {
        this.keyId = keyId;
    }

    public String keyId() {
        return keyId;
    }

    public KeyPair keyPair() {
        return keys;
    }

    public TrustedKeys trustedKeys() {
        return TrustedKeys.of(Map.of(keyId, keys.getPublic()));
    }

    /** A build that trusts this instance's key: for an app test's {@code LicenceBuild} bean. */
    public LicenceBuild licenceBuild() {
        return LicenceBuild.of(trustedKeys(), false, Instant.EPOCH);
    }

    /**
     * A one-slot subscription licence for {@link #PRODUCT} with 1000 pages a month and 10 named seats.
     */
    public Licence subscription(LicenceKind kind, LocalDate startsOn, LocalDate expiresOn) {
        return new Licence("LIC-TEST-0001", 1, "Test Customer", TENANT, List.of(PRODUCT),
                List.of(new EntitySlot(SLOT, List.of(
                        new Allowance(PRODUCT, AllowanceType.PAGES_PER_MONTH, 1000),
                        new Allowance(PRODUCT, AllowanceType.NAMED_SEATS, 10)))),
                LicenceType.SUBSCRIPTION, kind, startsOn, expiresOn, null, Deployment.ON_PREM, keyId,
                Instant.parse("2026-11-21T10:00:00Z"));
    }

    /** As {@link #subscription}, perpetual with the given "updates until" date. */
    public Licence perpetual(LicenceKind kind, LocalDate startsOn, LocalDate updatesUntil) {
        return new Licence("LIC-TEST-0002", 1, "Test Customer", TENANT, List.of(PRODUCT),
                List.of(new EntitySlot(SLOT, List.of(new Allowance(PRODUCT, AllowanceType.PAGES_PER_MONTH, 1000)))),
                LicenceType.PERPETUAL, kind, startsOn, null, updatesUntil, Deployment.ON_PREM, keyId,
                Instant.parse("2026-11-21T10:00:00Z"));
    }

    /** The signed file of {@code licence}, with this instance's private key. */
    public String sign(Licence licence) {
        return com.documentplatform.licensing.issue.LicenceSigner.sign(licence, keys.getPrivate());
    }
}
