package com.documentplatform.setup;

import com.documentplatform.identity.jdbc.LicenceSigningKey;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.signin.LocalTestIssuers;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code setup identity rollback-login-connection} (P056, LV-23): the previous ACTIVE login connection becomes ACTIVE
 * again, with the setup login only, for when nobody can sign in to the app any more. The same step as the app's
 * rollback; drafts are left alone. Without {@code --apply} it shows what it would do and changes nothing.
 */
final class SetupRollback {

    /** The start of {@code changed_by} of the history rows this writes; setup treats them as changes in the app. */
    static final String CHANGED_BY = "setup rollback";

    private final Connection c;
    private final Clock clock;
    private final Supplier<LicenceBuild> build;
    private final String runId;

    /** @param c a connection in the caller's open transaction */
    SetupRollback(Connection c, Clock clock, Supplier<LicenceBuild> build, String runId) {
        this.c = c;
        this.clock = clock;
        this.build = build;
        this.runId = runId;
    }

    /** Writes in the caller's transaction when {@code apply}. */
    void run(String tenant, String product, boolean apply, PrintStream out) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_advisory_xact_lock(?)")) {
            ps.setLong(1, SetupRun.LOCK_KEY);
            ps.execute();
        }
        requireCustomer(tenant);
        PostgresLoginConnectionStore store = PostgresLoginConnectionStore.onConnection(c);
        Optional<LoginConnection> active = store.active(tenant, product);
        LoginConnection previous = store.rollbackCandidate(tenant, product).orElseThrow(() -> new SetupException(
                SetupRefusal.SETUP_NO_PREVIOUS_CONNECTION, "No earlier active login connection of " + tenant + " for "
                + product + " is stored."));
        if (!"https".equals(previous.issuer().getScheme())
                && LocalTestIssuers.check(previous.issuer(), developmentLicence(tenant, product)).isPresent()) {
            throw new SetupException(SetupRefusal.SETUP_HTTP_ISSUER_NOT_ALLOWED, "The earlier login connection's "
                    + "issuer is an http address.");
        }
        out.println("Active now:  " + active.map(SetupRollback::line).orElse("none"));
        out.println("Goes back to: " + line(previous));
        if (!apply) {
            out.println("Shown only: nothing was changed. Run again with --apply to do it.");
            return;
        }
        LoginConnection on = store.rollback(tenant, product, ChangeSource.SETUP, CHANGED_BY + ", run " + runId)
                .orElseThrow(() -> new SetupException(SetupRefusal.SETUP_NO_PREVIOUS_CONNECTION, "No earlier active "
                        + "login connection of " + tenant + " for " + product + " is stored."));
        out.println("Done: " + line(on) + " is active. Restart the app if it reads its login connection only at start.");
    }

    private static String line(LoginConnection c) {
        return "login connection " + c.connectionId() + " (version " + c.configurationVersion() + "): provider "
                + c.providerId() + ", issuer " + c.issuer() + ", API audience " + c.apiAudience();
    }

    private void requireCustomer(String tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from idn_customer where tenant_id = ?")) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SetupException(SetupRefusal.SETUP_OTHER_CUSTOMER_INSTALLED, "The database holds no "
                            + "customer " + tenant + ".");
                }
            }
        }
    }

    private boolean developmentLicence(String tenant, String product) {
        var joined = Connections.joined(c);
        LicenceBuild b = build.get();
        return LicenceSigningKey.of(new LicenceService(new PostgresLicenceStore(joined), new LicenceVerifier(b.keys()),
                new ClockGuard(clock, ClockGuard.DEFAULT_TOLERANCE, new PostgresLastSeenStore(joined), b.builtAt()),
                new PostgresUsageLedger(joined), product, tenant), b.keys()).signedByDevelopmentKey();
    }
}
