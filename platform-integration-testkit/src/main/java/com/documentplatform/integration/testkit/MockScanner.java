package com.documentplatform.integration.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A mock virus scanner on 127.0.0.1 that speaks clamd ({@code zINSTREAM}, {@code zPING}) or ICAP ({@code RESPMOD},
 * {@code OPTIONS}). By default it finds the EICAR test file ({@link Eicar}) and nothing else. Switches:
 * {@link #infected} (everything is infected), {@link #slow}, {@link #down} (nothing listens), {@link #garbled} (an
 * answer that is not a scan result). It counts scans and bytes; it never keeps what it scanned. For tests only.
 */
public final class MockScanner implements AutoCloseable {

    public enum Protocol { CLAMD, ICAP }

    /** The threat name it gives the EICAR file. */
    public static final String EICAR_NAME = "Eicar-Test-Signature";
    public static final String ICAP_SERVICE = "avscan";
    private static final int MAX_SCAN = 64 * 1024 * 1024;

    private final Protocol protocol;
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-scanner");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger scans = new AtomicInteger();
    private final AtomicInteger found = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean infectedAlways;
    private volatile boolean garbled;
    private volatile Duration slow = Duration.ZERO;

    private MockScanner(Protocol protocol) {
        this.protocol = protocol;
        this.server = bind(0);
        this.port = server.getLocalPort();
        accept(server);
    }

    public static MockScanner clamd() {
        return new MockScanner(Protocol.CLAMD);
    }

    public static MockScanner icap() {
        return new MockScanner(Protocol.ICAP);
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return port;
    }

    public Protocol protocol() {
        return protocol;
    }

    /** Back to working: EICAR found, everything else clean. */
    public MockScanner working() {
        infectedAlways = false;
        garbled = false;
        slow = Duration.ZERO;
        down(false);
        return this;
    }

    /** Every file is infected. */
    public MockScanner infected(boolean on) {
        infectedAlways = on;
        return this;
    }

    /** It answers something that is not a scan result. */
    public MockScanner garbled(boolean on) {
        garbled = on;
        return this;
    }

    /** It waits this long before it answers. */
    public MockScanner slow(Duration d) {
        slow = d;
        return this;
    }

    /** Nothing listens on its port (connections are refused); up again on the same port. */
    public synchronized MockScanner down(boolean on) {
        if (on && server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closed
            }
            server = null;
        } else if (!on && server == null) {
            server = bind(port);
            accept(server);
        }
        return this;
    }

    /** Files scanned (whole requests answered). */
    public int scans() {
        return scans.get();
    }

    /** Files it reported infected. */
    public int found() {
        return found.get();
    }

    private static ServerSocket bind(int port) {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void accept(ServerSocket s) {
        pool.submit(() -> {
            while (!s.isClosed()) {
                Socket c;
                try {
                    c = s.accept();
                } catch (IOException e) {
                    return;
                }
                pool.submit(() -> serve(c));
            }
        });
    }

    private void serve(Socket c) {
        try (c) {
            c.setSoTimeout(30_000);
            if (protocol == Protocol.CLAMD) clamd(c);
            else icap(c);
        } catch (IOException ignored) {
            // the client went away
        }
    }

    private void pause() {
        Duration d = slow;
        if (d.isZero()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean verdict(byte[] content) {
        boolean bad = infectedAlways || contains(content, Eicar.bytes());
        scans.incrementAndGet();
        if (bad) found.incrementAndGet();
        return bad;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ---- clamd ----

    private void clamd(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream();
        String command = readUntil(in, (byte) 0);
        if ("zPING".equals(command)) {
            pause();
            out.write((garbled ? "WHAT\0" : "PONG\0").getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (!"zINSTREAM".equals(command)) {
            out.write("UNKNOWN COMMAND\0".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        while (true) {
            byte[] len = in.readNBytes(4);
            if (len.length < 4) return;
            int n = ((len[0] & 0xff) << 24) | ((len[1] & 0xff) << 16) | ((len[2] & 0xff) << 8) | (len[3] & 0xff);
            if (n == 0) break;
            if (n < 0 || content.size() + n > MAX_SCAN) {
                out.write("INSTREAM size limit exceeded. ERROR\0".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            content.write(in.readNBytes(n));
        }
        pause();
        if (garbled) {
            out.write("\u0001\u0002 nonsense".getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        String answer = verdict(content.toByteArray()) ? "stream: " + EICAR_NAME + " FOUND\0" : "stream: OK\0";
        out.write(answer.getBytes(StandardCharsets.US_ASCII));
    }

    private static String readUntil(InputStream in, byte end) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int x;
        while ((x = in.read()) >= 0 && x != end) {
            if (b.size() > 256) break;
            b.write(x);
        }
        return b.toString(StandardCharsets.US_ASCII);
    }

    // ---- ICAP ----

    private void icap(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String first = head.split("\r\n", 2)[0];
        if (first.startsWith("OPTIONS ")) {
            pause();
            out.write((garbled ? "HELLO\r\n\r\n" : "ICAP/1.0 200 OK\r\nMethods: RESPMOD\r\nAllow: 204\r\n"
                    + "Encapsulated: null-body=0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (!first.startsWith("RESPMOD ")) {
            out.write("ICAP/1.0 405 Method Not Allowed\r\nEncapsulated: null-body=0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            return;
        }
        // The encapsulated HTTP response head, then the chunked body.
        String httpHead = readHead(in);
        if (httpHead == null) return;
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        while (true) {
            String size = readLine(in);
            if (size == null) return;
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).strip(), 16);
            if (n == 0) {
                readLine(in);
                break;
            }
            if (content.size() + n > MAX_SCAN) return;
            content.write(in.readNBytes(n));
            readLine(in);
        }
        pause();
        if (garbled) {
            out.write("SOMETHING ELSE\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (verdict(content.toByteArray())) {
            String blocked = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n";
            out.write(("ICAP/1.0 200 OK\r\nX-Infection-Found: Type=0; Resolution=2; Threat=" + EICAR_NAME + ";\r\n"
                    + "Encapsulated: res-hdr=0, null-body=" + blocked.length() + "\r\n\r\n" + blocked)
                    .getBytes(StandardCharsets.US_ASCII));
        } else {
            out.write("ICAP/1.0 204 No Content\r\nEncapsulated: null-body=0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        int x;
        while ((x = in.read()) >= 0) {
            b.write(x);
            if (b.size() > 64 * 1024) return null;
            state = (state == 0 || state == 2) ? (x == '\r' ? state + 1 : 0) : (x == '\n' ? state + 1 : (x == '\r' ? 1 : 0));
            if (state == 4) return b.toString(StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int x;
        while ((x = in.read()) >= 0) {
            if (x == '\n') return b.toString(StandardCharsets.ISO_8859_1).replace("\r", "");
            b.write(x);
            if (b.size() > 1024) return null;
        }
        return null;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closed
            }
        }
        pool.shutdownNow();
    }

    @Override
    public String toString() {
        return "MockScanner[" + protocol + ", " + port + "]";
    }
}
