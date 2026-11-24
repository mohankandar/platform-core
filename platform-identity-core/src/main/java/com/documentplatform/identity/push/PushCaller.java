package com.documentplatform.identity.push;

import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingStatus;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static com.documentplatform.identity.push.PushRefusal.*;

/**
 * The push-API caller check: an app's own push API (a sending system calls the app) has verified the caller's token
 * or client certificate with its usual server set-up; the app's controller then asks, on every request, which
 * customer, entity and sending system (source) this caller is, and whether it may call. The answer comes from the
 * integration client bindings that setup loads ({@code idn_machine_binding}, {@code docs/design/setup.md} 6.1).
 *
 * <p><b>Token:</b> the issuer finds the customer's ACTIVE login connection for the product; the token's audiences must
 * include the connection's API audience; the client ID is the first of the configured claims present (default
 * {@code client_id}, {@code azp}, {@code cid}, {@code sub}); its binding under that customer must be ACTIVE and its
 * entity ACTIVE. A configured entity or source claim that is in the token must equal the binding.
 *
 * <p><b>Client certificate:</b> a binding whose client ID is {@code cert-sha256:<64 lowercase hex>}, the SHA-256 of
 * the certificate (DER), identifies a caller without a token; the customer must have an ACTIVE login connection for
 * the product. Two bindings with two fingerprints for the same entity and source are a rotation overlap. When a call
 * has both (or the product requires both), both must point to the same customer, entity and source.
 *
 * <p>The caller gets {@link PushCallerResult#NEUTRAL} only; the log gets the code (and a certificate's fingerprint,
 * which is public), never a token, claim or certificate content. Answers, allowed or refused, are kept for
 * {@link PushCallerSettings#cacheFor()} per caller, so a binding switched off takes effect within that time. A store
 * that cannot be read throws (and nothing is kept): there is no fallback.
 */
public final class PushCaller {

    /** The client ID prefix of a certificate binding: {@code cert-sha256:} followed by 64 lowercase hex digits. */
    public static final String CERT_PREFIX = "cert-sha256:";

    private static final System.Logger LOG = System.getLogger(PushCaller.class.getName());
    private static final int MAX_CACHED = 10_000;

    private record Kept(PushCallerResult result, Instant until) {
    }

    private final PushCallerDirectory directory;
    private final PushCallerSettings settings;
    private final Clock clock;
    private final Map<String, Kept> cache = new ConcurrentHashMap<>();

    public PushCaller(PushCallerDirectory directory, PushCallerSettings settings) {
        this(directory, settings, Clock.systemUTC());
    }

    public PushCaller(PushCallerDirectory directory, PushCallerSettings settings, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PushCallerSettings settings() {
        return settings;
    }

    /** The fingerprint client ID of a certificate: {@code cert-sha256:} + SHA-256 of its DER encoding, lowercase hex. */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return CERT_PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("the certificate cannot be encoded");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Whether a client ID is a certificate fingerprint ({@code cert-sha256:<64 lowercase hex>}). */
    public static boolean isFingerprint(String clientId) {
        return clientId != null && clientId.matches("cert-sha256:[0-9a-f]{64}");
    }

    /**
     * Who the caller is, or why it is refused.
     *
     * @throws RuntimeException when the identity store cannot be read (nothing is kept; the app answers with its
     *                          own server error)
     */
    public PushCallerResult check(PushCallerInput input) {
        Objects.requireNonNull(input, "input");
        String fingerprint = input.hasCertificate() ? fingerprint(input.certificate()) : null;
        String clientId = input.hasToken() ? clientId(input.claims()) : null;
        String key = cacheKey(input, clientId, fingerprint);
        Instant now = clock.instant();
        Kept kept = cache.get(key);
        if (kept != null && now.isBefore(kept.until())) return kept.result();

        PushCallerResult result = decide(input, clientId, fingerprint);
        if (result.allowed()) {
            LOG.log(System.Logger.Level.DEBUG, "Push caller admitted for {0}: entity {1}, source {2}",
                    settings.productCode(), result.identity().slotId(), result.identity().sourceKey());
        } else {
            LOG.log(System.Logger.Level.WARNING, "Push caller refused for {0}: {1}{2}", settings.productCode(),
                    result.refusal(), fingerprint == null ? "" : " (certificate " + fingerprint + ")");
        }
        if (!settings.cacheFor().isZero()) {
            if (cache.size() >= MAX_CACHED) cache.clear();
            cache.put(key, new Kept(result, now.plus(settings.cacheFor())));
        }
        return result;
    }

    /** Forgets every kept answer (for example right after the IT Admin switched a binding off). */
    public void forget() {
        cache.clear();
    }

    private PushCallerResult decide(PushCallerInput input, String clientId, String fingerprint) {
        if (settings.requireTokenAndCertificate()) {
            if (!input.hasToken()) return PushCallerResult.refused(IDN_PUSH_NO_CLIENT_ID, fingerprint);
            if (!input.hasCertificate()) return PushCallerResult.refused(IDN_PUSH_CERT_NOT_BOUND, null);
        }
        Found byToken = null;
        if (input.hasToken()) {
            byToken = byToken(input, clientId);
            if (byToken.refusal != null) return PushCallerResult.refused(byToken.refusal, fingerprint);
        }
        Found byCertificate = null;
        if (input.hasCertificate()) {
            byCertificate = byCertificate(fingerprint);
            if (byCertificate.refusal != null) return PushCallerResult.refused(byCertificate.refusal, fingerprint);
        }
        if (byToken != null && byCertificate != null) {
            MachineBinding t = byToken.binding;
            MachineBinding c = byCertificate.binding;
            if (!t.tenantId().equals(c.tenantId()) || !t.slotId().equals(c.slotId())
                    || !t.sourceKey().equals(c.sourceKey())) {
                return PushCallerResult.refused(IDN_PUSH_TOKEN_AND_CERT_DIFFER, fingerprint);
            }
            return PushCallerResult.allowed(identity(t, PushCallerIdentity.Via.TOKEN_AND_CERTIFICATE), fingerprint);
        }
        if (byToken != null) {
            return PushCallerResult.allowed(identity(byToken.binding, PushCallerIdentity.Via.TOKEN), null);
        }
        return PushCallerResult.allowed(identity(byCertificate.binding, PushCallerIdentity.Via.CERTIFICATE),
                fingerprint);
    }

    private record Found(MachineBinding binding, PushRefusal refusal) {
        static Found refused(PushRefusal r) {
            return new Found(null, r);
        }
    }

    /**
     * P045, SEC-7: null when the token is a machine's; else the refusal. A token with a claim only a person's sign-in
     * has, or without the product's machine claim and value, is never a push caller.
     */
    private PushRefusal machine(PushCallerInput input) {
        for (String c : settings.userClaims()) {
            if (input.claims().containsKey(c)) return IDN_PUSH_NOT_MACHINE;
        }
        if (settings.machineClaim() != null
                && !settings.machineClaimValue().equals(input.claims().get(settings.machineClaim()))) {
            return IDN_PUSH_NOT_MACHINE;
        }
        return null;
    }

    private Found byToken(PushCallerInput input, String clientId) {
        PushRefusal notMachine = machine(input);
        if (notMachine != null) return Found.refused(notMachine);
        List<PushCallerDirectory.ActiveConnection> connections =
                directory.activeConnections(settings.productCode(), input.issuer());
        if (connections.isEmpty()) return Found.refused(IDN_PUSH_NO_CONNECTION);
        Set<String> tenants = new LinkedHashSet<>();
        for (PushCallerDirectory.ActiveConnection c : connections) {
            if (input.audiences().contains(c.apiAudience())) tenants.add(c.tenantId());
        }
        if (tenants.isEmpty()) return Found.refused(IDN_PUSH_AUDIENCE);
        if (clientId == null) return Found.refused(IDN_PUSH_NO_CLIENT_ID);
        // P045, SEC-16: a certificate binding is reached by the certificate only, never by a token naming it.
        if (isFingerprint(clientId)) return Found.refused(IDN_PUSH_NOT_BOUND);
        List<PushCallerDirectory.BoundClient> bound = directory.bindings(settings.productCode(), clientId).stream()
                .filter(b -> tenants.contains(b.binding().tenantId())).toList();
        Found found = choose(bound, IDN_PUSH_NOT_BOUND);
        if (found.refusal != null) return found;
        MachineBinding b = found.binding;
        if (settings.entityClaim() != null && input.claims().containsKey(settings.entityClaim())) {
            Object v = input.claims().get(settings.entityClaim());
            if (!(v instanceof String s) || !(s.equals(b.slotId()) || s.equals(b.entityUuid().toString()))) {
                return Found.refused(IDN_PUSH_CLAIM_MISMATCH);
            }
        }
        if (settings.sourceClaim() != null && input.claims().containsKey(settings.sourceClaim())) {
            Object v = input.claims().get(settings.sourceClaim());
            if (!(v instanceof String s) || !s.equals(b.sourceKey())) return Found.refused(IDN_PUSH_CLAIM_MISMATCH);
        }
        return found;
    }

    private Found byCertificate(String fingerprint) {
        Found found = choose(directory.bindings(settings.productCode(), fingerprint), IDN_PUSH_CERT_NOT_BOUND);
        if (found.refusal != null) return found;
        if (!directory.hasActiveConnection(found.binding.tenantId(), settings.productCode())) {
            return Found.refused(IDN_PUSH_NO_CONNECTION);
        }
        return found;
    }

    /** Exactly one ACTIVE binding, its entity ACTIVE; else the refusal. */
    private static Found choose(List<PushCallerDirectory.BoundClient> bound, PushRefusal notBound) {
        if (bound.isEmpty()) return Found.refused(notBound);
        List<PushCallerDirectory.BoundClient> active = bound.stream()
                .filter(b -> b.binding().status() == MachineBindingStatus.ACTIVE).toList();
        if (active.isEmpty()) return Found.refused(IDN_PUSH_BINDING_OFF);
        if (active.size() > 1) return Found.refused(notBound); // bound under two customers: never guessed
        PushCallerDirectory.BoundClient one = active.get(0);
        if (one.entityStatus() != EntityStatus.ACTIVE) return Found.refused(IDN_PUSH_ENTITY_NOT_ACTIVE);
        return new Found(one.binding(), null);
    }

    private String clientId(Map<String, Object> claims) {
        for (String name : settings.clientIdClaims()) {
            if (claims.get(name) instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private static PushCallerIdentity identity(MachineBinding b, PushCallerIdentity.Via via) {
        return new PushCallerIdentity(b.tenantId(), b.slotId(), b.entityUuid(), b.sourceKey(), b.clientId(), via);
    }

    /** Everything the answer depends on; held only in memory, never logged. */
    private String cacheKey(PushCallerInput input, String clientId, String fingerprint) {
        StringBuilder k = new StringBuilder();
        if (input.hasToken()) {
            k.append("t\u0000").append(input.issuer()).append('\u0000').append(new TreeSet<>(input.audiences()))
                    .append('\u0000').append(clientId).append('\u0000')
                    .append(claim(input, settings.entityClaim())).append('\u0000')
                    .append(claim(input, settings.sourceClaim())).append('\u0000')
                    .append(machine(input) == null ? "m" : "p"); // the machine-token rule's answer (SEC-7)
        }
        if (fingerprint != null) k.append("\u0000c\u0000").append(fingerprint);
        return k.toString();
    }

    private static String claim(PushCallerInput input, String name) {
        if (name == null || !input.claims().containsKey(name)) return "-";
        Object v = input.claims().get(name);
        return v instanceof String s ? "s" + s : "o" + v;
    }
}
