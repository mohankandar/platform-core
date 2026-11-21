package com.documentplatform.notifications.approval;

import java.util.regex.Pattern;

/** The shapes of the ids and short texts this package accepts. Refusals never repeat the value. */
final class Ids {

    /** User ids and request type ids: the same shape as the notification ids. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    static final int NAME_MAX = 200;
    static final int REASON_MAX = 2000;

    private Ids() {
    }

    static String id(String value, String what) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " is not valid");
        }
        return value;
    }

    /** Trimmed, 1 to 200 characters, no control or zero-width characters. */
    static String name(String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > NAME_MAX || v.codePoints().anyMatch(Ids::unsafe)) {
            throw new IllegalArgumentException("Display name is not valid");
        }
        return v;
    }

    /** A trimmed reason: at most 2000 characters; line breaks and tabs allowed, other control characters not. */
    static boolean reasonOk(String trimmed) {
        return trimmed.length() <= REASON_MAX
                && trimmed.codePoints().allMatch(c -> c == '\n' || c == '\r' || c == '\t' || !unsafe(c));
    }

    private static boolean unsafe(int c) {
        return Character.isISOControl(c) || Character.getType(c) == Character.FORMAT;
    }
}
