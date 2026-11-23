package com.documentplatform.monitoring;

import com.documentplatform.errors.PlatformCode;

/**
 * The monitoring module's own codes (texts in {@code MonitoringCode.codes.properties}). A check reports a module's
 * own code where there is one (a schema problem, a {@code CRYPTO_*} code, an {@code IDN_TEST_*} code, an
 * {@code INT_*} code); these cover the rest.
 */
public enum MonitoringCode implements PlatformCode {
    MON_WORKING,
    MON_CHECK_FAILED,
    MON_CHECK_TIMEOUT,
    MON_DB_UNREACHABLE,
    MON_DB_LOGIN_REFUSED,
    /** The app's database user owns the platform tables or may create objects: more rights than it needs (P037). */
    MON_DB_USER_TOO_POWERFUL,
    MON_KEY_SERVICE_DOWN,
    MON_SECRET_MISSING,
    MON_SECRET_UNAVAILABLE,
    MON_LOGIN_NO_CONNECTION,
    MON_LICENCE_NOT_STARTED,
    MON_LICENCE_EXPIRING,
    MON_LICENCE_IN_GRACE,
    MON_LICENCE_EXPIRED,
    MON_CHANNEL_SETTINGS_INCOMPLETE,
    MON_CHANNEL_ADDRESS_INVALID,
    MON_CHANNEL_UNREACHABLE,
    MON_PATH_MISSING,
    MON_PATH_NOT_READABLE,
    MON_PATH_NOT_WRITABLE,
    MON_DISK_ALMOST_FULL,
    MON_DISK_FULL,
    MON_HTTP_ADDRESS_INVALID,
    MON_HTTP_UNREACHABLE,
    MON_HTTP_TIMEOUT,
    MON_HTTP_UNEXPECTED_ANSWER,
    MON_BUNDLE_TOO_LARGE
}
