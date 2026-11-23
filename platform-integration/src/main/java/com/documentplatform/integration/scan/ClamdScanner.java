package com.documentplatform.integration.scan;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.outbound.AddressGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A clamd (ClamAV daemon) scanner, over TCP: {@code zINSTREAM}, the content in chunks (each a 4-byte big-endian length
 * and the bytes), a zero length, then one answer: {@code stream: OK} (clean), {@code stream: <name> FOUND} (infected),
 * or an error ({@code ... ERROR}, for example clamd's own size limit). Anything else, or no answer, is SCAN_FAILED;
 * no connection is {@code INT_SCAN_UNREACHABLE}. Health: {@code zPING} → {@code PONG}.
 */
public final class ClamdScanner implements FileScanner {

    private static final int CHUNK = 64 * 1024;
    private static final int MAX_REPLY = 1024;

    private final ScannerAddress address;
    private final AddressGuard guard;

    public ClamdScanner(ScannerAddress address, AddressGuard guard) {
        this.address = Objects.requireNonNull(address, "address");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public ScanResult scan(InputStream content, long size) {
        try (Socket s = address.connect(guard)) {
            OutputStream out = s.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[CHUNK];
            int n;
            while (true) {
                try {
                    n = content.read(buf);
                } catch (IOException localRead) {
                    return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
                }
                if (n < 0) break;
                if (n == 0) continue;
                out.write(new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
                out.write(buf, 0, n);
            }
            out.write(new byte[4]);
            out.flush();
            String reply = reply(s.getInputStream());
            return verdict(reply);
        } catch (ScannerAddress.Unreachable u) {
            return ScanResult.failed(u.code);
        } catch (SocketTimeoutException e) {
            return ScanResult.failed(IntegrationCode.INT_SCAN_UNREACHABLE);
        } catch (IOException e) {
            // clamd closes the connection when the stream exceeds its own limit, before it has read it all.
            return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
        }
    }

    static ScanResult verdict(String reply) {
        if (reply == null) return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
        String r = reply.strip();
        if (r.equals("stream: OK")) return ScanResult.clean();
        if (r.startsWith("stream: ") && r.endsWith(" FOUND")) {
            return ScanResult.infected(r.substring("stream: ".length(), r.length() - " FOUND".length()));
        }
        return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
    }

    /** One NUL-terminated (or connection-terminated) answer, at most {@value #MAX_REPLY} bytes; null when none. */
    private static String reply(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != 0) {
            if (b.size() >= MAX_REPLY) return null;
            b.write(c);
        }
        if (b.size() == 0) return null;
        String s = b.toString(StandardCharsets.US_ASCII);
        return s.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f || ch == '\n') ? s : null;
    }

    @Override
    public Health health() {
        try (Socket s = address.connect(guard)) {
            s.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String reply = reply(s.getInputStream());
            return "PONG".equals(reply == null ? null : reply.strip()) ? new Health(true, IntegrationCode.INT_HEALTHY)
                    : new Health(false, IntegrationCode.INT_SCAN_FAILED);
        } catch (ScannerAddress.Unreachable u) {
            return new Health(false, u.code);
        } catch (IOException e) {
            return new Health(false, IntegrationCode.INT_SCAN_UNREACHABLE);
        }
    }

    @Override
    public String kind() {
        return "clamd";
    }

    @Override
    public String toString() {
        return "ClamdScanner";
    }
}
