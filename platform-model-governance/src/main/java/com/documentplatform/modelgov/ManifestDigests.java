package com.documentplatform.modelgov;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * An optional adapter: a model's digest from a model server or registry that speaks the common OCI distribution
 * API ({@code HEAD /v2/<name>/manifests/<reference>}, the digest in the {@code Docker-Content-Digest} header). Many
 * local model servers and registries store models this way; no particular one is assumed. Redirects are not followed.
 */
public final class ManifestDigests {

    private final URI base;
    private final HttpClient http;
    private final Duration timeout;

    /** @param base the server's address, for example {@code http://models.internal:5000} */
    public ManifestDigests(URI base, Duration timeout) {
        this.base = Objects.requireNonNull(base, "base");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    /** The manifest digest of {@code name:reference}, or empty when the server does not know it. */
    public Optional<ModelDigest> digest(String name, String reference) throws IOException {
        URI u = base.resolve("/v2/" + path(name) + "/manifests/" + URLEncoder.encode(reference, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(u).timeout(timeout)
                .header("Accept", "application/vnd.oci.image.manifest.v1+json, "
                        + "application/vnd.docker.distribution.manifest.v2+json")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 404) return Optional.empty();
            if (r.statusCode() != 200) throw new IOException("manifest answer " + r.statusCode());
            return r.headers().firstValue("Docker-Content-Digest").map(ModelDigest::parse);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String path(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(part, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
