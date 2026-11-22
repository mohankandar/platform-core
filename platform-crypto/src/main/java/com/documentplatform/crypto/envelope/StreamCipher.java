package com.documentplatform.crypto.envelope;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;

import javax.crypto.Cipher;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Envelope encryption of a stream of any size (a document), format {@value #FORMAT} version {@value #VERSION}:
 * AES-256-GCM in chunks of 64 KiB, with one new data key per stream, wrapped by the customer's wrapping key in the
 * key service. The format is described in {@code docs/design/crypto.md}.
 *
 * <pre>
 * header:  magic "PCSTRM01" | version (int) | chunk size (int) | nonce prefix (8)
 * chunk:   type 1 | index (int) | plaintext length (int) | ciphertext + tag
 * final:   type 127 | chunk count (int) | total plaintext length (long) | tag
 * </pre>
 *
 * Each chunk's nonce is the random per-stream prefix plus the chunk index; its associated data binds the format, the
 * customer, the record type, the index and the lengths, so chunks cannot be reordered, dropped, moved to another
 * customer or cut off (the authenticated final record). The wrapped data key travels in {@link EnvelopeInfo}, which
 * the app stores; {@link #rewrap} moves it to the latest key version without touching the stream.
 *
 * <p>{@link #decrypt} writes each chunk as soon as it is authenticated: if it throws, the caller discards what was
 * written. Stream errors are {@link IOException}s; crypto failures are {@link CryptoException}s with their code.
 * Thread-safe; logs nothing.
 */
public final class StreamCipher {

    public static final String FORMAT = "platform-stream";
    public static final int VERSION = 1;

    private static final byte[] MAGIC = "PCSTRM01".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int TAG_BYTES = 16;
    static final int CHUNK_BYTES = 64 * 1024;
    private static final int DATA_RECORD = 1;
    private static final int FINAL_RECORD = 127;

    private final KeyService keys;
    private final SecureRandom random;

    public StreamCipher(KeyService keys) {
        this(keys, new SecureRandom());
    }

    StreamCipher(KeyService keys, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Encrypts all of {@code plaintext} into {@code ciphertext} for the customer. Neither stream is closed. */
    public EnvelopeInfo encrypt(CustomerId customer, InputStream plaintext, OutputStream ciphertext)
            throws IOException {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(ciphertext, "ciphertext");
        byte[] chunk = new byte[CHUNK_BYTES];
        try (KeyService.DataKey dk = keys.newDataKey(customer)) {
            byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
            random.nextBytes(noncePrefix);
            Digesting digesting = new Digesting(ciphertext);
            DataOutputStream out = new DataOutputStream(digesting);
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(CHUNK_BYTES);
            out.write(noncePrefix);

            int index = 0;
            long total = 0;
            while (true) {
                int length = readChunk(plaintext, chunk);
                if (length == 0) {
                    break;
                }
                byte[] clear = Arrays.copyOf(chunk, length);
                byte[] sealed = ValueCipher.gcm(Cipher.ENCRYPT_MODE, dk.key(), nonce(noncePrefix, index),
                        aad(customer, DATA_RECORD, index, length, 0L), clear);
                Arrays.fill(clear, (byte) 0);
                out.writeByte(DATA_RECORD);
                out.writeInt(index);
                out.writeInt(length);
                out.write(sealed);
                Arrays.fill(chunk, 0, length, (byte) 0);
                total += length;
                index++;
            }
            byte[] tag = ValueCipher.gcm(Cipher.ENCRYPT_MODE, dk.key(), nonce(noncePrefix, index),
                    aad(customer, FINAL_RECORD, index, 0, total), new byte[0]);
            out.writeByte(FINAL_RECORD);
            out.writeInt(index);
            out.writeLong(total);
            out.write(tag);
            out.flush();
            return new EnvelopeInfo(FORMAT, VERSION, keys.id(), customer, dk.wrapped(), dk.version(),
                    digesting.count, digesting.sha256());
        } finally {
            Arrays.fill(chunk, (byte) 0);
        }
    }

    /**
     * Decrypts a stream made by {@link #encrypt} for this customer. {@link CryptoError#CRYPTO_WRONG_CUSTOMER} if the
     * envelope is another customer's (before any key is used); {@link CryptoError#CRYPTO_CIPHERTEXT_INVALID} if the
     * stream was changed, reordered or cut short.
     */
    public void decrypt(CustomerId customer, EnvelopeInfo info, InputStream ciphertext, OutputStream plaintext)
            throws IOException {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(plaintext, "plaintext");
        check(customer, info);
        DataInputStream in = new DataInputStream(ciphertext);
        byte[] magic = in.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "the stream is not " + FORMAT);
        }
        int version;
        int chunkSize;
        byte[] noncePrefix;
        try {
            version = in.readInt();
            chunkSize = in.readInt();
            noncePrefix = in.readNBytes(NONCE_PREFIX_BYTES);
        } catch (EOFException e) {
            throw invalid("its header is cut short");
        }
        if (version != VERSION) {
            throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "the stream is " + FORMAT + " version "
                    + version);
        }
        if (chunkSize != CHUNK_BYTES || noncePrefix.length != NONCE_PREFIX_BYTES) {
            throw invalid("its header is not valid");
        }
        byte[] key = keys.unwrap(customer, info.wrappedKey());
        try {
            int expected = 0;
            long total = 0;
            while (true) {
                int type;
                try {
                    type = in.readUnsignedByte();
                } catch (EOFException e) {
                    throw invalid("it ends before its final record");
                }
                if (type == DATA_RECORD) {
                    int index;
                    int length;
                    try {
                        index = in.readInt();
                        length = in.readInt();
                    } catch (EOFException e) {
                        throw invalid("a chunk is cut short");
                    }
                    if (index != expected) {
                        throw invalid("its chunks are out of order");
                    }
                    if (length <= 0 || length > CHUNK_BYTES) {
                        throw invalid("a chunk length is not valid");
                    }
                    byte[] sealed = in.readNBytes(length + TAG_BYTES);
                    if (sealed.length != length + TAG_BYTES) {
                        throw invalid("a chunk is cut short");
                    }
                    byte[] clear = ValueCipher.gcm(Cipher.DECRYPT_MODE, key, nonce(noncePrefix, index),
                            aad(customer, DATA_RECORD, index, length, 0L), sealed);
                    plaintext.write(clear);
                    Arrays.fill(clear, (byte) 0);
                    total += length;
                    expected++;
                } else if (type == FINAL_RECORD) {
                    int count;
                    long declared;
                    try {
                        count = in.readInt();
                        declared = in.readLong();
                    } catch (EOFException e) {
                        throw invalid("its final record is cut short");
                    }
                    if (count != expected || declared != total) {
                        throw invalid("its final record does not match its chunks");
                    }
                    byte[] tag = in.readNBytes(TAG_BYTES);
                    if (tag.length != TAG_BYTES) {
                        throw invalid("its final record is cut short");
                    }
                    ValueCipher.gcm(Cipher.DECRYPT_MODE, key, nonce(noncePrefix, count),
                            aad(customer, FINAL_RECORD, count, 0, declared), tag);
                    if (in.read() != -1) {
                        throw invalid("it has bytes after its final record");
                    }
                    plaintext.flush();
                    return;
                } else {
                    throw invalid("it has an unknown record type");
                }
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * The envelope with its data key wrapped by the latest version of the customer's wrapping key. The stream itself
     * is unchanged; store the new envelope in place of the old one.
     */
    public EnvelopeInfo rewrap(CustomerId customer, EnvelopeInfo info) {
        check(customer, info);
        String again = keys.rewrap(customer, info.wrappedKey());
        return info.withWrappedKey(again, keys.versionOf(again));
    }

    // ---- inside ----

    private void check(CustomerId customer, EnvelopeInfo info) {
        Objects.requireNonNull(info, "info");
        if (!FORMAT.equals(info.format()) || info.formatVersion() != VERSION) {
            throw new CryptoException(CryptoError.CRYPTO_FORMAT_UNKNOWN, "the envelope is not " + FORMAT + " version "
                    + VERSION);
        }
        if (!info.customer().equals(customer)) {
            throw new CryptoException(CryptoError.CRYPTO_WRONG_CUSTOMER, "the envelope belongs to customer "
                    + info.customer() + ", not " + customer);
        }
        if (!info.keyService().equals(keys.id())) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_MISMATCH, "the envelope was made with key"
                    + " service " + ValueCipher.safe(info.keyService()) + "; this one is " + keys.id());
        }
    }

    private static CryptoException invalid(String why) {
        return new CryptoException(CryptoError.CRYPTO_CIPHERTEXT_INVALID, "the encrypted stream is not valid: " + why);
    }

    private static byte[] nonce(byte[] prefix, int index) {
        return ByteBuffer.allocate(12).put(prefix).putInt(index).array();
    }

    private static byte[] aad(CustomerId customer, int type, int index, int length, long total) {
        return (FORMAT + "|v=" + VERSION + "|customer=" + customer + "|type=" + type + "|index=" + index
                + "|length=" + length + "|total=" + total).getBytes(StandardCharsets.UTF_8);
    }

    private static int readChunk(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    private static final class Digesting extends OutputStream {
        private final OutputStream out;
        private final MessageDigest digest;
        private long count;

        Digesting(OutputStream out) {
            this.out = out;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            digest.update((byte) b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            digest.update(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
