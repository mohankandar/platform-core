package com.documentplatform.identity.store;

import com.documentplatform.identity.signin.TestReport;

import java.util.Objects;

/**
 * The result of a login-connection step ({@link LoginConnections}). The app audits every call, refused or not, from
 * it (who, the customer and product, the result code, the connection ID and version). Messages are neutral: they name
 * IDs, versions and fields, never a token or a secret.
 *
 * @param connection the connection after the call (for a refusal, the one concerned when known); else null
 * @param test       the test sign-in's report, for {@code validate}; else null
 * @param detail     for IDN_VALUES_INVALID, which field and why (never its value); else null
 */
public record ConnectionOutcome(Result result, LoginConnection connection, TestReport test, String detail) {

    public enum Result {
        /** A new DRAFT: a copy of the ACTIVE connection, or the first connection. */
        DRAFT_CREATED(false),
        /** The draft has the new values; it is DRAFT again (a VALIDATED one must be tested again). */
        DRAFT_UPDATED(false),
        /** The draft already had these values; nothing changed. */
        UNCHANGED(false),
        /** The test sign-in passed: the draft is VALIDATED and may be activated. */
        VALIDATED(false),
        /** The draft is ACTIVE; the connection ACTIVE before is DISABLED. */
        ACTIVATED(false),
        /** The previous ACTIVE version is ACTIVE again, as a new version. */
        ROLLED_BACK(false),
        /** The draft was dropped (DISABLED, kept in the history). */
        DRAFT_DISCARDED(false),
        /** The test sign-in failed ({@link #test()} names the check); the draft is (again) DRAFT. */
        IDN_TEST_FAILED(true),
        /** The customer already has a DRAFT or VALIDATED connection for the product (at most one). */
        IDN_DRAFT_EXISTS(true),
        /** The version given is not the stored one: someone changed the connection first. Reload and try again. */
        IDN_VERSION_CONFLICT(true),
        /** No such connection for this customer and product. */
        IDN_CONNECTION_UNKNOWN(true),
        /** The connection is not a DRAFT or VALIDATED one (it is ACTIVE or DISABLED). */
        IDN_NOT_A_DRAFT(true),
        /** Only a VALIDATED connection can be activated: run the test sign-in first. */
        IDN_NOT_VALIDATED(true),
        /** There is no previous ACTIVE version to go back to. */
        IDN_NO_PREVIOUS_CONNECTION(true),
        /**
         * An {@code http} issuer: allowed only on {@code localhost}, {@code 127.0.0.1} or {@code [::1]} under a
         * licence signed by a development key.
         */
        IDN_HTTP_ISSUER_NOT_ALLOWED(true),
        /** There is no ACTIVE connection to copy: give the first connection's values. */
        IDN_VALUES_REQUIRED(true),
        /** A value is missing or not valid (the message names the field). */
        IDN_VALUES_INVALID(true),
        /** The setup command has not stored the customer yet. */
        IDN_CUSTOMER_UNKNOWN(true);

        private final boolean refused;

        Result(boolean refused) {
            this.refused = refused;
        }

        public boolean refused() {
            return refused;
        }
    }

    public ConnectionOutcome {
        Objects.requireNonNull(result, "result");
    }

    static ConnectionOutcome of(Result result, LoginConnection connection) {
        return new ConnectionOutcome(result, connection, null, null);
    }

    public boolean refused() {
        return result.refused();
    }

    /** A neutral message for the person and the log. */
    public String message() {
        String which = connection == null ? "the login connection"
                : "login connection " + connection.connectionId() + " (version " + connection.configurationVersion() + ")";
        return switch (result) {
            case DRAFT_CREATED -> "Draft " + which + " created.";
            case DRAFT_UPDATED -> "Draft " + which + " saved; run the test sign-in before activating it.";
            case UNCHANGED -> "Draft " + which + " already has these values.";
            case VALIDATED -> "The test sign-in passed; " + which + " can be activated.";
            case ACTIVATED -> which + " is now the active login connection.";
            case ROLLED_BACK -> "The previous login connection is active again: " + which + ".";
            case DRAFT_DISCARDED -> "Draft " + which + " discarded.";
            case IDN_TEST_FAILED -> "The test sign-in failed (" + (test == null ? "-" : test.failure()) + "): "
                    + (test == null ? "" : test.message());
            case IDN_DRAFT_EXISTS -> "There is already a draft login connection for this product; edit or discard it.";
            case IDN_VERSION_CONFLICT -> "Someone changed " + which + " first; reload it and try again.";
            case IDN_CONNECTION_UNKNOWN -> "No such login connection.";
            case IDN_NOT_A_DRAFT -> which + " is not a draft.";
            case IDN_NOT_VALIDATED -> which + " has not passed the test sign-in; run it first.";
            case IDN_NO_PREVIOUS_CONNECTION -> "There is no previous login connection to go back to.";
            case IDN_HTTP_ISSUER_NOT_ALLOWED -> "An http issuer is allowed only for a local test login server "
                    + "(localhost, 127.0.0.1 or [::1]) under a development licence; use the https issuer address.";
            case IDN_VALUES_REQUIRED -> "There is no active login connection to copy; give the connection's values.";
            case IDN_VALUES_INVALID -> "A value of the login connection is missing or not valid"
                    + (detail == null ? "." : ": " + detail + ".");
            case IDN_CUSTOMER_UNKNOWN -> "The customer is not set up in this installation; run setup with the licence.";
        };
    }
}
