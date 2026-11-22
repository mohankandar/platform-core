package com.documentplatform.integration.engine;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.signin.SignInHeaders;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.StoredIntegration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The Test action of every integration: runs the full path with <b>fake data only</b> (a one-page PDF made here, fake
 * values for every {@code {value.<name>}} placeholder), so no real content is ever sent. It takes no delivery from the
 * app: there is no way to pass real documents to it. In {@code CHECK_ONLY} mode it runs only the health check and the
 * sign-in. Every run goes to the app's audit.
 */
public final class TestAction {

    /** The fake document's ID and file name. */
    public static final String FAKE_DOCUMENT_ID = "test-document-1";
    public static final String FAKE_FILE_NAME = "test-page.pdf";

    /** A minimal one-page PDF that says it is a test page. */
    public static final byte[] FAKE_PDF = ("%PDF-1.4\n"
            + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
            + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
            + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>"
            + "endobj\n"
            + "4 0 obj<</Length 58>>stream\nBT /F1 18 Tf 72 720 Td (Integration test page - no real data) Tj ET\n"
            + "endstream endobj\n"
            + "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
            + "trailer<</Root 1 0 R>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    public static final String FAKE_PDF_SHA256 = Delivery.sha256(FAKE_PDF);

    private final CallEngine engine;
    private final FolderExport folders;
    private final IntegrationAudit audit;

    /** For API push only: a folder export's Test action answers {@code INT_KIND_NOT_SUPPORTED}. */
    public TestAction(CallEngine engine, IntegrationAudit audit) {
        this(engine, null, audit);
    }

    /** @param folders runs the Test action of folder exports (writes and removes a {@code platform-test-…} folder) */
    public TestAction(CallEngine engine, FolderExport folders, IntegrationAudit audit) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.folders = folders;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Runs the Test action.
     *
     * @param by who asked for it (for the audit)
     */
    public AttemptResult run(StoredIntegration integration, String by) {
        IntegrationConfig c = integration.config();
        AttemptResult result;
        if (c.kind() == Kind.FOLDER_EXPORT) {
            result = folders == null
                    ? result(c, integration.version(), Outcome.NOT_CONFIGURED, IntegrationCode.INT_KIND_NOT_SUPPORTED,
                    null, 0, null)
                    : folders.test(integration, fakeDelivery(c));
        } else {
            result = c.testMode() == IntegrationConfig.TestMode.CHECK_ONLY
                    ? checkOnly(integration)
                    : engine.run(integration, fakeDelivery(c), true);
        }
        audit.tested(result, by);
        return result;
    }

    /** The fake delivery: one fake PDF, a fake value for every value placeholder the templates use. */
    public static Delivery fakeDelivery(IntegrationConfig c) {
        Map<String, String> values = new LinkedHashMap<>();
        for (CallStep s : c.steps()) {
            collect(s.path(), Template.Place.PATH, values);
            s.headers().values().forEach(h -> collect(h, Template.Place.HEADER, values));
            if (s.bodyTemplate() != null) collect(s.bodyTemplate(), Template.Place.JSON, values);
        }
        if (c.folder() != null) {
            collect(c.folder().folderNameTemplate(), Template.Place.NAME, values);
            if (c.folder().fileNameTemplate() != null) collect(c.folder().fileNameTemplate(), Template.Place.NAME, values);
        }
        String id = "test-" + UUID.randomUUID();
        return new Delivery(id, id, List.of(Delivery.Document.of(FAKE_DOCUMENT_ID, FAKE_FILE_NAME, "application/pdf",
                FAKE_PDF)), values, Delivery.Resume.NONE);
    }

    private static void collect(String template, Template.Place place, Map<String, String> into) {
        try {
            for (String p : Template.parse(template, place).placeholders()) {
                if (p.startsWith("value.")) into.put(p.substring(6), "test-" + p.substring(6));
            }
        } catch (Template.Problem ignored) {
            // A template that does not parse is reported by the run itself (configuration invalid).
        }
    }

    private AttemptResult checkOnly(StoredIntegration integration) {
        IntegrationConfig c = integration.config();
        if (c.health() != null) {
            HealthResult h = engine.health(integration);
            if (!h.healthy()) {
                return result(c, integration.version(), h.outcome(), h.code(), h.destinationCode(), h.httpStatus(),
                        "health");
            }
        }
        SignInHeaders.Prepared p = engine.checkSignIn(c);
        if (!p.ok()) return result(c, integration.version(), p.failure(), p.code(), null, 0, "sign-in");
        return result(c, integration.version(), Outcome.SENT, IntegrationCode.INT_TEST_CHECKED, null, 0, null);
    }

    private static AttemptResult result(IntegrationConfig c, int version, Outcome o, IntegrationCode code,
                                        String destCode, int status, String step) {
        return new AttemptResult(c.id(), version, o, code, destCode, status, null, o.usesAttemptByDefault(), null,
                step, null, Map.of(), Map.of(), Set.of(), Set.of(), List.of(), true);
    }
}
