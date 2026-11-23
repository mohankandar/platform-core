package com.documentplatform.monitoring.testkit;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * A made-up HTTP service's health address on 127.0.0.1 ({@code /health}), with switches: the status it answers,
 * slow answers, and down (the port closed).
 */
public final class MockHttpService implements AutoCloseable {

    private HttpServer server;
    private final int port;
    private volatile int status = 200;
    private volatile Duration delay = Duration.ZERO;

    private MockHttpService(HttpServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    public static MockHttpService start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            MockHttpService m = new MockHttpService(s);
            m.serve(s);
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void serve(HttpServer s) {
        s.createContext("/health", x -> {
            try {
                if (!delay.isZero()) Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.sendResponseHeaders(status, -1);
            x.close();
        });
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
    }

    public URI health() {
        return URI.create("http://127.0.0.1:" + port + "/health");
    }

    public MockHttpService answering(int status) {
        this.status = status;
        return this;
    }

    public MockHttpService slow(Duration d) {
        this.delay = d;
        return this;
    }

    /** Closes the port: the service can no longer be reached. */
    public MockHttpService down() {
        if (server != null) server.stop(0);
        server = null;
        return this;
    }

    @Override
    public void close() {
        down();
    }
}
