package com.documentplatform.integration.scan;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.safety.AddressGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ICAP scanner (RFC 3507), over TCP: one {@code RESPMOD} request per file, the file as the body of an encapsulated
 * HTTP response (chunked), with {@code Allow: 204}. The answer {@code 204} is clean; a {@code 200} (the scanner changed
 * the response: it blocked the file) or any answer naming a threat ({@code X-Infection-Found}, {@code X-Violations-Found},
 * {@code X-Virus-ID}) is infected; any other status or a garbled answer is SCAN_FAILED; no connection is
 * {@code INT_SCAN_UNREACHABLE}. Health: {@code OPTIONS} → {@code 200}.
 */
public final class IcapScanner implements FileScanner {

    private static final int CHUNK = 64 * 1024;
    private static final int MAX_HEAD = 16 * 1024;
    private static final Pattern STATUS = Pattern.compile("ICAP/1\\.0 (\\d{3})( .*)?");
    private static final Pattern THREAT = Pattern.compile("(?i)threat=([^;]+)");
    private static final Pattern SERVICE = Pattern.compile("[A-Za-z0-9_.~/-]{1,200}");

    private final ScannerAddress address;
    private final String service;
    private final AddressGuard guard;

    /** @param service the ICAP service path ({@code avscan}, {@code srv_clamav}) */
    public IcapScanner(ScannerAddress address, String service, AddressGuard guard) {
        this.address = Objects.requireNonNull(address, "address");
        Objects.requireNonNull(service, "service");
        String s = service.startsWith("/") ? service.substring(1) : service;
        if (!SERVICE.matcher(s).matches()) throw new IllegalArgumentException("The ICAP service is not valid");
        this.service = s;
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    private String uri() {
        return "icap://" + address.host() + ":" + address.port() + "/" + service;
    }

    @Override
    public ScanResult scan(InputStream content, long size) {
        try (Socket s = address.connect(guard)) {
            OutputStream out = s.getOutputStream();
            String httpHead = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + size
                    + "\r\n\r\n";
            byte[] hh = httpHead.getBytes(StandardCharsets.US_ASCII);
            String icapHead = "RESPMOD " + uri() + " ICAP/1.0\r\nHost: " + address.host() + "\r\nAllow: 204\r\n"
                    + "Encapsulated: res-hdr=0, res-body=" + hh.length + "\r\n\r\n";
            out.write(icapHead.getBytes(StandardCharsets.US_ASCII));
            out.write(hh);
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
                out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(buf, 0, n);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Head head = head(s.getInputStream());
            if (head == null) return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
            String threat = threat(head.headers());
            if (head.status() == 204) {
                return threat == null ? ScanResult.clean() : ScanResult.infected(threat);
            }
            if (head.status() == 200) return ScanResult.infected(threat);
            return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
        } catch (ScannerAddress.Unreachable u) {
            return ScanResult.failed(u.code);
        } catch (SocketTimeoutException e) {
            return ScanResult.failed(IntegrationCode.INT_SCAN_UNREACHABLE);
        } catch (IOException e) {
            return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
        }
    }

    private record Head(int status, Map<String, String> headers) {
    }

    /** The ICAP status line and headers (names in lower case); null when it is not an ICAP answer. */
    private static Head head(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        int state = 0; // counts \r\n\r\n
        while ((c = in.read()) >= 0) {
            if (b.size() >= MAX_HEAD) return null;
            b.write(c);
            state = switch (state) {
                case 0, 2 -> c == '\r' ? state + 1 : 0;
                case 1, 3 -> c == '\n' ? state + 1 : (c == '\r' ? 1 : 0);
                default -> 0;
            };
            if (state == 4) break;
        }
        if (state != 4) return null;
        String[] lines = b.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        Matcher m = STATUS.matcher(lines[0]);
        if (!m.matches()) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).strip());
        }
        return new Head(Integer.parseInt(m.group(1)), headers);
    }

    private static String threat(Map<String, String> h) {
        String found = h.get("x-infection-found");
        if (found != null) {
            Matcher m = THREAT.matcher(found);
            return m.find() ? m.group(1) : found;
        }
        String v = h.get("x-violations-found");
        if (v != null) return v;
        return h.get("x-virus-id");
    }

    @Override
    public Health health() {
        try (Socket s = address.connect(guard)) {
            String req = "OPTIONS " + uri() + " ICAP/1.0\r\nHost: " + address.host() + "\r\nEncapsulated: null-body=0"
                    + "\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            Head head = head(s.getInputStream());
            return head != null && head.status() == 200 ? new Health(true, IntegrationCode.INT_HEALTHY)
                    : new Health(false, IntegrationCode.INT_SCAN_FAILED);
        } catch (ScannerAddress.Unreachable u) {
            return new Health(false, u.code);
        } catch (IOException e) {
            return new Health(false, IntegrationCode.INT_SCAN_UNREACHABLE);
        }
    }

    @Override
    public String kind() {
        return "icap";
    }

    @Override
    public String toString() {
        return "IcapScanner";
    }
}
