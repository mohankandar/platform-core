package com.documentplatform.identity.store;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.LocalTestIssuers;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.identity.store.ConnectionOutcome.Result.*;

/**
 * The IT Admin's login-connection screen: prepare a change as a draft, prove it with a test sign-in, switch to it,
 * and go back if needed. People keep signing in through the ACTIVE connection the whole time.
 *
 * <ol>
 *   <li><b>Draft:</b> {@link #createDraft} copies the ACTIVE connection (or creates the first one);
 *       {@link #updateDraft} changes it. At most one DRAFT or VALIDATED connection per customer and product.</li>
 *   <li><b>Test sign-in:</b> {@link #validate} fetches the issuer's discovery document and keys through the outbound
 *       rules and checks a test token if one is given ({@link OidcTestSignIn}). A pass makes the draft VALIDATED; a
 *       later edit makes it DRAFT again.</li>
 *   <li><b>Activate:</b> {@link #activate} makes a VALIDATED connection ACTIVE and the old one DISABLED, in one
 *       transaction.</li>
 *   <li><b>Rollback:</b> {@link #rollback} makes the previous ACTIVE version ACTIVE again, as a new version; refused
 *       when there is none.</li>
 * </ol>
 *
 * Every step that changes something writes a history row (who, when, source APP). Every step uses the version the
 * person saw ({@code expectedVersion}), so two admins never overwrite each other. An {@code http} issuer is allowed
 * only for a local test login server under a development licence ({@link LocalTestIssuers}). The app audits every
 * call from the returned {@link ConnectionOutcome}.
 */
public final class LoginConnections {

    private final LoginConnectionStore store;
    private final OidcTestSignIn tester;
    private final DevelopmentLicence licence;

    /**
     * @param tester  the test sign-in (in production over the integration module's outbound rules)
     * @param licence whether the licence in force is signed by a development key; {@link DevelopmentLicence#NONE}
     *                when the app has no licensing
     */
    public LoginConnections(LoginConnectionStore store, OidcTestSignIn tester, DevelopmentLicence licence) {
        this.store = Objects.requireNonNull(store, "store");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.licence = Objects.requireNonNull(licence, "licence");
    }

    /** The customer's connections for the product (ACTIVE, DRAFT/VALIDATED, DISABLED), oldest first. */
    public List<LoginConnection> connections(String tenantId, String productCode) {
        return store.connections(tenantId, productCode);
    }

    public Optional<LoginConnection> active(String tenantId, String productCode) {
        return store.active(tenantId, productCode);
    }

    /** The DRAFT or VALIDATED connection, if any. */
    public Optional<LoginConnection> pending(String tenantId, String productCode) {
        return store.pending(tenantId, productCode);
    }

    /**
     * Starts a draft.
     *
     * @param expectedActiveVersion the version of the ACTIVE connection the person saw, or 0 when there was none
     * @param values                the draft's values; null to copy the ACTIVE connection (required when there is
     *                              none)
     */
    public ConnectionOutcome createDraft(String tenantId, String productCode, long expectedActiveVersion,
                                         ConnectionValues values, String actor) {
        requireActor(actor);
        LoginConnection.requireProductCode(productCode);
        if (store.pending(tenantId, productCode).isPresent()) {
            return ConnectionOutcome.of(IDN_DRAFT_EXISTS, store.pending(tenantId, productCode).get());
        }
        Optional<LoginConnection> active = store.active(tenantId, productCode);
        long seen = active.map(LoginConnection::configurationVersion).orElse(0L);
        if (seen != expectedActiveVersion) return ConnectionOutcome.of(IDN_VERSION_CONFLICT, active.orElse(null));
        ConnectionValues v = values != null ? values : active.map(ConnectionValues::of).orElse(null);
        if (v == null) return ConnectionOutcome.of(IDN_VALUES_REQUIRED, null);
        Built draft = build(UUID.randomUUID(), tenantId, productCode, v, IdentityConnectionStatus.DRAFT, 1);
        if (draft.refusal != null) return draft.refusal;
        try {
            return ConnectionOutcome.of(DRAFT_CREATED, store.insert(draft.connection, ChangeSource.APP, actor));
        } catch (IllegalStateException e) {
            Optional<LoginConnection> pending = store.pending(tenantId, productCode);
            if (pending.isPresent()) return ConnectionOutcome.of(IDN_DRAFT_EXISTS, pending.get());
            return ConnectionOutcome.of(IDN_CUSTOMER_UNKNOWN, null);
        }
    }

    /** Changes the draft's values. A VALIDATED draft becomes DRAFT again (test it again before activating). */
    public ConnectionOutcome updateDraft(UUID draftId, long expectedVersion, ConnectionValues values, String actor) {
        requireActor(actor);
        Objects.requireNonNull(values, "values");
        Optional<LoginConnection> found = store.find(draftId);
        if (found.isEmpty()) return ConnectionOutcome.of(IDN_CONNECTION_UNKNOWN, null);
        LoginConnection now = found.get();
        if (!pending(now)) return ConnectionOutcome.of(IDN_NOT_A_DRAFT, now);
        if (now.configurationVersion() != expectedVersion) return ConnectionOutcome.of(IDN_VERSION_CONFLICT, now);
        if (ConnectionValues.of(now).equals(values)) return ConnectionOutcome.of(UNCHANGED, now);
        Built next = build(now.connectionId(), now.tenantId(), now.productCode(), values, IdentityConnectionStatus.DRAFT,
                expectedVersion);
        if (next.refusal != null) return next.refusal;
        return store.update(next.connection, expectedVersion, ChangeSource.APP, actor)
                .map(c -> ConnectionOutcome.of(DRAFT_UPDATED, c))
                .orElseGet(() -> ConnectionOutcome.of(IDN_VERSION_CONFLICT, store.find(draftId).orElse(null)));
    }

    /**
     * The test sign-in of a DRAFT (or VALIDATED) connection. A pass makes it VALIDATED; a failure leaves a DRAFT as it
     * is and makes a VALIDATED one DRAFT again. The token in {@code test} is checked and forgotten.
     */
    public ConnectionOutcome validate(UUID draftId, long expectedVersion, TestSignIn test, String actor) {
        requireActor(actor);
        Objects.requireNonNull(test, "test");
        Optional<LoginConnection> found = store.find(draftId);
        if (found.isEmpty()) return ConnectionOutcome.of(IDN_CONNECTION_UNKNOWN, null);
        LoginConnection now = found.get();
        if (!pending(now)) return ConnectionOutcome.of(IDN_NOT_A_DRAFT, now);
        if (now.configurationVersion() != expectedVersion) return ConnectionOutcome.of(IDN_VERSION_CONFLICT, now);
        Optional<ConnectionOutcome> issuer = issuerRefusal(now);
        if (issuer.isPresent()) return issuer.get();
        if (now.providerType() != IdentityProviderType.OIDC) {
            return new ConnectionOutcome(IDN_VALUES_INVALID, now, null, "the test sign-in supports OIDC connections");
        }
        TestReport report = tester.run(now.issuer(), now.apiAudience(), test, LocalTestIssuers.isLocalHttp(now.issuer()));
        IdentityConnectionStatus to = report.passed() ? IdentityConnectionStatus.VALIDATED : IdentityConnectionStatus.DRAFT;
        LoginConnection result = now;
        if (report.passed() || now.status() != to) {
            Optional<LoginConnection> stored = store.update(with(now, to), expectedVersion, ChangeSource.APP, actor);
            if (stored.isEmpty()) {
                return new ConnectionOutcome(IDN_VERSION_CONFLICT, store.find(draftId).orElse(null), report, null);
            }
            result = stored.get();
        }
        return new ConnectionOutcome(report.passed() ? VALIDATED : IDN_TEST_FAILED, result, report, null);
    }

    /** Makes a VALIDATED connection ACTIVE and the one ACTIVE until now DISABLED, in one transaction. */
    public ConnectionOutcome activate(UUID validatedId, long expectedVersion, String actor) {
        requireActor(actor);
        Optional<LoginConnection> found = store.find(validatedId);
        if (found.isEmpty()) return ConnectionOutcome.of(IDN_CONNECTION_UNKNOWN, null);
        LoginConnection now = found.get();
        if (now.status() != IdentityConnectionStatus.VALIDATED) {
            return ConnectionOutcome.of(pending(now) ? IDN_NOT_VALIDATED : IDN_NOT_A_DRAFT, now);
        }
        if (now.configurationVersion() != expectedVersion) return ConnectionOutcome.of(IDN_VERSION_CONFLICT, now);
        Optional<ConnectionOutcome> issuer = issuerRefusal(now);
        if (issuer.isPresent()) return issuer.get();
        return store.activate(validatedId, expectedVersion, ChangeSource.APP, actor)
                .map(c -> ConnectionOutcome.of(ACTIVATED, c))
                .orElseGet(() -> ConnectionOutcome.of(IDN_VERSION_CONFLICT, store.find(validatedId).orElse(null)));
    }

    /** Makes the previous ACTIVE version ACTIVE again, as a new version. Drafts are left alone. */
    public ConnectionOutcome rollback(String tenantId, String productCode, String actor) {
        requireActor(actor);
        LoginConnection.requireProductCode(productCode);
        Optional<LoginConnection> previous = store.rollbackCandidate(tenantId, productCode);
        if (previous.isEmpty()) return ConnectionOutcome.of(IDN_NO_PREVIOUS_CONNECTION, null);
        Optional<ConnectionOutcome> issuer = issuerRefusal(previous.get());
        if (issuer.isPresent()) return issuer.get();
        return store.rollback(tenantId, productCode, ChangeSource.APP, actor)
                .map(c -> ConnectionOutcome.of(ROLLED_BACK, c))
                .orElseGet(() -> ConnectionOutcome.of(IDN_NO_PREVIOUS_CONNECTION, null));
    }

    /** Drops a DRAFT or VALIDATED connection (it becomes DISABLED and stays in the history). */
    public ConnectionOutcome discardDraft(UUID draftId, long expectedVersion, String actor) {
        requireActor(actor);
        Optional<LoginConnection> found = store.find(draftId);
        if (found.isEmpty()) return ConnectionOutcome.of(IDN_CONNECTION_UNKNOWN, null);
        LoginConnection now = found.get();
        if (!pending(now)) return ConnectionOutcome.of(IDN_NOT_A_DRAFT, now);
        if (now.configurationVersion() != expectedVersion) return ConnectionOutcome.of(IDN_VERSION_CONFLICT, now);
        return store.update(with(now, IdentityConnectionStatus.DISABLED), expectedVersion, ChangeSource.APP, actor)
                .map(c -> ConnectionOutcome.of(DRAFT_DISCARDED, c))
                .orElseGet(() -> ConnectionOutcome.of(IDN_VERSION_CONFLICT, store.find(draftId).orElse(null)));
    }

    // ---- helpers

    private Optional<ConnectionOutcome> issuerRefusal(LoginConnection c) {
        return LocalTestIssuers.check(c.issuer(), licence.signedByDevelopmentKey()).map(p -> switch (p) {
            case HTTP_NOT_ALLOWED -> ConnectionOutcome.of(IDN_HTTP_ISSUER_NOT_ALLOWED, c);
            case NOT_AN_ISSUER -> new ConnectionOutcome(IDN_VALUES_INVALID, c, null,
                    "issuer must be the login system's https address, without query or fragment");
        });
    }

    private record Built(LoginConnection connection, ConnectionOutcome refusal) {
    }

    private Built build(UUID id, String tenantId, String productCode, ConnectionValues v,
                        IdentityConnectionStatus status, long version) {
        LoginConnection c;
        try {
            c = new LoginConnection(id, tenantId, productCode, v.providerId(), v.providerType(), v.issuer(),
                    v.browserClientId(), v.apiAudience(), v.secretReference(), status, version);
        } catch (IllegalArgumentException | NullPointerException e) {
            return new Built(null, new ConnectionOutcome(IDN_VALUES_INVALID, null, null, e.getMessage()));
        }
        return issuerRefusal(c).map(r -> new Built(null, r)).orElseGet(() -> new Built(c, null));
    }

    private static boolean pending(LoginConnection c) {
        return c.status() == IdentityConnectionStatus.DRAFT || c.status() == IdentityConnectionStatus.VALIDATED;
    }

    private static LoginConnection with(LoginConnection c, IdentityConnectionStatus status) {
        return new LoginConnection(c.connectionId(), c.tenantId(), c.productCode(), c.providerId(), c.providerType(),
                c.issuer(), c.browserClientId(), c.apiAudience(), c.secretReference(), status, c.configurationVersion());
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
    }
}
