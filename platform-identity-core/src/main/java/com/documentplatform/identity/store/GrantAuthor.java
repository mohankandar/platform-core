package com.documentplatform.identity.store;

import java.util.Objects;

/**
 * Who changes group grants, for the grant history ({@code idn_grant_history}).
 *
 * @param source     {@link ChangeSource#SETUP} or {@link ChangeSource#APP}
 * @param changedBy  the person (the app's user ID) or {@code setup}; never a token or claim
 * @param setupRunId the setup run's ID when the source is SETUP; null for the app
 */
public record GrantAuthor(ChangeSource source, String changedBy, String setupRunId) {

    /** {@code changed_by} written by the old {@link GroupGrantStore} methods, which do not say who. */
    public static final String UNNAMED_APP_USER = "app";

    public GrantAuthor {
        Objects.requireNonNull(source, "source");
        if (source == ChangeSource.MIGRATION) throw new IllegalArgumentException("grants change through SETUP or APP");
        if (changedBy == null || changedBy.isBlank() || changedBy.length() > 200) {
            throw new IllegalArgumentException("changedBy must be 1 to 200 characters");
        }
        if (source == ChangeSource.SETUP && (setupRunId == null || setupRunId.isBlank())) {
            throw new IllegalArgumentException("a setup change names its setup run");
        }
        if (source == ChangeSource.APP && setupRunId != null) {
            throw new IllegalArgumentException("only setup changes name a setup run");
        }
    }

    /** A change made in the app by this person. */
    public static GrantAuthor app(String changedBy) {
        return new GrantAuthor(ChangeSource.APP, changedBy, null);
    }

    /** A change made by a setup run. */
    public static GrantAuthor setup(String changedBy, String setupRunId) {
        return new GrantAuthor(ChangeSource.SETUP, changedBy, setupRunId);
    }
}
