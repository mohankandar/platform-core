package com.documentplatform.crypto.testkit;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyDestruction;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.Unwrapped;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A key service in memory, for the apps' tests: the same behaviour as the Vault one (per-customer wrapping and
 * fingerprint keys, versions, rotation, a lowest usable version, rewrap) and the same failure codes. The test can
 * make it fail: {@link #down(boolean)} (unreachable), {@link #denied(boolean)} (permission denied). Keys exist only
 * after {@link #createKeys} (or with {@link #createKeysOnDemand()}). Test scope only: its keys live in this JVM.
 */
public final class InMemoryKeyService implements KeyService {

    public static final String ID = "in-memory";

    private static final Pattern WRAPPED = Pattern.compile("mem:v([1-9][0-9]{0,8}):([A-Za-z0-9_-]+)");

    private final SecureRandom random = new SecureRandom();
    private final Map<CustomerId, Keys> wrapping = new ConcurrentHashMap<>();
    private final Map<CustomerId, Keys> fingerprinting = new ConcurrentHashMap<>();
    private final java.util.Set<CustomerId> destroyed = ConcurrentHashMap.newKeySet();
    private volatile boolean down;
    private volatile boolean denied;
    private volatile boolean onDemand;
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    private static final class Keys {
        final List<byte[]> versions = new ArrayList<>();
        int lowestUsable = 1;

        synchronized KeyVersions state() {
            return new KeyVersions(versions.size(), lowestUsable);
        }
    }

    /** Every call the key service would get fails as unreachable while this is on. */
    public InMemoryKeyService down(boolean down) {
        this.down = down;
        return this;
    }

    /** Every call fails with permission denied while this is on. */
    public InMemoryKeyService denied(boolean denied) {
        this.denied = denied;
        return this;
    }

    /** Creates a customer's keys at their first use, so tests need not call {@link #createKeys}. */
    public InMemoryKeyService createKeysOnDemand() {
        this.onDemand = true;
        return this;
    }

    /** Switches off the wrapping key's versions below {@code version} (Vault: {@code min_decryption_version}). */
    public void setLowestUsable(CustomerId customer, int version) {
        Keys k = keys(wrapping, customer, "wrapping");
        synchronized (k) {
            if (version < 1 || version > k.versions.size()) {
                throw new IllegalArgumentException("No such version");
            }
            k.lowestUsable = version;
        }
    }

    /** Adds a version to the customer's fingerprint key (rotating it is rare: stored fingerprints must be redone). */
    public int rotateFingerprintKey(CustomerId customer) {
        return add(keys(fingerprinting, customer, "fingerprint"));
    }

    /** How many calls the key service got (to check what a test caused). */
    public int calls() {
        return calls.get();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void createKeys(CustomerId customer) {
        gate();
        destroyed.remove(customer);
        create(customer);
    }

    @Override
    public KeyVersions wrappingKey(CustomerId customer) {
        gate();
        return keys(wrapping, customer, "wrapping").state();
    }

    @Override
    public KeyVersions fingerprintKey(CustomerId customer) {
        gate();
        return keys(fingerprinting, customer, "fingerprint").state();
    }

    @Override
    public DataKey newDataKey(CustomerId customer) {
        gate();
        Keys k = keys(wrapping, customer, "wrapping");
        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        int version;
        byte[] kek;
        synchronized (k) {
            version = k.versions.size();
            kek = k.versions.get(version - 1);
        }
        return new DataKey(dataKey, wrap(customer, version, kek, dataKey), version);
    }

    @Override
    public byte[] unwrap(CustomerId customer, String wrapped) {
        gate();
        return unwrapped(customer, wrapped);
    }

    /** One call for the whole batch, as Vault Transit's {@code batch_input}. */
    @Override
    public List<Unwrapped> unwrapAll(CustomerId customer, List<String> wrapped) {
        java.util.Objects.requireNonNull(customer, "customer");
        List<String> items = List.copyOf(wrapped);
        if (items.size() > MAX_BATCH) {
            throw new IllegalArgumentException("A batch has at most " + MAX_BATCH + " items");
        }
        if (items.isEmpty()) {
            return List.of();
        }
        gate();
        keys(wrapping, customer, "wrapping");
        List<Unwrapped> out = new ArrayList<>(items.size());
        for (String item : items) {
            try {
                out.add(Unwrapped.opened(unwrapped(customer, item)));
            } catch (CryptoException e) {
                out.add(Unwrapped.failed(e));
            }
        }
        return List.copyOf(out);
    }

    private byte[] unwrapped(CustomerId customer, String wrapped) {
        Keys k = keys(wrapping, customer, "wrapping");
        Matcher m = parse(wrapped);
        int version = Integer.parseInt(m.group(1));
        byte[] kek;
        synchronized (k) {
            if (version > k.versions.size()) {
                throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "no such key version");
            }
            if (version < k.lowestUsable) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_DISABLED, "key version " + version
                        + " is switched off");
            }
            kek = k.versions.get(version - 1);
        }
        byte[] all = Base64.getUrlDecoder().decode(m.group(2));
        if (all.length < 12 + 16) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the wrapped key is cut short");
        }
        ByteBuffer b = ByteBuffer.wrap(all);
        byte[] nonce = new byte[12];
        b.get(nonce);
        byte[] sealed = new byte[b.remaining()];
        b.get(sealed);
        return gcm(Cipher.DECRYPT_MODE, kek, nonce, aad(customer, version), sealed);
    }

    @Override
    public String rewrap(CustomerId customer, String wrapped) {
        byte[] dataKey = unwrap(customer, wrapped);
        try {
            Keys k = keys(wrapping, customer, "wrapping");
            synchronized (k) {
                int latest = k.versions.size();
                return wrap(customer, latest, k.versions.get(latest - 1), dataKey);
            }
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public int versionOf(String wrapped) {
        return Integer.parseInt(parse(wrapped).group(1));
    }

    @Override
    public int rotate(CustomerId customer) {
        gate();
        return add(keys(wrapping, customer, "wrapping"));
    }

    /** One call for the whole batch, as Vault Transit's {@code batch_input}. */
    @Override
    public List<byte[]> fingerprints(CustomerId customer, int version, List<byte[]> inputs) {
        gate();
        List<byte[]> out = new ArrayList<>(inputs.size());
        for (byte[] input : inputs) {
            out.add(mac(customer, version, input));
        }
        return out;
    }

    @Override
    public byte[] fingerprint(CustomerId customer, int version, byte[] input) {
        gate();
        return mac(customer, version, input);
    }

    private byte[] mac(CustomerId customer, int version, byte[] input) {
        Keys k = keys(fingerprinting, customer, "fingerprint");
        byte[] key;
        synchronized (k) {
            if (version < k.lowestUsable || version > k.versions.size()) {
                throw new CryptoException(CryptoError.CRYPTO_KEY_DISABLED, "fingerprint key version " + version
                        + " cannot be used");
            }
            key = k.versions.get(version - 1);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * Removes the customer's two keys with all their versions: afterwards unwrap, rewrap, new data keys and
     * fingerprints give {@link CryptoError#CRYPTO_KEY_MISSING} ("destroyed"), as with Vault. {@link #denied(boolean)}
     * stands for the runtime policy, which cannot destroy (nothing is destroyed then).
     */
    @Override
    public KeyDestruction destroyCustomerKeys(CustomerId customer) {
        gate();
        java.util.Objects.requireNonNull(customer, "customer");
        List<KeyDestruction.Key> keys = new ArrayList<>();
        keys.add(destroy(wrapping, customer, KeyDestruction.Role.WRAPPING, "customer-" + customer.hex()));
        keys.add(destroy(fingerprinting, customer, KeyDestruction.Role.FINGERPRINT, "customer-" + customer.hex()
                + "-fp"));
        destroyed.add(customer);
        return new KeyDestruction(customer, ID, keys, java.time.Instant.now());
    }

    private static KeyDestruction.Key destroy(Map<CustomerId, Keys> map, CustomerId customer, KeyDestruction.Role role,
                                              String name) {
        Keys k = map.remove(customer);
        if (k == null) {
            return KeyDestruction.Key.absent(role, name);
        }
        synchronized (k) {
            int latest = k.versions.size();
            k.versions.forEach(key -> Arrays.fill(key, (byte) 0));
            return KeyDestruction.Key.destroyed(role, name, latest);
        }
    }

    @Override
    public Health health() {
        if (down) {
            return new Health(Health.Status.DOWN, ID, CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE,
                    "the in-memory key service is switched off by the test");
        }
        return new Health(Health.Status.UP, ID, null, "in memory");
    }

    @Override
    public String toString() {
        return "InMemoryKeyService[" + wrapping.size() + " customers]";
    }

    // ---- inside ----

    private void gate() {
        calls.incrementAndGet();
        if (down) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE,
                    "the in-memory key service is switched off by the test");
        }
        if (denied) {
            throw new CryptoException(CryptoError.CRYPTO_PERMISSION_DENIED,
                    "the in-memory key service refuses calls (set by the test)");
        }
    }

    private void create(CustomerId customer) {
        wrapping.computeIfAbsent(customer, c -> first());
        fingerprinting.computeIfAbsent(customer, c -> first());
    }

    private Keys first() {
        Keys k = new Keys();
        add(k);
        return k;
    }

    private int add(Keys k) {
        byte[] key = new byte[32];
        random.nextBytes(key);
        synchronized (k) {
            k.versions.add(key);
            return k.versions.size();
        }
    }

    private Keys keys(Map<CustomerId, Keys> map, CustomerId customer, String kind) {
        java.util.Objects.requireNonNull(customer, "customer");
        if (destroyed.contains(customer)) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_MISSING, "customer " + customer + " has no " + kind
                    + " key: it was destroyed");
        }
        if (onDemand) {
            create(customer);
        }
        Keys k = map.get(customer);
        if (k == null) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_MISSING, "customer " + customer + " has no " + kind
                    + " key");
        }
        return k;
    }

    private String wrap(CustomerId customer, int version, byte[] kek, byte[] dataKey) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] sealed = gcm(Cipher.ENCRYPT_MODE, kek, nonce, aad(customer, version), dataKey);
        byte[] all = ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array();
        return "mem:v" + version + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    private static Matcher parse(String wrapped) {
        Matcher m = wrapped == null ? null : WRAPPED.matcher(wrapped);
        if (m == null || !m.matches()) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "not a wrapped key of this key service");
        }
        return m;
    }

    private static byte[] aad(CustomerId customer, int version) {
        return ("in-memory|" + customer + "|v" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID,
                    "the wrapped key was changed or is another customer's");
        } catch (GeneralSecurityException e) {
            throw new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the wrapped key is not valid");
        }
    }
}
