package com.example.sample;

import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Packaging;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.folder.ExportInfo;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.modelgov.Approver;
import com.documentplatform.modelgov.ConfigFingerprint;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelDigest;
import com.documentplatform.modelgov.ModelGate;
import com.documentplatform.modelgov.ModelGovException;
import com.documentplatform.modelgov.ModelGovernance;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.monitoring.spring.DiagnosticBundles;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.secret.SecretRef;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** The smoke steps, one POST each: "OK …" or 500 with what was wrong. Only the monitoring user on this machine reaches it. */
@RestController
class Smoke {

    static final String SLOT = "ENT-DEMO-0001";
    static final String PRODUCT = "PRODUCT_A";
    static final String NAME = "Sample Borrower Name";

    private final Environment env;
    private final DataSource ds;
    private final LoginConnections logins;
    private final SlotActivation slots;
    private final FieldDefinitions definitions;
    private final FieldValues values;
    private final Eraser eraser;
    private final IntegrationConfigs integrations;
    private final FolderExport folders;
    private final CallEngine calls;
    private final ModelGovernance models;
    private final ModelGate gate;
    private final DiagnosticBundles bundles;
    private final Notifier notifier;
    private final NotificationSettings notifications;
    private final LicenceService licence;

    Smoke(Environment env, DataSource ds, LoginConnections logins, SlotActivation slots, FieldDefinitions definitions,
          FieldValues values, Eraser eraser, IntegrationConfigs integrations, FolderExport folders, CallEngine calls,
          ModelGovernance models, ModelGate gate, DiagnosticBundles bundles, Notifier notifier,
          NotificationSettings notifications, LicenceService licence) {
        this.env = env;
        this.ds = ds;
        this.logins = logins;
        this.slots = slots;
        this.definitions = definitions;
        this.values = values;
        this.eraser = eraser;
        this.integrations = integrations;
        this.folders = folders;
        this.calls = calls;
        this.models = models;
        this.gate = gate;
        this.bundles = bundles;
        this.notifier = notifier;
        this.notifications = notifications;
        this.licence = licence;
    }

    @PostMapping("/smoke/{step}")
    ResponseEntity<String> run(@PathVariable String step, @RequestBody(required = false) String input) {
        try {
            String detail = switch (step) {
                case "sign-in" -> signIn(input);
                case "activate" -> activate();
                case "field" -> field();
                case "field-present" -> found(true);
                case "field-gone" -> found(false);
                case "erase" -> erase();
                case "export" -> export();
                case "push" -> push();
                case "model" -> model();
                case "bundle" -> bundle(input);
                case "notify" -> mail();
                case "intake" -> intake();
                default -> throw new IllegalStateException("no such step");
            };
            return ResponseEntity.ok("OK " + detail + "\n");
        } catch (Exception | AssertionError e) {
            return ResponseEntity.internalServerError().body("FAILED " + step + ": " + e + "\n");
        }
    }

    private static void check(boolean ok, Object what) {
        if (!ok) throw new IllegalStateException(String.valueOf(what));
    }

    private String tenant() {
        return env.getRequiredProperty("platform.licensing.tenant-id");
    }

    private UUID entity() {
        return new PostgresEntityStore(ds).entity(SLOT).orElseThrow().entityUuid();
    }

    private FieldScope scope() {
        return FieldScope.of(entity(), PRODUCT);
    }

    private String signIn(String token) {
        LoginConnection active = logins.active(tenant(), PRODUCT).orElseThrow();
        ConnectionOutcome draft = logins.createDraft(tenant(), PRODUCT, active.configurationVersion(), ConnectionValues.of(active), "it-admin");
        check(!draft.refused(), draft.message());
        ConnectionOutcome tested = logins.validate(draft.connection().connectionId(), draft.connection().configurationVersion(),
                TestSignIn.withToken(token.strip()), "it-admin");
        check(!tested.refused(), tested.message());
        ConnectionOutcome on = logins.activate(tested.connection().connectionId(), tested.connection().configurationVersion(), "it-admin");
        check(!on.refused(), on.message());
        return "test sign-in passed, connection switched on";
    }

    private String activate() {
        var outcome = slots.activate(SLOT, "Sample Finance", "business-admin");
        check(!outcome.refused(), outcome.message());
        return SLOT + " " + outcome.entity().status();
    }

    private String field() throws Exception {
        definitions.create(scope(), FieldSpec.builder("contract_number", "Contract number", FieldType.TEXT).primary(true)
                .searchable(SearchMode.EXACT).build(), "business-admin");
        definitions.create(scope(), FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).sensitive(true)
                .searchable(SearchMode.EXACT).build(), "business-admin");
        var claim = values.claim(record("C-1"), Map.of("contract_number", List.of(FieldValue.text("K-1001")),
                "borrower_name", List.of(FieldValue.text(NAME))));
        check(claim.created(), "record made");
        try (var c = ds.getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("select count(*) from fld_value v where v::text like '%" + NAME + "%'")) {
            rs.next();
            check(rs.getInt(1) == 0, "the name is stored readably");
        }
        found(true);
        check(values.readSensitive(record("C-1")).values("borrower_name").toString().contains(NAME), "opened with the key");
        return "encrypted, found by exact search, opened with the customer's key";
    }

    private RecordRef record(String id) {
        return RecordRef.of(scope(), "case", id);
    }

    private String found(boolean expected) {
        int n = values.search(scope(), "case", "borrower_name", SearchMode.EXACT, FieldValue.text(NAME)).records().size();
        check(n == (expected ? 1 : 0), "search found " + n + " record(s)");
        return expected ? "present" : "gone";
    }

    private String erase() {
        eraser.erase(new EraseRequest(entity(), PRODUCT, "case", "C-1", com.documentplatform.eraselog.fields.CaseFieldsEraseHandler.KIND,
                Instant.now(), ErasedBy.rule("RET-SAMPLE"), "AUD-1"));
        return found(false);
    }

    private static Delivery delivery(String id) {
        return Delivery.of(id, List.of(Delivery.Document.of("doc-1", "statement.pdf", "application/pdf",
                "%PDF-1.4 sample".getBytes(StandardCharsets.UTF_8))), Map.of());
    }

    private String export() throws Exception {
        Path target = Path.of(env.getRequiredProperty("sample.export-folder")).toAbsolutePath();
        var saved = integrations.create(IntegrationConfig.folderExport(entity(), PRODUCT, "Sample export",
                FolderTarget.of(target.toString())), "it-admin");
        AttemptResult r = folders.attempt(saved, delivery("PKG-1"), new ExportInfo(SLOT, "{\"case\":\"C-1\"}"));
        check(r.outcome() == Outcome.SENT, r);
        String manifest = Files.readString(target.resolve("PKG-1").resolve("manifest.json"));
        check(manifest.contains("platform-export-manifest/1") && manifest.contains("statement.pdf"), manifest);
        return "package and manifest written";
    }

    private String push() {
        var saved = integrations.create(IntegrationConfig.apiPush(entity(), PRODUCT, "Sample push",
                        Endpoint.of(env.getRequiredProperty("sample.push-address")),
                        new SignIn.ApiKey("X-Api-Key", SecretRef.of("int/" + SLOT + "/api-key")))
                .withSteps(Packaging.filePerCall("/documents", Map.of())), "it-admin");
        AttemptResult r = calls.attempt(saved, delivery("PKG-2"));
        check(r.delivered(), r);
        return "document sent: " + r.code();
    }

    private String model() {
        Model m = models.registerModel(Model.create("sample-detector", Model.Provider.VENDOR, "1.0",
                new ModelDigest("sha256", "ab".repeat(32)), Model.RunsWhere.CUSTOMER_ENVIRONMENT), "model-admin");
        String config = ConfigFingerprint.of("{\"prompt\":\"sample\"}");
        ModelUse use = models.registerUse(ModelUse.propose(PRODUCT, "sample: detection", null, "Finds sample items",
                ModelUse.Materiality.HIGH, "A person checks each item", "A reviewer confirms", "vendor owner", "customer owner",
                m.modelId(), config), "model-admin");
        models.setDefaultThreshold(Threshold.byDefault(use.useId(), "accuracy", new BigDecimal("0.9"),
                Threshold.Direction.HIGHER_IS_BETTER), "model-admin");
        String refused = refusedBy(() -> gate.requireLive(use.useId(), entity(), m.digest(), config));
        check(refused.equals("MOD_NOT_VALIDATED"), refused);
        var v = models.recordValidation(use.useId(), entity(), "sample-set", "1", Map.of("accuracy", new BigDecimal("0.95")), "tester");
        check(refusedBy(() -> models.approve(v.validationId(), new Approver("owner-1", java.util.Set.of("MODEL_APPROVER", "BUSINESS_ADMIN"))))
                .equals("MOD_APPROVER_CONFLICT"), "an approver who is also Business Admin");
        models.approve(v.validationId(), new Approver("owner-1", java.util.Set.of("MODEL_APPROVER")));
        models.goLive(use.useId(), entity(), v.validationId(), "model-admin");
        check(gate.requireLive(use.useId(), entity(), m.digest(), config) != null, "live");
        return "refused before, approved, live";
    }

    private static String refusedBy(Runnable action) {
        try {
            action.run();
            return "(nothing)";
        } catch (ModelGovException e) {
            return String.valueOf(e.platformCode().code());
        }
    }

    /** The input lists the installation's secrets, one per line: none may be in the bundle. */
    private String bundle(String secrets) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bundles.write(out);
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            while (zip.getNextEntry() != null) text.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        check(text.length() > 500, "the bundle is empty");
        int n = 0;
        for (String secret : secrets.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()) {
            n++;
            check(text.indexOf(secret) < 0, "secret number " + n + " is in the bundle");
        }
        check(n > 0, "no secrets were given to look for");
        check(text.indexOf(NAME) < 0, "a field value is in the bundle");
        return "bundle of " + out.size() + " bytes, none of " + n + " secrets in it";
    }

    private String mail() {
        TenantId t = TenantId.of(tenant());
        notifications.connectChannels(t, new TenantSettings(URI.create("https://app.sample.example/"), ZoneOffset.UTC,
                Map.of(DestinationId.of("mail"), ChannelKind.EMAIL)));
        EventTypeId type = EventTypeId.of(SampleApp.HELLO);
        notifications.setTechnicalEvent(t, type, notifications.effective(t, null, type).withDestinations(DestinationId.of("mail")));
        notifier.publish(NotificationEvent.builder(type, t).identifier(RecordIdentifier.of("C-1"))
                .link(AppLink.of("/cases/C-1")).occurredAt(Instant.now()).build());
        return "published";
    }

    private String intake() {
        boolean allowed = licence.intakeAllowed();
        boolean enforce = env.getRequiredProperty("sample.licensing").equals("enforce");
        if (enforce && !allowed) return "REFUSED " + licence.status().state();
        return "ACCEPTED " + licence.status().state() + (enforce ? "" : " (read-only, allowed=" + allowed + ")");
    }
}
