package com.documentplatform.outbound;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * What is shown of a client certificate: its subject, issuer, serial number, SHA-256 fingerprint and validity. Never the
 * private key or the file. All of it is public (it is in the certificate every server sees).
 *
 * @param subject  the subject, RFC 2253 form ({@code CN=client.example,O=Example})
 * @param issuer   the issuer, RFC 2253 form
 * @param serial   the serial number in lower-case hex
 * @param sha256   the SHA-256 fingerprint of the certificate (DER) in lower-case hex, 64 digits
 * @param validFrom not valid before
 * @param validTo   not valid after
 */
public record CertificateInfo(String subject, String issuer, String serial, String sha256, Instant validFrom,
                              Instant validTo) {

    public CertificateInfo {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validTo, "validTo");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("A SHA-256 fingerprint is 64 hex digits");
    }

    public static CertificateInfo of(X509Certificate c) {
        try {
            return new CertificateInfo(c.getSubjectX500Principal().getName(), c.getIssuerX500Principal().getName(),
                    hex(c.getSerialNumber()), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(c.getEncoded())), c.getNotBefore().toInstant(), c.getNotAfter().toInstant());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Valid at {@code now} (not before {@code validFrom}, not after {@code validTo}). */
    public boolean validAt(Instant now) {
        return !now.isBefore(validFrom) && !now.isAfter(validTo);
    }

    private static String hex(BigInteger serial) {
        return serial.toString(16);
    }
}
