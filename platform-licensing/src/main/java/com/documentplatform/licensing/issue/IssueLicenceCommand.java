package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.LastSeenReset;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceFormat;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceSigner;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.json.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.documentplatform.licensing.TrustedKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The licence-issuing command (our side only). Builds a licence from options, has it signed by the {@link Signer}
 * (Vault Transit in production: the private key never leaves Vault), writes the licence file and appends a record to
 * the issued-records file. Every run, issued or refused, is written to the run log (with the Vault request IDs, which
 * match Vault's audit log). Nothing it writes or prints contains key material, a Vault token or the signature.
 *
 * <pre>
 * issue-licence --customer "Example Customer Ltd" --products PRODUCT_A,PRODUCT_B
 *     [--type SUBSCRIPTION|PERPETUAL]  --kind INSTALL|PRODUCTION|NON_PRODUCTION
 *     --starts 2026-12-01  (--expires 2027-11-30 | --updates-until 2027-11-30)
 *     --deployment HOSTED|ON_PREM
 *     --slot [SLOT_ID@]PRODUCT:TYPE=AMOUNT[,PRODUCT:TYPE=AMOUNT...]   (repeat per slot)
 *     [--tenant-id TEN-...]   (new version for an existing tenant; omit for a new customer)
 *     --key-id lic-2026[:VERSION]  --records issued.jsonl  --out licence.json  [--run-log issued.runs.log]
 *
 * issue-licence --export-trusted-keys lic-2026[,lic-2027]  --out trusted-keys.json  [--run-log ...]
 *
 * Vault (every form): [--vault-addr https://vault:8200]  (else VAULT_ADDR)   [--vault-mount transit]
 *     [--vault-login token|userpass]  (token: VAULT_TOKEN)   [--vault-user NAME  [--vault-login-mount userpass]]
 * </pre>
 *
 * Exit codes: 0 issued; 1 refused (the message starts with the {@link IssueError} code); 2 options wrong.
 */
public final class IssueLicenceCommand {

    private static final System.Logger LOG = System.getLogger(IssueLicenceCommand.class.getName());
    static final int MAX_INSTALL_DAYS = 60;

    private static final Set<String> VAULT_OPTIONS = Set.of("--vault-addr", "--vault-mount", "--vault-login",
            "--vault-user", "--vault-login-mount");
    private static final Set<String> SINGLE = union(Set.of("--customer", "--products", "--type", "--kind", "--starts",
            "--expires", "--updates-until", "--deployment", "--tenant-id", "--key-id", "--records", "--out", "--run-log",
            "--reset-last-seen", "--valid-days", "--export-trusted-keys"), VAULT_OPTIONS);
    private static final Set<String> RESET_OPTIONS = union(Set.of("--reset-last-seen", "--valid-days", "--tenant-id",
            "--key-id", "--records", "--out", "--run-log"), VAULT_OPTIONS);
    private static final Set<String> EXPORT_OPTIONS = union(Set.of("--export-trusted-keys", "--out", "--run-log",
            "--records"), VAULT_OPTIONS);
    static final int DEFAULT_RESET_DAYS = 7;
    static final int MAX_RESET_DAYS = 30;
    private static final String SLOT = "--slot";

    /** Opens the signer for one run, from the run's options (Vault address, login). */
    @FunctionalInterface
    public interface SignerFactory {
        /** @throws IssueException when the options or the environment do not give a usable signer */
        Signer open(Map<String, String> options);
    }

    private final SignerFactory signers;
    private final Clock clock;
    private final IdGenerator ids;
    private Signer signer;

    public IssueLicenceCommand(SignerFactory signers, Clock clock, RandomGenerator random) {
        this.signers = Objects.requireNonNull(signers, "signers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = new IdGenerator(random);
    }

    /** With one signer for every run (tests). */
    public IssueLicenceCommand(Signer signer, Clock clock, RandomGenerator random) {
        this(options -> signer, clock, random);
        Objects.requireNonNull(signer, "signer");
    }

    /** Signs with Vault Transit ({@link VaultTransitSigner#from}); the private key never leaves Vault. */
    public static void main(String[] args) {
        int code = new IssueLicenceCommand(options -> VaultTransitSigner.from(options, System.getenv(),
                VaultTransitSigner.consolePrompt()), Clock.systemUTC(), new SecureRandom())
                .run(Arrays.asList(args), System.out, System.err);
        System.exit(code);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new HashSet<>(a);
        s.addAll(b);
        return Set.copyOf(s);
    }

    /** The run's signer, opened on first use (so wrong options never reach Vault). */
    private Signer signer(Map<String, String> options) {
        if (signer == null) {
            signer = signers.open(options);
        }
        return signer;
    }

    /** The result of an issued licence. */
    public record Issued(Licence licence, Path licenceFile, String payloadSha256) {
    }

    /** Runs the command; returns the exit code. */
    public int run(List<String> args, PrintStream out, PrintStream err) {
        try {
            return runOnce(args, out, err);
        } finally {
            if (signer != null) {
                signer.close();
                signer = null;
            }
        }
    }

    private int runOnce(List<String> args, PrintStream out, PrintStream err) {
        Map<String, String> options = null;
        try {
            Parsed parsed = parse(args);
            options = parsed.single();
            if (options.containsKey("--export-trusted-keys")) {
                TrustedKeys exported = exportKeys(parsed);
                Path file = Path.of(options.get("--out"));
                out.println("Exported " + exported.keyIds().size() + " public key(s) " + exported.keyIds()
                        + " -> " + file);
                out.println("Install config (key IDs with ':' need the brackets):");
                out.println("platform:\n  licensing:\n    trusted-keys:");
                for (String id : exported.keyIds()) {
                    out.println("      \"[" + id + "]\": " + Ed25519.encodePublic(exported.key(id).orElseThrow()));
                }
                runLog(options, "KEYS_EXPORTED", null, null, null, exported.keyIds());
                LOG.log(System.Logger.Level.INFO, "Exported public keys {0}", exported.keyIds());
                return 0;
            }
            if (options.containsKey("--reset-last-seen")) {
                LastSeenReset reset = issueReset(parsed);
                String summary = "Issued last-seen reset " + reset.resetId() + " for tenant " + reset.tenantId()
                        + ": sets the latest time seen to " + reset.resetTo() + ", valid until " + reset.notAfter()
                        + ", key " + reset.keyId() + " -> " + options.get("--out");
                out.println(summary);
                runLog(options, "RESET_ISSUED", null, null, reset, null);
                LOG.log(System.Logger.Level.INFO, summary);
                return 0;
            }
            Issued issued = issue(parsed);
            String summary = "Issued " + issued.licence().licenceId() + " v" + issued.licence().version()
                    + " for tenant " + issued.licence().tenantId() + " (" + issued.licence().kind() + ", "
                    + issued.licence().type() + "), slots " + issued.licence().entitySlots().stream()
                    .map(EntitySlot::slotId).toList() + ", key " + issued.licence().keyId() + ", payload sha256 "
                    + issued.payloadSha256().substring(0, 12) + "... -> " + issued.licenceFile();
            out.println(summary);
            runLog(options, "ISSUED", null, issued.licence(), null, null);
            LOG.log(System.Logger.Level.INFO, summary);
            return 0;
        } catch (IssueException e) {
            err.println(e.getMessage());
            runLog(options != null ? options : lenient(args), "REFUSED", e.code(), null, null, null);
            LOG.log(System.Logger.Level.WARNING, "Licence not issued: {0}", e.code());
            return e.code() == IssueError.ISSUE_BAD_OPTION || e.code() == IssueError.ISSUE_MISSING_OPTION ? 2 : 1;
        } catch (RuntimeException e) {
            err.println("Licence not issued: unexpected " + e.getClass().getSimpleName() + ".");
            runLog(options != null ? options : lenient(args), "FAILED", null, null, null, null);
            LOG.log(System.Logger.Level.ERROR, "Licence not issued: unexpected {0}", e.getClass().getSimpleName());
            return 1;
        }
    }

    /** The options that locate the run log, read leniently from arguments that did not parse. */
    private static Map<String, String> lenient(List<String> args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.size(); i++) {
            String name = args.get(i);
            if (name.equals("--records") || name.equals("--run-log") || name.equals("--key-id") || name.equals("--out")) {
                m.putIfAbsent(name, args.get(i + 1));
            }
        }
        return m;
    }

    private record Parsed(Map<String, String> single, List<String> slots) {
    }

    private static Parsed parse(List<String> args) {
        Map<String, String> single = new LinkedHashMap<>();
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String name = args.get(i);
            if (!SINGLE.contains(name) && !SLOT.equals(name)) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, "Unknown option '" + shown(name) + "'. Options: "
                        + new java.util.TreeSet<>(SINGLE) + " and --slot.");
            }
            if (i + 1 >= args.size()) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, name + " needs a value.");
            }
            String value = args.get(++i);
            if (SLOT.equals(name)) {
                slots.add(value);
            } else if (single.put(name, value) != null) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, name + " is given twice.");
            }
        }
        return new Parsed(single, slots);
    }

    /**
     * Issues a signed last-seen reset for a tenant in the issued records: {@code --reset-last-seen <UTC time>}
     * (the time to set the latest time seen back to, normally now), {@code --tenant-id}, optional
     * {@code --valid-days} (default 7, at most 30), {@code --key-id}, {@code --records}, {@code --out}.
     */
    LastSeenReset issueReset(Parsed p) {
        Map<String, String> o = p.single();
        for (String name : o.keySet()) {
            if (!RESET_OPTIONS.contains(name)) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, name + " is not used with --reset-last-seen;"
                        + " a reset takes " + new java.util.TreeSet<>(RESET_OPTIONS) + ".");
            }
        }
        if (!p.slots().isEmpty()) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--slot is not used with --reset-last-seen.");
        }
        Instant resetTo;
        try {
            resetTo = Instant.parse(required(o, "--reset-last-seen"));
        } catch (DateTimeParseException e) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION,
                    "--reset-last-seen must be a UTC time such as 2026-11-21T10:00:00Z.");
        }
        int days = DEFAULT_RESET_DAYS;
        if (o.containsKey("--valid-days")) {
            try {
                days = Integer.parseInt(o.get("--valid-days"));
            } catch (NumberFormatException e) {
                days = -1;
            }
            if (days < 1 || days > MAX_RESET_DAYS) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--valid-days must be 1 to " + MAX_RESET_DAYS + ".");
            }
        }
        String tenantId = required(o, "--tenant-id");
        String keyId = required(o, "--key-id");
        Path out = Path.of(required(o, "--out"));
        if (Files.exists(out)) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_EXISTS, out.toString());
        }
        IssuedRecords records = IssuedRecords.load(Path.of(required(o, "--records")));
        if (records.forTenant(tenantId).isEmpty()) {
            throw new IssueException(IssueError.ISSUE_UNKNOWN_TENANT, "Tenant " + shown(tenantId)
                    + " was never issued a licence.");
        }
        Signer s = signer(o);
        String signingKey = s.resolveKeyId(keyId);
        Instant now = clock.instant();
        LastSeenReset reset;
        try {
            reset = new LastSeenReset(ids.next("RST", records.allIds()), tenantId, resetTo,
                    now.plus(java.time.Duration.ofDays(days)), now, signingKey);
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, e.getMessage() + ".");
        }
        String file;
        try {
            file = reset.sign(message -> signWith(s, signingKey, message));
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Key ID " + keyId + ".");
        }
        try {
            Files.writeString(out, file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_EXISTS, out.toString());
        } catch (IOException e) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_UNWRITABLE, out + ": " + e.getClass().getSimpleName());
        }
        return reset;
    }

    /** Issues one licence. Holds a lock beside the records file for the whole run, so two runs never share an ID. */
    Issued issue(Parsed p) {
        Map<String, String> o = p.single();
        Path records = Path.of(required(o, "--records"));
        Path out = Path.of(required(o, "--out"));
        Path lockFile = records.resolveSibling(records.getFileName() + ".lock");
        try {
            if (records.toAbsolutePath().getParent() != null) {
                Files.createDirectories(records.toAbsolutePath().getParent());
            }
            try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = ch.lock()) {
                return issueLocked(p, records, out);
            }
        } catch (IOException e) {
            throw new IssueException(IssueError.ISSUE_RECORDS_UNWRITABLE, "Cannot lock " + lockFile + ": "
                    + e.getClass().getSimpleName());
        }
    }

    private Issued issueLocked(Parsed p, Path recordsFile, Path out) {
        Map<String, String> o = p.single();
        String customer = required(o, "--customer");
        List<String> products = Arrays.stream(required(o, "--products").split(",")).map(String::strip).toList();
        LicenceType type = enumOption(o, "--type", LicenceType.class, LicenceType.SUBSCRIPTION);
        LicenceKind kind = enumOption(o, "--kind", LicenceKind.class, null);
        Deployment deployment = enumOption(o, "--deployment", Deployment.class, null);
        LocalDate starts = date(o, "--starts", true);
        LocalDate expires = date(o, "--expires", false);
        LocalDate updatesUntil = date(o, "--updates-until", false);
        String keyId = required(o, "--key-id");
        if (p.slots().isEmpty()) {
            throw new IssueException(IssueError.ISSUE_MISSING_OPTION, "At least one --slot is required.");
        }
        if (type == LicenceType.SUBSCRIPTION && expires == null) {
            throw new IssueException(IssueError.ISSUE_MISSING_OPTION, "A SUBSCRIPTION licence needs --expires.");
        }
        if (type == LicenceType.PERPETUAL && updatesUntil == null) {
            throw new IssueException(IssueError.ISSUE_MISSING_OPTION, "A PERPETUAL licence needs --updates-until.");
        }
        if (type == LicenceType.SUBSCRIPTION && updatesUntil != null) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--updates-until is for PERPETUAL licences only.");
        }
        if (type == LicenceType.PERPETUAL && expires != null) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--expires is for SUBSCRIPTION licences only;"
                    + " a perpetual licence never expires.");
        }
        if (kind == LicenceKind.INSTALL) {
            if (type != LicenceType.SUBSCRIPTION) {
                throw new IssueException(IssueError.ISSUE_INVALID_LICENCE, "An INSTALL licence is a SUBSCRIPTION.");
            }
            long days = ChronoUnit.DAYS.between(starts, expires) + 1;
            if (days > MAX_INSTALL_DAYS) {
                throw new IssueException(IssueError.ISSUE_INSTALL_TOO_LONG, "--starts " + starts + " to --expires "
                        + expires + " is " + days + " days.");
            }
        }
        if (Files.exists(out)) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_EXISTS, out.toString());
        }

        IssuedRecords records = IssuedRecords.load(recordsFile);
        Set<String> taken = records.allIds();

        String tenantId = o.get("--tenant-id");
        long version = 1;
        Set<String> tenantSlots = new HashSet<>();
        if (tenantId == null) {
            tenantId = ids.next("TEN", taken);
        } else {
            List<IssuedRecords.Entry> history = records.forTenant(tenantId);
            if (history.isEmpty()) {
                throw new IssueException(IssueError.ISSUE_UNKNOWN_TENANT, "Tenant " + shown(tenantId)
                        + " was never issued. Omit --tenant-id for a new customer.");
            }
            IssuedRecords.Entry last = history.get(history.size() - 1);
            if (!last.customer().equals(customer)) {
                throw new IssueException(IssueError.ISSUE_TENANT_CUSTOMER_MISMATCH, "Tenant " + tenantId
                        + " belongs to '" + last.customer() + "', not '" + customer + "'.");
            }
            boolean wasTest = LicenceKind.valueOf(last.kind()).testEnvironment();
            if (wasTest != kind.testEnvironment()) {
                throw new IssueException(IssueError.ISSUE_TENANT_KIND_MISMATCH, "Tenant " + tenantId + " is a "
                        + (wasTest ? "NON_PRODUCTION" : "production/install") + " tenant; a " + kind
                        + " licence needs " + (wasTest ? "a production/install" : "its own NON_PRODUCTION")
                        + " tenant (omit --tenant-id to create one).");
            }
            version = history.stream().mapToLong(IssuedRecords.Entry::version).max().orElse(0) + 1;
            history.forEach(e -> tenantSlots.addAll(e.slotIds()));
        }

        List<EntitySlot> slots = new ArrayList<>();
        Set<String> usedHere = new HashSet<>();
        for (String spec : p.slots()) {
            String slotId = null;
            String allowancesSpec = spec;
            int at = spec.indexOf('@');
            if (at >= 0) {
                slotId = spec.substring(0, at).strip();
                allowancesSpec = spec.substring(at + 1);
                if (!tenantSlots.contains(slotId)) {
                    throw new IssueException(IssueError.ISSUE_UNKNOWN_SLOT, "Slot " + shown(slotId)
                            + " was not issued to " + (o.get("--tenant-id") == null ? "a new tenant" : "tenant " + tenantId)
                            + ". Omit the ID to add a new slot.");
                }
                if (!usedHere.add(slotId)) {
                    throw new IssueException(IssueError.ISSUE_BAD_OPTION, "Slot " + slotId + " is given twice.");
                }
            }
            List<Allowance> allowances = allowances(allowancesSpec);
            if (slotId == null) {
                slotId = ids.next("ENT", taken);
            }
            try {
                slots.add(new EntitySlot(slotId, allowances));
            } catch (IllegalArgumentException e) {
                throw new IssueException(IssueError.ISSUE_INVALID_LICENCE, e.getMessage() + ".");
            }
        }
        String licenceId = ids.next("LIC", taken);
        String signingKey = signer(o).resolveKeyId(keyId);
        Instant now = clock.instant();
        Licence licence;
        try {
            licence = new Licence(licenceId, version, customer, tenantId, products, slots, type, kind, starts,
                    expires, updatesUntil, deployment, signingKey, now);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IssueException(IssueError.ISSUE_INVALID_LICENCE, e.getMessage() + ".");
        }

        String file = sign(signer(o), licence);
        String payloadSha = Ed25519.sha256Hex(LicenceFormat.signedMessage(LicenceFormat.payload(licence)));
        try {
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            Files.writeString(out, file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_EXISTS, out.toString());
        } catch (IOException e) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_UNWRITABLE, out + ": " + e.getClass().getSimpleName());
        }
        try {
            records.append(licence, payloadSha, out);
        } catch (IssueException e) {
            try {
                Files.deleteIfExists(out); // an unrecorded licence must not be handed out
            } catch (IOException ignored) {
                // reported below
            }
            throw e;
        }
        return new Issued(licence, out, payloadSha);
    }

    /** One signature; a signer's own refusal keeps its code, anything else it throws is reported without its text. */
    private static byte[] signWith(Signer signer, String keyId, byte[] message) {
        try {
            return signer.sign(keyId, message);
        } catch (IssueException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IssueException(IssueError.ISSUE_KEY_UNAVAILABLE, "Key ID " + keyId + " ("
                    + e.getClass().getSimpleName() + ").");
        }
    }

    private static String sign(Signer signer, Licence licence) {
        try {
            return LicenceSigner.sign(licence, message -> signWith(signer, licence.keyId(), message));
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Key ID " + licence.keyId() + ".");
        }
    }

    /**
     * Writes the public keys of every version of the named signing keys into the trusted-keys file apps ship:
     * {@code --export-trusted-keys lic-2026[,lic-2027] --out trusted-keys.json}. Public keys only.
     */
    private TrustedKeys exportKeys(Parsed p) {
        Map<String, String> o = p.single();
        for (String name : o.keySet()) {
            if (!EXPORT_OPTIONS.contains(name)) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, name + " is not used with --export-trusted-keys;"
                        + " an export takes " + new java.util.TreeSet<>(EXPORT_OPTIONS) + ".");
            }
        }
        if (!p.slots().isEmpty()) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--slot is not used with --export-trusted-keys.");
        }
        Path out = Path.of(required(o, "--out"));
        if (Files.exists(out)) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_EXISTS, out.toString());
        }
        Map<String, java.security.PublicKey> all = new java.util.TreeMap<>();
        Signer s = signer(o);
        for (String name : required(o, "--export-trusted-keys").split(",")) {
            String n = name.strip();
            try {
                all.putAll(s.publicKeys(n));
            } catch (IssueException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IssueException(IssueError.ISSUE_KEY_UNAVAILABLE, "Key " + shown(n) + " ("
                        + e.getClass().getSimpleName() + ").");
            }
        }
        if (all.isEmpty()) {
            throw new IssueException(IssueError.ISSUE_KEY_NOT_FOUND, "The keys named have no versions.");
        }
        TrustedKeys keys = TrustedKeys.of(all);
        try {
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            Files.writeString(out, keys.toJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_EXISTS, out.toString());
        } catch (IOException e) {
            throw new IssueException(IssueError.ISSUE_OUTPUT_UNWRITABLE, out + ": " + e.getClass().getSimpleName());
        }
        return keys;
    }

    /** {@code PRODUCT:TYPE=AMOUNT[,PRODUCT:TYPE=AMOUNT...]} */
    static List<Allowance> allowances(String spec) {
        List<Allowance> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String s = part.strip();
            int colon = s.indexOf(':');
            int eq = s.indexOf('=');
            if (colon <= 0 || eq <= colon + 1 || eq == s.length() - 1) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, "--slot allowance '" + shown(s)
                        + "' must be PRODUCT:TYPE=AMOUNT, for example PRODUCT_A:PAGES_PER_MONTH=10000.");
            }
            String product = s.substring(0, colon);
            String typeName = s.substring(colon + 1, eq).toUpperCase(Locale.ROOT);
            AllowanceType type;
            try {
                type = AllowanceType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, "Allowance type '" + shown(typeName)
                        + "' is not one of " + Arrays.toString(AllowanceType.values()) + ".");
            }
            long amount;
            try {
                amount = Long.parseLong(s.substring(eq + 1));
            } catch (NumberFormatException e) {
                throw new IssueException(IssueError.ISSUE_BAD_OPTION, "Allowance amount in '" + shown(s)
                        + "' is not a whole number.");
            }
            try {
                out.add(new Allowance(product, type, amount));
            } catch (IllegalArgumentException e) {
                throw new IssueException(IssueError.ISSUE_INVALID_LICENCE, e.getMessage() + ".");
            }
        }
        return out;
    }

    private static String required(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null || v.isBlank()) {
            throw new IssueException(IssueError.ISSUE_MISSING_OPTION, name + " is required.");
        }
        return v;
    }

    private static <E extends Enum<E>> E enumOption(Map<String, String> o, String name, Class<E> type, E fallback) {
        String v = o.get(name);
        if (v == null) {
            if (fallback == null) {
                throw new IssueException(IssueError.ISSUE_MISSING_OPTION, name + " is required, one of "
                        + Arrays.toString(type.getEnumConstants()) + ".");
            }
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, name + " must be one of "
                    + Arrays.toString(type.getEnumConstants()) + ".");
        }
    }

    private static LocalDate date(Map<String, String> o, String name, boolean required) {
        String v = required ? required(o, name) : o.get(name);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IssueException(IssueError.ISSUE_BAD_OPTION, name + " must be a date written YYYY-MM-DD.");
        }
    }

    /** Values echoed back are cut short and stripped to safe characters: a key may have been pasted by mistake. */
    private static String shown(String value) {
        String s = value.replaceAll("[^A-Za-z0-9 ._@:=,-]", "?");
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private void runLog(Map<String, String> options, String outcome, IssueError code, Licence licence,
                        LastSeenReset reset, java.util.Collection<String> exportedKeyIds) {
        if (options == null || (options.get("--records") == null && options.get("--run-log") == null)) {
            return; // nothing to locate the log by; stderr and the logger have the refusal
        }
        Path log;
        if (options.containsKey("--run-log")) {
            log = Path.of(options.get("--run-log"));
        } else {
            Path records = Path.of(options.get("--records"));
            log = records.resolveSibling(records.getFileName() + ".runs.log");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", clock.instant().toString());
        m.put("outcome", outcome);
        if (code != null) {
            m.put("code", code.name());
        }
        if (licence != null) {
            m.put("licenceId", licence.licenceId());
            m.put("version", licence.version());
            m.put("tenantId", licence.tenantId());
            m.put("kind", licence.kind().name());
        }
        if (reset != null) {
            m.put("resetId", reset.resetId());
            m.put("tenantId", reset.tenantId());
            m.put("resetTo", reset.resetTo().toString());
            m.put("notAfter", reset.notAfter().toString());
        }
        // The key that signed (Vault: name and version); else the key ID asked for, if it has the shape of one.
        String keyId = licence != null ? licence.keyId() : reset != null ? reset.keyId() : options.get("--key-id");
        Optional.ofNullable(keyId).filter(k -> k.matches("[a-z0-9._-]{1,64}(:[0-9]{1,9})?"))
                .ifPresent(k -> m.put("keyId", k));
        if (exportedKeyIds != null) {
            m.put("keyIds", List.copyOf(exportedKeyIds));
        }
        if (signer != null) {
            m.put("signer", signer.describe());
            List<String> refs = signer.auditReferences();
            if (!refs.isEmpty()) {
                m.put("signerAuditRefs", refs);
            }
        }
        Optional.ofNullable(options.get("--out")).map(f -> Path.of(f).getFileName())
                .ifPresent(f -> m.put("out", shown(f.toString())));
        try {
            if (log.toAbsolutePath().getParent() != null) {
                Files.createDirectories(log.toAbsolutePath().getParent());
            }
            Files.writeString(log, Json.canonical(m) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Run log {0} cannot be written: {1}", log, e.getClass().getSimpleName());
        }
    }
}
