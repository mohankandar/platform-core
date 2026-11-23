package com.documentplatform.modelgov.testkit;

import com.documentplatform.modelgov.Approver;
import com.documentplatform.modelgov.ConfigFingerprint;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelDigest;
import com.documentplatform.modelgov.ModelGovSettings;
import com.documentplatform.modelgov.ModelUse;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Made-up models, uses, roles and configurations for tests: product {@code PRODUCT_A}, use "example: detection",
 * roles {@code MODEL_APPROVER}, {@code COMPLIANCE_OFFICER} and the forbidden {@code BUSINESS_ADMIN}. None names a
 * real product or model.
 */
public final class ModelGovFixtures {

    public static final String PRODUCT = "PRODUCT_A";
    public static final String APPROVER_ROLE = "MODEL_APPROVER";
    public static final String COMPLIANCE_ROLE = "COMPLIANCE_OFFICER";
    public static final String FORBIDDEN_ROLE = "BUSINESS_ADMIN";

    /** The configuration of the example use, as the app's canonical JSON would hold it. */
    public static final String CONFIG = "{\"prompt\":\"example prompt v1\",\"temperature\":0,\"maxTokens\":512}";
    public static final String CONFIG_CHANGED = "{\"prompt\":\"example prompt v2\",\"temperature\":0,\"maxTokens\":512}";

    private ModelGovFixtures() {
    }

    /** Model approver and compliance officer may approve; Business Admin never. */
    public static ModelGovSettings settings() {
        return ModelGovSettings.of(Set.of(APPROVER_ROLE, COMPLIANCE_ROLE), Set.of(FORBIDDEN_ROLE));
    }

    /** A digest made from a word, so tests can tell digests apart. */
    public static ModelDigest digest(String word) {
        return new ModelDigest("sha256", java.util.HexFormat.of().formatHex(sha256(word)));
    }

    public static Model vendorModel() {
        return Model.create("example-detector", Model.Provider.VENDOR, "1.0", digest("vendor-1.0"),
                Model.RunsWhere.CUSTOMER_ENVIRONMENT);
    }

    public static Model customerModel() {
        return Model.create("customer-detector", Model.Provider.CUSTOMER, "7", digest("customer-7"),
                Model.RunsWhere.CUSTOMER_ENVIRONMENT);
    }

    /** "example: detection" on {@code model}, for one entity (or every entity when null). */
    public static ModelUse use(Model model, UUID entity, ModelUse.Materiality materiality) {
        return ModelUse.propose(PRODUCT, "example: detection", entity, "Finds example items in a test document",
                materiality, "A wrong answer is checked by a person before it is used",
                "A reviewer confirms each item", "vendor model owner", "customer model owner", model.modelId(),
                ConfigFingerprint.of(CONFIG));
    }

    public static Approver approver() {
        return new Approver("approver-1", Set.of(APPROVER_ROLE));
    }

    public static Approver complianceOfficer() {
        return new Approver("compliance-1", Set.of(COMPLIANCE_ROLE));
    }

    /** Holds both the approver role and the forbidden one. */
    public static Approver bothRoles() {
        return new Approver("admin-and-approver", Set.of(APPROVER_ROLE, FORBIDDEN_ROLE));
    }

    public static Approver businessAdmin() {
        return new Approver("admin-1", Set.of(FORBIDDEN_ROLE));
    }

    public static Approver noRole() {
        return new Approver("someone", Set.of("VIEWER"));
    }

    /** Made-up pack texts for every use, with one independent review reference. */
    public static com.documentplatform.modelgov.PackTexts packTexts() {
        return new com.documentplatform.modelgov.PackTexts() {
            @Override
            public UseText describe(ModelUse use) {
                return new UseText("A classifier over page images, tuned on public example data",
                        "In: page images of the test documents; out: item positions with a confidence",
                        "Low-confidence items go to a reviewer; nothing is applied without a person",
                        "Weaker on handwriting and on pages scanned below 150 dpi");
            }

            @Override
            public ThirdParty thirdParty(String product) {
                return new ThirdParty("The vendor validates its default models; the customer validates its own, and "
                        + "the app's test set runs against every model before it goes live.",
                        List.of(new DocumentRef("Independent review of the default models", "review-2026-11.pdf",
                                "ab".repeat(32))));
            }

            @Override
            public String dataStatement(String product) {
                return "No model is trained on customer documents; models read data while they work and keep nothing.";
            }
        };
    }

    private static byte[] sha256(String word) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(word.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
