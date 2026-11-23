package com.documentplatform.monitoring.checks;

import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The customer's ACTIVE login connection: its issuer's discovery document and signing keys are reachable and right
 * (the test sign-in without a token, through the shared outbound rules: {@code OutboundIssuerFetch}). Its failure is
 * the test sign-in's {@code IDN_TEST_*} code. No connection is HAVING_PROBLEMS ({@code MON_LOGIN_NO_CONNECTION}).
 */
public final class LoginSystemCheck implements DependencyCheck {

    private final String product;
    private final Supplier<Optional<LoginConnection>> active;
    private final OidcTestSignIn signIn;
    private final boolean localTestIssuersAllowed;

    /**
     * @param active                  the customer's ACTIVE connection for the product (for example
     *                                {@code () -> store.active(tenant, product)})
     * @param localTestIssuersAllowed whether a development licence allows an http issuer on this machine
     */
    public LoginSystemCheck(String product, Supplier<Optional<LoginConnection>> active, IssuerFetch fetch,
                            boolean localTestIssuersAllowed, Clock clock) {
        this.product = Objects.requireNonNull(product, "product");
        this.active = Objects.requireNonNull(active, "active");
        this.signIn = new OidcTestSignIn(fetch, clock);
        this.localTestIssuersAllowed = localTestIssuersAllowed;
    }

    @Override
    public String name() {
        return "login-system";
    }

    @Override
    public Verdict check() {
        Optional<LoginConnection> c = active.get();
        if (c.isEmpty()) return Verdict.problems(MonitoringCode.MON_LOGIN_NO_CONNECTION, Map.of("product", product));
        TestReport r = signIn.run(c.get().issuer(), c.get().apiAudience(), TestSignIn.issuerOnly(),
                localTestIssuersAllowed);
        if (r.passed()) return Verdict.working();
        return Verdict.down(r.failure(), Map.of("issuer", c.get().issuer().toString(),
                "reason", r.reason() == null ? "-" : r.reason()));
    }
}
