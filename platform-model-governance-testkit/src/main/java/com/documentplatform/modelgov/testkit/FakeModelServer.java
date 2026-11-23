package com.documentplatform.modelgov.testkit;

import com.documentplatform.modelgov.ModelDigest;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake model server on 127.0.0.1 that answers the OCI distribution manifest call
 * ({@code HEAD|GET /v2/<name>/manifests/<reference>}) with a {@code Docker-Content-Digest} header, for
 * {@code ManifestDigests}. Models are added with {@link #put}; {@link #failWith} makes every call answer that status;
 * {@link #redirectTo} answers 307 to another address (never to be followed). Records the paths asked for.
 */
public final class FakeModelServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, ModelDigest> models = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int failStatus;
    private volatile String redirect;

    private FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v2/", ex -> {
            try (ex) {
                String path = ex.getRequestURI().getRawPath();
                requests.add(ex.getRequestMethod() + " " + path);
                if (redirect != null) {
                    ex.getResponseHeaders().add("Location", redirect);
                    ex.sendResponseHeaders(307, -1);
                    return;
                }
                if (failStatus != 0) {
                    ex.sendResponseHeaders(failStatus, -1);
                    return;
                }
                int m = path.lastIndexOf("/manifests/");
                ModelDigest d = m < 0 ? null : models.get(path.substring(4, m) + ":" + path.substring(m + 11));
                if (d == null) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                ex.getResponseHeaders().add("Docker-Content-Digest", d.toString());
                ex.getResponseHeaders().add("Content-Type", "application/vnd.oci.image.manifest.v1+json");
                ex.sendResponseHeaders(200, -1);
            }
        });
        server.start();
    }

    public static FakeModelServer start() throws IOException {
        return new FakeModelServer();
    }

    public URI address() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Serves {@code name:reference} with this digest. {@code name} as in the path, for example {@code library/x}. */
    public FakeModelServer put(String name, String reference, ModelDigest digest) {
        models.put(name + ":" + reference, digest);
        return this;
    }

    public FakeModelServer failWith(int status) {
        failStatus = status;
        return this;
    }

    public FakeModelServer redirectTo(String location) {
        redirect = location;
        return this;
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
