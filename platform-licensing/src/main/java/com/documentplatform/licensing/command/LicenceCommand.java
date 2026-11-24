package com.documentplatform.licensing.command;

import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LastSeenReset;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.ResetException;
import com.documentplatform.licensing.SignedDocument;
import com.documentplatform.licensing.jdbc.LicensingSchema;
import com.documentplatform.licensing.jdbc.LicensingSchemaException;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.documentplatform.licensing.jdbc.LicensingStoreException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The licence command (customer side): stores a licence (first install or renewal), shows the status, or applies a
 * last-seen reset from us. Verifies before storing; refuses another customer's licence; storing the same licence
 * again changes nothing.
 *
 * <pre>
 * licence store       --file licence.json   [connection options]
 * licence status                            [connection options]
 * licence apply-reset --file reset.json     [connection options]
 *
 * connection options: --jdbc-url jdbc:postgresql://host/db  --db-user app_user
 *                     [--tenant-id TEN-...] [--product-code CODE] [--clock-tolerance PT1H]
 * The trusted keys are those inside the product's own build ({@link LicenceBuild}): run the command the product ships.
 * apply-reset needs the schema owner's database user (only the owner may move the latest time seen back).
 * The database password is read from the environment variable PLATFORM_LICENSING_DB_PASSWORD, never an option.
 * </pre>
 *
 * Exit codes: 0 done; 1 refused (the message starts with the stable code); 2 options wrong.
 */
public final class LicenceCommand {

    public static final String PASSWORD_VARIABLE = "PLATFORM_LICENSING_DB_PASSWORD";

    private static final Set<String> OPTIONS = Set.of("--file", "--jdbc-url", "--db-user", "--tenant-id",
            "--product-code", "--clock-tolerance");
    private static final List<String> ACTIONS = List.of("store", "status", "apply-reset");

    /** Builds the service from the parsed options. */
    public interface ServiceFactory {
        LicenceService create(Map<String, String> options);
    }

    private final ServiceFactory factory;

    public LicenceCommand(ServiceFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public static void main(String[] args) {
        System.exit(new LicenceCommand(o -> jdbc(o, System.getenv(), Clock.systemUTC(), builtIn()))
                .run(Arrays.asList(args), System.out, System.err));
    }

    /** The service on the installation's database, as {@link #main} builds it. */
    public static LicenceService jdbc(Map<String, String> o, Map<String, String> env, Clock clock,
                                      LicenceBuild build) {
        DataSource ds = new DriverManagerDataSource(required(o, "--jdbc-url"), required(o, "--db-user"),
                env.getOrDefault(PASSWORD_VARIABLE, ""));
        LicensingSchema.verify(ds);
        return service(o, new PostgresLicenceStore(ds), new PostgresLastSeenStore(ds), new PostgresUsageLedger(ds),
                clock, build);
    }

    private static LicenceBuild builtIn() {
        try {
            return LicenceBuild.load();
        } catch (LicenceBuild.Invalid e) {
            throw new UsageError(e.getMessage());
        }
    }

    /** The service on the given stores, with the build's keys, and tenant, product code and tolerance from the options. */
    public static LicenceService service(Map<String, String> o, com.documentplatform.licensing.LicenceStore licences,
                                         com.documentplatform.licensing.LastSeenStore lastSeen,
                                         com.documentplatform.licensing.usage.UsageLedger ledger, Clock clock,
                                         LicenceBuild build) {
        Duration tolerance = ClockGuard.DEFAULT_TOLERANCE;
        if (o.containsKey("--clock-tolerance")) {
            try {
                tolerance = Duration.parse(o.get("--clock-tolerance"));
            } catch (DateTimeParseException e) {
                throw new UsageError("--clock-tolerance must be an ISO-8601 duration such as PT1H.");
            }
        }
        try {
            return new LicenceService(licences, new LicenceVerifier(build.keys()),
                    new ClockGuard(clock, tolerance, lastSeen, build.builtAt()),
                    ledger, o.get("--product-code"), o.get("--tenant-id"));
        } catch (IllegalArgumentException e) {
            throw new UsageError(e.getMessage() + ".");
        }
    }

    /** Options that are wrong: exit code 2. */
    static final class UsageError extends RuntimeException {
        UsageError(String message) {
            super(message);
        }
    }

    public int run(List<String> args, PrintStream out, PrintStream err) {
        try {
            if (args.isEmpty() || !ACTIONS.contains(args.get(0))) {
                throw new UsageError("The first word must be one of " + ACTIONS + ".");
            }
            String action = args.get(0);
            Map<String, String> o = parse(args.subList(1, args.size()));
            if (!action.equals("status") && !o.containsKey("--file")) {
                throw new UsageError(action + " needs --file.");
            }
            LicenceService service = factory.create(o);
            switch (action) {
                case "store" -> {
                    LicenceService.StoreOutcome r = service.store(readFile(o.get("--file")));
                    out.println(switch (r.result()) {
                        case STORED_FIRST -> "Stored licence " + r.licence().licenceId() + " v" + r.licence().version() + " (first licence).";
                        case STORED_CURRENT -> "Stored licence " + r.licence().licenceId() + " v" + r.licence().version() + "; it is now in force.";
                        case STORED_WAITING -> "Stored licence " + r.licence().licenceId() + " v" + r.licence().version()
                                + "; it takes over on " + r.licence().startsOn() + ". The current licence stays in force until then.";
                        case ALREADY_STORED -> "Licence " + r.licence().licenceId() + " v" + r.licence().version() + " was already stored; nothing changed.";
                    });
                    out.println(statusLine(r.status()));
                }
                case "status" -> out.println(statusLine(service.status()));
                case "apply-reset" -> {
                    LastSeenReset.Outcome outcome = service.applyReset(readFile(o.get("--file")));
                    out.println(switch (outcome) {
                        case APPLIED -> "Reset applied: the latest time seen was set back.";
                        case ALREADY_APPLIED -> "This reset was applied before; nothing changed.";
                        case NOT_NEEDED -> "Nothing to reset: the latest time seen is not ahead of the reset time.";
                    });
                    out.println(statusLine(service.status()));
                }
                default -> throw new IllegalStateException();
            }
            return 0;
        } catch (UsageError e) {
            err.println("Options: " + e.getMessage());
            return 2;
        } catch (LicenceException e) {
            err.println(e.getMessage());
            err.println("What to do: " + e.code().action());
            return 1;
        } catch (ResetException | LicensingSchemaException | LicensingStoreException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    /** One plain line: licence, type, kind, entities, expiry or updates-until, state, then the state message. */
    public static String statusLine(LicenceStatus s) {
        Licence l = s.licence();
        String end = l.type() == LicenceType.PERPETUAL ? "updates until " + l.updatesUntil() : "expires " + l.expiresOn();
        String line = "Licence " + l.licenceId() + " v" + l.version() + " for " + l.customer() + " (tenant "
                + l.tenantId() + "): " + l.type() + ", " + l.kind() + ", " + l.entitySlots().size()
                + (l.entitySlots().size() == 1 ? " entity" : " entities") + ", " + end + ". State " + s.state()
                + ". " + s.message();
        return s.clock().message().map(m -> line + " " + m).orElse(line);
    }

    private static Map<String, String> parse(List<String> args) {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String name = args.get(i);
            if (!OPTIONS.contains(name)) {
                throw new UsageError("Unknown option '" + name.replaceAll("[^A-Za-z0-9-]", "?") + "'. Options: "
                        + new java.util.TreeSet<>(OPTIONS) + ".");
            }
            if (i + 1 >= args.size()) {
                throw new UsageError(name + " needs a value.");
            }
            if (o.put(name, args.get(++i)) != null) {
                throw new UsageError(name + " is given twice.");
            }
        }
        return o;
    }

    private static byte[] readFile(String name) {
        try {
            Path file = Path.of(name);
            if (Files.size(file) > SignedDocument.MAX_BYTES) {
                throw new UsageError("--file " + name + " is larger than any licence or reset file.");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UsageError("--file " + name + " cannot be read (" + e.getClass().getSimpleName() + ").");
        }
    }

    private static String required(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null || v.isBlank()) {
            throw new UsageError(name + " is required.");
        }
        return v;
    }
}
