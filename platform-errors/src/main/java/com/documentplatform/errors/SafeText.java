package com.documentplatform.errors;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps secrets and content out of errors, logs and bundles. A detail named like a secret ({@code password},
 * {@code secret}, {@code token}, {@code key}, {@code credential}, …) loses its value, and so does any value that looks
 * like a JWT, a PEM block, a {@code password=…} pair (plain, JSON, YAML or URL-encoded), an {@code Authorization}
 * value, the user part of a URL ({@code scheme://user:password@host}), a known token form or a long run of key-like
 * characters. Names and addresses of things (a secret path, a key alias, a server address) stay.
 *
 * <p>A backstop (P045, SEC-19), never the first line: what is logged must be safe already. Lower-case hex is kept on
 * purpose, as digests (SHA-256) are part of many messages.
 */
public final class SafeText {

    /** What a removed value is replaced with. */
    public static final String REMOVED = "[removed]";

    /** The words of a name that say its value is a secret (names are split at separators and camelCase first). */
    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "pwd", "pass", "passphrase", "secret",
            "secrets", "token", "tokens", "credential", "credentials", "private", "apikey", "signature", "cookie",
            "authorization", "auth", "bearer", "key", "keys", "assertion", "pfx", "jwt", "sas");
    private static final Pattern SECRET_JOINED = Pattern.compile(
            ".*(password|passwd|passphrase|secret|apikey|accesskey|connectionstring|credential).*");
    /** A name that ends in "-secret"/"-ref"/"reference" holds a reference (a name), never the secret itself. */
    private static final Pattern REFERENCE_NAME = Pattern.compile("(?i).*(secret-ref|secretref|reference|-ref|_ref|"
            + "password-secret|secret-name|key-alias|keyalias|key-prefix|key-id|keyid|alias|path|file|location)$");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^A-Za-z0-9]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern PEM = Pattern.compile("(?s)-----BEGIN [A-Z ]+-----.*?(-----END [A-Z ]+-----|$)");
    /**
     * name, optional closing quote, = or : (or %3D), then a quoted value whole, or every word up to a separator, the
     * next {@code name=} or the end of the line.
     */
    private static final Pattern PAIR = Pattern.compile("(?i)([\\w.-]*(?:password|passwd|passphrase|pwd|secret|token|"
            + "credential|api[_-]?key|access[_-]?key|private[_-]?key|signing[_-]?key|\\bpass|\\bsig)[\\w-]*)"
            + "(\\\\?[\"']?\\s*(?:=|:(?!//)|%3D)\\s*)"
            + "(\\\\?\"[^\"\\r\\n]*\"?|'[^'\\r\\n]*'?|[^\\s&;,}\\]]+(?:[ \\t]+(?![\\w.-]+\\s*[=:])[^\\s&;,}\\]]+)*)");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)\\b((?:proxy-)?authorization)\\s*[:=]\\s*[^\\r\\n]+");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic|Digest|Token)\\s+[A-Za-z0-9._~+/=-]{8,}");
    /**
     * The user part of a URL ({@code scheme://user:password@host}, also {@code jdbc:postgresql://…}). Greedy up to the
     * last {@code @} of the authority, so a password holding an {@code @} goes whole; a path, query or fragment ends it.
     */
    private static final Pattern URL_USER = Pattern.compile("://[^/?#\\s]*@");
    /** The same with a password that holds a slash (what follows the colon is not a port number). */
    private static final Pattern URL_USER_SLASH = Pattern.compile(
            "://[^/?#\\s@:]*:(?!\\d{1,5}(?:[/?#\\s]|$))[^\\s\"'?#]*@");
    private static final Pattern ORACLE_USER = Pattern.compile("(?i)(jdbc:oracle:[a-z]+:)[^\\s@/]+/[^\\s@]+@");
    private static final Pattern KNOWN_TOKEN = Pattern.compile("\\b(?:hv[sbr]\\.|s\\.(?=[A-Za-z0-9]{20})|gh[pousr]_|"
            + "xox[abprs]-|GOCSPX-)[A-Za-z0-9_-]{10,}");
    private static final Pattern KEYLIKE = Pattern.compile("[A-Za-z0-9+/_=-]{40,}");

    private SafeText() {
    }

    /** Whether a property or detail name says its value is a secret (not a reference to one). */
    public static boolean secretName(String name) {
        if (name == null) return false;
        if (REFERENCE_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches()) return false;
        StringBuilder joined = new StringBuilder();
        for (String w : CAMEL.split(name)) {
            String word = w.toLowerCase(Locale.ROOT);
            if (SECRET_WORDS.contains(word)) return true;
            joined.append(word);
        }
        return SECRET_JOINED.matcher(joined).matches();
    }

    /** The value with every secret-looking part removed. Null stays null. */
    public static String scrub(String value) {
        if (value == null) return null;
        String v = PEM.matcher(value).replaceAll(REMOVED);
        v = JWT.matcher(v).replaceAll(REMOVED);
        v = ORACLE_USER.matcher(v).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + REMOVED + "@"));
        v = URL_USER.matcher(v).replaceAll(Matcher.quoteReplacement("://" + REMOVED + "@"));
        v = URL_USER_SLASH.matcher(v).replaceAll(Matcher.quoteReplacement("://" + REMOVED + "@"));
        v = AUTH_HEADER.matcher(v).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + ": " + REMOVED));
        v = PAIR.matcher(v).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "=" + REMOVED));
        v = AUTH.matcher(v).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + REMOVED));
        v = KNOWN_TOKEN.matcher(v).replaceAll(REMOVED);
        v = KEYLIKE.matcher(v).replaceAll(m -> Matcher.quoteReplacement(keyLike(m.group()) ? REMOVED : m.group()));
        return v;
    }

    /** The value for a named detail or property: removed when the name says secret, else scrubbed. */
    public static String forName(String name, String value) {
        if (value == null) return null;
        return secretName(name) ? REMOVED : scrub(value);
    }

    /** The longest detail a log line or check result carries. */
    static final int MAX_DETAIL = 500;

    /**
     * The value for one line of a log (P045, SEC-20): control characters and line separators become {@code ?}, so a
     * detail can never start a line of its own, and it is cut at {@value #MAX_DETAIL} characters.
     */
    public static String oneLine(String value) {
        if (value == null) return null;
        StringBuilder b = new StringBuilder(Math.min(value.length(), MAX_DETAIL));
        for (int i = 0; i < value.length() && b.length() < MAX_DETAIL; i++) {
            char c = value.charAt(i);
            b.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? '?' : c);
        }
        return value.length() > MAX_DETAIL ? b + "…" : b.toString();
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
