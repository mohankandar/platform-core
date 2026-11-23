package com.documentplatform.integration.credential;

import com.documentplatform.outbound.CertificateInfo;
import com.documentplatform.outbound.ClientCertificate;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The client certificates of the integrations (mutual TLS, requirement 13): one per integration (and so per entity),
 * with rotation without downtime.
 *
 * <ul>
 *   <li><b>Two sources:</b> an uploaded PKCS#12 or PEM file, stored <b>sealed under the customer's keys</b>
 *       (platform-crypto's {@link ValueCipher}, context {@code integration-credential:<integration>:<credential>});
 *       or a keystore the customer manages (path, password by secret reference, alias; nothing copied).</li>
 *   <li><b>Rotation:</b> up to two in use, CURRENT and NEXT. The first one added is CURRENT; a later one is NEXT, which
 *       the Test action can try ({@code TestAction.runWithNext}), and which becomes CURRENT at the admin's
 *       {@link #switchToNext} or by itself at its set time ({@link #switchDue}). The old CURRENT is kept as RETIRED
 *       (its sealed key dropped).</li>
 *   <li><b>History:</b> every change is numbered per integration, with who and when, kept by the store and given to
 *       the app's audit ({@link IntegrationAudit#credentialChanged}).</li>
 *   <li><b>Shown only as</b> {@link CredentialView}: subject, issuer, serial, SHA-256 fingerprint, validity. The key
 *       and the file are never returned, logged or put in a result.</li>
 * </ul>
 * Refusals are {@link Refused} with an {@code INT_CREDENTIAL_*} code.
 */
public final class IntegrationCredentials implements CertificateSource {

    /** A change was refused; {@link #code()} says why (its {@code message()} tells IT what to do). */
    public static final class Refused extends RuntimeException {
        private final IntegrationCode code;

        public Refused(IntegrationCode code) {
            super(code.hasMessage() ? code.name() + ": " + code.message() : code.name(), null, false, false);
            this.code = code;
        }

        public IntegrationCode code() {
            return code;
        }
    }

    /** Who switches at the set time, in the history and the audit. */
    public static final String SCHEDULED = "scheduled-switch";

    private static final System.Logger LOG = System.getLogger(IntegrationCredentials.class.getName());
    private static final Pattern BY = Pattern.compile("[\\x21-\\x7e][\\x20-\\x7e]{0,199}");

    private final CredentialStore store;
    private final ValueCipher cipher;
    private final Function<UUID, CustomerId> customers;
    private final SecretResolver secrets;
    private final IntegrationAudit audit;
    private final Clock clock;

    /**
     * @param cipher    seals uploaded certificates; null when the installation has no key service (uploads are then
     *                  refused with {@code INT_CREDENTIAL_KEY_SERVICE_FAILED}; keystores still work)
     * @param customers the customer (whose keys seal the certificate) of an entity
     * @param secrets   reads keystore passwords
     */
    public IntegrationCredentials(CredentialStore store, ValueCipher cipher, Function<UUID, CustomerId> customers,
                                  SecretResolver secrets, IntegrationAudit audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.cipher = cipher;
        this.customers = Objects.requireNonNull(customers, "customers");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- Adding ----

    /**
     * Uploads a PKCS#12 or PEM file with the certificate and its private key. Refused unless the key matches the
     * certificate, and when the certificate has expired. It becomes CURRENT when the integration has none, else NEXT
     * ({@code INT_CREDENTIAL_NEXT_EXISTS} when there is a NEXT already).
     *
     * @param password the PKCS#12 file's password (null or empty for none); not kept
     * @param switchAt for a NEXT: when it becomes CURRENT by itself, or null for the admin's switch
     */
    public CredentialView upload(StoredIntegration integration, byte[] file, char[] password, Instant switchAt,
                                 String by) {
        checkBy(by);
        ClientCertificate c = read(() -> CredentialFiles.read(file, password));
        refuseExpired(c.info());
        if (cipher == null) throw new Refused(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED);
        UUID id = UUID.randomUUID();
        IntegrationConfig cfg = integration.config();
        String sealed;
        try {
            sealed = cipher.seal(customers.apply(cfg.entity()), context(cfg.id(), id), CredentialFiles.toPem(c));
        } catch (CryptoException | IllegalArgumentException e) {
            throw new Refused(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED);
        }
        return add(cfg, id, CredentialSource.UPLOADED, c.info(), sealed, null, switchAt,
                CredentialChange.Kind.UPLOADED, by);
    }

    /**
     * Points to an entry of a keystore the customer manages; it is read now to check it (and its certificate is
     * shown), and again whenever a call needs it. CURRENT when the integration has none, else NEXT.
     */
    public CredentialView useKeystore(StoredIntegration integration, KeystoreRef keystore, Instant switchAt,
                                      String by) {
        checkBy(by);
        ClientCertificate c = readKeystore(keystore);
        refuseExpired(c.info());
        return add(integration.config(), UUID.randomUUID(), CredentialSource.KEYSTORE, c.info(), null, keystore,
                switchAt, CredentialChange.Kind.KEYSTORE_SET, by);
    }

    private CredentialView add(IntegrationConfig cfg, UUID id, CredentialSource source, CertificateInfo info,
                               String sealed, KeystoreRef keystore, Instant switchAt, CredentialChange.Kind kind,
                               String by) {
        int version = store.version(cfg.id());
        List<StoredCredential> all = store.list(cfg.id());
        boolean hasCurrent = find(all, CredentialState.CURRENT).isPresent();
        if (find(all, CredentialState.NEXT).isPresent()) throw new Refused(IntegrationCode.INT_CREDENTIAL_NEXT_EXISTS);
        Instant now = now();
        StoredCredential row = new StoredCredential(id, cfg.id(), cfg.entity(), cfg.product(),
                hasCurrent ? CredentialState.NEXT : CredentialState.CURRENT, source, info, sealed, keystore,
                hasCurrent ? truncate(switchAt) : null, by, now, null);
        save(version, row, kind, by, now, List.of(row));
        return row.view();
    }

    // ---- Rotation ----

    /** NEXT becomes CURRENT now; the old CURRENT is RETIRED. Refused when NEXT is expired or not valid yet. */
    public CredentialView switchToNext(UUID integrationId, String by) {
        checkBy(by);
        return switchNext(integrationId, by, CredentialChange.Kind.SWITCHED, now());
    }

    /** Sets (or with null, clears) the time NEXT becomes CURRENT by itself. */
    public CredentialView scheduleSwitch(UUID integrationId, Instant at, String by) {
        checkBy(by);
        int version = store.version(integrationId);
        StoredCredential next = find(store.list(integrationId), CredentialState.NEXT)
                .orElseThrow(() -> new Refused(IntegrationCode.INT_CREDENTIAL_NO_NEXT));
        StoredCredential changed = next.withSwitchAt(truncate(at));
        save(version, changed, CredentialChange.Kind.SWITCH_SCHEDULED, by, now(), List.of(changed));
        return changed.view();
    }

    /** NEXT is discarded (RETIRED) without being used. */
    public CredentialView discardNext(UUID integrationId, String by) {
        checkBy(by);
        int version = store.version(integrationId);
        StoredCredential next = find(store.list(integrationId), CredentialState.NEXT)
                .orElseThrow(() -> new Refused(IntegrationCode.INT_CREDENTIAL_NO_NEXT));
        Instant now = now();
        StoredCredential retired = next.retired(now);
        save(version, retired, CredentialChange.Kind.NEXT_DISCARDED, by, now, List.of(retired));
        return retired.view();
    }

    /**
     * Switches every NEXT whose set time has come (the app's or the Spring wiring's scheduler calls it). A NEXT that
     * is not valid yet, or already expired, waits (and is logged); a conflict with a concurrent change is skipped and
     * tried at the next run.
     *
     * @return the changes made
     */
    public List<CredentialView> switchDue(Instant now) {
        List<CredentialView> done = new ArrayList<>();
        for (StoredCredential c : store.active()) {
            if (c.state() != CredentialState.NEXT || c.switchAt() == null || c.switchAt().isAfter(now)) continue;
            try {
                done.add(switchNext(c.integrationId(), SCHEDULED, CredentialChange.Kind.SWITCHED_AT_TIME, now));
            } catch (Refused | IntegrationStore.Conflict e) {
                LOG.log(System.Logger.Level.WARNING, "Integration {0}: the next client certificate could not switch "
                        + "at its time: {1}", c.integrationId(), e instanceof Refused r ? r.code() : "conflict");
            }
        }
        return done;
    }

    private CredentialView switchNext(UUID integrationId, String by, CredentialChange.Kind kind, Instant now) {
        int version = store.version(integrationId);
        List<StoredCredential> all = store.list(integrationId);
        StoredCredential next = find(all, CredentialState.NEXT)
                .orElseThrow(() -> new Refused(IntegrationCode.INT_CREDENTIAL_NO_NEXT));
        if (now.isAfter(next.info().validTo())) throw new Refused(IntegrationCode.INT_CREDENTIAL_EXPIRED);
        if (now.isBefore(next.info().validFrom())) throw new Refused(IntegrationCode.INT_CREDENTIAL_NOT_YET_VALID);
        List<StoredCredential> rows = new ArrayList<>();
        find(all, CredentialState.CURRENT).ifPresent(c -> rows.add(c.retired(now)));
        StoredCredential current = next.current();
        rows.add(current);
        save(version, current, kind, by, now, rows);
        return current.view();
    }

    // ---- Reading ----

    /** The integration's certificates, as shown (CURRENT, NEXT and RETIRED). */
    public List<CredentialView> list(UUID integrationId) {
        return store.list(integrationId).stream().map(StoredCredential::view).toList();
    }

    public Optional<CredentialView> current(UUID integrationId) {
        return find(store.list(integrationId), CredentialState.CURRENT).map(StoredCredential::view);
    }

    public Optional<CredentialView> next(UUID integrationId) {
        return find(store.list(integrationId), CredentialState.NEXT).map(StoredCredential::view);
    }

    public List<CredentialChange> history(UUID integrationId) {
        return store.history(integrationId);
    }

    /**
     * The certificate for a call (CURRENT) or for a Test of NEXT: opened (one key service call) or read from its
     * keystore, and checked against {@code now}: {@code INT_CREDENTIAL_EXPIRED}, {@code _NOT_YET_VALID},
     * {@code _MISSING}, {@code _KEY_SERVICE_FAILED}, or the keystore's own codes.
     */
    @Override
    public Loaded load(IntegrationConfig integration, CredentialState which, Instant now) {
        if (which == CredentialState.RETIRED) throw new IllegalArgumentException("A retired certificate is not used");
        Optional<StoredCredential> found;
        try {
            found = find(store.list(integration.id()), which);
        } catch (RuntimeException storeDown) {
            return Loaded.failed(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED);
        }
        if (found.isEmpty()) return Loaded.failed(IntegrationCode.INT_CREDENTIAL_MISSING);
        StoredCredential s = found.get();
        ClientCertificate c;
        try {
            if (s.source() == CredentialSource.UPLOADED) {
                if (cipher == null) return Loaded.failed(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED);
                String pem;
                try {
                    pem = cipher.openText(customers.apply(s.entity()), context(s.integrationId(), s.id()), s.sealed());
                } catch (CryptoException | IllegalArgumentException e) {
                    return Loaded.failed(IntegrationCode.INT_CREDENTIAL_KEY_SERVICE_FAILED);
                }
                c = CredentialFiles.fromPem(pem);
            } else {
                c = readKeystoreForCall(s);
            }
        } catch (CredentialFiles.Unusable u) {
            return Loaded.failed(u.code());
        } catch (Refused r) {
            return Loaded.failed(r.code());
        }
        if (now.isAfter(c.info().validTo())) return Loaded.failed(IntegrationCode.INT_CREDENTIAL_EXPIRED);
        if (now.isBefore(c.info().validFrom())) return Loaded.failed(IntegrationCode.INT_CREDENTIAL_NOT_YET_VALID);
        return Loaded.of(c);
    }

    /** A keystore read for a call; a certificate the customer replaced in the file is recorded (KEYSTORE_RENEWED). */
    private ClientCertificate readKeystoreForCall(StoredCredential s) {
        ClientCertificate c = readKeystore(s.keystore());
        if (!c.fingerprint().equals(s.info().sha256())) {
            try {
                StoredCredential renewed = s.withInfo(c.info());
                save(store.version(s.integrationId()), renewed, CredentialChange.Kind.KEYSTORE_RENEWED,
                        "keystore-file", now(), List.of(renewed));
            } catch (RuntimeException e) {
                // Recorded at a later use; the call goes on with the certificate in the file.
                LOG.log(System.Logger.Level.WARNING, "Integration {0}: the renewed keystore certificate could not be "
                        + "recorded yet", s.integrationId());
            }
        }
        return c;
    }

    // ---- inside ----

    private ClientCertificate readKeystore(KeystoreRef k) {
        Optional<Secret> password;
        try {
            password = secrets.resolve(k.password());
        } catch (RuntimeException storeDown) {
            throw new Refused(IntegrationCode.INT_SECRET_UNAVAILABLE);
        }
        if (password.isEmpty()) throw new Refused(IntegrationCode.INT_SECRET_NOT_FOUND);
        char[] pw = password.get().reveal().toCharArray();
        try {
            return read(() -> CredentialFiles.keystore(Path.of(k.path()), pw, k.alias()));
        } finally {
            java.util.Arrays.fill(pw, '\0');
        }
    }

    private interface Reader {
        ClientCertificate read() throws CredentialFiles.Unusable;
    }

    private static ClientCertificate read(Reader r) {
        try {
            return r.read();
        } catch (CredentialFiles.Unusable u) {
            throw new Refused(u.code());
        }
    }

    private void refuseExpired(CertificateInfo info) {
        if (now().isAfter(info.validTo())) throw new Refused(IntegrationCode.INT_CREDENTIAL_EXPIRED);
    }

    private void save(int version, StoredCredential about, CredentialChange.Kind kind, String by, Instant now,
                      List<StoredCredential> rows) {
        CredentialChange change = new CredentialChange(about.integrationId(), about.entity(), about.product(),
                version + 1, kind, about.id(), about.info().sha256(), by, now);
        store.save(version, change, rows);
        LOG.log(System.Logger.Level.INFO, "Integration {0}: client certificate change {1} ({2}, {3}) by {4}",
                change.integrationId(), change.version(), kind, about.info().sha256(), by);
        audit.credentialChanged(change);
    }

    static String context(UUID integration, UUID credential) {
        return "integration-credential:" + integration + ":" + credential;
    }

    private static Optional<StoredCredential> find(List<StoredCredential> all, CredentialState state) {
        return all.stream().filter(c -> c.state() == state).findFirst();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant truncate(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    }

    private static void checkBy(String by) {
        if (by == null || !BY.matcher(by).matches()) {
            throw new IllegalArgumentException("Who made the change must be 1-200 printable characters");
        }
    }
}
