package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.ActionAudience;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed, single-recipient, expiring tokens for one-click links.
 *
 * <p>A token is {@code base64url(payload) "." base64url(HMAC-SHA256)}, about 100 characters, and fits in a link path.
 * The payload holds only: a format version, the action (approve or decline), whether it is for a person or a channel,
 * the request id, the expiry (epoch seconds) and the first 16 bytes of a SHA-256 hash of the recipient's user id (or
 * the channel's destination id). No secret, no name, no address and no document data. The signature also covers the
 * tenant id, so a token works only for its own tenant.
 *
 * <p>The key is read through the {@link SecretResolver} on every use (so a replaced key is picked up without a
 * restart; links made with the old key stop working) and is never kept, logged or put in an exception. It must be at
 * least 32 characters (for example {@code openssl rand -base64 48}).
 *
 * <p>A token is not a login: the app checks it for the person signed in ({@link Approvals#open}), and the decision
 * needs a POST from the app's page.
 */
public final class ActionTokens {

    /** The shortest key accepted, in characters. */
    public static final int MIN_KEY_LENGTH = 32;

    private static final byte VERSION = 1;
    private static final int PAYLOAD_LENGTH = 1 + 1 + 1 + 16 + 8 + 16;
    private static final int MAC_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 200;
    private static final byte[] CONTEXT = "platform-notifications/approval-link/1".getBytes(StandardCharsets.UTF_8);
    private static final byte PERSON = 1;
    private static final byte CHANNEL = 2;
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();

    private final SecretResolver secrets;
    private final SecretRef keyRef;

    public ActionTokens(SecretResolver secrets, SecretRef keyRef) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.keyRef = Objects.requireNonNull(keyRef, "keyRef");
    }

    /**
     * A token for one action on one request, for one audience, until {@code expiresAt}.
     *
     * @throws ApprovalKeyException if the key cannot be read
     */
    public String mint(TenantId tenant, UUID request, ApprovalAction action, ActionAudience audience,
                       Instant expiresAt) {
        ByteBuffer b = ByteBuffer.allocate(PAYLOAD_LENGTH);
        b.put(VERSION);
        b.put((byte) (action.ordinal() + 1));
        b.put(audience instanceof ActionAudience.Person ? PERSON : CHANNEL);
        b.putLong(request.getMostSignificantBits()).putLong(request.getLeastSignificantBits());
        b.putLong(expiresAt.getEpochSecond());
        b.put(audienceHash(audience));
        byte[] payload = b.array();
        return ENCODE.encodeToString(payload) + "." + ENCODE.encodeToString(mac(tenant, payload));
    }

    /**
     * Reads a token if it is well formed and its signature is right for this tenant. Says nothing about expiry or
     * recipient: see {@link Claims}.
     *
     * @return empty for a tampered, malformed or foreign token
     * @throws ApprovalKeyException if the key cannot be read
     */
    public Optional<Claims> read(TenantId tenant, String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        byte[] payload;
        byte[] mac;
        try {
            payload = DECODE.decode(token.substring(0, dot));
            mac = DECODE.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        if (payload.length != PAYLOAD_LENGTH || mac.length != MAC_LENGTH || payload[0] != VERSION) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac, mac(tenant, payload))) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(payload);
        b.get();
        int action = b.get();
        byte kind = b.get();
        if (action < 1 || action > ApprovalAction.values().length || (kind != PERSON && kind != CHANNEL)) {
            return Optional.empty();
        }
        UUID request = new UUID(b.getLong(), b.getLong());
        Instant expiresAt = Instant.ofEpochSecond(b.getLong());
        byte[] hash = new byte[16];
        b.get(hash);
        return Optional.of(new Claims(request, ApprovalAction.values()[action - 1], kind == CHANNEL, hash, expiresAt));
    }

    private byte[] mac(TenantId tenant, byte[] payload) {
        Optional<Secret> secret;
        try {
            secret = secrets.resolve(keyRef);
        } catch (RuntimeException storeDown) {
            throw new ApprovalKeyException(FailureCode.SECRET_UNAVAILABLE);
        }
        if (secret.isEmpty()) {
            throw new ApprovalKeyException(FailureCode.ACTION_KEY_NOT_FOUND);
        }
        byte[] key = secret.get().reveal().getBytes(StandardCharsets.UTF_8);
        try {
            if (secret.get().reveal().length() < MIN_KEY_LENGTH) {
                throw new ApprovalKeyException(FailureCode.ACTION_KEY_INVALID);
            }
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(CONTEXT);
            hmac.update((byte) 0x1f);
            hmac.update(tenant.value().getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 0x1f);
            return hmac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java runtime");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] audienceHash(ActionAudience audience) {
        String value = switch (audience) {
            case ActionAudience.Person p -> "person\u001f" + p.userId();
            case ActionAudience.Channel c -> "channel\u001f" + c.destination().value();
        };
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)),
                    16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime");
        }
    }

    /**
     * What a correctly signed token says.
     *
     * @param forChannel true for a Teams or Slack link (anyone signed in who holds the approver role); false for a
     *                   person's e-mail link
     */
    public record Claims(UUID request, ApprovalAction action, boolean forChannel, byte[] audienceHash,
                         Instant expiresAt) {

        public Claims {
            audienceHash = audienceHash.clone();
        }

        /** Whether the token was made for this person (always true for a channel's token). */
        public boolean isFor(String userId) {
            return forChannel || MessageDigest.isEqual(audienceHash,
                    ActionTokens.audienceHash(new ActionAudience.Person(userId)));
        }

        public boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "Claims[" + request + ", " + action + (forChannel ? ", channel" : ", person") + "]";
        }
    }
}
