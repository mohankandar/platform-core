package com.documentplatform.identity.store;

import com.documentplatform.identity.IdentityConnectionStatus;

import java.time.Instant;

/** One row of a login connection's history ({@code idn_login_connection_history}). */
public record ConnectionChange(long configurationVersion, IdentityConnectionStatus status, ChangeSource source,
                               String changedBy, Instant changedAt) {
}
