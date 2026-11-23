package com.documentplatform.integration.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test HTTP proxy: answers {@code CONNECT host:port} (https) by opening a tunnel, and forwards plain-http requests
 * in absolute form, one request per connection; refuses both with 403 when {@link #refuse(boolean)} is on; with
 * {@link #requireBasic(String, String)} it asks for proxy sign-in (407) unless the request carries the expected
 * {@code Proxy-Authorization: Basic …}; records the {@code host:port} targets asked for. For tests only.
 *
 * <p>Plain http: the request (head and exactly its body, by {@code Content-Length} or chunked) is relayed upstream with
 * {@code Connection: close}, and the answer comes back with {@code Connection: close}, so the client never puts this
 * connection back in its pool. (Before P025 the answer was relayed as it came: the client could reuse a connection
 * this proxy was closing, and the next request was lost now and then.)
 */
public final class MockProxy implements AutoCloseable {

    private static final byte[] FORBIDDEN = ("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SIGN_IN = ("HTTP/1.1 407 Proxy Authentication Required\r\n"
            + "Proxy-Authenticate: Basic realm=\"test-proxy\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);

    private final ServerSocket socket;
    private final List<String> targets = new CopyOnWriteArrayList<>();
    private final AtomicInteger signInsRefused = new AtomicInteger();
    private final AtomicInteger signInsAccepted = new AtomicInteger();
    private final AtomicInteger proxyHeadersForwarded = new AtomicInteger();
    private volatile boolean refuse;
    private volatile String expectedAuthorization;
    private final Thread acceptor;
    private volatile boolean closing;

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

    /** The {@code host:port} of every request received (CONNECT and plain http), sign-in refusals included. */
    public List<String> targets() {
        return List.copyOf(targets);
    }

    public MockProxy refuse(boolean on) {
        refuse = on;
        return this;
    }

    /**
     * From now on every request needs {@code Proxy-Authorization: Basic base64(user:password)}; others get 407 with
     * {@code Proxy-Authenticate: Basic}. {@code null} for both switches it off.
     */
    public MockProxy requireBasic(String user, String password) {
        expectedAuthorization = user == null ? null : "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Requests answered 407 (sign-in missing or wrong). */
    public int signInsRefused() {
        return signInsRefused.get();
    }

    /** Requests whose proxy sign-in matched. */
    public int signInsAccepted() {
        return signInsAccepted.get();
    }

    /**
     * Plain-http requests that would have carried a {@code Proxy-*} header to the destination; the proxy strips them,
     * and a test can check that the client never asked for that (always 0 here: they are removed before forwarding).
     */
    public int proxyHeadersForwarded() {
        return proxyHeadersForwarded.get();
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                if (closing) { // accepted while close() ran: never served, so a closed proxy really is closed
                    client.close();
                    return;
                }
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
                forward(head, parts, client, in);
                return;
            }
            targets.add(parts[1]);
            if (refuse) {
                out.write(FORBIDDEN);
                out.flush();
                return;
            }
            if (!signedIn(head)) {
                out.write(SIGN_IN);
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

    /** Whether the request's proxy sign-in is what {@link #requireBasic} asked for (always, when not asked). */
    private boolean signedIn(String head) {
        String expected = expectedAuthorization;
        if (expected == null) return true;
        String given = header(head, "proxy-authorization");
        if (expected.equals(given)) {
            signInsAccepted.incrementAndGet();
            return true;
        }
        signInsRefused.incrementAndGet();
        return false;
    }

    /**
     * Plain http: {@code POST http://host:port/path} → the request in origin form with exactly its body, then the
     * answer with {@code Connection: close}. One request per connection.
     */
    private void forward(String head, String[] parts, Socket client, InputStream in) throws IOException {
        java.net.URI target = java.net.URI.create(parts[1]);
        int port = target.getPort() < 0 ? 80 : target.getPort();
        targets.add(target.getHost() + ":" + port);
        OutputStream out = client.getOutputStream();
        long length = contentLength(head);
        boolean chunked = "chunked".equalsIgnoreCase(header(head, "transfer-encoding"));
        if (refuse || !"http".equalsIgnoreCase(target.getScheme())) {
            refuseAfterBody(in, out, FORBIDDEN, chunked, length);
            return;
        }
        if (!signedIn(head)) {
            refuseAfterBody(in, out, SIGN_IN, chunked, length);
            return;
        }
        StringBuilder rewritten = new StringBuilder();
        rewritten.append(parts[0]).append(' ').append(target.getRawPath() == null || target.getRawPath().isEmpty()
                        ? "/" : target.getRawPath())
                .append(target.getRawQuery() == null ? "" : "?" + target.getRawQuery()).append(' ').append(parts[2])
                .append("\r\n");
        appendHeaders(head, rewritten);
        rewritten.append("Connection: close\r\n\r\n");
        try (Socket upstream = new Socket(target.getHost(), port)) {
            OutputStream up = upstream.getOutputStream();
            up.write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (chunked) {
                relayChunked(in, up);
            } else {
                copy(in, up, length);
            }
            up.flush();
            InputStream down = upstream.getInputStream();
            String answer = readHead(down);
            String[] lines = answer.split("\r\n");
            StringBuilder back = new StringBuilder(lines[0]).append("\r\n");
            appendHeaders(answer, back);
            back.append("Connection: close\r\n\r\n");
            out.write(back.toString().getBytes(StandardCharsets.ISO_8859_1));
            // The destination closes after this one answer (it was asked to): relay until it does.
            down.transferTo(out);
            out.flush();
            client.shutdownOutput();
        }
    }

    /**
     * Reads the request's body before refusing (as a real proxy does), so the client is not cut off while it still
     * sends and sees the refusal, not a lost connection.
     */
    private static void refuseAfterBody(InputStream in, OutputStream out, byte[] answer, boolean chunked, long length)
            throws IOException {
        if (chunked) {
            relayChunked(in, OutputStream.nullOutputStream());
        } else {
            copy(in, OutputStream.nullOutputStream(), length);
        }
        out.write(answer);
        out.flush();
    }

    /** The head's header lines without the hop-by-hop and proxy ones. */
    private void appendHeaders(String head, StringBuilder into) {
        String[] lines = head.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("proxy-authorization:") || lower.startsWith("proxy-connection:")) {
                continue;
            }
            if (lower.startsWith("connection:") || lower.startsWith("keep-alive:")) {
                continue;
            }
            into.append(line).append("\r\n");
        }
    }

    private static String header(String head, String lowerName) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static long contentLength(String head) {
        String v = header(head, "content-length");
        if (v == null) return 0;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[8192];
        long left = length;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("request body ended early");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    /** A chunked body as it is, up to and including the last chunk and the trailer's empty line. */
    private static void relayChunked(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String hex = sizeLine.contains(";") ? sizeLine.substring(0, sizeLine.indexOf(';')) : sizeLine;
            long size = Long.parseLong(hex.trim(), 16);
            if (size == 0) {
                String trailer;
                do {
                    trailer = readLine(in);
                    out.write((trailer + "\r\n").getBytes(StandardCharsets.US_ASCII));
                } while (!trailer.isEmpty());
                return;
            }
            copy(in, out, size + 2); // the data and its CRLF
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c < 0) throw new IOException("request ended early");
        return b.toString(StandardCharsets.US_ASCII);
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
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() {
        closing = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
        // Return only once the accept loop has stopped, so the port is really closed (P032 report: CI run
        // 45119824506 caught a connection that was accepted while close() ran and was still forwarded).
        try {
            acceptor.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
