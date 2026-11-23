package com.documentplatform.crypto.command;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyDestruction;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.vault.VaultEnvironment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The crypto command. One action, for customer exit:
 *
 * <pre>
 * crypto destroy-customer-keys --tenant-id TEN-… --operator "&lt;who runs it&gt;"
 *                              [--confirm TEN-…] [--result-out customer-keys-destroyed.json]
 *                              [--backup-retention-days 35]
 *
 * Key service: PLATFORM_SETUP_VAULT_ADDR and one login (see VaultEnvironment) from the environment only, with the
 * platform-crypto-setup and platform-crypto-offboard policies, given to this offboarding run only. The set-up policy
 * alone, and the app's runtime policy, cannot destroy keys.
 * </pre>
 *
 * <p>Destroys the customer's wrapping key and fingerprint key ({@link KeyService#destroyCustomerKeys}). This is
 * <b>irreversible</b>: afterwards every record, value, document and backup of that customer is unreadable. So the
 * command asks for the tenant ID to be typed back ({@code --confirm}, or on the console when it is not given) and
 * refuses, before calling the key service, when it is missing or different. The result ({@value #FORMAT}: customer,
 * the keys destroyed with names and versions, date and time, who ran it; no key material) is written to a new file
 * and printed: it is the data for the offboarding certificate. It carries two dates: the day the live data was
 * destroyed ({@code liveDataDestroyedOn}, UTC), and the day from which it is unreadable everywhere, backups included
 * ({@code unreadableEverywhereFrom}: that day plus the backup retention, {@code --backup-retention-days}, 30 to 90,
 * default 35, the same as the erase log's). The second date holds only when the key service's own snapshots are kept
 * no longer than the backup retention ({@code docs/install/crypto.md}).
 *
 * <p>Exit codes: 0 destroyed; 1 failed (the key service refused or failed: rerun; keys already gone are then
 * reported as absent); 2 refused (usage, confirmation missing or wrong, settings, result file exists, no keys found).
 */
public final class CryptoCommand {

    public static final String FORMAT = "platform-key-destruction/1";
    public static final String ACTION = "destroy-customer-keys";

    public static final int EXIT_DONE = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_REFUSED = 2;

    /** Why the command refused; printed in front of the message. */
    public enum Code implements com.documentplatform.errors.PlatformCode {
        CRYPTO_COMMAND_USAGE,
        /** No confirmation was typed. Nothing was destroyed. */
        CRYPTO_DESTROY_NOT_CONFIRMED,
        /** The confirmation is not the tenant ID given. Nothing was destroyed. */
        CRYPTO_DESTROY_CONFIRMATION_WRONG,
        /** The result file already exists, or cannot be created. Nothing was destroyed. */
        CRYPTO_DESTROY_RESULT_FILE,
        /** The key service has neither of the customer's keys (wrong tenant ID or prefix, or already destroyed). */
        CRYPTO_DESTROY_NO_KEYS
    }

    /** How the command reaches the key service; the default reads the set-up login from the environment. */
    @FunctionalInterface
    public interface Connector {
        Connection connect(Map<String, String> env);
    }

    /** A key service for one run, and what to do when the run is over (Vault: revoke the login). */
    public record Connection(KeyService keys, Runnable close) {
        public Connection {
            Objects.requireNonNull(keys, "keys");
            Objects.requireNonNull(close, "close");
        }
    }

    /** Backup retention in days when {@code --backup-retention-days} is not given; the erase log's default. */
    public static final int DEFAULT_BACKUP_RETENTION_DAYS = 35;
    public static final int MIN_BACKUP_RETENTION_DAYS = 30;
    public static final int MAX_BACKUP_RETENTION_DAYS = 90;

    private static final Set<String> OPTIONS = Set.of("--tenant-id", "--operator", "--confirm", "--result-out",
            "--backup-retention-days");
    private static final Pattern OPERATOR = Pattern.compile("[\\p{L}\\p{N} .,'@_()/-]{1,120}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Map<String, String> env;
    private final Clock clock;
    private final Connector connector;

    public CryptoCommand(Map<String, String> env, Clock clock, Connector connector) {
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    /** With the set-up login from the environment ({@link VaultEnvironment#SETUP}). */
    public CryptoCommand(Map<String, String> env, Clock clock) {
        this(env, clock, CryptoCommand::setupLogin);
    }

    /** The default connector: Vault Transit with the set-up login from the environment. */
    public static Connection setupLogin(Map<String, String> env) {
        VaultEnvironment.Connection c = VaultEnvironment.connect(env, VaultEnvironment.SETUP);
        return new Connection(c.keys(), c::close);
    }

    public static void main(String[] args) {
        System.exit(new CryptoCommand(System.getenv(), Clock.systemUTC()).run(Arrays.asList(args),
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out, System.err));
    }

    private static final class Refused extends RuntimeException {
        final Code code;

        Refused(Code code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /**
     * @param console where the confirmation is read from when {@code --confirm} is not given (one line); may be null
     */
    public int run(List<String> args, BufferedReader console, PrintStream out, PrintStream err) {
        Path result = null;
        boolean destroyCalled = false;
        try {
            if (args.isEmpty() || !ACTION.equals(args.get(0))) {
                throw new Refused(Code.CRYPTO_COMMAND_USAGE, "usage: crypto " + ACTION
                        + " --tenant-id TEN-... --operator <who> [--confirm TEN-...] [--result-out <file>]"
                        + " [--backup-retention-days " + DEFAULT_BACKUP_RETENTION_DAYS + "]");
            }
            Map<String, String> o = parse(args.subList(1, args.size()));
            String tenantId = required(o, "--tenant-id");
            CustomerId customer;
            try {
                customer = CustomerId.fromTenantId(tenantId);
            } catch (IllegalArgumentException e) {
                throw new Refused(Code.CRYPTO_COMMAND_USAGE, "--tenant-id: " + e.getMessage());
            }
            String operator = required(o, "--operator").strip();
            if (!OPERATOR.matcher(operator).matches()) {
                throw new Refused(Code.CRYPTO_COMMAND_USAGE, "--operator is 1 to 120 letters, digits, spaces and "
                        + ". , ' @ _ ( ) / -");
            }
            int retentionDays = retentionDays(o.get("--backup-retention-days"));
            String confirmation = o.get("--confirm");
            if (confirmation == null) {
                out.println("This destroys the keys of customer " + tenantId + " (" + customer + ") in the key "
                        + "service. It cannot be undone: every record, value, document and backup of this customer "
                        + "becomes unreadable.");
                out.print("Type the tenant ID to confirm: ");
                out.flush();
                confirmation = readLine(console);
            }
            if (confirmation == null || confirmation.isBlank()) {
                throw new Refused(Code.CRYPTO_DESTROY_NOT_CONFIRMED, "No confirmation was typed; nothing was "
                        + "destroyed.");
            }
            if (!confirmation.strip().equals(tenantId)) {
                throw new Refused(Code.CRYPTO_DESTROY_CONFIRMATION_WRONG, "The confirmation is not the tenant ID "
                        + tenantId + "; nothing was destroyed.");
            }
            result = Path.of(o.getOrDefault("--result-out", "customer-keys-destroyed-" + tenantId + "-"
                    + STAMP.format(clock.instant()) + ".json"));
            createResultFile(result);

            Connection c;
            try {
                c = connector.connect(env);
            } catch (CryptoException e) {
                deleteQuietly(result);
                err.println(e.getMessage());
                return EXIT_REFUSED;
            }
            KeyDestruction done;
            try {
                destroyCalled = true;
                done = c.keys().destroyCustomerKeys(customer);
            } finally {
                c.close().run();
            }
            if (!done.destroyedAny()) {
                deleteQuietly(result);
                throw new Refused(Code.CRYPTO_DESTROY_NO_KEYS, "The key service has no keys for customer " + tenantId
                        + " (" + names(done) + "): wrong tenant ID or key prefix, or already destroyed. Nothing was "
                        + "destroyed now.");
            }
            String json = json(tenantId, done, operator, retentionDays);
            Files.writeString(result, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            out.println("Destroyed the keys of customer " + tenantId + " (" + customer + ") at " + done.at() + ":");
            for (KeyDestruction.Key k : done.keys()) {
                out.println("  " + k.role().name().toLowerCase(Locale.ROOT) + " key " + k.name() + ": "
                        + (k.state() == KeyDestruction.State.DESTROYED
                        ? "destroyed, versions 1 to " + k.latestVersion() : "absent (already gone)"));
            }
            out.println("Live data destroyed on " + liveDataDestroyedOn(done) + "; unreadable everywhere, backups "
                    + "included, from " + unreadableEverywhereFrom(done, retentionDays) + " (backup retention "
                    + retentionDays + " days).");
            out.println("Result for the offboarding certificate: " + result.toAbsolutePath());
            out.print(json);
            return EXIT_DONE;
        } catch (Refused r) {
            err.println(r.code + ": " + r.getMessage());
            return EXIT_REFUSED;
        } catch (CryptoException e) {
            deleteQuietly(result);
            err.println(e.getMessage());
            err.println(destroyCalled ? "Rerun the command: keys already destroyed are then reported as absent."
                    : "Nothing was destroyed.");
            return EXIT_FAILED;
        } catch (IOException e) {
            err.println("The result file could not be written (" + e.getClass().getSimpleName() + "); the keys are "
                    + "destroyed: keep this output for the offboarding certificate.");
            return EXIT_FAILED;
        }
    }

    /** The result as JSON (format {@value #FORMAT}): ids, names, versions, times; no key material. */
    static String json(String tenantId, KeyDestruction d, String operator, int retentionDays) {
        StringBuilder b = new StringBuilder("{\n");
        field(b, "format", FORMAT).append(",\n");
        field(b, "tenantId", tenantId).append(",\n");
        field(b, "customer", d.customer().toString()).append(",\n");
        field(b, "keyService", d.keyService()).append(",\n");
        b.append("  \"keys\": [");
        for (int i = 0; i < d.keys().size(); i++) {
            KeyDestruction.Key k = d.keys().get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"role\": ").append(quote(k.role().name()))
                    .append(", \"name\": ").append(quote(k.name()))
                    .append(", \"versions\": ").append(k.state() == KeyDestruction.State.DESTROYED
                            ? quote("1-" + k.latestVersion()) : "null")
                    .append(", \"state\": ").append(quote(k.state().name())).append('}');
        }
        b.append("\n  ],\n");
        field(b, "destroyedAt", d.at().toString()).append(",\n");
        field(b, "liveDataDestroyedOn", liveDataDestroyedOn(d).toString()).append(",\n");
        b.append("  \"backupRetentionDays\": ").append(retentionDays).append(",\n");
        field(b, "unreadableEverywhereFrom", unreadableEverywhereFrom(d, retentionDays).toString()).append(",\n");
        field(b, "operator", operator).append('\n');
        return b.append("}\n").toString();
    }

    /** The day (UTC) the customer's keys, and so the live data, were destroyed. */
    public static LocalDate liveDataDestroyedOn(KeyDestruction d) {
        return LocalDate.ofInstant(d.at(), ZoneOffset.UTC);
    }

    /**
     * The day from which the customer's data is unreadable everywhere, backups included: the destruction day plus the
     * backup retention. By then every backup made before the destruction has expired, and with it every copy of the
     * keys in the key service's snapshots, provided those are kept no longer than the backup retention.
     */
    public static LocalDate unreadableEverywhereFrom(KeyDestruction d, int retentionDays) {
        return liveDataDestroyedOn(d).plusDays(retentionDays);
    }

    private static int retentionDays(String given) {
        if (given == null) {
            return DEFAULT_BACKUP_RETENTION_DAYS;
        }
        int days;
        try {
            days = Integer.parseInt(given.strip());
        } catch (NumberFormatException e) {
            days = -1;
        }
        if (days < MIN_BACKUP_RETENTION_DAYS || days > MAX_BACKUP_RETENTION_DAYS) {
            throw new Refused(Code.CRYPTO_COMMAND_USAGE, "--backup-retention-days is a whole number of days from "
                    + MIN_BACKUP_RETENTION_DAYS + " to " + MAX_BACKUP_RETENTION_DAYS);
        }
        return days;
    }

    private static StringBuilder field(StringBuilder b, String name, String value) {
        return b.append("  ").append(quote(name)).append(": ").append(quote(value));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static String names(KeyDestruction d) {
        return String.join(", ", d.keys().stream().map(KeyDestruction.Key::name).toList());
    }

    private static void createResultFile(Path result) {
        try {
            Files.createFile(result);
        } catch (FileAlreadyExistsException e) {
            throw new Refused(Code.CRYPTO_DESTROY_RESULT_FILE, "The result file " + result + " already exists; "
                    + "nothing was destroyed.");
        } catch (IOException e) {
            throw new Refused(Code.CRYPTO_DESTROY_RESULT_FILE, "The result file " + result + " cannot be created ("
                    + e.getClass().getSimpleName() + "); nothing was destroyed.");
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // an empty file left behind holds nothing
        }
    }

    private static String readLine(BufferedReader console) {
        if (console == null) {
            return null;
        }
        try {
            return console.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, String> parse(List<String> args) {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!OPTIONS.contains(a)) {
                throw new Refused(Code.CRYPTO_COMMAND_USAGE, "Unknown option " + a);
            }
            if (i + 1 >= args.size()) {
                throw new Refused(Code.CRYPTO_COMMAND_USAGE, a + " needs a value");
            }
            if (o.put(a, args.get(++i)) != null) {
                throw new Refused(Code.CRYPTO_COMMAND_USAGE, a + " is given twice");
            }
        }
        return o;
    }

    private static String required(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null || v.isBlank()) {
            throw new Refused(Code.CRYPTO_COMMAND_USAGE, name + " is required");
        }
        return v;
    }
}
