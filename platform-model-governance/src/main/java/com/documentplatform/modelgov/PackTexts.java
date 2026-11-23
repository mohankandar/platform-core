package com.documentplatform.modelgov;

import java.util.List;
import java.util.Objects;

/**
 * The texts the app (or the vendor) supplies for the documentation pack, in plain words: per use its design summary,
 * inputs and outputs, guardrails and known limits; per product the third-party statement with references to the
 * independent review documents, and the data statement. Never document content, values or secrets: the texts are
 * checked like every other free text of the register.
 */
public interface PackTexts {

    /** A use's descriptive texts (design section 6, item 2). */
    record UseText(String designSummary, String inputsAndOutputs, String guardrails, String knownLimits) {
        public UseText {
            Values.text("pack.designSummary", designSummary, 4000, true);
            Values.text("pack.inputsAndOutputs", inputsAndOutputs, 4000, true);
            Values.text("pack.guardrails", guardrails, 4000, true);
            Values.text("pack.knownLimits", knownLimits, 4000, true);
        }
    }

    /** A document the pack refers to (for example the independent outside review), not its content. */
    record DocumentRef(String title, String reference, String sha256) {
        public DocumentRef {
            Values.text("pack.document.title", title, 200, true);
            Values.text("pack.document.reference", reference, 500, true);
            if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) {
                throw Values.invalid("pack.document.sha256", "64 lowercase hex characters, or none");
            }
        }
    }

    /** Which models are whose and who validates what, with the independent review (item 6). */
    record ThirdParty(String statement, List<DocumentRef> independentReviews) {
        public ThirdParty {
            Values.text("pack.thirdParty", statement, 4000, true);
            independentReviews = List.copyOf(Objects.requireNonNull(independentReviews, "independentReviews"));
        }
    }

    UseText describe(ModelUse use);

    ThirdParty thirdParty(String product);

    /** No training on customer documents; where data goes (item 7). */
    String dataStatement(String product);

    /** Placeholders, for an app that has not supplied its texts yet: the pack says so. */
    PackTexts NOT_SUPPLIED = new PackTexts() {
        static final String NONE = "Not supplied by the app.";

        @Override
        public UseText describe(ModelUse use) {
            return new UseText(NONE, NONE, NONE, NONE);
        }

        @Override
        public ThirdParty thirdParty(String product) {
            return new ThirdParty(NONE, List.of());
        }

        @Override
        public String dataStatement(String product) {
            return NONE;
        }
    };
}
