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
        byte[] input = input(context, value);
        try {
            byte[] mac = keys.fingerprint(customer, version, input);
            return new Fingerprint(version, Base64.getUrlEncoder().withoutPadding().encodeToString(mac));
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /**
     * One value to fingerprint together with others. {@code toString()} shows the context only.
     *
     * @param context for example the field key
     * @param value   the value (normalised by the caller; canonicalised here)
     */
    public record Input(String context, String value) {
        public Input {
            Objects.requireNonNull(value, "value");
            if (context == null || !CONTEXT.matcher(context).matches()) {
                throw new IllegalArgumentException("A context is 1 to 200 of A-Z a-z 0-9 _ . : / @ -");
            }
        }

        @Override
        public String toString() {
            return "Input[" + context + "]";
        }
    }

    /**
     * The fingerprints of several values with the latest version of the fingerprint key, in order: two key service
     * calls however many values (the key's versions, then one batch). Empty input calls nothing.
     */
    public List<Fingerprint> fingerprintAll(CustomerId customer, List<Input> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        return ofAll(customer, keys.fingerprintKey(customer).latest(), inputs);
    }

    /** The fingerprints of several values with one given key version, in order: one key service call. */
    public List<Fingerprint> ofAll(CustomerId customer, int version, List<Input> inputs) {
        Objects.requireNonNull(customer, "customer");
        if (inputs.isEmpty()) {
            return List.of();
        }
        List<byte[]> raw = new ArrayList<>(inputs.size());
        try {
            for (Input i : inputs) {
                raw.add(input(i.context(), i.value()));
            }
            List<byte[]> macs = keys.fingerprints(customer, version, raw);
            if (macs.size() != inputs.size()) {
                throw new com.documentplatform.crypto.CryptoException(
                        com.documentplatform.crypto.CryptoError.CRYPTO_KEY_SERVICE_ERROR,
                        "the key service gave " + macs.size() + " fingerprints for " + inputs.size() + " values");
            }
            List<Fingerprint> out = new ArrayList<>(macs.size());
            for (byte[] mac : macs) {
                out.add(new Fingerprint(version, Base64.getUrlEncoder().withoutPadding().encodeToString(mac)));
            }
            return List.copyOf(out);
        } finally {
            raw.forEach(b -> Arrays.fill(b, (byte) 0));
        }
    }

    /** The usable versions of the customer's fingerprint key, the latest first (one key service call). */
    public List<Integer> usableVersions(CustomerId customer) {
        KeyService.KeyVersions v = keys.fingerprintKey(customer);
        List<Integer> out = new ArrayList<>();
        for (int version = v.latest(); version >= v.lowestUsable(); version--) {
            out.add(version);
        }
        return List.copyOf(out);
    }

    private static byte[] input(String context, String value) {
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
        Arrays.fill(v, (byte) 0);
        return input;
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
