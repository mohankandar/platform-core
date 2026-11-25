package com.documentplatform.crypto.envelope;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.Unwrapped;

import javax.crypto.Cipher;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The record envelope ("case envelope"): all sensitive values of one record sealed under <b>one</b> data key, so
 * opening a record, however many values it has, is one call to the key service, and no plaintext key is ever kept in
 * memory beyond the one operation.
 *
 * <p>Two texts are stored:
 * <ul>
 *   <li>the record's <b>envelope</b>, {@code pck1.<base64url>}, once per record:
 *       {@code version (1) | customer UUID (16) | key service id (1 + n) | wrapped data key (2 + n)};</li>
 *   <li>one <b>sealed item</b> per value, {@code pci1.<base64url>}: {@code version (1) | nonce (12) | ciphertext + tag}.</li>
 * </ul>
 * Each item is AES-256-GCM with its own random nonce and the associated data
 * {@code platform-crypto/record/1|customer=<uuid>|keys=<id>|record=<record context>|item=<item context>}, so an item
 * cannot be moved to another customer, record or item (field, position). The wrapped key is not part of the associated
 * data, so {@link #rewrap} moves a record to the latest key version without touching any item.
 *
 * <p>Use: {@link #newKey} for a new record (one call), {@link #openKey} to read or add items of a stored record (one
 * call), then {@link RecordKey#close()} at once, which wipes the plaintext key. Thread-safe; logs nothing.
 */
public final class RecordCipher {

    public static final String ENVELOPE_PREFIX = "pck1.";
    public static final String ITEM_PREFIX = "pci1.";

    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final Pattern CONTEXT = Pattern.compile("[A-Za-z0-9_.:/@-]{1,200}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final KeyService keys;
    private final SecureRandom random;

    public RecordCipher(KeyService keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.random = new SecureRandom();
    }

    /** A new data key for a new record (one key service call). */
    public RecordKey newKey(CustomerId customer, String recordContext) {
        Objects.requireNonNull(customer, "customer");
        checkContext(recordContext, "record");
        KeyService.DataKey dk = keys.newDataKey(customer);
        byte[] key = Arrays.copyOf(dk.key(), dk.key().length);
        dk.close();
        return new RecordKey(customer, keys.id(), recordContext, key, encodeEnvelope(customer.value(), keys.id(),
                dk.wrapped()));
    }

    /**
     * The data key of a stored record (one key service call), to open its items or seal more under the same key.
     * Refused with {@link CryptoError#CRYPTO_WRONG_CUSTOMER} (before any key is used) if the envelope is another
     * customer's.
     */
    public RecordKey openKey(CustomerId customer, String recordContext, String envelope) {
        Objects.requireNonNull(customer, "customer");
        checkContext(recordContext, "record");
        Envelope e = decodeEnvelope(envelope);
        check(customer, e);
        byte[] key = keys.unwrap(customer, e.wrapped());
        return new RecordKey(customer, e.keyService(), recordContext, key, envelope);
    }

    /** One stored record whose key {@link #openKeys} opens. {@code toString()} does not show the envelope. */
    public record StoredKey(String recordContext, String envelope) {
        @Override
        public String toString() {
            return "StoredKey[" + recordContext + "]";
        }
    }

    /**
     * One record's result of {@link #openKeys}: its key, or the exception {@link #openKey} throws for it.
     * {@link #close()} wipes the key.
     */
    public static final class OpenedKey implements AutoCloseable {
        private final RecordKey key;
        private final CryptoException failure;

        private OpenedKey(RecordKey key, CryptoException failure) {
            this.key = key;
            this.failure = failure;
        }

        /** The record's key; throws the record's failure if it did not open. */
        public RecordKey key() {
            if (failure != null) {
                throw failure;
            }
            return key;
        }

        /** Why the record's key did not open, or null when it did. */
        public CryptoException failure() {
            return failure;
        }

        @Override
        public void close() {
            if (key != null) {
                key.close();
            }
        }

        @Override
        public String toString() {
            return failure == null ? "OpenedKey[ok]" : "OpenedKey[" + failure.code() + "]";
        }
    }

    /**
     * The data keys of many stored records of one customer, one result per record in the order given: one
     * {@link KeyService#unwrapAll} (none when no envelope can be used). An envelope that is damaged, another customer's
     * or another key service's, and a key that does not open, fail as that record; a failure of the key service itself
     * throws. At most {@link KeyService#MAX_BATCH} records. The caller closes every result.
     */
    public List<OpenedKey> openKeys(CustomerId customer, List<StoredKey> records) {
        Objects.requireNonNull(customer, "customer");
        List<StoredKey> all = List.copyOf(records);
        if (all.size() > KeyService.MAX_BATCH) {
            throw new IllegalArgumentException("A batch has at most " + KeyService.MAX_BATCH + " records");
        }
        all.forEach(r -> checkContext(r.recordContext(), "record"));
        OpenedKey[] out = new OpenedKey[all.size()];
        List<Integer> at = new ArrayList<>();
        List<String> wrapped = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            try {
                Envelope e = decodeEnvelope(all.get(i).envelope());
                check(customer, e);
                at.add(i);
                wrapped.add(e.wrapped());
            } catch (CryptoException e) {
                out[i] = new OpenedKey(null, e);
            }
        }
        if (wrapped.isEmpty()) {
            return List.of(out);
        }
        List<Unwrapped> dataKeys = keys.unwrapAll(customer, wrapped);
        if (dataKeys.size() != wrapped.size()) {
            Unwrapped.closeAll(dataKeys);
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "key service " + keys.id()
                    + " did not give one result per item");
        }
        for (int n = 0; n < dataKeys.size(); n++) {
            int i = at.get(n);
            Unwrapped u = dataKeys.get(n);
            // The record key takes the array over and wipes it when closed; check() matched the key service.
            out[i] = u.ok() ? new OpenedKey(new RecordKey(customer, keys.id(), all.get(i).recordContext(), u.key(),
                    all.get(i).envelope()), null) : new OpenedKey(null, u.failure());
        }
        return List.of(out);
    }

    /** The envelope with its data key wrapped by the latest key version; the items stay as they are. */
    public String rewrap(CustomerId customer, String envelope) {
        Envelope e = decodeEnvelope(envelope);
        check(customer, e);
        return encodeEnvelope(e.customer(), e.keyService(), keys.rewrap(customer, e.wrapped()));
    }

    /** The wrapping key version of an envelope. Calls nothing. */
    public int keyVersion(String envelope) {
        return keys.versionOf(decodeEnvelope(envelope).wrapped());
    }

    /** The customer an envelope belongs to. Calls nothing. */
    public static CustomerId customerOf(String envelope) {
        return CustomerId.of(decodeEnvelope(envelope).customer());
    }

    /**
     * One record's plaintext data key, for one operation. {@link #close()} wipes it; {@code toString()} shows
     * neither the key nor the envelope.
     */
    public final class RecordKey implements AutoCloseable {
        private final CustomerId customer;
        private final String keyService;
        private final String recordContext;
        private final byte[] key;
        private final String envelope;
        private volatile boolean closed;

        private RecordKey(CustomerId customer, String keyService, String recordContext, byte[] key, String envelope) {
            this.customer = customer;
            this.keyService = keyService;
            this.recordContext = recordContext;
            this.key = key;
            this.envelope = envelope;
        }

        /** The envelope to store with the record. */
        public String envelope() {
            return envelope;
        }

        /** Seals one item (at most 64 KiB) under the record key. Calls nothing. */
        public String seal(String itemContext, byte[] value) {
            Objects.requireNonNull(value, "value");
            checkContext(itemContext, "item");
            if (value.length > ValueCipher.MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("A sealed item has at most " + ValueCipher.MAX_VALUE_BYTES
                        + " bytes");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] ct = ValueCipher.gcm(Cipher.ENCRYPT_MODE, live(), nonce, aad(itemContext), value);
            ByteBuffer b = ByteBuffer.allocate(1 + NONCE_BYTES + ct.length);
            b.put(VERSION).put(nonce).put(ct);
            return ITEM_PREFIX + B64.encodeToString(b.array());
        }

        public String seal(String itemContext, String value) {
            return seal(itemContext, Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Opens one item sealed under this record key with the same item context. Calls nothing.
         * {@link CryptoError#CRYPTO_CIPHERTEXT_INVALID} if it was changed or belongs to another record or item.
         */
        public byte[] open(String itemContext, String sealed) {
            checkContext(itemContext, "item");
            if (sealed == null || !sealed.startsWith(ITEM_PREFIX)) {
                throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "not a sealed item (" + ITEM_PREFIX
                        + "...)");
            }
            byte[] all;
            try {
                all = B64D.decode(sealed.substring(ITEM_PREFIX.length()));
            } catch (IllegalArgumentException e) {
                throw damaged();
            }
            if (all.length < 1 + NONCE_BYTES + TAG_BYTES) {
                throw damaged();
            }
            if (all[0] != VERSION) {
                throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "a sealed item of an unknown version");
            }
            byte[] nonce = Arrays.copyOfRange(all, 1, 1 + NONCE_BYTES);
            byte[] ct = Arrays.copyOfRange(all, 1 + NONCE_BYTES, all.length);
            return ValueCipher.gcm(Cipher.DECRYPT_MODE, live(), nonce, aad(itemContext), ct);
        }

        public String openText(String itemContext, String sealed) {
            byte[] clear = open(itemContext, sealed);
            try {
                return new String(clear, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        }

        private byte[] live() {
            if (closed) {
                throw new IllegalStateException("The record key is closed");
            }
            return key;
        }

        private byte[] aad(String itemContext) {
            return ("platform-crypto/record/1|customer=" + customer + "|keys=" + keyService + "|record="
                    + recordContext + "|item=" + itemContext).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            closed = true;
            Arrays.fill(key, (byte) 0);
        }

        @Override
        public String toString() {
            return "RecordKey[customer " + customer + "]";
        }
    }

    // ---- inside ----

    private record Envelope(UUID customer, String keyService, String wrapped) {
    }

    private void check(CustomerId customer, Envelope e) {
        if (!e.customer().equals(customer.value())) {
            throw new CryptoException(CryptoError.CRYPTO_WRONG_CUSTOMER, "the record envelope belongs to customer "
                    + e.customer() + ", not " + customer);
        }
        if (!e.keyService().equals(keys.id())) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_MISMATCH, "the record envelope was made with "
                    + "key service " + ValueCipher.safe(e.keyService()) + "; this one is " + keys.id());
        }
    }

    private static String encodeEnvelope(UUID customer, String keyService, String wrapped) {
        byte[] ks = keyService.getBytes(StandardCharsets.US_ASCII);
        byte[] w = wrapped.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(1 + 16 + 1 + ks.length + 2 + w.length);
        b.put(VERSION);
        b.putLong(customer.getMostSignificantBits()).putLong(customer.getLeastSignificantBits());
        b.put((byte) ks.length).put(ks);
        b.putShort((short) w.length).put(w);
        return ENVELOPE_PREFIX + B64.encodeToString(b.array());
    }

    private static Envelope decodeEnvelope(String envelope) {
        if (envelope == null || !envelope.startsWith(ENVELOPE_PREFIX)) {
            throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "not a record envelope (" + ENVELOPE_PREFIX
                    + "...)");
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(B64D.decode(envelope.substring(ENVELOPE_PREFIX.length())));
            if (b.get() != VERSION) {
                throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "a record envelope of an unknown "
                        + "version");
            }
            UUID customer = new UUID(b.getLong(), b.getLong());
            byte[] ks = new byte[b.get() & 0xff];
            b.get(ks);
            byte[] w = new byte[b.getShort() & 0xffff];
            b.get(w);
            if (b.hasRemaining() || w.length == 0) {
                throw damaged();
            }
            return new Envelope(customer, new String(ks, StandardCharsets.US_ASCII),
                    new String(w, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException | BufferUnderflowException e) {
            throw damaged();
        }
    }

    private static void checkContext(String context, String what) {
        if (context == null || !CONTEXT.matcher(context).matches()) {
            throw new IllegalArgumentException("A " + what + " context is 1 to 200 of A-Z a-z 0-9 _ . : / @ -");
        }
    }

    private static CryptoException damaged() {
        return new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the record envelope or item is cut "
                + "short or damaged");
    }
}
