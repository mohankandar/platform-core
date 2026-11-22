package com.documentplatform.integration.result;

/**
 * The class of one attempt's result. The app maps it to its own workflow (retry schedule, "Waiting for you", IT queue);
 * the engine never retries.
 */
public enum Outcome {
    /** Every step answered as expected. */
    SENT,
    /** The destination already had it. Treat as done. */
    ALREADY_RECEIVED,
    /** The destination refused the request with a code (fix the data or the set-up, then send again). */
    REFUSED,
    /** The destination or its token address refused the sign-in, or the credential is unusable. */
    SIGN_IN_FAILED,
    /** No answer: address unknown, connection refused, TLS failure, timeout, address blocked, proxy refused. */
    CANT_CONNECT,
    /** The destination is busy: wait (Retry-After when sent), send less. No attempt used. */
    BUSY,
    /** The destination is down or failing: check its health, then pause or retry on the app's schedule. */
    OUTAGE,
    /** A 2xx answer that does not match what the configuration expects. */
    UNCLEAR,
    /** Nothing was sent: the integration is off, invalid, or a secret or value is missing. */
    NOT_CONFIGURED;

    /** Whether the app should count this as a used attempt, unless a rule said otherwise. */
    public boolean usesAttemptByDefault() {
        return this != BUSY;
    }
}
