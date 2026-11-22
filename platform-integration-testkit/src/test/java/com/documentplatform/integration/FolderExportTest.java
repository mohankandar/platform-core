package com.documentplatform.integration;

import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.identity.EntityUuid;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.CaseFields;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.folder.ExportFiles;
import com.documentplatform.integration.folder.ExportInfo;
import com.documentplatform.integration.folder.ExportManifest;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.http.Outbound;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.json.JsonSchema;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.testkit.MockFolder;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B3: folder export with the manifest written last: the happy path, every code with the mock folder's switches,
 * idempotency, the manifest's schema, the health check and the Test action, and real folders (Windows paths where
 * the JDK allows them).
 */
class FolderExportTest {

    static final String SLOT = "ENT-0001";
    static final UUID ENTITY = EntityUuid.fromSlotId(SLOT);
    static final FieldSet FIELDS = FieldSet.of(List.of(
            FieldSpec.builder("reference_number", "Reference", FieldType.TEXT).build(),
            FieldSpec.builder("amount", "Amount", FieldType.MONEY).currency("USD").build(),
            FieldSpec.builder("holder_name", "Holder", FieldType.TEXT).sensitive(true).build(),
            FieldSpec.builder("tax_id", "Tax ID", FieldType.TEXT).sensitive(true).build()));

    @TempDir
    Path dir;
    MockFolder folder;
    FolderExport export;

    @BeforeEach
    void setUp() {
        folder = MockFolder.in(dir);
        export = new FolderExport(Installation.onPremises(), folder, Clock.systemUTC());
    }

    private IntegrationConfig config(FolderTarget t) {
        return IntegrationConfig.folderExport(ENTITY, "PRODUCT_A", "Share export", t)
                .withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(10)));
    }

    private IntegrationConfig config() {
        return config(FolderTarget.of(folder.folder()));
    }

    private static StoredIntegration stored(IntegrationConfig c) {
        return new StoredIntegration(c, 3, "admin", Instant.now());
    }

    private static Delivery delivery(String packageId, String... names) {
        List<Delivery.Document> docs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            docs.add(Delivery.Document.of("doc-" + (i + 1), names[i], "application/pdf",
                    ("%PDF-1.4 document " + i + " " + "x".repeat(1000 * (i + 1))).getBytes(StandardCharsets.UTF_8)));
        }
        return Delivery.of(packageId, docs, Map.of("batch", "B7"));
    }

    static CaseFields fields() {
        Map<String, List<FieldValue>> in = new LinkedHashMap<>();
        in.put("reference_number", List.of(new FieldValue.Text("R-42")));
        in.put("amount", List.of(new FieldValue.Text("$1,250")));
        in.put("holder_name", List.of(new FieldValue.Text("Ann Example")));
        in.put("tax_id", List.of(new FieldValue.Text("000-00-0000")));
        ValidatedFields v = FIELDS.validate(in);
        assertTrue(v.accepted(), v.problems().toString());
        return CaseFields.of(FIELDS, v);
    }

    /** The manifest against its JSON Schema (the file next to the profile schema). */
    static List<JsonSchema.Problem> schemaProblems(Object manifest) throws Exception {
        try (InputStream in = FolderExport.class.getClassLoader().getResourceAsStream(ExportManifest.SCHEMA_RESOURCE)) {
            Map<String, Object> root = Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return new JsonSchema(root).check(manifest, root);
        }
    }

    @Test
    void writesTheDocumentsThenTheManifestThenRenamesTheFolder() throws Exception {
        Recording rec = new Recording(folder);
        FolderExport ex = new FolderExport(Installation.onPremises(), rec, Clock.systemUTC());
        IntegrationConfig c = config().withAllowedSensitiveFields(Set.of("holder_name"));
        Delivery d = delivery("PKG-2026-0042", "statement.pdf", "letter.pdf").withFields(fields());
        AttemptResult r = ex.attempt(stored(c), d, new ExportInfo(SLOT, "{\"caseRef\":\"C-9\",\"levels\":[1,2]}"));

        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertEquals(IntegrationCode.INT_SENT, r.code());
        assertEquals("PKG-2026-0042", r.values().get(FolderExport.FOLDER_NAME_VALUE));
        assertEquals(List.of("PKG-2026-0042"), folder.entries(), "no .partial left");
        assertEquals(List.of("letter.pdf", "manifest.json", "statement.pdf"), folder.entries("PKG-2026-0042"));
        assertArrayEquals(read(d.documents().get(0)), folder.read("PKG-2026-0042", "statement.pdf"));

        // Order: both documents into place, then the manifest, then the folder's own rename.
        List<String> renames = rec.renames;
        assertEquals(4, renames.size(), renames.toString());
        assertEquals("statement.pdf", renames.get(0));
        assertEquals("letter.pdf", renames.get(1));
        assertEquals("manifest.json", renames.get(2));
        assertEquals("PKG-2026-0042", renames.get(3));
        assertTrue(rec.created.get(0).endsWith(".partial"), rec.created.toString());
        // Each file was read back (its checksum checked) before it was renamed.
        assertEquals(List.of(".tmp-0", ".tmp-1", ".tmp-manifest"), rec.readBack);

        // The manifest: the common envelope, the app's section as given, the allowed sensitive field only.
        String text = folder.manifest("PKG-2026-0042");
        Map<String, Object> m = Json.object(text);
        assertEquals(List.of(), schemaProblems(m));
        assertEquals(ExportManifest.FORMAT, m.get("format"));
        assertEquals("PKG-2026-0042", m.get("packageId"));
        assertEquals("PRODUCT_A", m.get("product"));
        assertEquals(SLOT, m.get("entitySlotId"));
        assertEquals(Map.of("id", c.id().toString(), "configVersion", 3L), m.get("integration"));
        assertTrue(text.endsWith(",\"app\":{\"caseRef\":\"C-9\",\"levels\":[1,2]}}"), text);
        @SuppressWarnings("unchecked") List<Map<String, Object>> docs = (List<Map<String, Object>>) m.get("documents");
        assertEquals(List.of("statement.pdf", "letter.pdf"), docs.stream().map(x -> x.get("fileName")).toList());
        assertEquals(d.documents().get(1).sha256(), docs.get(1).get("sha256"));
        assertEquals(d.documents().get(1).size(), docs.get(1).get("size"));
        assertEquals("doc-2", docs.get(1).get("sourceDocumentId"));
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) m.get("fields");
        assertEquals("platform-field-values/1", f.get("format"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) f.get("fields");
        assertEquals(Set.of("reference_number", "amount", "holder_name"),
                Set.copyOf(entries.stream().map(e -> e.get("key")).toList()));
        assertFalse(text.contains("000-00-0000"), "a sensitive field not allowed never goes");
    }

    private static byte[] read(Delivery.Document doc) throws IOException {
        try (InputStream in = doc.content().open()) {
            return in.readAllBytes();
        }
    }

    @Test
    void theSamePackageAgainIsAlreadyReceivedAnotherOneIsRefused() {
        IntegrationConfig c = config();
        Delivery d = delivery("PKG-1", "a.pdf");
        assertEquals(Outcome.SENT, export.attempt(stored(c), d, ExportInfo.of(SLOT)).outcome());
        String before = folder.manifest("PKG-1");
        // A new attempt (new correlation ID and time, a later configuration version): already there.
        AttemptResult again = export.attempt(new StoredIntegration(c, 4, "admin", Instant.now()), d, ExportInfo.of(SLOT));
        assertEquals(Outcome.ALREADY_RECEIVED, again.outcome());
        assertEquals(IntegrationCode.INT_ALREADY_RECEIVED, again.code());
        assertEquals(before, folder.manifest("PKG-1"), "nothing rewritten");
        // Same package ID, other content: refused, nothing changed.
        AttemptResult other = export.attempt(stored(c), delivery("PKG-1", "b.pdf"), ExportInfo.of(SLOT));
        assertEquals(Outcome.REFUSED, other.outcome());
        assertEquals(IntegrationCode.INT_FOLDER_EXISTS_DIFFERENT, other.code());
        assertEquals(before, folder.manifest("PKG-1"));
        assertEquals(List.of("PKG-1"), folder.entries());
        // Someone else's folder of that name (no or another manifest).
        folder.existingPackage("PKG-2", "{\"format\":\"something else\"}");
        assertEquals(IntegrationCode.INT_FOLDER_EXISTS_DIFFERENT,
                export.attempt(stored(c), delivery("PKG-2", "a.pdf"), ExportInfo.of(SLOT)).code());
        folder.existingPackage("PKG-3", "not json");
        assertEquals(IntegrationCode.INT_FOLDER_EXISTS_DIFFERENT,
                export.attempt(stored(c), delivery("PKG-3", "a.pdf"), ExportInfo.of(SLOT)).code());
    }

    @Test
    void everyFolderFailureHasItsCodeAndNeverLeavesAFinalFolder() {
        IntegrationConfig c = config();
        record Case(String what, Runnable setUp, Outcome outcome, IntegrationCode code) {
        }
        List<Case> cases = List.of(
                new Case("missing", () -> folder.missing(true), Outcome.NOT_CONFIGURED, IntegrationCode.INT_FOLDER_NOT_FOUND),
                new Case("read-only", () -> folder.readOnly(true), Outcome.NOT_CONFIGURED,
                        IntegrationCode.INT_FOLDER_NO_PERMISSION),
                new Case("unreachable", () -> folder.unreachable(true), Outcome.CANT_CONNECT,
                        IntegrationCode.INT_FOLDER_UNREACHABLE),
                new Case("full while writing", () -> folder.fullAfter(1500), Outcome.OUTAGE, IntegrationCode.INT_FOLDER_FULL),
                new Case("full before writing", () -> folder.reportedSpace(100), Outcome.OUTAGE,
                        IntegrationCode.INT_FOLDER_FULL),
                new Case("disk changes the bytes", () -> folder.corrupt(true), Outcome.UNCLEAR,
                        IntegrationCode.INT_INTEGRITY_MISMATCH));
        int n = 0;
        for (Case k : cases) {
            folder.working();
            k.setUp().run();
            String pkg = "PKG-F" + (n++);
            AttemptResult r = export.attempt(stored(c), delivery(pkg, "a.pdf", "b.pdf"), ExportInfo.of(SLOT));
            assertEquals(k.outcome(), r.outcome(), k.what() + ": " + r);
            assertEquals(k.code(), r.code(), k.what());
            folder.working();
            assertFalse(folder.entries().contains(pkg), k.what() + ": no final folder");
            assertFalse(folder.entries().contains(pkg + ".partial"), k.what() + ": the .partial folder was removed");
        }
        // Nothing usable: a configured folder that does not exist on this machine.
        IntegrationConfig gone = config(FolderTarget.of(dir.resolve("not-there").toString()));
        AttemptResult r = new FolderExport(Installation.onPremises()).attempt(stored(gone), delivery("P", "a.pdf"),
                ExportInfo.of(SLOT));
        assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
        assertEquals(IntegrationCode.INT_FOLDER_NOT_FOUND, r.code());
    }

    @Test
    void aSlowShareTimesOutAndALeftoverPartialFolderIsReplaced() throws Exception {
        IntegrationConfig c = config().withLimits(Limits.DEFAULT.withReadTimeout(Duration.ofSeconds(1)));
        folder.slow(Duration.ofMillis(300));
        byte[] big = new byte[6 * 64 * 1024];
        Delivery d = Delivery.of("PKG-SLOW", List.of(Delivery.Document.of("doc-1", "big.pdf", "application/pdf", big)),
                Map.of());
        AttemptResult r = export.attempt(stored(c), d, ExportInfo.of(SLOT));
        assertEquals(Outcome.CANT_CONNECT, r.outcome());
        assertEquals(IntegrationCode.INT_TIMEOUT, r.code());
        assertEquals("document", r.stepReached());
        assertEquals("doc-1", r.documentReached());
        folder.working();
        assertEquals(List.of(), folder.entries());

        // An earlier attempt that died half way left a .partial folder (with a temporary file): the next one
        // starts afresh.
        Path partial = Files.createDirectories(folder.path().resolve("PKG-SLOW.partial"));
        Files.writeString(partial.resolve(".tmp-0"), "half");
        assertEquals(Outcome.SENT, export.attempt(stored(config()), d, ExportInfo.of(SLOT)).outcome());
        assertEquals(List.of("PKG-SLOW"), folder.entries());
        assertEquals(List.of("big.pdf", "manifest.json"), folder.entries("PKG-SLOW"));
    }

    @Test
    void limitsUnreadableContentAndTheEntitysSlotId() {
        IntegrationConfig c = config().withLimits(Limits.DEFAULT.withMaxDocuments(1));
        assertEquals(IntegrationCode.INT_TOO_MANY_DOCUMENTS,
                export.attempt(stored(c), delivery("P1", "a.pdf", "b.pdf"), ExportInfo.of(SLOT)).code());
        assertEquals(IntegrationCode.INT_FILE_TOO_LARGE, export.attempt(stored(config().withLimits(
                Limits.DEFAULT.withMaxFileBytes(10))), delivery("P2", "a.pdf"), ExportInfo.of(SLOT)).code());
        assertEquals(IntegrationCode.INT_PACKAGE_TOO_LARGE, export.attempt(stored(config().withLimits(
                Limits.DEFAULT.withMaxPackageBytes(2500))), delivery("P3", "a.pdf", "b.pdf"), ExportInfo.of(SLOT)).code());
        Delivery unreadable = Delivery.of("P4", List.of(new Delivery.Document("doc-1", "a.pdf", "application/pdf", 10,
                "0".repeat(64), () -> {
                    throw new IOException("the app's store is down");
                })), Map.of());
        AttemptResult r = export.attempt(stored(config()), unreadable, ExportInfo.of(SLOT));
        assertEquals(Outcome.NOT_CONFIGURED, r.outcome());
        assertEquals(IntegrationCode.INT_CONTENT_UNREADABLE, r.code());
        assertEquals(List.of(), folder.entries());
        assertEquals(IntegrationCode.INT_DISABLED, export.attempt(stored(config().withEnabled(false)),
                delivery("P5", "a.pdf"), ExportInfo.of(SLOT)).code());
        assertThrows(IllegalArgumentException.class, () -> export.attempt(stored(config()), delivery("P6", "a.pdf"),
                ExportInfo.of("ENT-OTHER")));
        assertThrows(IllegalArgumentException.class, () -> new ExportInfo(SLOT, "[1]"));
        assertThrows(IllegalArgumentException.class, () -> new ExportInfo("ent-1", null));
        // On the hosted service a folder export is never run.
        assertEquals(IntegrationCode.INT_CONFIG_INVALID, new FolderExport(Installation.hostedService(), folder,
                Clock.systemUTC()).attempt(stored(config()), delivery("P7", "a.pdf"), ExportInfo.of(SLOT)).code());
    }

    @Test
    void namesFromTemplatesAreSafeAndUnique() {
        IntegrationConfig c = config(FolderTarget.of(folder.folder()).withFolderName("{value.batch}-{package.id}"));
        Delivery d = delivery("PKG:7", "same.pdf", "SAME.pdf", "manifest.json", "a:b?.pdf");
        AttemptResult r = export.attempt(stored(c), d, ExportInfo.of(SLOT));
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertEquals(List.of("B7-PKG%3A7"), folder.entries(), "the value is percent-encoded: one segment");
        assertEquals(List.of("SAME (2).pdf", "a_b_.pdf", "manifest (2).json", "manifest.json", "same.pdf"),
                folder.entries("B7-PKG%3A7"));
        IntegrationConfig byId = config(FolderTarget.of(folder.folder()).withFileName("{doc.sourceDocumentId}.pdf"));
        assertEquals(Outcome.SENT, export.attempt(stored(byId), delivery("PKG-8", "x.pdf", "y.pdf"),
                ExportInfo.of(SLOT)).outcome());
        assertEquals(List.of("doc-1.pdf", "doc-2.pdf", "manifest.json"), folder.entries("PKG-8"));
        // A value that would make a reserved name: refused, nothing written.
        IntegrationConfig reserved = config(FolderTarget.of(folder.folder()).withFolderName("{value.batch}"));
        AttemptResult bad = export.attempt(stored(reserved), Delivery.of("P9", List.of(), Map.of("batch", "CON")),
                ExportInfo.of(SLOT));
        assertEquals(IntegrationCode.INT_VALUE_NOT_ALLOWED, bad.code());
        assertEquals(IntegrationCode.INT_VALUE_MISSING, export.attempt(stored(reserved),
                Delivery.of("P10", List.of(), Map.of()), ExportInfo.of(SLOT)).code());
        // A sensitive field in a name is refused at run time too (by the delivery's own flags).
        IntegrationConfig sensitive = config(FolderTarget.of(folder.folder()).withFolderName("{field:holder_name}"));
        assertEquals(IntegrationCode.INT_SENSITIVE_FIELD_NOT_ALLOWED, export.attempt(stored(sensitive),
                delivery("P11", "a.pdf").withFields(fields()), ExportInfo.of(SLOT)).code());
    }

    @Test
    void theHealthCheckWritesAndRemovesOneProbeFile() {
        Recording rec = new Recording(folder);
        FolderExport ex = new FolderExport(Installation.onPremises(), rec, Clock.systemUTC());
        HealthResult h = ex.health(stored(config()));
        assertTrue(h.healthy(), h.toString());
        assertEquals(IntegrationCode.INT_HEALTHY, h.code());
        assertEquals(1, rec.created.size());
        assertTrue(rec.created.get(0).startsWith(".platform-health-"));
        assertEquals(List.of(), folder.entries(), "nothing left");
        folder.readOnly(true);
        assertEquals(IntegrationCode.INT_FOLDER_NO_PERMISSION, ex.health(stored(config())).code());
        folder.working().missing(true);
        HealthResult missing = ex.health(stored(config()));
        assertEquals(IntegrationCode.INT_FOLDER_NOT_FOUND, missing.code());
        assertTrue(missing.down());
        folder.working().unreachable(true);
        assertEquals(IntegrationCode.INT_FOLDER_UNREACHABLE, ex.health(stored(config())).code());
        folder.working().fullAfter(0);
        assertEquals(IntegrationCode.INT_FOLDER_FULL, ex.health(stored(config())).code());
    }

    @Test
    void theTestActionWritesTheFakePdfAndAManifestThenRemovesThem() {
        List<AttemptResult> audited = new ArrayList<>();
        IntegrationAudit audit = new IntegrationAudit() {
            @Override
            public void changed(ConfigChange change) {
            }

            @Override
            public void tested(AttemptResult result, String by) {
                audited.add(result);
            }
        };
        Recording rec = new Recording(folder);
        FolderExport ex = new FolderExport(Installation.onPremises(), rec, Clock.systemUTC());
        CallEngine engine = new CallEngine(new Outbound(Installation.onPremises()), new InMemorySecretResolver(),
                Clock.systemUTC());
        TestAction test = new TestAction(engine, ex, audit);
        AttemptResult r = test.run(stored(config(FolderTarget.of(folder.folder())
                .withFileName("{field:reference_number}-{doc.fileName}"))), "admin");
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertTrue(r.test());
        assertEquals(List.of(r), audited);
        assertTrue(rec.renames.get(rec.renames.size() - 1).startsWith("platform-test-"), rec.renames.toString());
        assertTrue(rec.renames.contains("test-reference_number-" + TestAction.FAKE_FILE_NAME), rec.renames.toString());
        assertEquals(List.of(), folder.entries(), "the test folder is removed");
        folder.readOnly(true);
        AttemptResult refused = test.run(stored(config()), "admin");
        assertEquals(IntegrationCode.INT_FOLDER_NO_PERMISSION, refused.code());
        assertEquals(2, audited.size());
        // Without a folder exporter, a folder export's Test action says so.
        assertEquals(IntegrationCode.INT_KIND_NOT_SUPPORTED,
                new TestAction(engine, audit).run(stored(config()), "admin").code());
        engine.close();
    }

    @Test
    void realFoldersOnThisSystem() throws Exception {
        Path target = Files.createDirectories(dir.resolve("real exports"));
        FolderExport ex = new FolderExport(Installation.onPremises());
        IntegrationConfig c = config(FolderTarget.of(target.toAbsolutePath().toString()));
        assertEquals(Outcome.SENT, ex.attempt(stored(c), delivery("PKG-R", "a.pdf"), ExportInfo.of(SLOT)).outcome());
        assertTrue(Files.exists(target.resolve("PKG-R").resolve("manifest.json")));
        assertTrue(ex.health(stored(c)).healthy());
        try (var s = Files.list(target)) {
            assertEquals(List.of("PKG-R"), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    /**
     * Windows-style paths where the JDK allows them: on Windows a drive path written with forward slashes works; on
     * other systems a share path is not a path here (the share is mounted and its mount path configured). One test,
     * so nothing is skipped on either system.
     */
    @Test
    void windowsStylePathsWhereTheJdkAllowsThem() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            aSharePathIsNotAPathOnThisSystem();
            return;
        }
        Path target = Files.createDirectories(dir.resolve("win"));
        String forward = target.toAbsolutePath().toString().replace('\\', '/');
        assertTrue(FolderTarget.validFolder(forward), forward);
        FolderExport ex = new FolderExport(Installation.onPremises());
        assertEquals(Outcome.SENT, ex.attempt(stored(config(FolderTarget.of(forward))), delivery("PKG-W", "a.pdf"),
                ExportInfo.of(SLOT)).outcome());
        assertTrue(Files.exists(target.resolve("PKG-W").resolve("manifest.json")));
    }

    private void aSharePathIsNotAPathOnThisSystem() {
        // On Linux a share is mounted and its mount path configured: a \\server\share path is not found here.
        AttemptResult r = new FolderExport(Installation.onPremises()).attempt(
                stored(config(FolderTarget.of("\\\\fileserver.example.test\\exports"))), delivery("P", "a.pdf"),
                ExportInfo.of(SLOT));
        assertEquals(IntegrationCode.INT_FOLDER_NOT_FOUND, r.code());
    }

    /** Records what the exporter does, by file name only. */
    static final class Recording implements ExportFiles {
        final ExportFiles inner;
        final List<String> renames = new ArrayList<>();
        final List<String> created = new ArrayList<>();
        final List<String> readBack = new ArrayList<>();

        Recording(ExportFiles inner) {
            this.inner = inner;
        }

        @Override
        public Path resolve(String f) throws IOException {
            return inner.resolve(f);
        }

        @Override
        public boolean reachable(Path target) {
            return inner.reachable(target);
        }

        @Override
        public boolean isDirectory(Path p) {
            return inner.isDirectory(p);
        }

        @Override
        public boolean exists(Path p) {
            return inner.exists(p);
        }

        @Override
        public void createDirectory(Path p) throws IOException {
            created.add(p.getFileName().toString());
            inner.createDirectory(p);
        }

        @Override
        public OutputStream create(Path p) throws IOException {
            created.add(p.getFileName().toString());
            return inner.create(p);
        }

        @Override
        public InputStream open(Path p) throws IOException {
            readBack.add(p.getFileName().toString());
            return inner.open(p);
        }

        @Override
        public void rename(Path from, Path to) throws IOException {
            renames.add(to.getFileName().toString());
            inner.rename(from, to);
        }

        @Override
        public void delete(Path p) throws IOException {
            inner.delete(p);
        }

        @Override
        public long usableSpace(Path p) throws IOException {
            return inner.usableSpace(p);
        }
    }
}
