package com.documentplatform.errors;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps secrets and content out of errors, logs and bundles. A detail named like a secret ({@code password},
 * {@code secret}, {@code token}, {@code key}, {@code credential}, …) loses its value, and so does any value that looks
 * like a JWT, a PEM block, a {@code password=…} pair, an {@code Authorization} value, the user part of a
 * URL ({@code scheme://user:password@host}) or a long run of key-like characters. Names and addresses of things (a secret path, a key alias, a server address) stay.
 */
public final class SafeText {

    /** What a removed value is replaced with. */
    public static final String REMOVED = "[removed]";

    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|credential|private|apikey|api-key|api_key|signature|cookie"
                    + "|authorization|bearer|(^|[._-])key($|[._-])|keystore-pass|passphrase).*");
    /** A name that ends in "-secret"/"-ref"/"reference" holds a reference (a name), never the secret itself. */
    private static final Pattern REFERENCE_NAME = Pattern.compile("(?i).*(secret-ref|secretref|reference|-ref|_ref|"
            + "password-secret|secret-name|key-alias|keyalias|key-prefix|key-id|keyid|alias|path|file|location)$");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern PEM = Pattern.compile("(?s)-----BEGIN [A-Z ]+-----.*?(-----END [A-Z ]+-----|$)");
    private static final Pattern PAIR = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|client_secret)\\s*[=:]\\s*\\S+");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    /**
     * The user part of a URL ({@code scheme://user:password@host}, also {@code jdbc:postgresql://…}). Greedy up to the
     * last {@code @} of the authority, so a password holding an {@code @} goes whole; a path, query or fragment ends it.
     */
    private static final Pattern URL_USER = Pattern.compile("://[^/?#\\s]*@");
    private static final Pattern KEYLIKE = Pattern.compile("[A-Za-z0-9+/_=-]{40,}");

    private SafeText() {
    }

    /** Whether a property or detail name says its value is a secret (not a reference to one). */
    public static boolean secretName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (REFERENCE_NAME.matcher(n).matches()) return false;
        return SECRET_NAME.matcher(n).matches();
    }

    /** The value with every secret-looking part removed. Null stays null. */
    public static String scrub(String value) {
        if (value == null) return null;
        String v = PEM.matcher(value).replaceAll(REMOVED);
        v = JWT.matcher(v).replaceAll(REMOVED);
        v = PAIR.matcher(v).replaceAll(m -> m.group(1) + "=" + REMOVED);
        v = AUTH.matcher(v).replaceAll(m -> m.group(1) + " " + REMOVED);
        v = URL_USER.matcher(v).replaceAll(Matcher.quoteReplacement("://" + REMOVED + "@"));
        v = KEYLIKE.matcher(v).replaceAll(m -> keyLike(m.group()) ? REMOVED : m.group());
        return v;
    }

    /** The value for a named detail or property: removed when the name says secret, else scrubbed. */
    public static String forName(String name, String value) {
        if (value == null) return null;
        return secretName(name) ? REMOVED : scrub(value);
    }

    /** Upper and lower case and digits mixed in 40+ characters: a key, not a word or a hex hash. */
    private static boolean keyLike(String s) {
        boolean upper = false, lower = false, digit = false;
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
        }
        return upper && lower && digit;
    }
}
