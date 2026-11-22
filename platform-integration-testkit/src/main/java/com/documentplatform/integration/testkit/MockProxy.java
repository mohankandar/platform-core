package com.documentplatform.integration.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test HTTP proxy: answers {@code CONNECT host:port} (https) by opening a tunnel, and forwards plain-http requests
 * in absolute form (one request per connection); refuses both with 403 when {@link #refuse(boolean)} is on; records
 * the {@code host:port} targets asked for. For tests only.
 */
public final class MockProxy implements AutoCloseable {

    private final ServerSocket socket;
    private final List<String> targets = new CopyOnWriteArrayList<>();
    private volatile boolean refuse;
    private final Thread acceptor;

    private MockProxy() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::accept, "mock-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public static MockProxy start() {
        try {
            return new MockProxy();
        } catch (IOException e) {
            throw new IllegalStateException("mock proxy could not start", e);
        }
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** The {@code host:port} of every CONNECT received. */
    public List<String> targets() {
        return List.copyOf(targets);
    }

    public MockProxy refuse(boolean on) {
        refuse = on;
        return this;
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread t = new Thread(() -> serve(client), "mock-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            String first = head.lines().findFirst().orElse("");
            String[] parts = first.split(" ");
            if (parts.length < 3) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            if (!parts[0].equals("CONNECT")) {
                forward(head, parts, client);
                return;
            }
            targets.add(parts[1]);
            if (refuse) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            int colon = parts[1].lastIndexOf(':');
            try (Socket upstream = new Socket(parts[1].substring(0, colon),
                    Integer.parseInt(parts[1].substring(colon + 1)))) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread back = new Thread(() -> pump(upstream, client), "mock-proxy-back");
                back.setDaemon(true);
                back.start();
                pump(client, upstream);
                back.join(5000);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // the connection ends
        }
    }

    /** Plain http: {@code GET http://host:port/path} → the request in origin form, one request per connection. */
    private void forward(String head, String[] parts, Socket client) throws IOException, InterruptedException {
        java.net.URI target = java.net.URI.create(parts[1]);
        int port = target.getPort() < 0 ? 80 : target.getPort();
        targets.add(target.getHost() + ":" + port);
        OutputStream out = client.getOutputStream();
        if (refuse || !"http".equalsIgnoreCase(target.getScheme())) {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return;
        }
        StringBuilder rewritten = new StringBuilder();
        rewritten.append(parts[0]).append(' ').append(target.getRawPath() == null || target.getRawPath().isEmpty()
                        ? "/" : target.getRawPath())
                .append(target.getRawQuery() == null ? "" : "?" + target.getRawQuery()).append(' ').append(parts[2])
                .append("\r\n");
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("connection:") || lower.startsWith("proxy-connection:")
                    || lower.startsWith("proxy-authorization:")) {
                continue;
            }
            rewritten.append(line).append("\r\n");
        }
        rewritten.append("Connection: close\r\n\r\n");
        try (Socket upstream = new Socket(target.getHost(), port)) {
            upstream.getOutputStream().write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
            upstream.getOutputStream().flush();
            Thread up = new Thread(() -> pump(client, upstream), "mock-proxy-up");
            up.setDaemon(true);
            up.start();
            pump(upstream, client);
            up.join(1000);
        }
    }

    private static void pump(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
            to.shutdownOutput();
        } catch (IOException e) {
            // one side closed
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        int state = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            state = switch (state) {
                case 0, 2 -> c == '\r' ? state + 1 : 0;
                case 1 -> c == '\n' ? 2 : 0;
                case 3 -> c == '\n' ? 4 : 0;
                default -> 0;
            };
            if (state == 4 || b.size() > 16384) break;
        }
        return b.toString(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
