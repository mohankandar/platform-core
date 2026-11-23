package com.documentplatform.outbound;


import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Checks every address before each call (destination and token address), after the name is resolved:
 * <ul>
 *   <li><b>Always refused:</b> cloud metadata addresses (169.254.169.254, fd00:ec2::254, Azure 168.63.129.16, Alibaba
 *       100.100.100.200, {@code metadata.google.internal} and similar names), link-local (169.254.0.0/16, fe80::/10),
 *       "any" (0.0.0.0, ::), multicast and broadcast.</li>
 *   <li><b>Refused unless internal addresses are allowed</b> (on premises only): loopback, private ranges (10/8,
 *       172.16/12, 192.168/16, fc00::/7), shared 100.64/10 and benchmark 198.18/15.</li>
 * </ul>
 * Every address the name resolves to must pass, so a name that also points inside is refused. The check runs for each
 * attempt, right before the call, and the connection goes to the addresses checked (never a fresh lookup). Behind
 * a proxy, a name the local resolver does not know is left to the proxy (name checks still apply).
 */
public final class AddressGuard {

    /** Resolves a host name. Replaceable for tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final Set<String> METADATA_NAMES = Set.of("metadata.google.internal", "metadata.goog", "metadata",
            "instance-data", "instance-data.ec2.internal", "metadata.azure.internal", "metadata.tencentyun.com");
    private static final Set<String> METADATA_ADDRESSES = Set.of("169.254.169.254", "169.254.170.2",
            "168.63.129.16", "100.100.100.200", "fd00:ec2:0:0:0:0:0:254");

    private final boolean allowInternal;
    private final Resolver resolver;

    public AddressGuard(boolean allowInternal) {
        this(allowInternal, InetAddress::getAllByName);
    }

    public AddressGuard(boolean allowInternal, Resolver resolver) {
        this.allowInternal = allowInternal;
        this.resolver = resolver;
    }

    /**
     * @param host     the address's host (an IPv6 literal may keep its brackets)
     * @param viaProxy whether the call goes through the outbound proxy
     * @return empty when the call may go ahead; else {@code OUT_ADDRESS_BLOCKED} or {@code OUT_HOST_UNKNOWN}
     */
    public Optional<OutboundCode> check(String host, boolean viaProxy) {
        return checked(host, viaProxy).refused();
    }

    /**
     * The result of one check: refused with a code, or allowed with the addresses that were checked (empty behind a
     * proxy when the local resolver does not know the name). The connection is made to exactly these addresses
     * (connection pinning, {@link Outbound}): never to a fresh lookup.
     */
    public record Checked(OutboundCode code, List<InetAddress> addresses) {
        public Checked {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
        }

        public Optional<OutboundCode> refused() {
            return Optional.ofNullable(code);
        }

        @Override
        public String toString() {
            return code == null ? "Checked[allowed]" : "Checked[" + code + "]"; // never the addresses
        }
    }

    /** {@link #check}, keeping the addresses the name resolved to. */
    public Checked checked(String host, boolean viaProxy) {
        if (host == null || host.isEmpty()) return refuse(OutboundCode.OUT_HOST_UNKNOWN);
        String h = normalise(host);
        if (METADATA_NAMES.contains(h)) return refuse(OutboundCode.OUT_ADDRESS_BLOCKED);
        if (!allowInternal && (h.equals("localhost") || h.endsWith(".localhost"))) {
            return refuse(OutboundCode.OUT_ADDRESS_BLOCKED);
        }
        InetAddress[] all;
        try {
            all = resolver.resolve(h);
        } catch (UnknownHostException | SecurityException e) {
            return viaProxy ? new Checked(null, List.of()) : refuse(OutboundCode.OUT_HOST_UNKNOWN);
        }
        if (all == null || all.length == 0) {
            return viaProxy ? new Checked(null, List.of()) : refuse(OutboundCode.OUT_HOST_UNKNOWN);
        }
        for (InetAddress a : all) {
            if (alwaysBlocked(a) || (!allowInternal && internal(a))) {
                return refuse(OutboundCode.OUT_ADDRESS_BLOCKED);
            }
        }
        return new Checked(null, List.of(all));
    }

    /** Lower case, without IPv6 brackets or trailing dots: the form the checked addresses are kept under. */
    public static String normalise(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static Checked refuse(OutboundCode code) {
        return new Checked(code, List.of());
    }

    /** Cloud metadata, link-local, any, multicast, broadcast. */
    public static boolean alwaysBlocked(InetAddress a) {
        if (METADATA_ADDRESSES.contains(a.getHostAddress().toLowerCase(Locale.ROOT).replaceAll("%.*$", ""))) {
            return true;
        }
        if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return true;
        if (a.isLoopbackAddress()) return false; // ::1 is not the IPv4-compatible 0.0.0.1
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            if (b0 == 255 && (b[1] & 0xff) == 255 && (b[2] & 0xff) == 255 && (b[3] & 0xff) == 255) return true;
            return b0 == 0; // 0.0.0.0/8: "this network"
        }
        if (a instanceof Inet6Address) {
            // IPv4-compatible / -mapped forms of a blocked IPv4 address.
            boolean zeros = true;
            for (int k = 0; k < 10; k++) zeros &= b[k] == 0;
            if (zeros && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xff && b[11] == (byte) 0xff))) {
                try {
                    InetAddress v4 = InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                    return alwaysBlocked(v4);
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Loopback and private ranges, allowed on premises when switched on. */
    public static boolean internal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 100 && b1 >= 64 && b1 <= 127 // shared address space 100.64/10
                    || b0 == 198 && (b1 == 18 || b1 == 19); // benchmark 198.18/15
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true; // unique local fc00::/7
            boolean zeros = true;
            for (int k = 0; k < 10; k++) zeros &= b[k] == 0;
            if (zeros && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
                try {
                    return internal(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
