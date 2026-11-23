package com.documentplatform.integration.callback;

/** Where a callback is in the outbox. */
public enum CallbackState {
    /** Waiting for its (next) attempt. */
    PENDING,
    /** Claimed by one sender until its lease ends (a lease that ran out means that sender stopped). */
    SENDING,
    /** The receiver took it. Final. */
    SENT,
    /** Refused by the receiver, or retries ran out: listed for the app, which can resend it. */
    GAVE_UP
}
