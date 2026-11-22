package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyDestruction;
import com.documentplatform.crypto.KeyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key service on HashiCorp Vault's Transit engine. Each customer has two Transit keys of type
 * {@code aes256-gcm96}, never exportable:
 * <ul>
 *   <li>{@code <prefix><customer hex>}: the wrapping key ({@code datakey/plaintext}, {@code decrypt},
 *       {@code rewrap}, {@code keys/.../rotate});</li>
 *   <li>{@code <prefix><customer hex>-fp}: the fingerprint key ({@code hmac}, SHA-256, with an explicit version).</li>
 * </ul>
 * The default prefix is {@value #DEFAULT_PREFIX}, so a policy can name the customers' keys as
 * {@code transit/.../customer-*}. Key creation and rotation are for the set-up step's credentials; the app's runtime
 * policy needs only datakey, decrypt, rewrap, hmac and reading the keys (see {@code docs/design/crypto.md}).
 *
 * <p>Failures: unreachable, TLS, sealed, login refused, permission denied, key missing, key version switched off
 * (below {@code min_decryption_version}: {@link CryptoError#CRYPTO_KEY_DISABLED}), ciphertext invalid. No message
 * holds a key, a wrapped key or a value.
 */
public final class VaultKeyService implements KeyService {

    public static final String ID = "vault-transit";
    public static final String DEFAULT_MOUNT = "transit";
    public static final String DEFAULT_PREFIX = "customer-";
    public static final String FINGERPRINT_SUFFIX = "-fp";

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final Pattern WRAPPED = Pattern.compile("vault:v([1-9][0-9]{0,8}):[A-Za-z0-9+/=]{1,4096}");
    private static final Pattern HMAC = Pattern.compile("vault:v([1-9][0-9]{0,8}):([A-Za-z0-9+/=]{1,128})");

    private final VaultClient vault;
    private final String mount;
    private final String prefix;

    /**
     * @param vault  the client (its login is the app's)
     * @param mount  the Transit mount, normally {@value #DEFAULT_MOUNT}
     * @param prefix the start of every customer key name, normally {@value #DEFAULT_PREFIX}
     */
    public VaultKeyService(VaultClient vault, String mount, String prefix) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.mount = VaultClient.checkMount(mount, "the Transit mount");
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID,
                    "the key prefix is 1 to 48 of a-z 0-9 _ - and starts with a letter or digit");
        }
        this.prefix = prefix;
    }

    public VaultKeyService(VaultClient vault) {
        this(vault, DEFAULT_MOUNT, DEFAULT_PREFIX);
    }

    @Override
    public String id() {
        return ID;
    }

    /** The name of the customer's wrapping key in Transit. */
    public String wrappingKeyName(CustomerId customer) {
        return prefix + Objects.requireNonNull(customer, "customer").hex();
    }

    /** The name of the customer's fingerprint key in Transit. */
    public String fingerprintKeyName(CustomerId customer) {
        return wrappingKeyName(customer) + FINGERPRINT_SUFFIX;
    }

    @Override
    public void createKeys(CustomerId customer) {
        for (String name : new String[]{wrappingKeyName(customer), fingerprintKeyName(customer)}) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "aes256-gcm96");
            body.put("exportable", false);
            body.put("allow_plaintext_backup", false);
            VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/keys/" + name, VaultJson.write(body));
            vault.expectOk(r, "creating key " + name);
        }
    }

    @Override
    public KeyVersions wrappingKey(CustomerId customer) {
        return versions(wrappingKeyName(customer));
    }

    @Override
    public KeyVersions fingerprintKey(CustomerId customer) {
        return versions(fingerprintKeyName(customer));
    }

    @Override
    public DataKey newDataKey(CustomerId customer) {
        String name = wrappingKeyName(customer);
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/datakey/plaintext/" + name,
                VaultJson.write(Map.of("bits", 256)));
        check(r, name, "making a data key with key " + name);
        Map<String, Object> data = data(r, name);
        String wrapped = VaultJson.string(data, "ciphertext").orElse("");
        Matcher m = WRAPPED.matcher(wrapped);
        if (!m.matches()) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the data key reply for key " + name
                    + " has no wrapped key");
        }
        return new DataKey(decode(VaultJson.string(data, "plaintext").orElse(""), name), wrapped,
                Integer.parseInt(m.group(1)));
    }

    @Override
    public byte[] unwrap(CustomerId customer, String wrapped) {
        String name = wrappingKeyName(customer);
        int version = versionOf(wrapped);
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/decrypt/" + name,
                VaultJson.write(Map.of("ciphertext", wrapped)));
        check(r, name, "unwrapping a data key with key " + name + " version " + version);
        byte[] key = decode(VaultJson.string(data(r, name), "plaintext").orElse(""), name);
        if (key.length != 32) {
            java.util.Arrays.fill(key, (byte) 0);
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the wrapped data key for key " + name
                    + " is not a 256-bit key");
        }
        return key;
    }

    @Override
    public String rewrap(CustomerId customer, String wrapped) {
        String name = wrappingKeyName(customer);
        int version = versionOf(wrapped);
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/rewrap/" + name,
                VaultJson.write(Map.of("ciphertext", wrapped)));
        check(r, name, "rewrapping a data key with key " + name + " version " + version);
        String again = VaultJson.string(data(r, name), "ciphertext").orElse("");
        if (!WRAPPED.matcher(again).matches()) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the rewrap reply for key " + name
                    + " has no wrapped key");
        }
        return again;
    }

    @Override
    public int versionOf(String wrapped) {
        Matcher m = wrapped == null ? null : WRAPPED.matcher(wrapped);
        if (m == null || !m.matches()) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID,
                    "the wrapped data key is not a Vault Transit ciphertext");
        }
        return Integer.parseInt(m.group(1));
    }

    @Override
    public int rotate(CustomerId customer) {
        String name = wrappingKeyName(customer);
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/keys/" + name + "/rotate", null);
        check(r, name, "rotating key " + name);
        return versions(name).latest();
    }

    @Override
    public byte[] fingerprint(CustomerId customer, int version, byte[] input) {
        Objects.requireNonNull(input, "input");
        String name = fingerprintKeyName(customer);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Base64.getEncoder().encodeToString(input));
        body.put("key_version", version);
        body.put("algorithm", "sha2-256");
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/hmac/" + name, VaultJson.write(body));
        check(r, name, "a fingerprint with key " + name + " version " + version);
        return mac(VaultJson.string(data(r, name), "hmac").orElse(""), name, version);
    }

    /** One {@code hmac} call with a {@code batch_input} of all inputs. */
    @Override
    public java.util.List<byte[]> fingerprints(CustomerId customer, int version, java.util.List<byte[]> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty()) {
            return java.util.List.of();
        }
        String name = fingerprintKeyName(customer);
        java.util.List<Map<String, Object>> batch = new java.util.ArrayList<>();
        for (byte[] input : inputs) {
            batch.add(Map.of("input", Base64.getEncoder().encodeToString(Objects.requireNonNull(input, "input"))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch_input", batch);
        body.put("key_version", version);
        body.put("algorithm", "sha2-256");
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/hmac/" + name, VaultJson.write(body));
        check(r, name, inputs.size() + " fingerprints with key " + name + " version " + version);
        Object results = data(r, name).get("batch_results");
        if (!(results instanceof java.util.List<?> list) || list.size() != inputs.size()) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the hmac reply for key " + name
                    + " does not hold one result per input");
        }
        java.util.List<byte[]> out = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = item instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            if (VaultJson.string(m, "error").filter(e -> !e.isBlank()).isPresent()) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the key service refused a "
                        + "fingerprint in a batch with key " + name + " version " + version);
            }
            out.add(mac(VaultJson.string(m, "hmac").orElse(""), name, version));
        }
        return out;
    }

    private static byte[] mac(String hmac, String name, int version) {
        Matcher m = HMAC.matcher(hmac);
        if (!m.matches() || Integer.parseInt(m.group(1)) != version) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the hmac reply for key " + name
                    + " is not a fingerprint by version " + version);
        }
        byte[] mac = decode(m.group(2), name);
        if (mac.length != 32) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the hmac reply for key " + name
                    + " is not HMAC-SHA256");
        }
        return mac;
    }

    /**
     * For each of the customer's two keys: reads its versions, allows its deletion
     * ({@code keys/<name>/config deletion_allowed=true}), deletes it ({@code DELETE keys/<name>}), then checks it is
     * gone. Needs {@code update} and {@code delete} on {@code transit/keys/customer-*} (the set-up policy); the runtime
     * policy has only {@code read}, so the first write is refused and nothing is destroyed.
     */
    @Override
    public KeyDestruction destroyCustomerKeys(CustomerId customer) {
        List<KeyDestruction.Key> keys = new ArrayList<>();
        keys.add(destroy(KeyDestruction.Role.WRAPPING, wrappingKeyName(customer)));
        keys.add(destroy(KeyDestruction.Role.FINGERPRINT, fingerprintKeyName(customer)));
        return new KeyDestruction(customer, ID, keys, Instant.now());
    }

    private KeyDestruction.Key destroy(KeyDestruction.Role role, String name) {
        KeyVersions v;
        try {
            v = versions(name);
        } catch (CryptoException e) {
            if (e.code() == CryptoError.CRYPTO_KEY_MISSING) {
                return KeyDestruction.Key.absent(role, name);
            }
            throw e;
        }
        VaultClient.Reply r = vault.call("POST", "/v1/" + mount + "/keys/" + name + "/config",
                VaultJson.write(Map.of("deletion_allowed", true)));
        check(r, name, "allowing deletion of key " + name);
        r = vault.call("DELETE", "/v1/" + mount + "/keys/" + name, null);
        check(r, name, "deleting key " + name);
        r = vault.call("GET", "/v1/" + mount + "/keys/" + name, null);
        if (r.status() != 404) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "key " + name + " is still there after it"
                    + " was deleted (HTTP " + r.status() + ")");
        }
        return KeyDestruction.Key.destroyed(role, name, v.latest());
    }

    @Override
    public Health health() {
        return vault.health(ID);
    }

    @Override
    public String toString() {
        return "VaultKeyService[" + vault.where() + ", mount " + mount + ", prefix " + prefix + "]";
    }

    // ---- inside ----

    private KeyVersions versions(String name) {
        VaultClient.Reply r = vault.call("GET", "/v1/" + mount + "/keys/" + name, null);
        if (r.status() == 404) {
            throw missing(name);
        }
        vault.expectOk(r, "reading key " + name);
        Map<String, Object> data = data(r, name);
        int latest = VaultJson.integer(data, "latest_version").map(Long::intValue).orElse(0);
        int lowest = VaultJson.integer(data, "min_decryption_version").map(Long::intValue).orElse(1);
        if (latest < 1) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "key " + name + " has no versions");
        }
        return new KeyVersions(latest, Math.max(1, Math.min(lowest, latest)));
    }

    /** Maps Transit's 400 answers to their specific codes; everything else as the client does. */
    private void check(VaultClient.Reply r, String name, String what) {
        if (r.ok()) {
            return;
        }
        if (r.status() == 404) {
            throw missing(name);
        }
        if (r.status() == 400) {
            String e = r.error().toLowerCase(Locale.ROOT);
            if (e.contains("not found") || e.contains("no existing key")) {
                throw missing(name);
            }
            if (e.contains("too old") || e.contains("disallowed by policy") || e.contains("disabled")) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_DISABLED, what + ": that key version is switched"
                        + " off (below the key's lowest usable version)");
            }
            if (e.contains("version") && (e.contains("not") || e.contains("invalid")) && what.contains("fingerprint")) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_DISABLED, what + ": that key version cannot be"
                        + " used");
            }
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, what + ": Vault refused the input ("
                    + r.error() + ")");
        }
        throw vault.failure(r, what);
    }

    private static CryptoException missing(String name) {
        return new CryptoException(CryptoError.CRYPTO_KEY_MISSING, "Vault has no Transit key " + name
                + "; the set-up step creates the customer's keys");
    }

    private static Map<String, Object> data(VaultClient.Reply r, String name) {
        return VaultJson.object(r.body(), "data").orElseThrow(() -> new CryptoException(
                CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the reply for key " + name + " has no data"));
    }

    private static byte[] decode(String base64, String name) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "the reply for key " + name
                    + " is not Base64");
        }
    }
}
