package com.documentplatform.integration.scan;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.outbound.AddressGuard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;

/**
 * Where a scanner listens, reached over the outbound safety rules: the name is checked by the {@link AddressGuard}
 * (cloud metadata always refused; internal addresses only where the installation allows them, as on premises) and the
 * connection goes to the address it checked, never to a fresh lookup.
 *
 * @param host    the scanner's host name or address
 * @param port    its port (1–65535)
 * @param timeout to connect, and for each read (1 s – 10 min)
 */
public record ScannerAddress(String host, int port, Duration timeout) {

    /** The connection could not be made: the code says why. */
    static final class Unreachable extends Exception {
        final IntegrationCode code;

        Unreachable(IntegrationCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }
    }

    public ScannerAddress {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(timeout, "timeout");
        if (host.isBlank() || host.length() > 253 || host.chars().anyMatch(c -> c <= 0x20 || c == 0x7f || c == '/')) {
            throw new IllegalArgumentException("The scanner's host is not valid");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("The scanner's port is 1-65535");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("The scanner's timeout is 1 second to 10 minutes");
        }
    }

    Socket connect(AddressGuard guard) throws Unreachable {
        AddressGuard.Checked checked = guard.checked(host, false);
        if (checked.refused().isPresent()) throw new Unreachable(IntegrationCode.of(checked.refused().get()));
        if (checked.addresses().isEmpty()) throw new Unreachable(IntegrationCode.INT_SCAN_UNREACHABLE);
        Socket s = new Socket();
        try {
            int millis = (int) timeout.toMillis();
            s.connect(new InetSocketAddress(checked.addresses().get(0), port), millis);
            s.setSoTimeout(millis);
            return s;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            throw new Unreachable(IntegrationCode.INT_SCAN_UNREACHABLE);
        }
    }

    @Override
    public String toString() {
        return "ScannerAddress[" + port + "]"; // the host is configuration, kept out of logs
    }
}
