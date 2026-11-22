package com.documentplatform.integration.http;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.safety.AddressGuard;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.safety.ProxySettings;
import com.documentplatform.integration.safety.TrustBundle;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every outbound HTTP call of the integrations (destination calls and token requests) goes through here, under the
 * installation's rules: https only (plain http only where internal addresses are allowed), the address guard before
 * each call, the outbound proxy, the private certificate authority added to the system's, redirects never followed,
 * timeouts, and the answer read only up to a limit. Failures are codes; no address, header or body ever reaches an
 * exception text.
 */
public final class Outbound implements AutoCloseable {

    /** One request. Header values may hold sign-in; they are only put on the wire. */
    public record Call(String method, URI uri, List<Map.Entry<String, String>> headers,
                       HttpRequest.BodyPublisher body, Duration readTimeout) {
        public Call {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(uri, "uri");
            headers = List.copyOf(headers);
            Objects.requireNonNull(readTimeout, "readTimeout");
        }

        @Override
        public String toString() {
            return "Call[" + method + "]"; // never the address or headers
        }
    }

    /** An answer. {@code tooLarge}: the body was larger than the limit and was not kept. */
    public record Answer(int status, HttpHeaders headers, byte[] body, boolean tooLarge) {
        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }

        @Override
        public String toString() {
            return "Answer[" + status + "]";
        }
    }

    /** The call could not be made or got no answer. */
    public static final class Failure extends Exception {
        private final IntegrationCode code;

        public Failure(IntegrationCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        public IntegrationCode code() {
            return code;
        }
    }

    private record ClientKey(boolean proxied, Duration connectTimeout) {
    }

    private final Installation installation;
    private final AddressGuard guard;
    private final SSLContext ssl;
    private final Map<ClientKey, HttpClient> clients = new ConcurrentHashMap<>();

    public Outbound(Installation installation) {
        this(installation, new AddressGuard(installation.allowInternalAddresses()));
    }

    public Outbound(Installation installation, AddressGuard guard) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.ssl = TrustBundle.context(installation.privateCaCertificates());
    }

    public Installation installation() {
        return installation;
    }

    /**
     * Sends one request and reads the answer up to {@code maxResponseBytes}.
     *
     * @param useProxy whether this integration uses the installation's proxy (when one is set)
     */
    public Answer send(Call call, Duration connectTimeout, int maxResponseBytes, boolean useProxy) throws Failure {
        URI uri = call.uri();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(scheme.equals("http") && installation.allowPlainHttp())) {
            throw new Failure(scheme.equals("http") ? IntegrationCode.INT_ADDRESS_NOT_SECURE
                    : IntegrationCode.INT_CANT_CONNECT);
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new Failure(IntegrationCode.INT_CANT_CONNECT);
        }
        ProxySettings proxy = installation.proxy();
        boolean proxied = useProxy && proxy != null && !proxy.bypasses(uri.getHost());
        Optional<IntegrationCode> blocked = guard.check(uri.getHost(), proxied);
        if (blocked.isPresent()) {
            throw new Failure(blocked.get());
        }

        HttpRequest request;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(call.readTimeout())
                    .method(call.method(), call.body() == null ? HttpRequest.BodyPublishers.noBody() : call.body());
            for (Map.Entry<String, String> h : call.headers()) {
                b.header(h.getKey(), h.getValue());
            }
            request = b.build();
        } catch (IllegalArgumentException restrictedOrBad) {
            throw new Failure(IntegrationCode.INT_VALUE_NOT_ALLOWED);
        }

        HttpClient http = clients.computeIfAbsent(new ClientKey(proxied, connectTimeout), this::client);
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw new Failure(IntegrationCode.INT_CONNECT_TIMEOUT);
        } catch (HttpTimeoutException e) {
            throw new Failure(IntegrationCode.INT_TIMEOUT);
        } catch (IOException e) {
            throw new Failure(classify(e, proxied));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(IntegrationCode.INT_TIMEOUT);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new Failure(IntegrationCode.INT_CANT_CONNECT);
        }

        try (InputStream in = response.body()) {
            byte[] body = in.readNBytes(maxResponseBytes + 1);
            boolean tooLarge = body.length > maxResponseBytes;
            return new Answer(response.statusCode(), response.headers(), tooLarge ? new byte[0] : body, tooLarge);
        } catch (HttpTimeoutException e) {
            throw new Failure(IntegrationCode.INT_TIMEOUT);
        } catch (IOException e) {
            throw new Failure(IntegrationCode.INT_CONNECTION_LOST);
        }
    }

    private static IntegrationCode classify(IOException e, boolean proxied) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) return IntegrationCode.INT_TLS_FAILED;
            if (t instanceof UnknownHostException) return IntegrationCode.INT_HOST_UNKNOWN;
            if (t instanceof HttpConnectTimeoutException) return IntegrationCode.INT_CONNECT_TIMEOUT;
            if (t instanceof HttpTimeoutException) return IntegrationCode.INT_TIMEOUT;
        }
        // The JDK client reports a proxy's refusal of CONNECT as an IOException "Unable to tunnel through proxy".
        String m = e.getMessage();
        if (proxied && m != null && m.toLowerCase().contains("tunnel")) return IntegrationCode.INT_PROXY_REFUSED;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) return IntegrationCode.INT_CANT_CONNECT;
        }
        return IntegrationCode.INT_CONNECTION_LOST;
    }

    private HttpClient client(ClientKey key) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(key.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(ssl);
        if (key.proxied()) {
            ProxySettings p = installation.proxy();
            InetSocketAddress at = InetSocketAddress.createUnresolved(p.host(), p.port());
            b.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return p.bypasses(uri.getHost()) ? List.of(Proxy.NO_PROXY) : List.of(new Proxy(Proxy.Type.HTTP, at));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                }
            });
        } else {
            b.proxy(HttpClient.Builder.NO_PROXY);
        }
        return b.build();
    }

    @Override
    public void close() {
        clients.values().forEach(HttpClient::close);
        clients.clear();
    }
}
