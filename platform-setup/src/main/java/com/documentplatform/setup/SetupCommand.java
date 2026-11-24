package com.documentplatform.setup;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.identity.jdbc.IdentitySchemaException;
import com.documentplatform.identity.jdbc.IdentityStoreException;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.jdbc.LicensingSchema;
import com.documentplatform.licensing.jdbc.LicensingSchemaException;
import com.documentplatform.licensing.jdbc.LicensingStoreException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The setup command, direct sign-in mode. Run once per product database (or schema) with the same input files.
 *
 * <pre>
 * setup plan  --licence licence.json --signin signin.json --product PRODUCT_A
 *             --product-roles product-roles.json [--config-out platform-tenant.properties]
 *             [--run-log platform-setup-runs.log] [--remove-unlisted-grants] [--replace-connection]
 *             [--no-customer-keys]
 * setup apply (the same options)
 *
 * Database: PLATFORM_SETUP_DB_URL (jdbc:postgresql://host/db), PLATFORM_SETUP_DB_USER and PLATFORM_SETUP_DB_PASSWORD
 * from the environment only; the app's everyday (DML-only) database user is enough.
 * Key service: PLATFORM_SETUP_VAULT_ADDR and one login (see SetupKeys) from the environment only, with the
 * platform-crypto-setup policy, given to this run only.
 * </pre>
 *
 * <ol>
 *   <li><b>Preflight, no writes:</b> the licence verifies with the trusted keys inside the product's build
 *       ({@link LicenceBuild}: run the setup command the product ships); the sign-in file is valid,
 *       direct mode, for the licence's customer and lists the product; the licence lists the product; the
 *       product-roles file is the product's; the {@code lic_} and {@code idn_} tables are at this release's version;
 *       the database holds no other customer; the licence is not older than the stored one; every mapping row names a
 *       slot in the licence and a role the product knows. Each refusal has its own SETUP_* code (exit 2).</li>
 *   <li><b>Plan:</b> every item as create / unchanged / update / conflict / remove / not removed. {@code plan} stops
 *       here and changes nothing.</li>
 *   <li><b>Apply,</b> in one transaction under {@code pg_advisory_xact_lock}: store the licence, the customer, the
 *       entity slots (never deleted: dropped ones become NOT_IN_LICENCE), the login connection (a connection changed in
 *       the app since the last setup is a conflict, replaced only with {@code --replace-connection}), the grants (added;
 *       grants not in the file removed only with {@code --remove-unlisted-grants}). Then the config fragment with
 *       {@code platform.licensing.tenant-id}.</li>
 *   <li><b>Customer keys</b> ({@link SetupKeys}): the customer's wrapping key and fingerprint key in the key service.
 *       {@code plan} shows whether they exist; {@code apply} creates the missing ones just before the database part is
 *       committed; existing keys are left alone, so a rerun is safe. {@code --no-customer-keys} skips the step.</li>
 *   <li><b>Run log:</b> one line per run with IDs, counts and codes only.</li>
 * </ol>
 *
 * A failure rolls back the whole database part; a rerun does whatever is missing. Exit codes: 0 done or unchanged;
 * 1 failed (rolled back, or the config fragment could not be written: rerun); 2 refused; 3 done except items that
 * need a flag (conflict or not removed).
 *
 * <p>Output and run log never hold secrets: the sign-in file cannot hold any (unknown fields refuse it, naming the
 * field only), the database password is read from the environment and never printed, and database errors are shown by
 * SQL state only.
 */
public final class SetupCommand {

    public static final String DB_URL = "PLATFORM_SETUP_DB_URL";
    public static final String DB_USER = "PLATFORM_SETUP_DB_USER";
    public static final String DB_PASSWORD = "PLATFORM_SETUP_DB_PASSWORD";

    public static final int EXIT_DONE = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_REFUSED = 2;
    public static final int EXIT_NEEDS_FLAG = 3;

    static final String DEFAULT_RUN_LOG = "platform-setup-runs.log";

    private static final List<String> ACTIONS = List.of("plan", "apply");
    private static final Set<String> VALUE_OPTIONS = Set.of("--licence", "--signin", "--product",
            "--product-roles", "--config-out", "--run-log");
    private static final List<String> REQUIRED = List.of("--licence", "--signin", "--product",
            "--product-roles");
    private static final Set<String> FLAGS = Set.of("--remove-unlisted-grants", "--replace-connection",
            "--no-customer-keys");

    private final Map<String, String> env;
    private final Clock clock;
    private final SetupKeys.Connector keyConnector;
    private final Supplier<LicenceBuild> build;

    /**
     * @param env the environment (the database and key service settings are read from it, nothing else)
     */
    public SetupCommand(Map<String, String> env, Clock clock) {
        this(env, clock, SetupKeys::vault, LicenceBuild::load);
    }

    /** For a product's setup wrapper that has its licence keys in code. */
    public SetupCommand(Map<String, String> env, Clock clock, LicenceBuild build) {
        this(env, clock, SetupKeys::vault, () -> build);
    }

    /**
     * Test seam (package-private): the tests in this package pass their own key service connector.
     *
     * @param env          the environment (the database and key service settings are read from it, nothing else)
     * @param keyConnector how to reach the key service (the default: Vault, settings from {@code env})
     */
    SetupCommand(Map<String, String> env, Clock clock, SetupKeys.Connector keyConnector,
                 Supplier<LicenceBuild> build) {
        this.build = Objects.requireNonNull(build, "build");
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.keyConnector = Objects.requireNonNull(keyConnector, "keyConnector");
    }

    public static void main(String[] args) {
        System.exit(new SetupCommand(System.getenv(), Clock.systemUTC()).run(Arrays.asList(args), System.out,
                System.err));
    }

    /** What one run did, for the run log. */
    private static final class Outcome {
        String action = "-";
        String product = "-";
        String tenant = "-";
        String licence = "-";
        String result = "REFUSED";
        String code = "";
        String keys = "-";
        /** This run's ID: in the output, the run log and the grant history rows it writes. */
        String runId = java.util.UUID.randomUUID().toString();
        Plan plan;
        int exit;
    }

    public int run(List<String> args, PrintStream out, PrintStream err) {
        Outcome o = new Outcome();
        Path runLog = null;
        try {
            if (args.isEmpty() || !ACTIONS.contains(args.get(0))) {
                throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, "The first word must be plan or apply. "
                        + usage());
            }
            o.action = args.get(0);
            Map<String, String> options = new LinkedHashMap<>();
            Set<String> flags = new TreeSet<>();
            parse(args.subList(1, args.size()), options, flags);
            runLog = Path.of(options.getOrDefault("--run-log", DEFAULT_RUN_LOG));
            for (String name : REQUIRED) {
                if (!options.containsKey(name)) {
                    throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, name + " is required. " + usage());
                }
            }
            o.product = safe(options.get("--product"));
            try {
                com.documentplatform.identity.store.LoginConnection.requireProductCode(options.get("--product"));
            } catch (IllegalArgumentException e) {
                throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, "--product must be a product code (2 to 32 "
                        + "of A-Z 0-9 _).");
            }
            SetupInputs in = SetupInputs.load(options, build, flags.contains("--remove-unlisted-grants"),
                    flags.contains("--replace-connection"));
            if (flags.contains("--no-customer-keys") && in.roles().customerKeysRequired()) {
                throw new SetupException(SetupRefusal.SETUP_CUSTOMER_KEYS_REQUIRED, "The product-roles file for "
                        + in.product() + " says customerKeys \"required\": --no-customer-keys is not allowed.");
            }
            o.tenant = in.licence().tenantId();
            o.licence = in.licence().licenceId() + "/v" + in.licence().version();
            out.println("Setup " + o.action + " for product " + in.product() + ", customer " + o.tenant + ", licence "
                    + in.licence().licenceId() + " v" + in.licence().version() + " (" + in.licence().kind()
                    + "), sign-in mode " + in.signin().mode() + ", run " + o.runId + ".");
            o.exit = database(in, o, out, flags.contains("--no-customer-keys"));
        } catch (SetupException e) {
            o.code = e.code().name();
            o.result = e.code().exitCode() == EXIT_REFUSED ? "REFUSED" : "FAILED";
            o.exit = e.code().exitCode();
            err.println(e.getMessage());
            for (String d : e.details()) err.println("  " + d);
            err.println("What to do: " + e.code().action());
        }
        if (runLog != null) {
            writeRunLog(runLog, o, err);
        }
        return o.exit;
    }

    /** Checks the tables, then plans (and applies) in one transaction. */
    private int database(SetupInputs in, Outcome o, PrintStream out, boolean noKeys) {
        DataSource ds = dataSource();
        try {
            LicensingSchema.verify(ds);
            IdentitySchema.verify(ds);
        } catch (LicensingSchemaException e) {
            if (e.problem() == LicensingSchemaException.Problem.LIC_CHECK_FAILED) {
                throw new SetupException(SetupRefusal.SETUP_DB_UNREACHABLE, e.problem() + ": " + e.getMessage());
            }
            throw new SetupException(SetupRefusal.SETUP_TABLES_NOT_READY, e.problem() + ": " + e.getMessage());
        } catch (IdentitySchemaException e) {
            if (e.problem() == IdentitySchemaException.Problem.IDN_CHECK_FAILED) {
                throw new SetupException(SetupRefusal.SETUP_DB_UNREACHABLE, e.problem() + ": " + e.getMessage());
            }
            throw new SetupException(SetupRefusal.SETUP_TABLES_NOT_READY, e.problem() + ": " + e.getMessage());
        }
        Plan plan;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                SetupRun run = new SetupRun(in, clock, c, o.runId);
                plan = run.plan();
                o.plan = plan;
                out.println("Plan:");
                plan.items().forEach(i -> out.println(i.line()));
                SetupKeys.Connection keyService = noKeys ? null : keyConnector.connect(env);
                try {
                    CustomerId customer = CustomerId.fromTenantId(in.licence().tenantId());
                    SetupKeys.State keys = keyService == null ? SetupKeys.State.SKIPPED
                            : SetupKeys.check(keyService.keys(), customer);
                    out.println(keysLine(keys, in.licence().tenantId(), customer, keyService));
                    o.keys = keys == SetupKeys.State.CREATE ? "missing" : keys.name().toLowerCase(Locale.ROOT);
                    if (o.action.equals("plan")) {
                        c.rollback();
                        o.result = "PLANNED";
                        out.println("Plan only: nothing was changed. Run 'setup apply' with the same files to do it.");
                        return plan.needsFlag() ? EXIT_NEEDS_FLAG : EXIT_DONE;
                    }
                    if (keys == SetupKeys.State.CREATE) {
                        SetupKeys.create(keyService.keys(), customer);
                        o.keys = "created";
                    }
                    run.apply(plan);
                    c.commit();
                } finally {
                    if (keyService != null) {
                        keyService.close().run();
                    }
                }
            } catch (SetupException e) {
                c.rollback();
                throw e;
            } catch (IdentityStoreException | LicensingStoreException | IllegalStateException e) {
                c.rollback();
                throw new SetupException(SetupRefusal.SETUP_FAILED, "The database part failed and was rolled back: "
                        + e.getMessage());
            } catch (SQLException e) {
                c.rollback();
                throw new SetupException(SetupRefusal.SETUP_FAILED, "The database part failed and was rolled back "
                        + "(SQL state " + e.getSQLState() + ").");
            } catch (RuntimeException e) {
                c.rollback();
                throw new SetupException(SetupRefusal.SETUP_FAILED, "The database part failed and was rolled back ("
                        + e.getClass().getSimpleName() + ").");
            }
        } catch (SQLException e) {
            throw new SetupException(SetupRefusal.SETUP_DB_UNREACHABLE, "The database cannot be reached (SQL state "
                    + e.getSQLState() + ").");
        }
        if (plan.config != ConfigFragment.State.SAME) {
            try {
                ConfigFragment.write(in.configOut(), in.licence().tenantId(), plan.config);
            } catch (IOException | RuntimeException e) {
                throw new SetupException(SetupRefusal.SETUP_CONFIG_NOT_WRITTEN, "The config fragment "
                        + in.configOut().getFileName() + " could not be written (" + e.getClass().getSimpleName() + ").");
            }
            out.println("Config fragment " + in.configOut().getFileName() + ": " + ConfigFragment.KEY + "="
                    + in.licence().tenantId() + ". The app imports it (spring.config.import).");
        }
        Map<Plan.Action, Integer> n = plan.counts();
        String summary = n.get(Plan.Action.CREATE) + " created, " + n.get(Plan.Action.UPDATE) + " updated, "
                + n.get(Plan.Action.REMOVE) + " removed, " + n.get(Plan.Action.SWITCH_OFF) + " switched off, "
                + n.get(Plan.Action.UNCHANGED) + " unchanged";
        if (plan.needsFlag()) {
            o.result = "DONE_EXCEPT_FLAGGED";
            out.println("Setup done except " + (n.get(Plan.Action.CONFLICT) + n.get(Plan.Action.NOT_REMOVED))
                    + " item(s) marked 'conflict' or 'not removed' above, which need a flag (" + summary + ").");
            return EXIT_NEEDS_FLAG;
        }
        o.result = "DONE";
        out.println("Setup done (" + summary + ").");
        return EXIT_DONE;
    }

    /** The keys step's line: the customer, its key-service UUID and what happens; never a key or a login. */
    private static String keysLine(SetupKeys.State state, String tenant, CustomerId customer,
                                   SetupKeys.Connection keyService) {
        String what = "customer keys " + tenant + " (" + customer + ")";
        return "Customer keys:" + System.lineSeparator() + switch (state) {
            case CREATE -> String.format("  %-11s %s in key service %s", "create", what, keyService.keys().id());
            case PRESENT -> String.format("  %-11s %s in key service %s", "unchanged", what, keyService.keys().id());
            case SKIPPED -> String.format("  %-11s %s (--no-customer-keys)", "skipped", what);
        };
    }

    private DataSource dataSource() {
        String url = env.get(DB_URL);
        String user = env.get(DB_USER);
        String password = env.get(DB_PASSWORD);
        List<String> missing = new ArrayList<>();
        if (url == null || url.isBlank()) missing.add(DB_URL);
        if (user == null || user.isBlank()) missing.add(DB_USER);
        if (password == null) missing.add(DB_PASSWORD);
        if (!missing.isEmpty()) {
            throw new SetupException(SetupRefusal.SETUP_DB_SETTINGS_MISSING, "Not set in the environment: "
                    + String.join(", ", missing) + ".");
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new SetupException(SetupRefusal.SETUP_DB_SETTINGS_MISSING, DB_URL
                    + " must be a PostgreSQL JDBC address (jdbc:postgresql://host/database).");
        }
        return Connections.driverManager(url, user, password);
    }

    private void writeRunLog(Path file, Outcome o, PrintStream err) {
        StringBuilder line = new StringBuilder();
        line.append(clock.instant().truncatedTo(ChronoUnit.SECONDS)).append(" setup ").append(o.action)
                .append(" product=").append(o.product).append(" tenant=").append(o.tenant)
                .append(" licence=").append(o.licence).append(" result=").append(o.result)
                .append(" exit=").append(o.exit);
        if (o.plan != null) {
            o.plan.counts().forEach((a, count) -> line.append(' ').append(a.name().toLowerCase()).append('=').append(count));
        }
        if (!o.code.isEmpty()) line.append(" code=").append(o.code);
        line.append(" run=").append(o.runId);
        if (!o.keys.equals("-")) line.append(" keys=").append(o.keys);
        line.append(System.lineSeparator());
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            err.println("Run log " + file.getFileName() + " could not be written (" + e.getClass().getSimpleName()
                    + ").");
        }
    }

    private static void parse(List<String> args, Map<String, String> options, Set<String> flags) {
        for (int i = 0; i < args.size(); i++) {
            String name = args.get(i);
            if (FLAGS.contains(name)) {
                if (!flags.add(name)) throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, name + " is given twice.");
                continue;
            }
            if (!VALUE_OPTIONS.contains(name)) {
                throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, "Unknown option '" + safe(name) + "'. "
                        + usage());
            }
            if (i + 1 >= args.size()) throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, name + " needs a value.");
            if (options.put(name, args.get(++i)) != null) {
                throw new SetupException(SetupRefusal.SETUP_BAD_OPTION, name + " is given twice.");
            }
        }
    }

    private static String usage() {
        return "Usage: setup plan|apply --licence FILE --signin FILE --product CODE "
                + "--product-roles product-roles.json [--config-out FILE] [--run-log FILE] [--remove-unlisted-grants] "
                + "[--replace-connection] [--no-customer-keys]; database settings " + DB_URL + ", " + DB_USER + ", " + DB_PASSWORD
                + " from the environment.";
    }

    /** A value shown back in a message: only harmless characters. */
    private static String safe(String s) {
        return s == null ? "-" : s.replaceAll("[^A-Za-z0-9_.-]", "?");
    }
}
