package com.documentplatform.eraselog.spring;

import java.util.List;

/** The erase log settings are not usable; every problem is listed. */
final class EraseLogConfigException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public EraseLogConfigException(List<String> problems) {
        super(com.documentplatform.eraselog.EraseLogError.ERASE_LOG_SETTINGS_INVALID.name() + ": the erase log settings are not valid: " + String.join("; ", problems),
                null, false, true);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return com.documentplatform.eraselog.EraseLogError.ERASE_LOG_SETTINGS_INVALID;
    }
}
