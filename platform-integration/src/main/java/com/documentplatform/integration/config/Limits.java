package com.documentplatform.integration.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Limits and timeouts of one integration. Sizes are checked before anything is sent.
 *
 * @param connectTimeout   to open the connection (1 s – 2 min)
 * @param readTimeout      from sending a request until the answer's headers arrive (1 s – 30 min)
 * @param maxFileBytes     largest document sent (1 byte – 64 GiB)
 * @param maxDocuments     most documents in one delivery (1 – 100 000)
 * @param maxResponseBytes most of an answer that is read (1 KiB – 16 MiB); a larger answer is not read
 * @param maxPackageBytes  largest total of one delivery's documents (1 byte – 1 TiB)
 */
public record Limits(Duration connectTimeout, Duration readTimeout, long maxFileBytes, int maxDocuments,
                     int maxResponseBytes, long maxPackageBytes) {

    public static final long DEFAULT_PACKAGE_BYTES = 2L * 1024 * 1024 * 1024;

    public static final Limits DEFAULT = new Limits(Duration.ofSeconds(10), Duration.ofSeconds(120),
            250L * 1024 * 1024, 100, 1024 * 1024, DEFAULT_PACKAGE_BYTES);

    public Limits {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (connectTimeout.compareTo(Duration.ofSeconds(1)) < 0 || connectTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Connect timeout must be between 1 second and 2 minutes");
        }
        if (readTimeout.compareTo(Duration.ofSeconds(1)) < 0 || readTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Read timeout must be between 1 second and 30 minutes");
        }
        if (maxFileBytes < 1 || maxFileBytes > 64L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("Largest file must be between 1 byte and 64 GiB");
        }
        if (maxDocuments < 1 || maxDocuments > 100_000) {
            throw new IllegalArgumentException("Most documents must be between 1 and 100000");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Largest answer read must be between 1 KiB and 16 MiB");
        }
        if (maxPackageBytes < 1 || maxPackageBytes > 1024L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("Largest package must be between 1 byte and 1 TiB");
        }
    }

    /** With the default package limit. */
    public Limits(Duration connectTimeout, Duration readTimeout, long maxFileBytes, int maxDocuments,
                  int maxResponseBytes) {
        this(connectTimeout, readTimeout, maxFileBytes, maxDocuments, maxResponseBytes, DEFAULT_PACKAGE_BYTES);
    }

    public Limits withReadTimeout(Duration d) {
        return new Limits(connectTimeout, d, maxFileBytes, maxDocuments, maxResponseBytes, maxPackageBytes);
    }

    public Limits withConnectTimeout(Duration d) {
        return new Limits(d, readTimeout, maxFileBytes, maxDocuments, maxResponseBytes, maxPackageBytes);
    }

    public Limits withMaxFileBytes(long n) {
        return new Limits(connectTimeout, readTimeout, n, maxDocuments, maxResponseBytes, maxPackageBytes);
    }

    public Limits withMaxDocuments(int n) {
        return new Limits(connectTimeout, readTimeout, maxFileBytes, n, maxResponseBytes, maxPackageBytes);
    }

    public Limits withMaxResponseBytes(int n) {
        return new Limits(connectTimeout, readTimeout, maxFileBytes, maxDocuments, n, maxPackageBytes);
    }

    public Limits withMaxPackageBytes(long n) {
        return new Limits(connectTimeout, readTimeout, maxFileBytes, maxDocuments, maxResponseBytes, n);
    }
}
