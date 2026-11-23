package com.documentplatform.integration.spring;

import com.documentplatform.integration.config.Limits;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.scan.ScannerAddress;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The checked integration settings. {@link #from} lists every problem at once and stops the app
 * ({@link IntegrationConfigException}). Problems name the property, never a value (a pasted secret stays out of the
 * log).
 *
 * @param installation  the installation every integration runs under (the {@code platform.outbound.*} bean)
 * @param defaultLimits the limits a new integration starts with
 * @param profileFolder where published profile files are, or null when none is set
 * @param health        whether the health contributor is on
 * @param healthCacheFor how long one health result is kept
 * @param scannerType    NONE (scanning off), CLAMD or ICAP
 * @param scanner        where the scanner listens, or null when off
 * @param icapService    the ICAP service path, or null
 * @param intakeWorkFolder where downloads are kept while the app takes them, or null for the system's
 */
record IntegrationSettings(Installation installation, Limits defaultLimits, Path profileFolder, boolean health,
                                  Duration healthCacheFor, IntegrationProperties.Scanner.Type scannerType,
                                  ScannerAddress scanner, String icapService, Path intakeWorkFolder) {

    public IntegrationSettings {
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(defaultLimits, "defaultLimits");
        Objects.requireNonNull(healthCacheFor, "healthCacheFor");
    }

    /** @param installation the outbound installation (platform.outbound.*), or null when none is set */
    public static IntegrationSettings from(IntegrationProperties p, Installation installation) {
        String prefix = IntegrationProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();

        moved(prefix + "installation", p.getInstallation() != null, problems);
        moved(prefix + "allow-internal-addresses", p.getAllowInternalAddresses() != null, problems);
        moved(prefix + "allow-sign-in-none", p.getAllowSignInNone() != null, problems);
        moved(prefix + "private-ca-file", p.getPrivateCaFile() != null, problems);
        for (String key : p.getProxy().keySet()) moved(prefix + "proxy." + key, true, problems);
        if (installation == null) {
            problems.add("platform.outbound.installation is required: ON_PREMISES or HOSTED (the installation "
                    + "every outbound call runs under, platform-outbound-spring)");
        }

        Limits limits = null;
        IntegrationProperties.Limits l = p.getLimits();
        try {
            limits = new Limits(l.getConnectTimeout(), l.getReadTimeout(), bytes(l.getMaxFileSize()),
                    l.getMaxDocuments() == null ? 0 : l.getMaxDocuments(), (int) Math.min(Integer.MAX_VALUE,
                    bytes(l.getMaxResponseSize())), bytes(l.getMaxPackageSize()));
        } catch (IllegalArgumentException | NullPointerException e) {
            problems.add(prefix + "limits: " + (e.getMessage() == null ? "a limit is missing" : e.getMessage()));
        }

        Path profiles = null;
        if (p.getProfileFolder() != null && !p.getProfileFolder().isBlank()) {
            try {
                profiles = Path.of(p.getProfileFolder().strip());
                if (!profiles.isAbsolute()) {
                    problems.add(prefix + "profile-folder must be an absolute path");
                } else if (!Files.isDirectory(profiles)) {
                    problems.add(prefix + "profile-folder is not a folder (missing, or no right to read it)");
                }
            } catch (InvalidPathException e) {
                problems.add(prefix + "profile-folder is not a valid path");
            }
        }

        Duration cache = p.getHealth().getCacheFor();
        if (cache == null || cache.compareTo(Duration.ofSeconds(5)) < 0 || cache.compareTo(Duration.ofHours(1)) > 0) {
            problems.add(prefix + "health.cache-for must be 5 seconds to 1 hour");
        }

        ScannerAddress scanner = null;
        IntegrationProperties.Scanner sc = p.getScanner();
        IntegrationProperties.Scanner.Type type = sc.getType() == null ? IntegrationProperties.Scanner.Type.NONE
                : sc.getType();
        if (type != IntegrationProperties.Scanner.Type.NONE) {
            int port = sc.getPort() != null ? sc.getPort()
                    : type == IntegrationProperties.Scanner.Type.CLAMD ? 3310 : 1344;
            if (sc.getHost() == null || sc.getHost().isBlank()) {
                problems.add(prefix + "scanner.host is required for a scanner");
            } else {
                try {
                    scanner = new ScannerAddress(sc.getHost().strip(), port, sc.getTimeout());
                } catch (IllegalArgumentException | NullPointerException e) {
                    problems.add(prefix + "scanner: the host, a port 1-65535 and a timeout of 1 second to 10 minutes");
                }
            }
            if (type == IntegrationProperties.Scanner.Type.ICAP && (sc.getIcapService() == null
                    || !sc.getIcapService().matches("/?[A-Za-z0-9_.~/-]{1,200}"))) {
                problems.add(prefix + "scanner.icap-service is not a service path");
            }
        }

        Path work = null;
        if (p.getIntake().getWorkFolder() != null && !p.getIntake().getWorkFolder().isBlank()) {
            try {
                work = Path.of(p.getIntake().getWorkFolder().strip());
                if (!work.isAbsolute()) {
                    problems.add(prefix + "intake.work-folder must be an absolute path");
                } else if (!Files.isDirectory(work) || !Files.isWritable(work)) {
                    problems.add(prefix + "intake.work-folder is not a folder this account may write to");
                }
            } catch (InvalidPathException e) {
                problems.add(prefix + "intake.work-folder is not a valid path");
            }
        }
        if (!problems.isEmpty()) {
            throw new IntegrationConfigException(problems);
        }

        return new IntegrationSettings(installation, limits, profiles, p.getHealth().isEnabled(), cache,
                scanner == null ? IntegrationProperties.Scanner.Type.NONE : type, scanner,
                scanner == null ? null : sc.getIcapService(), work);
    }

    private static long bytes(org.springframework.util.unit.DataSize d) {
        if (d == null) throw new IllegalArgumentException("a size is missing");
        return d.toBytes();
    }

    private static void moved(String name, boolean set, List<String> problems) {
        if (set) {
            String rest = name.substring((IntegrationProperties.PREFIX + ".").length());
            problems.add(name + " moved to platform.outbound." + rest
                    + " (P028): set it there");
        }
    }
}
