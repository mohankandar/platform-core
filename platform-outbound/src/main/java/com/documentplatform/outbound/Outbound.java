package com.documentplatform.outbound;

import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.auth.AuthCache;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.TunnelRefusedException;
import org.apache.hc.client5.http.impl.auth.BasicAuthCache;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Every outbound HTTP call of the platform (the integrations' destination calls, token requests, health and
 * callbacks; the identity test sign-in's issuer fetch) goes through here, under the installation's rules: https only
 * (plain http only where internal addresses are allowed), the address guard before each call, the outbound proxy (with its Basic sign-in when set), the private
 * certificate authority added to the system's, redirects never followed, timeouts, and the answer read only up to a
 * limit. Failures are codes; no address, header or body ever reaches an exception text.
 *
 * <p><b>Connection pinning:</b> the connection goes to the addresses the address guard checked for this call, never
 * to a fresh lookup (the HTTP client, Apache HttpClient 5, resolves names only through a resolver that hands it the
 * checked addresses). Host name verification and SNI stay on the original name. So a name that answers a public
 * address to the check and an internal or metadata address a moment later (DNS rebinding) is not followed. Through the
 * proxy, the connection goes to the proxy; the target is checked before the CONNECT, as before.
 *
 * <p><b>Client certificate (mutual TLS):</b> a call may carry a {@link ClientCertificate}; the TLS handshake then
 * offers it. Each certificate has its own client (its own connections), so a connection made with one certificate is
 * never reused with another. A destination refusing it (a TLS alert) gives {@link OutboundCode#OUT_CREDENTIAL_REFUSED}.
 *
 * <p><b>Proxy sign-in:</b> the proxy's user and password (read from the secret store at every call, by reference)
 * are sent preemptively as {@code Proxy-Authorization: Basic …} on the CONNECT of an https call and on plain-http
 * requests through the proxy; they never reach the destination. A proxy answering 407 gives
 * {@link OutboundCode#OUT_PROXY_SIGN_IN_REFUSED}. The library never sets JVM properties.
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
    public static final class Failure extends Exception implements com.documentplatform.errors.HasPlatformCode {
        private final OutboundCode code;

        public Failure(OutboundCode code) {
            super(code.hasMessage() ? code.name() + ": " + code.message() : code.name(), null, false, false);
            this.code = code;
        }

        public OutboundCode code() {
            return code;
        }

        /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
            return code;
        }
    }

    /** Headers the HTTP layer sets itself; a caller giving one is refused ({@code OUT_VALUE_NOT_ALLOWED}). */
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade",
            "transfer-encoding", "te", "trailer", "keep-alive", "proxy-authorization", "proxy-connection");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,256}");
    private static final Set<String> CERTIFICATE_ALERTS = Set.of("bad_certificate", "certificate_required",
            "unknown_ca", "certificate_unknown", "certificate_expired", "certificate_revoked", "access_denied",
            "unsupported_certificate");
    private static final int MAX_CLIENTS = 64;

    /** The addresses checked for the call running on this thread: host (normalised) → addresses. */
    private static final ThreadLocal<Map<String, InetAddress[]>> PINNED = ThreadLocal.withInitial(HashMap::new);

    private record ClientKey(boolean proxied, Duration connectTimeout, String certificate) {
    }

    private record Client(CloseableHttpClient http, PoolingHttpClientConnectionManager connections, SSLContext ssl) {
    }

    private final Installation installation;
    private final AddressGuard guard;
    private final SecretResolver secrets;
    private final List<java.security.cert.X509Certificate> privateCas;
    private final SSLContext ssl;
    private final Map<ClientKey, Client> clients = new ConcurrentHashMap<>();

    /** For an installation whose proxy (if any) needs no sign-in. */
    public Outbound(Installation installation) {
        this(installation, new AddressGuard(installation.allowInternalAddresses()), null);
    }

    /** @param secrets reads the proxy password; required when the installation's proxy has sign-in */
    public Outbound(Installation installation, SecretResolver secrets) {
        this(installation, new AddressGuard(installation.allowInternalAddresses()), secrets);
    }

    public Outbound(Installation installation, AddressGuard guard) {
        this(installation, guard, null);
    }

    public Outbound(Installation installation, AddressGuard guard, SecretResolver secrets) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.guard = Objects.requireNonNull(guard, "guard");
        if (installation.proxy() != null && installation.proxy().signIn() && secrets == null) {
            throw new IllegalArgumentException("The proxy has sign-in: a SecretResolver is needed to read its password");
        }
        this.secrets = secrets;
        this.privateCas = List.copyOf(installation.privateCaCertificates());
        this.ssl = TrustBundle.context(privateCas);
    }

    public Installation installation() {
        return installation;
    }

    /**
     * Sends one request and reads the answer up to {@code maxResponseBytes}.
     *
     * @param useProxy whether this call uses the installation's proxy (when one is set)
     */
    public Answer send(Call call, Duration connectTimeout, int maxResponseBytes, boolean useProxy) throws Failure {
        return send(call, connectTimeout, maxResponseBytes, useProxy, null);
    }

    /**
     * As {@link #send(Call, Duration, int, boolean)}, offering {@code certificate} in the TLS handshake (mutual TLS)
     * when it is not null.
     */
    public Answer send(Call call, Duration connectTimeout, int maxResponseBytes, boolean useProxy,
                       ClientCertificate certificate) throws Failure {
        return send(call, connectTimeout, maxResponseBytes, useProxy, certificate, null);
    }

    /**
     * Receives a 2xx answer's body as a stream (a download), instead of it being read into memory. It reads what it
     * wants and keeps its own limit; its own local failures (a full disk) it records itself and returns. An
     * {@link IOException} it throws is a network failure ({@code OUT_TIMEOUT}, {@code OUT_CONNECTION_LOST}).
     */
    @FunctionalInterface
    public interface BodySink {
        /** @return true when it read the body to its end; false when it stopped early (the connection is dropped) */
        boolean read(InputStream body) throws IOException;
    }

    /**
     * As {@link #send(Call, Duration, int, boolean, ClientCertificate)}; a 2xx answer's body goes to {@code sink}
     * (the answer then has an empty body). Any other answer is read up to {@code maxResponseBytes} as usual.
     */
    public Answer send(Call call, Duration connectTimeout, int maxResponseBytes, boolean useProxy,
                       ClientCertificate certificate, BodySink sink) throws Failure {
        URI uri = call.uri();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && installation.allowPlainHttp())) {
            throw new Failure(scheme.equals("http") ? OutboundCode.OUT_ADDRESS_NOT_SECURE
                    : OutboundCode.OUT_CANT_CONNECT);
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new Failure(OutboundCode.OUT_CANT_CONNECT);
        }
        ProxySettings proxy = installation.proxy();
        boolean proxied = useProxy && proxy != null && !proxy.bypasses(uri.getHost());
        AddressGuard.Checked checked = guard.checked(uri.getHost(), proxied);
        if (checked.refused().isPresent()) {
            throw new Failure(checked.refused().get());
        }
        String proxyPassword = proxied && proxy.signIn() ? proxyPassword(proxy) : null;

        ClassicRequestBuilder b = ClassicRequestBuilder.create(call.method()).setUri(uri);
        for (Map.Entry<String, String> h : call.headers()) {
            if (!allowed(h.getKey(), h.getValue())) throw new Failure(OutboundCode.OUT_VALUE_NOT_ALLOWED);
            b.addHeader(h.getKey(), h.getValue());
        }
        if (call.body() != null) b.setEntity(new PublisherEntity(call.body()));

        HttpClientContext context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(call.readTimeout()))
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .setRedirectsEnabled(false)
                .setExpectContinueEnabled(false)
                .setAuthenticationEnabled(proxyPassword != null)
                .build());
        if (proxyPassword != null) {
            // Preemptive: the proxy's Basic sign-in goes on the first CONNECT (or plain-http request), and only there.
            BasicScheme basic = new BasicScheme();
            basic.initPreemptive(new UsernamePasswordCredentials(proxy.user(), proxyPassword.toCharArray()));
            AuthCache cache = new BasicAuthCache();
            cache.put(new HttpHost("http", proxy.host(), proxy.port()), basic);
            context.setAuthCache(cache);
            context.setCredentialsProvider(new BasicCredentialsProvider());
        }

        Client client = client(new ClientKey(proxied, connectTimeout,
                certificate == null ? null : certificate.fingerprint()), certificate);
        Map<String, InetAddress[]> pinned = PINNED.get();
        pinned.clear();
        if (!checked.addresses().isEmpty()) {
            // The first address checked, as the JDK client did: one connection attempt, its own failure reported.
            pinned.put(AddressGuard.normalise(uri.getHost()), new InetAddress[]{checked.addresses().get(0)});
        }
        Answer stoppedEarly = null;
        try (ClassicHttpResponse response = client.http().executeOpen(null, b.build(), context)) {
            int status = response.getCode();
            if (proxied && status == 407) {
                // The proxy itself refused the sign-in (on a plain-http request through it).
                throw new Failure(OutboundCode.OUT_PROXY_SIGN_IN_REFUSED);
            }
            HttpEntity entity = response.getEntity();
            byte[] body = new byte[0];
            if (sink != null && status / 100 == 2) {
                InputStream in = entity == null ? InputStream.nullInputStream() : entity.getContent();
                boolean whole;
                try {
                    whole = sink.read(in);
                } catch (SocketTimeoutException e) {
                    throw new Failure(OutboundCode.OUT_TIMEOUT);
                } catch (IOException e) {
                    throw new Failure(OutboundCode.OUT_CONNECTION_LOST);
                }
                Answer answer = new Answer(status, headers(response), body, false);
                if (!whole) {
                    // Stopped early on purpose: the connection is dropped, never drained. What dropping it throws
                    // (here or when the response closes) is not a failure of the call.
                    stoppedEarly = answer;
                    try {
                        if (in instanceof org.apache.hc.core5.http.io.EofSensorInputStream e) e.abort();
                        else in.close();
                    } catch (IOException ignored) {
                        // dropped
                    }
                    return answer;
                }
                try {
                    in.close();
                } catch (IOException e) {
                    throw new Failure(OutboundCode.OUT_CONNECTION_LOST);
                }
                return answer;
            }
            if (entity != null) {
                try (InputStream in = entity.getContent()) {
                    body = in.readNBytes(maxResponseBytes + 1);
                } catch (SocketTimeoutException e) {
                    throw new Failure(OutboundCode.OUT_TIMEOUT);
                } catch (IOException e) {
                    throw new Failure(OutboundCode.OUT_CONNECTION_LOST);
                }
            }
            boolean tooLarge = body.length > maxResponseBytes;
            return new Answer(status, headers(response), tooLarge ? new byte[0] : body, tooLarge);
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            if (stoppedEarly != null) return stoppedEarly; // closing the dropped connection
            OutboundCode code = classify(e, proxied, certificate != null);
            if ((code == OutboundCode.OUT_CONNECTION_LOST || code == OutboundCode.OUT_TLS_FAILED)
                    && certificate != null && !proxied
                    && scheme.equals("https") && !checked.addresses().isEmpty()
                    && certificateRefused(certificate, uri, checked.addresses().get(0), connectTimeout)) {
                code = OutboundCode.OUT_CREDENTIAL_REFUSED;
            }
            throw new Failure(code);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            throw new Failure(OutboundCode.OUT_CANT_CONNECT);
        } finally {
            pinned.clear();
        }
    }

    private static boolean allowed(String name, String value) {
        if (name == null || value == null || !TOKEN.matcher(name).matches()) return false;
        if (RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) return false;
        }
        return true;
    }

    private static HttpHeaders headers(ClassicHttpResponse response) {
        Map<String, List<String>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : response.getHeaders()) {
            m.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
        }
        return HttpHeaders.of(m, (k, v) -> true);
    }

    /** The proxy password, read now; or a failure code. */
    private String proxyPassword(ProxySettings proxy) throws Failure {
        Optional<Secret> password;
        try {
            password = secrets.resolve(proxy.password());
        } catch (RuntimeException unavailable) {
            throw new Failure(OutboundCode.OUT_SECRET_UNAVAILABLE);
        }
        if (password.isEmpty()) throw new Failure(OutboundCode.OUT_SECRET_NOT_FOUND);
        return password.get().reveal();
    }

    private static OutboundCode classify(IOException e, boolean proxied, boolean withCertificate) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TunnelRefusedException refused) {
                String m = refused.getMessage();
                return m != null && m.contains(" 407") ? OutboundCode.OUT_PROXY_SIGN_IN_REFUSED
                        : OutboundCode.OUT_PROXY_REFUSED;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return withCertificate && certificateAlert(t) ? OutboundCode.OUT_CREDENTIAL_REFUSED
                        : OutboundCode.OUT_TLS_FAILED;
            }
            if (t instanceof ConnectTimeoutException) return OutboundCode.OUT_CONNECT_TIMEOUT;
            if (t instanceof SocketTimeoutException) return OutboundCode.OUT_TIMEOUT;
            if (t instanceof UnknownHostException) return OutboundCode.OUT_HOST_UNKNOWN;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) return OutboundCode.OUT_CANT_CONNECT;
            if (t instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
                return OutboundCode.OUT_TIMEOUT;
            }
        }
        return OutboundCode.OUT_CONNECTION_LOST;
    }

    /**
     * With TLS 1.3 a server checks the client certificate after the handshake has finished, and a server that refuses
     * it often just closes the connection: the client sees a lost connection, not the alert. After such a failure of
     * a call that presented a certificate, one TLS 1.2 handshake to the same checked address tells what happened.
     * There the server sends its own certificate first (checked against our trust before anything else), then asks
     * for the client's. When it asked for ours and the handshake then failed, it refused our certificate (by an alert
     * or by closing). Nothing is sent over this connection.
     */
    private boolean certificateRefused(ClientCertificate certificate, URI uri, InetAddress address,
                                       Duration timeout) {
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        java.util.concurrent.atomic.AtomicBoolean asked = new java.util.concurrent.atomic.AtomicBoolean();
        javax.net.ssl.X509KeyManager offered = certificate.keyManager();
        javax.net.ssl.X509ExtendedKeyManager recording = new javax.net.ssl.X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
                return offered.getClientAliases(keyType, issuers);
            }

            @Override
            public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers,
                                            java.net.Socket socket) {
                asked.set(true);
                return offered.chooseClientAlias(keyType, issuers, socket);
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, java.security.Principal[] issuers,
                                                  javax.net.ssl.SSLEngine engine) {
                asked.set(true);
                return offered.chooseClientAlias(keyType, issuers, null);
            }

            @Override
            public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, java.security.Principal[] issuers,
                                            java.net.Socket socket) {
                return null;
            }

            @Override
            public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
                return offered.getCertificateChain(alias);
            }

            @Override
            public java.security.PrivateKey getPrivateKey(String alias) {
                return offered.getPrivateKey(alias);
            }
        };
        int millis = (int) Math.min(timeout.toMillis(), 30_000);
        try (java.net.Socket plain = new java.net.Socket()) {
            SSLContext probe = SSLContext.getInstance("TLS");
            probe.init(new KeyManager[]{recording}, new TrustManager[]{TrustBundle.trustManager(privateCas)}, null);
            plain.connect(new java.net.InetSocketAddress(address, port), millis);
            plain.setSoTimeout(millis);
            try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) probe.getSocketFactory()
                    .createSocket(plain, uri.getHost(), port, false)) {
                javax.net.ssl.SSLParameters p = s.getSSLParameters();
                p.setProtocols(new String[]{"TLSv1.2"});
                p.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(p);
                s.startHandshake();
                return false; // it took the certificate: the failure was something else
            }
        } catch (IOException e) {
            return asked.get() || certificateAlert(e);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** A TLS alert about the client's certificate ("Received fatal alert: bad_certificate" and the like). */
    private static boolean certificateAlert(Throwable t) {
        for (; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("alert")) {
                for (String a : CERTIFICATE_ALERTS) {
                    if (lower.contains(a)) return true;
                }
            }
        }
        return false;
    }

    private Client client(ClientKey key, ClientCertificate certificate) {
        Client c = clients.get(key);
        if (c != null) return c;
        synchronized (clients) {
            c = clients.get(key);
            if (c != null) return c;
            if (clients.size() >= MAX_CLIENTS) closeAll(); // old certificates' clients do not pile up
            c = build(key, certificate);
            clients.put(key, c);
            return c;
        }
    }

    private Client build(ClientKey key, ClientCertificate certificate) {
        SSLContext context = ssl;
        if (certificate != null) {
            try {
                context = SSLContext.getInstance("TLS");
                context.init(new KeyManager[]{certificate.keyManager()},
                        new TrustManager[]{TrustBundle.trustManager(privateCas)}, null);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("TLS with the client certificate could not be set up");
            }
        }
        ProxySettings proxy = installation.proxy();
        String proxyHost = key.proxied() ? AddressGuard.normalise(proxy.host()) : null;
        DnsResolver pinnedOnly = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                String h = AddressGuard.normalise(host);
                InetAddress[] a = PINNED.get().get(h);
                if (a != null) return a.clone();
                // The installation's own proxy is the only name looked up here.
                if (h.equals(proxyHost)) return SystemDefaultDnsResolver.INSTANCE.resolve(host);
                throw new UnknownHostException("not checked");
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(context)
                        .setHostnameVerifier(new DefaultHostnameVerifier())
                        .build())
                .setDnsResolver(pinnedOnly)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(key.connectTimeout()))
                        .build())
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .build();
        var builder = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .setUserAgent("platform-integration"); // unchanged since P023
        if (key.proxied()) builder.setProxy(new HttpHost("http", proxy.host(), proxy.port()));
        return new Client(builder.build(), connections, context);
    }

    private void closeAll() {
        for (Client c : clients.values()) {
            c.http().close(CloseMode.GRACEFUL);
            c.connections().close(CloseMode.GRACEFUL);
        }
        clients.clear();
    }

    @Override
    public void close() {
        synchronized (clients) {
            closeAll();
        }
    }

    /** A JDK body publisher as an Apache entity: written by pulling the publisher's buffers one at a time. */
    private static final class PublisherEntity extends AbstractHttpEntity {
        private static final Object DONE = new Object();
        private final HttpRequest.BodyPublisher publisher;

        PublisherEntity(HttpRequest.BodyPublisher publisher) {
            super((String) null, null, publisher.contentLength() < 0);
            this.publisher = publisher;
        }

        @Override
        public long getContentLength() {
            return publisher.contentLength();
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public InputStream getContent() {
            throw new UnsupportedOperationException("written only");
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
            AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    subscription.set(s);
                    s.request(1);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    queue.add(item);
                }

                @Override
                public void onError(Throwable t) {
                    queue.add(t);
                }

                @Override
                public void onComplete() {
                    queue.add(DONE);
                }
            });
            try {
                while (true) {
                    Object o = queue.take();
                    if (o == DONE) break;
                    if (o instanceof Throwable) {
                        throw new IOException("the request body could not be read"); // never the cause's text
                    }
                    ByteBuffer buf = (ByteBuffer) o;
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    out.write(bytes);
                    subscription.get().request(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Flow.Subscription s = subscription.get();
                if (s != null) s.cancel();
                throw new InterruptedIOException("interrupted");
            }
            out.flush();
        }

        @Override
        public void close() {
        }
    }
}
