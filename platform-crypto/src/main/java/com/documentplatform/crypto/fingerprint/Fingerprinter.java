package com.documentplatform.crypto.fingerprint;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Keyed fingerprints of sensitive values, for exact-match search without storing the value readably (case fields
 * rule 6). A fingerprint is HMAC-SHA256, computed <b>inside the key service</b> with the customer's own fingerprint
 * key, of:
 *
 * <pre>"platform-crypto/fp/1" NUL context NUL canonical(value)</pre>
 *
 * where {@code canonical} is Unicode NFC, trimmed, runs of white space as one space, lower case; so matching ignores
 * capitals and extra spaces. Give it the field's normalised value (from platform-fields) and a context such as the
 * field key, so equal values in different fields do not match each other. The same value gives the same fingerprint
 * for the same customer and context; another customer's fingerprint of it is unrelated.
 *
 * <p>Text form: {@code f<version>.<base64url>}, for example {@code f1.Qm9…}, where the version is the fingerprint
 * key's. Store {@link #fingerprint} with the value; search with every term of {@link #searchTerms}, which covers all
 * usable key versions, so a rotated fingerprint key does not lose matches until the values are fingerprinted again.
 * Thread-safe; logs nothing; nothing it throws holds the value.
 */
public final class Fingerprinter {

    private static final Pattern CONTEXT = Pattern.compile("[A-Za-z0-9_.:/@-]{1,200}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final byte[] DOMAIN = "platform-crypto/fp/1".getBytes(StandardCharsets.US_ASCII);

    private final KeyService keys;

    public Fingerprinter(KeyService keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** The fingerprint to store, made with the latest version of the customer's fingerprint key. */
    public Fingerprint fingerprint(CustomerId customer, String context, String value) {
        int version = keys.fingerprintKey(customer).latest();
        return of(customer, version, context, value);
    }

    /** The fingerprints to search for: one per usable version of the fingerprint key, the latest first. */
    public List<Fingerprint> searchTerms(CustomerId customer, String context, String value) {
        KeyService.KeyVersions v = keys.fingerprintKey(customer);
        List<Fingerprint> out = new ArrayList<>();
        for (int version = v.latest(); version >= v.lowestUsable(); version--) {
            out.add(of(customer, version, context, value));
        }
        return List.copyOf(out);
    }

    /** The fingerprint with one given version of the customer's fingerprint key. */
    public Fingerprint of(CustomerId customer, int version, String context, String value) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(value, "value");
        if (context == null || !CONTEXT.matcher(context).matches()) {
            throw new IllegalArgumentException("A context is 1 to 200 of A-Z a-z 0-9 _ . : / @ -");
        }
        byte[] c = context.getBytes(StandardCharsets.US_ASCII);
        byte[] v = canonical(value).getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[DOMAIN.length + 1 + c.length + 1 + v.length];
        System.arraycopy(DOMAIN, 0, input, 0, DOMAIN.length);
        System.arraycopy(c, 0, input, DOMAIN.length + 1, c.length);
        System.arraycopy(v, 0, input, DOMAIN.length + 2 + c.length, v.length);
        try {
            byte[] mac = keys.fingerprint(customer, version, input);
            return new Fingerprint(version, Base64.getUrlEncoder().withoutPadding().encodeToString(mac));
        } finally {
            Arrays.fill(input, (byte) 0);
            Arrays.fill(v, (byte) 0);
        }
    }

    /** NFC, trimmed, white space runs as one space, lower case (locale-neutral). */
    public static String canonical(String value) {
        String n = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        return SPACES.matcher(n).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /**
     * One fingerprint: the fingerprint key version and the HMAC in Base64url. Not secret on its own, but it stands
     * for a sensitive value, so {@code toString()} shows the version only; use {@link #text()} to store it.
     */
    public record Fingerprint(int version, String mac) {
        public Fingerprint {
            Objects.requireNonNull(mac, "mac");
        }

        /** The stored form, {@code f<version>.<mac>}. */
        public String text() {
            return "f" + version + "." + mac;
        }

        /** Reads the stored form. */
        public static Fingerprint parse(String text) {
            if (text == null || !text.matches("f[1-9][0-9]{0,8}\\.[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("Not a fingerprint");
            }
            int dot = text.indexOf('.');
            return new Fingerprint(Integer.parseInt(text.substring(1, dot)), text.substring(dot + 1));
        }

        @Override
        public String toString() {
            return "Fingerprint[version " + version + "]";
        }
    }
}
