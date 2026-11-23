package com.documentplatform.integration.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The EICAR anti-virus test file: harmless, and recognised by every scanner as a test virus. It is put together at
 * run time, so no source or class file (and no jar) of this project holds it and no virus scanner on a build machine
 * quarantines them. For tests only.
 */
public final class Eicar {

    /** The 68 characters, reversed: so this class does not hold the signature itself. */
    private static final String REVERSED = "*H+H$!ELIF-TSET-SURIVITNA-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X";

    private Eicar() {
    }

    /** The test file's bytes. */
    public static byte[] bytes() {
        return new StringBuilder(REVERSED).reverse().toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Whether a test file written to {@code dir} stays there, and also passes over a loopback HTTP connection
     * unchanged: a virus scanner on the machine (for example a desktop one) may remove it at once, or cut a connection
     * that carries it. Tests that use it skip where either happens; CI, which has no such scanner, forbids skips.
     */
    public static boolean staysOnDisk(Path dir) {
        return staysInFile(dir) && travelsOverLoopback();
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
