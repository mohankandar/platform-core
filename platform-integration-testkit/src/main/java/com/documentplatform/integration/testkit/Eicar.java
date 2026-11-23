package com.documentplatform.integration.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The test "virus" for scanner tests. By default it is a harmless placeholder that {@link MockScanner} reports as
 * infected exactly like EICAR, so the quarantine paths are tested everywhere and no desktop virus scanner on a
 * developer's machine removes the file or cuts the connection carrying it. With the environment variable
 * {@value #OPT_IN}{@code =true} (as CI sets it), {@link #testFile()} is the real EICAR anti-virus test file:
 * harmless, recognised by every scanner as a test virus, and put together at run time, so no source or class file (and
 * no jar) of this project holds it. For tests only.
 */
public final class Eicar {

    /** The environment variable that turns the real EICAR file on: {@code RUN_EICAR_TESTS=true}. */
    public static final String OPT_IN = "RUN_EICAR_TESTS";

    /** The 68 characters, reversed: so this class does not hold the signature itself. */
    private static final String REVERSED = "*H+H$!ELIF-TSET-SURIVITNA-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X";

    /** The placeholder: plain text that no real scanner flags; only {@link MockScanner} treats it as infected. */
    private static final String PLACEHOLDER = "PLATFORM-MOCK-SCANNER-TEST-FILE: harmless text that the mock scanner "
            + "reports as infected. Not a virus, not a virus signature.";

    private Eicar() {
    }

    /** The real EICAR test file's bytes (built in memory; written or sent only by tests that opt in). */
    public static byte[] bytes() {
        return new StringBuilder(REVERSED).reverse().toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** The harmless placeholder's bytes. */
    public static byte[] placeholder() {
        return PLACEHOLDER.getBytes(StandardCharsets.US_ASCII);
    }

    /** Whether the real EICAR file is used: {@value #OPT_IN} is {@code true}. */
    public static boolean real() {
        return "true".equalsIgnoreCase(System.getenv(OPT_IN));
    }

    /** The file scanner tests write to disk or send over a connection: EICAR when opted in, else the placeholder. */
    public static byte[] testFile() {
        return real() ? bytes() : placeholder();
    }

    /**
     * Whether the test file ({@link #testFile()}) can be used here. Always for the placeholder. For the real EICAR file
     * (opted in): written to {@code dir} it stays there, and it passes over a loopback HTTP connection unchanged; a
     * virus scanner on the machine may remove it at once, or cut a connection that carries it. Tests skip where it
     * cannot; CI, which opts in and has no such scanner, forbids skips.
     */
    public static boolean staysOnDisk(Path dir) {
        return !real() || staysInFile(dir) && travelsOverLoopback();
    }

    /** Whether the test file passes unchanged from a loopback HTTP server to a client on this machine. */
    static boolean travelsOverLoopback() {
        com.sun.net.httpserver.HttpServer server = null;
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/eicar", x -> {
                byte[] b = bytes();
                x.getResponseHeaders().set("Content-Type", "application/octet-stream");
                x.sendResponseHeaders(200, b.length);
                try (java.io.OutputStream out = x.getResponseBody()) {
                    out.write(b);
                }
            });
            server.start();
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/eicar").toURL().openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            try (java.io.InputStream in = c.getInputStream()) {
                return c.getResponseCode() == 200 && java.util.Arrays.equals(in.readAllBytes(), bytes());
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static boolean staysInFile(Path dir) {
        try {
            Path p = Files.createTempFile(dir, "eicar-probe-", ".bin");
            try {
                Files.write(p, bytes());
                Thread.sleep(500);
                return Files.exists(p) && java.util.Arrays.equals(Files.readAllBytes(p), bytes());
            } finally {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a scanner holding it; nothing to do
                }
            }
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
