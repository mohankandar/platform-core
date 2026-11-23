package com.documentplatform.monitoring;

import java.time.Duration;

/**
 * One self-check of something the app depends on: a database, the key service, a login system, a folder, another
 * service. {@link #check()} runs it once and says what it found; it may throw or hang, and is then DOWN with
 * {@code MON_CHECK_FAILED} or {@code MON_CHECK_TIMEOUT} ({@link CheckRunner}).
 *
 * <p>An app adds its own by implementing this interface (and, in Spring, making it a bean).
 */
public interface DependencyCheck {

    /** A short unique name, for example {@code database} or {@code ocr-service}. */
    String name();

    /** The group the check belongs to, for example {@code platform}, {@code integrations} or the app's name. */
    default String group() {
        return "platform";
    }

    /** Whether the app refuses to start while this check is DOWN (and is not ready while it is DOWN). */
    default boolean requiredAtStartup() {
        return false;
    }

    /** How long one run may take before it counts as DOWN ({@code MON_CHECK_TIMEOUT}). */
    default Duration timeout() {
        return Duration.ofSeconds(10);
    }

    /** How often the scheduler runs it. */
    default Duration every() {
        return Duration.ofSeconds(60);
    }

    /** One run. Never put a secret, token or content into the verdict's details. */
    Verdict check() throws Exception;
}
