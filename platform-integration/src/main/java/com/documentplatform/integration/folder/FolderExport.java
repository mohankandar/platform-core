package com.documentplatform.integration.folder;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.engine.CaseFields;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.store.StoredIntegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Folder export (kind {@code FOLDER_EXPORT}): one attempt writes one package to the target folder (a local folder or a
 * network share) so that a reader never sees a package folder without its manifest:
 * <ol>
 *   <li>{@code <folder>/<package>.partial/} is made (a leftover of an earlier failed attempt is removed first);</li>
 *   <li>each document is written to a temporary name, forced to the disk, read back and checked against the
 *       delivery's size and SHA-256, then renamed to its file name;</li>
 *   <li>{@code manifest.json} ({@link ExportManifest}) is written last, the same way;</li>
 *   <li>the folder is renamed from {@code .partial} to its final name.</li>
 * </ol>
 * On any failure only the {@code .partial} folder is touched (removed when possible). Like the call engine it never
 * retries: the result says what happened ({@link AttemptResult}, same outcomes as API push). The final folder already
 * there with the same package's manifest is {@code ALREADY_RECEIVED}; with another one {@code REFUSED}
 * ({@code INT_FOLDER_EXISTS_DIFFERENT}) and nothing is changed. Logs carry the integration ID, version and codes;
 * never a path, a file name, a value or content.
 */
public final class FolderExport {

    private static final System.Logger LOG = System.getLogger(FolderExport.class.getName());
    private static final int CHUNK = 64 * 1024;
    private static final int MAX_MANIFEST = 8 * 1024 * 1024;
    private static final DateTimeFormatter TEST_NAME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);
    /** The value the result carries: the package folder's final name. */
    public static final String FOLDER_NAME_VALUE = "export.folderName";
    /** The slot ID written in a Test action's manifest. */
    public static final String TEST_SLOT_ID = "ENT-PLATFORM-TEST";

    private final Installation installation;
    private final ExportFiles files;
    private final Clock clock;

    /** Local folders and shares, the system clock. */
    public FolderExport(Installation installation) {
        this(installation, LocalFiles.INSTANCE, Clock.systemUTC());
    }

    public FolderExport(Installation installation, ExportFiles files, Clock clock) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.files = Objects.requireNonNull(files, "files");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One attempt: writes the delivery's documents and the manifest.
     *
     * @param info the entity's slot ID (must be the integration's entity) and the app's manifest section
     * @throws IllegalArgumentException when the slot ID is not the integration's entity (a mistake in the app)
     */
    public AttemptResult attempt(StoredIntegration integration, Delivery delivery, ExportInfo info) {
        Objects.requireNonNull(info, "info");
        IntegrationConfig c = integration.config();
        if (c.kind() == Kind.FOLDER_EXPORT && !SlotIds.entityUuid(info.entitySlotId()).equals(c.entity())) {
            throw new IllegalArgumentException("The entity slot ID is not this integration's entity");
        }
        return logged(new Run(integration, delivery, info, null).go());
    }

    /**
     * The Test action of a folder export: the fake one-page PDF and a manifest, written into a
     * {@code platform-test-<timestamp>} folder exactly as a real package, which is then removed. The app's
     * {@code TestAction} calls this and audits it.
     */
    public AttemptResult test(StoredIntegration integration, Delivery fakeDelivery) {
        String name = "platform-test-" + TEST_NAME.format(clock.instant());
        AttemptResult r = new Run(integration, fakeDelivery, ExportInfo.of(TEST_SLOT_ID), name).go();
        if (r.delivered()) {
            try {
                Path target = files.resolve(integration.config().folder().folder());
                files.delete(target.resolve(name));
            } catch (IOException e) {
                r = result(integration, Outcome.NOT_CONFIGURED, e instanceof LinkRefusedException
                        ? IntegrationCode.INT_FOLDER_LINK_REFUSED : IntegrationCode.INT_FOLDER_NO_PERMISSION, "cleanup",
                        null, null, true, Map.of());
            }
        }
        return logged(r);
    }

    /**
     * The health check: the target folder exists and this account can write there. It writes and removes one probe
     * file ({@code .platform-health-<random>.tmp}) and nothing else.
     */
    public HealthResult health(StoredIntegration integration) {
        IntegrationConfig c = integration.config();
        int v = integration.version();
        if (c.kind() != Kind.FOLDER_EXPORT) {
            return health(c, v, Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED);
        }
        if (c.folder() == null || !ConfigCheck.check(c, installation).ok()) {
            return health(c, v, Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID);
        }
        Path target;
        try {
            target = files.resolve(c.folder().folder());
        } catch (IOException e) {
            return health(c, v, Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_NOT_FOUND);
        }
        Failed f = reachable(target);
        if (f != null) return health(c, v, f.outcome(), f.code());
        Path probe = target.resolve(".platform-health-" + UUID.randomUUID() + ".tmp");
        HealthResult result;
        try {
            try (OutputStream out = files.create(probe)) {
                out.write("platform health probe\n".getBytes(StandardCharsets.US_ASCII));
            }
            result = health(c, v, Outcome.SENT, IntegrationCode.INT_HEALTHY);
        } catch (IOException e) {
            Failed why = classify(e, target);
            result = health(c, v, why.outcome(), why.code());
        } finally {
            try {
                files.delete(probe);
            } catch (IOException ignored) {
                // The probe could not be removed; the next check uses a new name.
            }
        }
        LOG.log(System.Logger.Level.INFO, "Integration {0} v{1} health: {2} {3}", c.id(), v, result.outcome(),
                result.code());
        return result;
    }

    private HealthResult health(IntegrationConfig c, int version, Outcome o, IntegrationCode code) {
        return new HealthResult(c.id(), version, o, code, null, 0, null, clock.instant());
    }

    private static AttemptResult logged(AttemptResult r) {
        LOG.log(System.Logger.Level.INFO, "Integration {0} v{1}{2}: {3} {4}{5}", r.integrationId(), r.configVersion(),
                r.test() ? " (test)" : "", r.outcome(), r.code(), r.stepReached() == null ? "" : ", at " + r.stepReached());
        return r;
    }

    // ---- One attempt ----

    /** Why the folder could not be used: a result class and a code. */
    private record Failed(Outcome outcome, IntegrationCode code) {
    }

    /** The app's content could not be read (not the folder's fault). */
    private static final class ContentUnreadable extends IOException {
        ContentUnreadable() {
            super("content unreadable", null);
        }
    }

    /** Writing one file took longer than the integration's read timeout. */
    private static final class TimedOut extends IOException {
        TimedOut() {
            super("timed out", null);
        }
    }

    private final class Run {
        final StoredIntegration integration;
        final IntegrationConfig c;
        final Delivery d;
        final ExportInfo info;
        final String testName;
        final String correlationId;

        Run(StoredIntegration integration, Delivery d, ExportInfo info, String testName) {
            this.integration = integration;
            this.c = integration.config();
            this.d = d;
            this.info = info;
            this.testName = testName;
            this.correlationId = d.correlationId() != null ? d.correlationId() : UUID.randomUUID().toString();
        }

        boolean test() {
            return testName != null;
        }

        AttemptResult stop(Outcome o, IntegrationCode code, String step, String docId) {
            return result(integration, o, code, step, docId, c.sendCorrelationId() ? correlationId : null,
                    o.usesAttemptByDefault(), Map.of(), test());
        }

        AttemptResult go() {
            if (!c.enabled()) return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_DISABLED, null, null);
            if (c.kind() != Kind.FOLDER_EXPORT) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED, null, null);
            }
            if (c.folder() == null || !ConfigCheck.check(c, installation).ok()) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, null, null);
            }
            if (d.documents().size() > c.limits().maxDocuments()) {
                return stop(Outcome.REFUSED, IntegrationCode.INT_TOO_MANY_DOCUMENTS, null, null);
            }
            long total = 0;
            for (Delivery.Document doc : d.documents()) {
                if (doc.size() > c.limits().maxFileBytes()) {
                    return stop(Outcome.REFUSED, IntegrationCode.INT_FILE_TOO_LARGE, null, doc.sourceDocumentId());
                }
                total += doc.size();
            }
            if (total > c.limits().maxPackageBytes()) {
                return stop(Outcome.REFUSED, IntegrationCode.INT_PACKAGE_TOO_LARGE, null, null);
            }
            FolderTarget f = c.folder();
            List<Template> names = new ArrayList<>();
            Template folderName;
            Template fileName;
            try {
                folderName = Template.parse(f.folderNameTemplate(), Template.Place.NAME);
                fileName = f.fileNameTemplate() == null ? null : Template.parse(f.fileNameTemplate(), Template.Place.NAME);
            } catch (Template.Problem p) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONFIG_INVALID, "names", null);
            }
            names.add(folderName);
            if (fileName != null) names.add(fileName);
            // A sensitive value never goes into a name (everyone with access to the folder sees names).
            for (Template t : names) {
                for (String p : t.placeholders()) {
                    if (!p.startsWith(Template.FIELD_PREFIX)) continue;
                    Optional<CaseFields.Field> field = d.fields().field(p.substring(Template.FIELD_PREFIX.length()));
                    if (field.isPresent() && field.get().sensitive()) {
                        return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_SENSITIVE_FIELD_NOT_ALLOWED, "names",
                                null);
                    }
                }
            }
            Map<String, Object> fields;
            try {
                fields = fieldsForManifest();
            } catch (Json.Invalid | RuntimeException e) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_FIELD_VALUES_INVALID, "manifest", null);
            }

            String name;
            List<ExportManifest.Entry> entries = new ArrayList<>();
            try {
                name = test() ? testName : folderName.fill(new Names(null));
                if (!FolderTarget.validName(name)) {
                    return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_VALUE_NOT_ALLOWED, "names", null);
                }
                Set<String> taken = new HashSet<>();
                taken.add(FolderTarget.MANIFEST_FILE);
                for (Delivery.Document doc : d.documents()) {
                    String n = fileName == null ? FolderTarget.safeName(doc.fileName()) : fileName.fill(new Names(doc));
                    if (!FolderTarget.validName(n) || n.startsWith(".tmp-")) {
                        return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_VALUE_NOT_ALLOWED, "names",
                                doc.sourceDocumentId());
                    }
                    n = unique(n, taken);
                    entries.add(new ExportManifest.Entry(n, doc.sourceDocumentId(), doc.contentType(), doc.size(),
                            doc.sha256()));
                }
            } catch (Template.Problem p) {
                return stop(Outcome.NOT_CONFIGURED, p.code(), "names", null);
            }
            byte[] manifest = ExportManifest.write(d.packageId(), correlationId,
                    clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), c.product(),
                    info.entitySlotId(), c.id(), integration.version(), entries, fields, info.appSection())
                    .getBytes(StandardCharsets.UTF_8);

            Path target;
            try {
                target = files.resolve(f.folder());
            } catch (IOException e) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_NOT_FOUND, "folder", null);
            }
            Failed cannot = reachable(target);
            if (cannot != null) return stop(cannot.outcome(), cannot.code(), "folder", null);
            Path done = target.resolve(name);
            Path partial = target.resolve(name + FolderTarget.PARTIAL_SUFFIX);
            // A link where the package folder or its .partial folder belongs is never followed, read or removed.
            if (files.link(done) || files.link(partial)) {
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_LINK_REFUSED, "folder", null);
            }
            if (files.exists(done)) return existing(done, manifest, name);
            try {
                if (files.usableSpace(target) < total + manifest.length) {
                    return stop(Outcome.OUTAGE, IntegrationCode.INT_FOLDER_FULL, "folder", null);
                }
            } catch (IOException | RuntimeException unknown) {
                // Free space unknown: try, and classify a failure.
            }

            String step = "folder";
            String docId = null;
            try {
                files.delete(partial); // a leftover of an earlier attempt: ours, never read
                files.createDirectory(partial);
                List<Delivery.Document> docs = d.documents();
                for (int i = 0; i < docs.size(); i++) {
                    Delivery.Document doc = docs.get(i);
                    step = "document";
                    docId = doc.sourceDocumentId();
                    Path tmp = partial.resolve(".tmp-" + i);
                    try (InputStream in = open(doc)) {
                        write(in, tmp);
                    }
                    if (!sameOnDisk(tmp, doc.size(), doc.sha256())) {
                        cleanUp(partial);
                        return stop(Outcome.UNCLEAR, IntegrationCode.INT_INTEGRITY_MISMATCH, step, docId);
                    }
                    files.rename(tmp, partial.resolve(entries.get(i).fileName()));
                }
                step = "manifest";
                docId = null;
                Path tmp = partial.resolve(".tmp-manifest");
                write(new java.io.ByteArrayInputStream(manifest), tmp);
                if (!sameOnDisk(tmp, manifest.length, sha256(manifest))) {
                    cleanUp(partial);
                    return stop(Outcome.UNCLEAR, IntegrationCode.INT_INTEGRITY_MISMATCH, step, null);
                }
                files.rename(tmp, partial.resolve(FolderTarget.MANIFEST_FILE));
                step = "rename";
                try {
                    files.rename(partial, done);
                } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                    // Another writer finished the same name first.
                    cleanUp(partial);
                    return existing(done, manifest, name);
                }
            } catch (ContentUnreadable e) {
                cleanUp(partial);
                return stop(Outcome.NOT_CONFIGURED, IntegrationCode.INT_CONTENT_UNREADABLE, step, docId);
            } catch (IOException | RuntimeException e) {
                cleanUp(partial);
                Failed why = e instanceof IOException io ? classify(io, target)
                        : new Failed(Outcome.OUTAGE, IntegrationCode.INT_FOLDER_WRITE_FAILED);
                return stop(why.outcome(), why.code(), step, docId);
            }
            return result(integration, Outcome.SENT, IntegrationCode.INT_SENT, null, null,
                    c.sendCorrelationId() ? correlationId : null, true, Map.of(FOLDER_NAME_VALUE, name), test());
        }

        /** The final folder is already there: the same package (ALREADY_RECEIVED) or another one (REFUSED). */
        AttemptResult existing(Path done, byte[] ours, String name) {
            String theirs = null;
            try (InputStream in = files.open(done.resolve(FolderTarget.MANIFEST_FILE))) {
                byte[] b = in.readNBytes(MAX_MANIFEST + 1);
                if (b.length <= MAX_MANIFEST) theirs = new String(b, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                // No readable manifest: not ours.
            }
            if (theirs != null && ExportManifest.samePackage(theirs, new String(ours, StandardCharsets.UTF_8))) {
                return result(integration, Outcome.ALREADY_RECEIVED, IntegrationCode.INT_ALREADY_RECEIVED, null, null,
                        c.sendCorrelationId() ? correlationId : null, true, Map.of(FOLDER_NAME_VALUE, name), test());
            }
            return stop(Outcome.REFUSED, IntegrationCode.INT_FOLDER_EXISTS_DIFFERENT, "folder", null);
        }

        InputStream open(Delivery.Document doc) throws ContentUnreadable {
            try {
                InputStream in = doc.content().open();
                if (in == null) throw new ContentUnreadable();
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        try {
                            return in.read();
                        } catch (IOException e) {
                            throw new ContentUnreadable();
                        }
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        try {
                            return in.read(b, off, len);
                        } catch (IOException e) {
                            throw new ContentUnreadable();
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        in.close();
                    }
                };
            } catch (IOException | RuntimeException e) {
                throw new ContentUnreadable();
            }
        }

        /** Streams to a new file, within the read timeout; {@code close} forces it to the disk. */
        void write(InputStream in, Path to) throws IOException {
            long deadline = System.nanoTime() + c.limits().readTimeout().toNanos();
            try (OutputStream out = files.create(to)) {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (System.nanoTime() > deadline) throw new TimedOut();
                }
            }
        }

        /** Reads the file back: its size and SHA-256 must be what the delivery says. */
        boolean sameOnDisk(Path p, long size, String sha256) throws IOException {
            MessageDigest md = digest();
            long n = 0;
            try (InputStream in = files.open(p)) {
                byte[] buf = new byte[CHUNK];
                int r;
                while ((r = in.read(buf)) >= 0) {
                    md.update(buf, 0, r);
                    n += r;
                }
            }
            return n == size && HexFormat.of().formatHex(md.digest()).equals(sha256);
        }

        void cleanUp(Path partial) {
            try {
                files.delete(partial);
            } catch (IOException | RuntimeException ignored) {
                // Left as .partial: never a final folder without its manifest. The next attempt removes it.
            }
        }

        /**
         * The delivery's case fields in the platform-field-values/1 wire format, without the sensitive fields this
         * integration does not allow; null when the delivery has none.
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldsForManifest() throws Json.Invalid {
            String doc = d.fields().document();
            if (doc == null) return null;
            Map<String, Object> all = Json.object(doc);
            Map<String, Object> out = new LinkedHashMap<>(all);
            List<Object> kept = new ArrayList<>();
            for (Object o : (List<Object>) all.get("fields")) {
                Map<String, Object> entry = (Map<String, Object>) o;
                String key = (String) entry.get("key");
                Optional<CaseFields.Field> field = d.fields().field(key);
                boolean sensitive = field.isEmpty() || field.get().sensitive();
                if (!sensitive || c.allowedSensitiveFields().contains(key)) kept.add(entry);
            }
            out.put("fields", kept);
            return out;
        }

        /** The values for the folder and file name templates. */
        final class Names implements Template.Values {
            private final Delivery.Document doc;

            Names(Delivery.Document doc) {
                this.doc = doc;
            }

            @Override
            public Optional<String> text(String name) {
                if (name.startsWith(Template.FIELD_PREFIX)) {
                    String key = name.substring(Template.FIELD_PREFIX.length());
                    if (test()) return Optional.of("test-" + key);
                    return d.fields().field(key).flatMap(CaseFields::text);
                }
                switch (name) {
                    case "package.id":
                        return Optional.of(d.packageId());
                    case "package.documentCount":
                        return Optional.of(Integer.toString(d.documents().size()));
                    case "correlationId":
                        return Optional.of(correlationId);
                    default:
                        break;
                }
                if (name.startsWith("value.")) return Optional.ofNullable(d.values().get(name.substring(6)));
                if (doc == null) return Optional.empty();
                if (name.startsWith(Template.ATTR_PREFIX)) {
                    return Optional.ofNullable(doc.attributes().get(name.substring(Template.ATTR_PREFIX.length())));
                }
                return Optional.ofNullable(switch (name) {
                    case "doc.sourceDocumentId" -> doc.sourceDocumentId();
                    case "doc.fileName" -> doc.fileName();
                    case "doc.contentType" -> doc.contentType();
                    case "doc.sha256" -> doc.sha256();
                    case "doc.size" -> Long.toString(doc.size());
                    default -> null;
                });
            }
        }
    }

    /** {@code name}, or {@code name (2)}, {@code name (3)}, … before the extension, unique ignoring case. */
    static String unique(String name, Set<String> taken) {
        String n = name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int k = 2; taken.contains(n.toLowerCase(Locale.ROOT)); k++) {
            String suffix = " (" + k + ")";
            String s = stem.length() + suffix.length() + ext.length() > FolderTarget.MAX_NAME
                    ? stem.substring(0, FolderTarget.MAX_NAME - suffix.length() - ext.length()) : stem;
            n = s + suffix + ext;
        }
        taken.add(n.toLowerCase(Locale.ROOT));
        return n;
    }

    /** Whether the target can be used at all: its share answers and it is a folder. */
    private Failed reachable(Path target) {
        if (!files.reachable(target)) return new Failed(Outcome.CANT_CONNECT, IntegrationCode.INT_FOLDER_UNREACHABLE);
        if (!files.isDirectory(target)) return new Failed(Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_NOT_FOUND);
        return null;
    }

    /** A failure while writing: permission, share gone, folder gone, disk or quota full, timeout, or other. */
    private Failed classify(IOException e, Path target) {
        if (e instanceof LinkRefusedException) {
            return new Failed(Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_LINK_REFUSED);
        }
        if (e instanceof TimedOut) return new Failed(Outcome.CANT_CONNECT, IntegrationCode.INT_TIMEOUT);
        if (e instanceof AccessDeniedException) {
            return new Failed(Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_NO_PERMISSION);
        }
        Failed cannot = reachable(target);
        if (cannot != null) return cannot;
        if (full(e)) return new Failed(Outcome.OUTAGE, IntegrationCode.INT_FOLDER_FULL);
        try {
            if (files.usableSpace(target) < CHUNK) return new Failed(Outcome.OUTAGE, IntegrationCode.INT_FOLDER_FULL);
        } catch (IOException | RuntimeException unknown) {
            // not known
        }
        return new Failed(Outcome.OUTAGE, IntegrationCode.INT_FOLDER_WRITE_FAILED);
    }

    /** The JDK passes the system's text ("No space left on device", "There is not enough space on the disk"). */
    private static boolean full(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("space") || lower.contains("quota") || lower.contains("disk full")) return true;
        }
        return false;
    }

    private static AttemptResult result(StoredIntegration integration, Outcome o, IntegrationCode code, String step,
                                        String docId, String correlationId, boolean attemptUsed,
                                        Map<String, String> values, boolean test) {
        return new AttemptResult(integration.id(), integration.version(), o, code, null, 0, null, attemptUsed,
                correlationId, step, docId, values, Map.of(), Set.of(), Set.of(), List.of(), test);
    }

    private static AttemptResult result(StoredIntegration integration, Outcome o, IntegrationCode code, String step,
                                        String docId, String correlationId, boolean attemptUsed,
                                        Map<String, String> values) {
        return result(integration, o, code, step, docId, correlationId, attemptUsed, values, true);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] b) {
        return HexFormat.of().formatHex(digest().digest(b));
    }
}
