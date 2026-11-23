package com.documentplatform.modelgov;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The documentation pack for the customer's model-risk team (design section 6), per product and entity over a
 * period: JSON in the format {@value #FORMAT} (schema {@code schema/platform-model-pack-1.schema.json}) and a PDF
 * that prints the JSON's fingerprint. Items: 1 the register entries in scope, 2 per use its purpose, design summary,
 * inputs and outputs, human in the loop, guardrails and known limits (the app's {@link PackTexts}), 3 the validation
 * history with pass marks, results, approvals and acceptances, 4 monitoring (pass marks, readings, drifts and actions),
 * 5 the change log (versions, digests, configuration fingerprints, live switches and rollbacks), 6 the third-party
 * statement with the independent review references, 7 the data statement.
 *
 * <p>The register entries are as they are now; items 3 to 5 are those of the period [from, to). The same data
 * always gives the same JSON (keys sorted, lists in a fixed order, no export time), so the same fingerprint. Neither
 * part holds document content or values: measures are numbers, configurations only their fingerprints.
 */
public final class ModelPack {

    public static final String FORMAT = "platform-model-pack/1";

    /** The section titles, in order (also the PDF's headings). */
    public static final List<String> SECTIONS = List.of("1. Model register", "2. Uses: purpose, design and limits",
            "3. Validation history", "4. Monitoring", "5. Change log", "6. Third-party statement",
            "7. Data statement");

    /** The exported pack: the JSON, its fingerprint ({@code sha256:} of the JSON's UTF-8 bytes) and the PDF. */
    public record Pack(String json, String fingerprint, byte[] pdf) {
        public Pack {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(fingerprint, "fingerprint");
            pdf = pdf.clone();
        }

        @Override
        public byte[] pdf() {
            return pdf.clone();
        }

        @Override
        public String toString() {
            return "Pack[" + fingerprint + ", json " + json.length() + " chars, pdf " + pdf.length + " bytes]";
        }
    }

    private final ModelGovStore store;
    private final ModelMeasures measures;
    private final PackTexts texts;

    public ModelPack(ModelGovStore store, ModelMeasures measures, PackTexts texts) {
        this.store = Objects.requireNonNull(store, "store");
        this.measures = Objects.requireNonNull(measures, "measures");
        this.texts = Objects.requireNonNull(texts, "texts");
    }

    /** The pack for a product and entity, for the period [from, to). */
    public Pack export(String product, UUID entity, Instant from, Instant to) {
        Map<String, Object> tree = tree(product, entity, from, to);
        String json = ConfigFingerprint.canonicalOf(tree);
        String fingerprint = ConfigFingerprint.sha256Of(json);
        try {
            return new Pack(json, fingerprint, PackPdf.render(tree, fingerprint));
        } catch (IOException e) {
            throw new UncheckedIOException("The documentation pack's PDF could not be written", e);
        }
    }

    /** The pack's data as maps and lists, before it is written. */
    Map<String, Object> tree(String product, UUID entity, Instant from, Instant to) {
        Values.product(product);
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) throw Values.invalid("period", "from before to");
        List<ModelUse> uses = store.uses(product).stream().filter(u -> u.entity() == null || u.entity().equals(entity))
                .sorted(Comparator.comparing(ModelUse::feature).thenComparing(u -> u.useId().toString())).toList();
        Map<UUID, Model> models = new LinkedHashMap<>();
        for (ModelUse u : uses) {
            store.model(u.modelId()).ifPresent(m -> models.put(m.modelId(), m));
        }

        List<Object> register = new ArrayList<>();
        List<Object> described = new ArrayList<>();
        List<Object> validations = new ArrayList<>();
        List<Object> thresholds = new ArrayList<>();
        List<Object> readings = new ArrayList<>();
        List<Object> drifts = new ArrayList<>();
        List<Object> useChanges = new ArrayList<>();
        List<Object> liveChanges = new ArrayList<>();
        for (ModelUse u : uses) {
            Model m = models.get(u.modelId());
            Map<String, Object> e = map();
            e.put("useId", u.useId().toString());
            e.put("product", u.product());
            e.put("feature", u.feature());
            e.put("scope", u.entity() == null ? "every entity" : "this entity");
            e.put("status", u.status().name());
            e.put("needsReview", measures.needsReview(u.useId()));
            e.put("materiality", u.materiality().name());
            e.put("materialityReason", u.materialityReason());
            e.put("lastValidatedAt", str(u.lastValidatedAt()));
            e.put("nextReviewDue", u.nextReviewDue() == null ? null : u.nextReviewDue().toString());
            e.put("reviewEveryDays", u.reviewEveryDays());
            e.put("vendorOwner", u.vendorOwner());
            e.put("customerOwner", u.customerOwner());
            e.put("configFingerprint", u.configFingerprint());
            e.put("model", m == null ? null : model(m));
            register.add(e);

            PackTexts.UseText t = texts.describe(u);
            Map<String, Object> d = map();
            d.put("useId", u.useId().toString());
            d.put("feature", u.feature());
            d.put("purpose", u.purpose());
            d.put("humanInTheLoop", u.humanInTheLoop());
            d.put("designSummary", t.designSummary());
            d.put("inputsAndOutputs", t.inputsAndOutputs());
            d.put("guardrails", t.guardrails());
            d.put("knownLimits", t.knownLimits());
            described.add(d);

            for (ValidationRecord v : store.validations(u.useId(), entity)) {
                if (!in(v.ranAt(), from, to)) continue;
                validations.add(validation(v));
            }
            for (Monitoring.MonitorThreshold mt : measures.thresholdsInForce(u.useId(), entity).values()) {
                Map<String, Object> x = map();
                x.put("useId", u.useId().toString());
                x.put("measure", mt.measure());
                x.put("setFor", mt.entity() == null ? "default" : "this entity");
                x.put("value", mt.value());
                x.put("direction", mt.direction().name());
                x.put("minSample", mt.minSample());
                thresholds.add(x);
            }
            for (Monitoring.Reading r : store.readings(u.useId(), entity, from, to)) {
                Map<String, Object> x = map();
                x.put("readingId", r.readingId().toString());
                x.put("useId", u.useId().toString());
                x.put("measure", r.measure());
                x.put("value", r.value());
                x.put("periodFrom", str(r.period().from()));
                x.put("periodTo", str(r.period().to()));
                x.put("samples", r.period().samples());
                x.put("reportedAt", str(r.reportedAt()));
                readings.add(x);
            }
            for (ModelMeasures.DriftStatus ds : measures.drifts(u.useId(), entity)) {
                Monitoring.Drift dr = ds.drift();
                Optional<Monitoring.DriftAction> act = ds.action();
                if (!in(dr.raisedAt(), from, to) && !(act.isEmpty() || in(act.get().actedAt(), from, to))) continue;
                Map<String, Object> x = map();
                x.put("driftId", dr.driftId().toString());
                x.put("useId", u.useId().toString());
                x.put("measure", dr.measure());
                x.put("value", dr.value());
                x.put("threshold", dr.threshold());
                x.put("direction", dr.direction().name());
                x.put("samples", dr.samples());
                x.put("raisedAt", str(dr.raisedAt()));
                x.put("action", act.map(a -> {
                    Map<String, Object> y = map();
                    y.put("note", a.note());
                    y.put("by", a.actedBy());
                    y.put("at", str(a.actedAt()));
                    return (Object) y;
                }).orElse(null));
                drifts.add(x);
            }
            for (ModelGovStore.HistoryRow h : store.useHistory(u.useId())) {
                if (!in(h.changedAt(), from, to)) continue;
                Map<String, Object> x = history(h);
                x.put("useId", u.useId().toString());
                useChanges.add(x);
            }
            for (Decisions.LiveChange lc : store.liveHistory(u.useId(), entity)) {
                if (!in(lc.changedAt(), from, to)) continue;
                Map<String, Object> x = map();
                x.put("useId", u.useId().toString());
                x.put("kind", lc.kind().name());
                x.put("fromValidationId", lc.fromValidationId() == null ? null : lc.fromValidationId().toString());
                x.put("toValidationId", lc.toValidationId().toString());
                x.put("by", lc.changedBy());
                x.put("at", str(lc.changedAt()));
                liveChanges.add(x);
            }
        }
        List<Object> modelChanges = new ArrayList<>();
        List<Object> vendorModels = new ArrayList<>();
        List<Object> customerModels = new ArrayList<>();
        for (Model m : models.values().stream().sorted(Comparator.comparing(Model::name)
                .thenComparing(x -> x.modelId().toString())).toList()) {
            for (ModelGovStore.HistoryRow h : store.modelHistory(m.modelId())) {
                if (!in(h.changedAt(), from, to)) continue;
                Map<String, Object> x = history(h);
                x.put("modelId", m.modelId().toString());
                modelChanges.add(x);
            }
            (m.provider() == Model.Provider.VENDOR ? vendorModels : customerModels).add(model(m));
        }

        PackTexts.ThirdParty tp = texts.thirdParty(product);
        List<Object> reviews = new ArrayList<>();
        for (PackTexts.DocumentRef r : tp.independentReviews()) {
            Map<String, Object> x = map();
            x.put("title", r.title());
            x.put("reference", r.reference());
            x.put("sha256", r.sha256());
            reviews.add(x);
        }
        String dataStatement = texts.dataStatement(product);
        Values.text("pack.dataStatement", dataStatement, 4000, true);

        Map<String, Object> monitoring = map();
        monitoring.put("thresholds", thresholds);
        monitoring.put("readings", readings);
        monitoring.put("drifts", drifts);
        Map<String, Object> changes = map();
        changes.put("models", modelChanges);
        changes.put("uses", useChanges);
        changes.put("live", liveChanges);
        Map<String, Object> thirdParty = map();
        thirdParty.put("vendorModels", vendorModels);
        thirdParty.put("customerModels", customerModels);
        thirdParty.put("statement", tp.statement());
        thirdParty.put("independentReviews", reviews);
        Map<String, Object> period = map();
        period.put("from", str(from));
        period.put("to", str(to));

        Map<String, Object> root = map();
        root.put("format", FORMAT);
        root.put("product", product);
        root.put("entity", entity.toString());
        root.put("period", period);
        root.put("register", register);
        root.put("uses", described);
        root.put("validations", validations);
        root.put("monitoring", monitoring);
        root.put("changes", changes);
        root.put("thirdParty", thirdParty);
        root.put("dataStatement", dataStatement);
        return root;
    }

    private Map<String, Object> validation(ValidationRecord v) {
        Map<String, Object> x = map();
        x.put("validationId", v.validationId().toString());
        x.put("useId", v.useId().toString());
        x.put("modelName", v.modelName());
        x.put("modelVersion", v.modelVersion());
        x.put("provider", v.provider().name());
        x.put("digest", v.digest().toString());
        x.put("configFingerprint", v.configFingerprint());
        x.put("testSet", v.testSetName() + " " + v.testSetVersion());
        List<Object> ms = new ArrayList<>();
        for (ValidationRecord.MeasureResult m : v.measures()) {
            Map<String, Object> y = map();
            y.put("measure", m.measure());
            y.put("result", m.result());
            y.put("threshold", m.threshold());
            y.put("direction", m.direction().name());
            y.put("passed", m.passed());
            ms.add(y);
        }
        x.put("measures", ms);
        x.put("passed", v.passed());
        x.put("ranBy", v.ranBy());
        x.put("ranAt", str(v.ranAt()));
        x.put("approval", store.approval(v.validationId()).map(a -> {
            Map<String, Object> y = map();
            y.put("by", a.approvedBy());
            y.put("roles", new ArrayList<Object>(new TreeSet<>(a.roles())));
            y.put("at", str(a.approvedAt()));
            return (Object) y;
        }).orElse(null));
        x.put("acceptance", store.acceptance(v.validationId()).map(a -> {
            Map<String, Object> y = map();
            y.put("by", a.acceptedBy());
            y.put("at", str(a.acceptedAt()));
            y.put("results", a.results());
            y.put("reason", a.reason());
            return (Object) y;
        }).orElse(null));
        return x;
    }

    private static Map<String, Object> model(Model m) {
        Map<String, Object> x = map();
        x.put("modelId", m.modelId().toString());
        x.put("name", m.name());
        x.put("provider", m.provider().name());
        x.put("version", m.modelVersion());
        x.put("digest", m.digest().toString());
        x.put("runsWhere", m.runsWhere().name());
        return x;
    }

    private static Map<String, Object> history(ModelGovStore.HistoryRow h) {
        Map<String, Object> x = map();
        x.put("version", h.version());
        x.put("by", h.changedBy());
        x.put("at", str(h.changedAt()));
        x.put("changedFields", new ArrayList<Object>(h.changedFields()));
        x.put("recorded", new LinkedHashMap<String, Object>(h.recorded()));
        return x;
    }

    private static boolean in(Instant t, Instant from, Instant to) {
        return !t.isBefore(from) && t.isBefore(to);
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
