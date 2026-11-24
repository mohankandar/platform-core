package com.documentplatform.identity.push;

/**
 * Why a push-API caller was refused. The caller only ever gets a neutral answer ({@link PushCallerResult#NEUTRAL});
 * the log and the IT Admin get the code.
 */
public enum PushRefusal implements com.documentplatform.errors.PlatformCode {
    /** No ACTIVE login connection for this product has the token's issuer (or, for a certificate, the customer). */
    IDN_PUSH_NO_CONNECTION,
    /** The token's audiences do not include the connection's API audience. */
    IDN_PUSH_AUDIENCE,
    /** None of the configured client ID claims is in the token (or no token where one is required). */
    IDN_PUSH_NO_CLIENT_ID,
    /** The client ID has no binding for this product under the connection's customer. */
    IDN_PUSH_NOT_BOUND,
    /** The client's (or the certificate's) binding is switched off. */
    IDN_PUSH_BINDING_OFF,
    /** The bound entity is not ACTIVE (not activated, suspended or no longer in the licence). */
    IDN_PUSH_ENTITY_NOT_ACTIVE,
    /** A configured entity or source claim is in the token and differs from the binding. */
    IDN_PUSH_CLAIM_MISMATCH,
    /** The certificate's fingerprint has no binding for this product (or no certificate where one is required). */
    IDN_PUSH_CERT_NOT_BOUND,
    /** The token and the certificate point to different bindings (another customer, entity or source). */
    IDN_PUSH_TOKEN_AND_CERT_DIFFER,
    /**
     * The token is not a machine's (P045, SEC-7): it carries a claim only a person's sign-in has, or lacks the
     * product's configured machine claim.
     */
    IDN_PUSH_NOT_MACHINE
}
