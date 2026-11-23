package com.documentplatform.errors;

/**
 * An exception that carries its {@link PlatformCode}, so a caller (a monitoring check, an app's error handler) can
 * report the exact code, cause and fix instead of the exception's text.
 */
public interface HasPlatformCode {
    PlatformCode platformCode();
}
