package com.documentplatform.integration.watch;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.WatchSource;
import com.documentplatform.integration.intake.IntakeAnswer;
import com.documentplatform.integration.intake.IntakeFile;
import com.documentplatform.integration.intake.IntakeHandler;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.scan.ScanEvents;
import com.documentplatform.integration.scan.ScanResult;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.StoredIntegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A watched folder or network share (kind {@code WATCHED_FOLDER}): one pass per call; the app keeps the schedule.
 * platform-core finds, waits, claims, reads, scans and hands over; the app decides what a drop means.
 *
 * <p>One pass:
 * <ol>
 *   <li>stuck claims (older than the claim time: their instance died) are put back;</li>
 *   <li>drops are found: a subfolder each, or the root's files grouped by base name. A drop is taken only when its
 *       trigger is there (the customer's process writes it <b>last</b>) and its files are stable (sizes and
 *       modification times unchanged for the configured time). A drop without its trigger after the
 *       incomplete-drop time is reported once as incomplete; nothing is moved;</li>
 *   <li>each drop is <b>claimed</b> by one atomic rename into {@code _processing/<drop>-<fence>}, the fence a random
 *       token new for each claim, so two app instances never take the same drop: the one whose rename fails lost the
 *       claim (in the base-name layout the trigger is moved first, and its move decides). Every later step checks
 *       that its own claim folder is still there;</li>
 *   <li>its files are checked (no link, no folder, the limits; each read only inside the root) and their SHA-256 and
 *       sizes taken; the trigger is read with the hardened parser and the fixed mapping applied;</li>
 *   <li>every file is scanned (when a scanner is configured): an infected drop goes to {@code _quarantine/} and the
 *       app is told ({@link ScanEvents}); a scan that failed puts the drop back to be scanned at the next pass;</li>
 *   <li>the app's handler answers, and the drop is moved: {@code _archive/} on TAKEN or ALREADY_TAKEN,
 *       {@code _problem/} with a small {@code problem.json} (code and time, never content) on REFUSED or a problem.
 *       A handler that fails (an error, not an answer) puts the drop back and stops the pass.</li>
 * </ol>
 * A restart at any point is safe: a claimed drop is put back after the claim time and handed over again, and the
 * app's idempotency answers ALREADY_TAKEN for what it has. Links (symbolic links, junctions) are never followed.
 */
public final class WatchedFolder {

    /** The marker in a claim folder: the claim's fence and time. */
    public static final String CLAIM_MARKER = ".platform-claim";
    public static final String PROBLEM_FORMAT = "platform-watch-problem/1";

    private static final System.Logger LOG = System.getLogger(WatchedFolder.class.getName());
    private static final Pattern CLAIMED = Pattern.compile("(.+)-([0-9a-f]{12})");
    private static final int MAX_DROP_NAME = 150;
    private static final int MAX_REMEMBERED = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WatchFiles files;
    private final Installation installation;
    private final Scanning scanning;
    private final Clock clock;
    /** Per drop: what it looked like and since when (for the stability rule across passes). */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    /** Incomplete drops already reported (by drop and its oldest file's time). */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private record Seen(String snapshot, Instant since) {
    }

    public WatchedFolder(WatchFiles files, Installation installation, Scanning scanning, Clock clock) {
        this.files = Objects.requireNonNull(files, "files");
        this.installation = Objects.requireNonNull(installation, "installation");
        this.scanning = Objects.requireNonNull(scanning, "scanning");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One pass over the folder; {@code handler} is called once per drop taken. */
    public WatchResult pass(StoredIntegration stored, IntakeHandler<Drop> handler) {
        Objects.requireNonNull(handler, "handler");
        Pass p = new Pass(stored, handler);
        WatchResult r = p.go();
        LOG.log(r.ok() ? System.Logger.Level.INFO : System.Logger.Level.WARNING, "Integration {0} v{1} watch: {2}",
                stored.config().id(), stored.version(), r);
        return r;
    }

    /**
     * The health of the folder: reachable, a folder, and this account may create and remove a file in its
     * {@code _processing} folder (one probe file, removed again).
     */
    public HealthResult health(StoredIntegration stored) {
        IntegrationConfig c = stored.config();
        Instant now = clock.instant();
        if (c.kind() != Kind.WATCHED_FOLDER || c.watch() == null) {
            return new HealthResult(c.id(), stored.version(), Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID,
                    null, 0, null, now);
        }
        Path root;
        try {
            root = files.resolve(c.watch().root());
        } catch (IOException e) {
            return health(stored, Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_ROOT_NOT_FOUND);
        }
        if (!files.reachable(root)) return health(stored, Outcome.CANT_CONNECT, IntegrationCode.INT_WATCH_SHARE_UNREACHABLE);
        if (!files.isDirectory(root)) return health(stored, Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_ROOT_NOT_FOUND);
        try {
            Path processing = root.resolve(WatchSource.PROCESSING);
            if (!files.isDirectory(processing)) files.createDirectory(processing);
            Path probe = processing.resolve(".platform-health-" + fence() + ".tmp");
            try (OutputStream out = files.create(probe)) {
                out.write('.');
            }
            files.delete(probe);
            files.list(root);
            return health(stored, Outcome.SENT, IntegrationCode.INT_HEALTHY);
        } catch (AccessDeniedException e) {
            return health(stored, Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_NO_PERMISSION);
        } catch (IOException e) {
            return files.reachable(root) ? health(stored, Outcome.OUTAGE, IntegrationCode.INT_WATCH_MOVE_FAILED)
                    : health(stored, Outcome.CANT_CONNECT, IntegrationCode.INT_WATCH_SHARE_UNREACHABLE);
        }
    }

    private HealthResult health(StoredIntegration s, Outcome o, IntegrationCode code) {
        return new HealthResult(s.config().id(), s.version(), o, code, null, 0, null, clock.instant());
    }

    private static String fence() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The pass stopped: root missing, no permission, share gone, scanner, handler. */
    private static final class Stop extends Exception {
        final Outcome outcome;
        final IntegrationCode code;

        Stop(Outcome outcome, IntegrationCode code) {
            super(code.name(), null, false, false);
            this.outcome = outcome;
            this.code = code;
        }
    }

    /** One drop found in the root. {@code folder}: its subfolder, or the root (base-name layout). */
    private record Candidate(String id, Path folder, List<WatchFiles.Entry> entries, Instant oldest,
                             Instant newest) {
    }

    private final class Pass {
        final IntegrationConfig c;
        final int version;
        final WatchSource w;
        final IntakeHandler<Drop> handler;
        final Instant now = clock.instant();
        Path root;
        Path rootReal;
        Path processing;
        int taken;
        int alreadyTaken;
        int claimsLost;
        int released;
        boolean more;
        final List<WatchResult.Refusal> refused = new ArrayList<>();
        final List<WatchResult.Problem> problems = new ArrayList<>();
        final List<WatchResult.Quarantined> quarantined = new ArrayList<>();
        final List<WatchResult.Incomplete> incomplete = new ArrayList<>();
        final List<WatchResult.Problem> held = new ArrayList<>();

        Pass(StoredIntegration stored, IntakeHandler<Drop> handler) {
            this.c = stored.config();
            this.version = stored.version();
            this.w = c.watch();
            this.handler = handler;
        }

        WatchResult go() {
            if (!c.enabled()) return result(new WatchResult.Failure(Outcome.NOT_CONFIGURED, IntegrationCode.INT_DISABLED,
                    null));
            if (c.kind() != Kind.WATCHED_FOLDER || w == null) {
                return result(new WatchResult.Failure(Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED,
                        null));
            }
            if (!ConfigCheck.check(c, installation).ok()) {
                return result(new WatchResult.Failure(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null));
            }
            String at = null;
            try {
                open();
                releaseStuck();
                int handled = 0;
                for (Candidate d : candidates()) {
                    at = d.id();
                    Path trigger = trigger(d);
                    if (trigger == null) continue;
                    if (!stable(d)) continue;
                    if (handled >= w.maxDropsPerPass()) {
                        more = true;
                        break;
                    }
                    handled++;
                    take(d, trigger.getFileName().toString());
                }
                return result(null);
            } catch (Stop s) {
                return result(new WatchResult.Failure(s.outcome, s.code, at));
            }
        }

        /** The root, its real path, and the four folders platform-core keeps there. */
        void open() throws Stop {
            try {
                root = files.resolve(w.root());
            } catch (IOException e) {
                throw new Stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_ROOT_NOT_FOUND);
            }
            if (!files.reachable(root)) throw new Stop(Outcome.CANT_CONNECT, IntegrationCode.INT_WATCH_SHARE_UNREACHABLE);
            if (!files.isDirectory(root)) throw new Stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_ROOT_NOT_FOUND);
            try {
                rootReal = files.realPath(root);
                for (String f : List.of(WatchSource.PROCESSING, WatchSource.ARCHIVE, WatchSource.PROBLEM,
                        WatchSource.QUARANTINE)) {
                    Path p = root.resolve(f);
                    if (!files.isDirectory(p)) files.createDirectory(p);
                }
            } catch (FileAlreadyExistsException e) {
                throw new Stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_NO_PERMISSION); // a file of that name
            } catch (IOException e) {
                throw failure(e);
            }
            processing = root.resolve(WatchSource.PROCESSING);
        }

        /** An I/O failure of the folder itself: the share is gone, or this account may not work there. */
        Stop failure(IOException e) {
            if (!files.reachable(root)) return new Stop(Outcome.CANT_CONNECT, IntegrationCode.INT_WATCH_SHARE_UNREACHABLE);
            if (e instanceof AccessDeniedException) {
                return new Stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_NO_PERMISSION);
            }
            if (!files.isDirectory(root)) return new Stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_WATCH_ROOT_NOT_FOUND);
            return new Stop(Outcome.OUTAGE, IntegrationCode.INT_WATCH_MOVE_FAILED);
        }

        // ---- Finding ----

        List<Candidate> candidates() throws Stop {
            List<WatchFiles.Entry> top;
            try {
                top = files.list(root);
            } catch (IOException e) {
                throw failure(e);
            }
            List<Candidate> out = new ArrayList<>();
            if (w.layout() == WatchSource.Layout.SUBFOLDER) {
                for (WatchFiles.Entry e : top) {
                    if (ours(e.name()) || !e.directory() || e.link()) continue;
                    if (!validDropName(e.name())) {
                        LOG.log(System.Logger.Level.WARNING, "Integration {0} watch: a folder whose name cannot be a "
                                + "drop is left alone", c.id());
                        continue;
                    }
                    List<WatchFiles.Entry> inside;
                    try {
                        inside = files.list(root.resolve(e.name()));
                    } catch (NoSuchFileException gone) {
                        continue;
                    } catch (IOException x) {
                        throw failure(x);
                    }
                    inside = inside.stream().filter(x -> !x.name().equals(CLAIM_MARKER)).toList();
                    if (inside.isEmpty()) continue;
                    out.add(candidate(e.name(), root.resolve(e.name()), inside));
                }
            } else {
                Map<String, List<WatchFiles.Entry>> groups = new TreeMap<>();
                for (WatchFiles.Entry e : top) {
                    if (ours(e.name()) || e.directory() || e.link()) continue;
                    String base = e.name().contains(".") ? e.name().substring(0, e.name().indexOf('.')) : e.name();
                    if (!validDropName(base)) continue;
                    groups.computeIfAbsent(base, k -> new ArrayList<>()).add(e);
                }
                groups.forEach((base, entries) -> out.add(candidate(base, root, entries)));
            }
            out.sort(Comparator.comparing(Candidate::oldest).thenComparing(Candidate::id));
            return out;
        }

        Candidate candidate(String id, Path folder, List<WatchFiles.Entry> entries) {
            Instant oldest = entries.stream().map(WatchFiles.Entry::modified).min(Comparator.naturalOrder()).orElse(now);
            Instant newest = entries.stream().map(WatchFiles.Entry::modified).max(Comparator.naturalOrder()).orElse(now);
            return new Candidate(id, folder, entries, oldest, newest);
        }

        boolean ours(String name) {
            return name.startsWith("_") || name.startsWith(".");
        }

        /** The drop's trigger, or null (reported once as incomplete when it waited too long). */
        Path trigger(Candidate d) {
            List<WatchFiles.Entry> triggers = d.entries().stream()
                    .filter(e -> !e.directory() && !e.link() && w.isTrigger(e.name(), d.id())).toList();
            if (triggers.size() >= 1) {
                // More than one trigger: taken all the same, and refused as a problem after the claim.
                return d.folder().resolve(triggers.get(0).name());
            }
            Duration age = Duration.between(d.oldest(), now);
            if (age.compareTo(w.incompleteAfter()) >= 0) {
                String key = c.id() + "/" + d.id() + "/" + d.oldest().toEpochMilli();
                if (reported.size() > MAX_REMEMBERED) reported.clear();
                if (reported.add(key)) {
                    incomplete.add(new WatchResult.Incomplete(d.id(), age.truncatedTo(ChronoUnit.SECONDS),
                            d.entries().stream().map(WatchFiles.Entry::name).sorted().toList()));
                    LOG.log(System.Logger.Level.WARNING, "Integration {0} watch: drop {1} has no trigger after {2} "
                            + "({3})", c.id(), d.id(), age.truncatedTo(ChronoUnit.SECONDS),
                            IntegrationCode.INT_WATCH_INCOMPLETE);
                }
            }
            return null;
        }

        /**
         * Sizes and modification times unchanged for the configured time: every file's time is at least that old, or
         * this instance saw exactly the same sizes and times that long ago.
         */
        boolean stable(Candidate d) {
            String key = c.id() + "/" + d.id();
            StringBuilder snap = new StringBuilder();
            d.entries().stream().sorted(Comparator.comparing(WatchFiles.Entry::name)).forEach(e -> snap.append(e.name())
                    .append('|').append(e.size()).append('|').append(e.modified().toEpochMilli()).append('/'));
            String s = snap.toString();
            if (!d.newest().plus(w.stableFor()).isAfter(now)) {
                seen.remove(key);
                return true;
            }
            Seen before = seen.get(key);
            if (before != null && before.snapshot().equals(s)) {
                if (!before.since().plus(w.stableFor()).isAfter(now)) {
                    seen.remove(key);
                    return true;
                }
                return false;
            }
            if (seen.size() > MAX_REMEMBERED) seen.clear();
            seen.put(key, new Seen(s, now));
            return false;
        }

        // ---- Claiming and taking ----

        void take(Candidate d, String triggerName) throws Stop {
            String claimId = fence();
            Path claim = processing.resolve(d.id() + "-" + claimId);
            if (!claim(d, triggerName, claim, claimId)) return;
            Drop drop;
            try {
                drop = read(d, claim, claimId, triggerName);
            } catch (Lost lost) {
                claimsLost++;
                return;
            } catch (Refuse r) {
                finish(d.id(), claim, claimId, WatchSource.PROBLEM, r.code, null, null);
                return;
            } catch (IOException e) {
                Stop s = failure(e);
                if (s.code == IntegrationCode.INT_WATCH_SHARE_UNREACHABLE) throw s; // released after the claim time
                release(d.id(), claim);
                held.add(new WatchResult.Problem(d.id(), s.code));
                return;
            }
            if (drop == null) return; // quarantined or held
            IntakeAnswer answer;
            try {
                answer = handler.handle(drop);
            } catch (RuntimeException e) {
                answer = null;
            }
            if (!exists(claim)) {
                claimsLost++;
                return;
            }
            if (answer == null) {
                if (changed(claim, drop)) {
                    finish(d.id(), claim, claimId, WatchSource.PROBLEM, IntegrationCode.INT_WATCH_FILE_CHANGED, null, null);
                    return;
                }
                release(d.id(), claim);
                throw new Stop(Outcome.OUTAGE, IntegrationCode.INT_WATCH_HANDLER_FAILED);
            }
            switch (answer.kind()) {
                case TAKEN, ALREADY_TAKEN -> {
                    if (finish(d.id(), claim, claimId, WatchSource.ARCHIVE, null, null, null)) {
                        if (answer.kind() == IntakeAnswer.Kind.TAKEN) taken++;
                        else alreadyTaken++;
                    }
                }
                case REFUSED -> {
                    if (finish(d.id(), claim, claimId, WatchSource.PROBLEM, IntegrationCode.INT_WATCH_REFUSED,
                            answer.code(), null)) {
                        refused.add(new WatchResult.Refusal(d.id(), answer.code()));
                    }
                }
            }
        }

        /** The atomic claim; false when another instance was first (or the drop is not movable now). */
        boolean claim(Candidate d, String triggerName, Path claim, String claimId) throws Stop {
            try {
                if (w.layout() == WatchSource.Layout.SUBFOLDER) {
                    files.move(d.folder(), claim);
                } else {
                    files.createDirectory(claim);
                    try {
                        files.move(root.resolve(triggerName), claim.resolve(triggerName));
                    } catch (IOException e) {
                        files.delete(claim);
                        throw e;
                    }
                    for (WatchFiles.Entry e : d.entries()) {
                        if (e.name().equals(triggerName)) continue;
                        try {
                            files.move(root.resolve(e.name()), claim.resolve(e.name()));
                        } catch (NoSuchFileException gone) {
                            // removed meanwhile by the customer: the drop is checked as it is now
                        }
                    }
                }
                marker(claim, claimId);
                return true;
            } catch (NoSuchFileException | FileAlreadyExistsException lost) {
                claimsLost++;
                return false;
            } catch (IOException e) {
                Stop s = failure(e);
                if (s.code == IntegrationCode.INT_WATCH_SHARE_UNREACHABLE) throw s;
                // Most often a file still held open by the writer: the drop is tried again at the next pass.
                held.add(new WatchResult.Problem(d.id(), IntegrationCode.INT_WATCH_MOVE_FAILED));
                return false;
            }
        }

        void marker(Path claim, String claimId) throws IOException {
            String json = "{\"claimId\":" + Json.quote(claimId) + ",\"claimedAt\":" + Json.quote(now.toString()) + "}";
            try (OutputStream out = files.create(claim.resolve(CLAIM_MARKER))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }

        boolean exists(Path claim) {
            return files.exists(claim.resolve(CLAIM_MARKER));
        }

        /** The claim folder is gone (released and claimed by another instance meanwhile). */
        private static final class Lost extends Exception {
            Lost() {
                super(null, null, false, false);
            }
        }

        /** The drop cannot be handed over as it is; it goes to {@code _problem} with this code. */
        private static final class Refuse extends Exception {
            final IntegrationCode code;

            Refuse(IntegrationCode code) {
                super(code.name(), null, false, false);
                this.code = code;
            }
        }

        /** The claimed drop, checked, hashed, its trigger read and its files scanned; null when it was set aside. */
        Drop read(Candidate d, Path claim, String claimId, String triggerName) throws IOException, Lost, Refuse, Stop {
            if (!exists(claim)) throw new Lost();
            List<WatchFiles.Entry> entries = files.list(claim).stream()
                    .filter(e -> !e.name().equals(CLAIM_MARKER)).toList();
            WatchFiles.Entry trigger = null;
            int triggers = 0;
            List<WatchFiles.Entry> data = new ArrayList<>();
            for (WatchFiles.Entry e : entries) {
                if (e.link() || e.directory()) throw new Refuse(IntegrationCode.INT_WATCH_DROP_INVALID);
                if (w.isTrigger(e.name(), d.id())) {
                    triggers++;
                    if (e.name().equals(triggerName)) trigger = e;
                    continue;
                }
                data.add(e);
            }
            if (trigger == null || triggers != 1) throw new Refuse(IntegrationCode.INT_WATCH_DROP_INVALID);
            if (data.size() > c.limits().maxDocuments()) throw new Refuse(IntegrationCode.INT_WATCH_DROP_INVALID);
            for (WatchFiles.Entry e : data) {
                if (e.size() > c.limits().maxFileBytes()) throw new Refuse(IntegrationCode.INT_WATCH_DROP_INVALID);
            }
            if (trigger.size() > w.maxTriggerBytes()) throw new Refuse(IntegrationCode.INT_WATCH_TRIGGER_UNREADABLE);
            for (WatchFiles.Entry e : entries) inside(claim.resolve(e.name()));

            // The trigger: raw bytes (within its limit), then the hardened parser and the fixed mapping.
            byte[] raw;
            try (InputStream in = files.open(claim.resolve(trigger.name()))) {
                raw = in.readNBytes((int) w.maxTriggerBytes() + 1);
            }
            if (raw.length > w.maxTriggerBytes()) throw new Refuse(IntegrationCode.INT_WATCH_TRIGGER_UNREADABLE);
            Map<String, List<String>> values = Map.of();
            try {
                Object parsed = switch (w.triggerType()) {
                    case JSON -> TriggerValues.json(raw);
                    case XML -> TriggerValues.xml(raw);
                    case MARKER -> null;
                };
                if (parsed != null) values = TriggerValues.extract(parsed, w.mapping());
            } catch (TriggerValues.Unreadable u) {
                throw new Refuse(IntegrationCode.INT_WATCH_TRIGGER_UNREADABLE);
            }

            // Checksums and sizes, taken now; the app's streams are checked against them.
            List<IntakeFile> intake = new ArrayList<>();
            for (WatchFiles.Entry e : data.stream().sorted(Comparator.comparing(WatchFiles.Entry::name)).toList()) {
                Path p = claim.resolve(e.name());
                String[] sha = new String[1];
                long size = hash(p, sha);
                if (size != e.size()) throw new Refuse(IntegrationCode.INT_WATCH_FILE_CHANGED);
                if (size > c.limits().maxFileBytes()) throw new Refuse(IntegrationCode.INT_WATCH_DROP_INVALID);
                intake.add(new IntakeFile(e.name(), size, sha[0], null, () -> files.open(p)));
            }

            // Scan every file, the trigger too, before the app sees any of it.
            if (scanning.on()) {
                Map<String, IntakeFile.Content> all = new LinkedHashMap<>();
                for (IntakeFile f : intake) all.put(f.name(), () -> files.open(claim.resolve(f.name())));
                all.put(trigger.name(), () -> new java.io.ByteArrayInputStream(raw));
                for (Map.Entry<String, IntakeFile.Content> f : all.entrySet()) {
                    long size = f.getKey().equals(trigger.name()) ? raw.length
                            : intake.stream().filter(x -> x.name().equals(f.getKey())).findFirst().orElseThrow().size();
                    ScanResult r = scanning.scan(f.getValue(), size);
                    if (!exists(claim)) throw new Lost();
                    if (r.verdict() == ScanResult.Verdict.INFECTED) {
                        quarantine(d.id(), claim, claimId, f.getKey(), r.threat());
                        return null;
                    }
                    if (r.verdict() == ScanResult.Verdict.SCAN_FAILED) {
                        release(d.id(), claim);
                        held.add(new WatchResult.Problem(d.id(), r.code()));
                        throw new Stop(r.code() == IntegrationCode.INT_SCAN_FAILED ? Outcome.OUTAGE
                                : Outcome.CANT_CONNECT, r.code());
                    }
                }
            }

            List<String> unlisted = List.of();
            List<String> missing = List.of();
            if (w.fileListValue() != null) {
                List<String> listed = values.getOrDefault(w.fileListValue(), List.of());
                Map<String, String> byLower = new LinkedHashMap<>();
                for (IntakeFile f : intake) byLower.put(f.name().toLowerCase(Locale.ROOT), f.name());
                Set<String> listedLower = new java.util.LinkedHashSet<>();
                for (String n : listed) listedLower.add(n.toLowerCase(Locale.ROOT));
                unlisted = intake.stream().map(IntakeFile::name)
                        .filter(n -> !listedLower.contains(n.toLowerCase(Locale.ROOT))).toList();
                missing = listed.stream().filter(n -> !byLower.containsKey(n.toLowerCase(Locale.ROOT))).distinct()
                        .toList();
            }
            return new Drop(c.id(), d.id(), claimId, intake,
                    new Drop.Trigger(trigger.name(), raw, w.triggerType()), values, unlisted, missing);
        }

        /** A file must stay inside the root once links are resolved. */
        void inside(Path p) throws IOException, Refuse {
            Path real = files.realPath(p);
            if (!real.startsWith(rootReal)) throw new Refuse(IntegrationCode.INT_WATCH_DROP_INVALID);
        }

        long hash(Path p, String[] sha) throws IOException {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long size = 0;
            try (InputStream in = files.open(p)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                    size += n;
                    if (size > c.limits().maxFileBytes()) break;
                }
            }
            sha[0] = HexFormat.of().formatHex(digest.digest());
            return size;
        }

        /** After a failed handler: whether a file differs from what was handed over. */
        boolean changed(Path claim, Drop drop) {
            try {
                for (IntakeFile f : drop.files()) {
                    String[] sha = new String[1];
                    long size = hash(claim.resolve(f.name()), sha);
                    if (size != f.size() || !sha[0].equals(f.sha256())) return true;
                }
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        void quarantine(String id, Path claim, String claimId, String fileName, String threat) throws Stop {
            if (finish(id, claim, claimId, WatchSource.QUARANTINE, IntegrationCode.INT_SCAN_INFECTED, null, threat)) {
                quarantined.add(new WatchResult.Quarantined(id, fileName, threat));
            }
            LOG.log(System.Logger.Level.WARNING, "Integration {0} watch: drop {1} infected ({2}); quarantined", c.id(),
                    id, threat);
            try {
                scanning.events().infected(new ScanEvents.Infected(c.id(), c.entity(), c.product(), "watch", id,
                        fileName, threat, now));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Integration {0} watch: the infected-file event failed", c.id());
            }
        }

        /**
         * Moves the claimed drop to {@code _archive}, {@code _problem} or {@code _quarantine} (with {@code problem.json}
         * for the last two). False (and a problem reported) when moving failed: the drop stays claimed and is put back
         * after the claim time.
         */
        boolean finish(String id, Path claim, String claimId, String to, IntegrationCode code, String appCode,
                       String threat) throws Stop {
            try {
                if (!exists(claim)) {
                    claimsLost++;
                    return false;
                }
                if (code != null) {
                    Map<String, Object> note = new LinkedHashMap<>();
                    note.put("format", PROBLEM_FORMAT);
                    note.put("drop", id);
                    note.put("claimId", claimId);
                    note.put("code", code.name());
                    if (appCode != null) note.put("appCode", appCode);
                    if (threat != null) note.put("threat", threat);
                    note.put("at", now.truncatedTo(ChronoUnit.SECONDS).toString());
                    String name = files.exists(claim.resolve(WatchSource.PROBLEM_FILE))
                            ? "problem-" + claimId + ".json" : WatchSource.PROBLEM_FILE;
                    try (OutputStream out = files.create(claim.resolve(name))) {
                        out.write(Json.write(note).getBytes(StandardCharsets.UTF_8));
                    }
                    if (!code.equals(IntegrationCode.INT_WATCH_REFUSED) && !code.equals(IntegrationCode.INT_SCAN_INFECTED)) {
                        problems.add(new WatchResult.Problem(id, code));
                    }
                    LOG.log(System.Logger.Level.WARNING, "Integration {0} watch: drop {1} to {2}: {3}{4}", c.id(), id, to,
                            code, appCode == null ? "" : " (" + appCode + ")");
                }
                files.delete(claim.resolve(CLAIM_MARKER));
                files.move(claim, root.resolve(to).resolve(id + "-" + claimId));
                return true;
            } catch (IOException e) {
                Stop s = failure(e);
                if (s.code == IntegrationCode.INT_WATCH_SHARE_UNREACHABLE) throw s;
                problems.add(new WatchResult.Problem(id, IntegrationCode.INT_WATCH_MOVE_FAILED));
                return false;
            }
        }

        /** Puts a claimed drop back where it was found, to be tried again. */
        void release(String id, Path claim) throws Stop {
            try {
                files.delete(claim.resolve(CLAIM_MARKER));
                if (w.layout() == WatchSource.Layout.SUBFOLDER) {
                    files.move(claim, root.resolve(id));
                } else {
                    for (WatchFiles.Entry e : files.list(claim)) files.move(claim.resolve(e.name()), root.resolve(e.name()));
                    files.delete(claim);
                }
            } catch (IOException e) {
                Stop s = failure(e);
                if (s.code == IntegrationCode.INT_WATCH_SHARE_UNREACHABLE) throw s;
                problems.add(new WatchResult.Problem(id, IntegrationCode.INT_WATCH_MOVE_FAILED));
            }
        }

        /** Claims older than the claim time, whose instance died: put back. */
        void releaseStuck() throws Stop {
            List<WatchFiles.Entry> claims;
            try {
                claims = files.list(processing);
            } catch (IOException e) {
                throw failure(e);
            }
            for (WatchFiles.Entry e : claims) {
                Matcher m = CLAIMED.matcher(e.name());
                if (!e.directory() || e.link() || !m.matches()) continue;
                Path claim = processing.resolve(e.name());
                Instant at = claimedAt(claim, e.modified());
                if (at.plus(w.claimTimeout()).isAfter(now)) continue;
                String id = m.group(1);
                if (files.exists(root.resolve(id)) && w.layout() == WatchSource.Layout.SUBFOLDER) {
                    problems.add(new WatchResult.Problem(id, IntegrationCode.INT_WATCH_MOVE_FAILED));
                    continue;
                }
                int before = problems.size();
                release(id, claim);
                if (problems.size() == before) {
                    released++;
                    LOG.log(System.Logger.Level.WARNING, "Integration {0} watch: a stuck claim of drop {1} was put "
                            + "back", c.id(), id);
                }
            }
        }

        Instant claimedAt(Path claim, Instant fallback) {
            try (InputStream in = files.open(claim.resolve(CLAIM_MARKER))) {
                Object v = Json.object(new String(in.readNBytes(1024), StandardCharsets.UTF_8)).get("claimedAt");
                return v instanceof String s ? Instant.parse(s) : fallback;
            } catch (IOException | Json.Invalid | RuntimeException e) {
                return fallback;
            }
        }

        WatchResult result(WatchResult.Failure failure) {
            return new WatchResult(c.id(), version, taken, alreadyTaken, refused, problems, quarantined, incomplete,
                    held, claimsLost, released, more, failure);
        }
    }

    private static boolean validDropName(String name) {
        return name.length() <= MAX_DROP_NAME && FolderTarget.validName(name) && !name.startsWith("_")
                && !name.startsWith(".");
    }
}
