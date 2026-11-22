package com.documentplatform.eraselog.spring;

import java.util.List;

/** The erase log settings are not usable; every problem is listed. */
public final class EraseLogConfigException extends RuntimeException {

    private final List<String> problems;

    public EraseLogConfigException(List<String> problems) {
        super("ERASE_LOG_SETTINGS_INVALID: the erase log settings are not valid: " + String.join("; ", problems),
                null, false, true);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
