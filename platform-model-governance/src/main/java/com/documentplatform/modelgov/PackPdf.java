package com.documentplatform.modelgov;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pack's PDF (Apache PDFBox): a title page block with the product, entity, period and the JSON's fingerprint,
 * then the seven sections with one line per entry. Plain A4 text in Helvetica; characters the standard font cannot
 * show are replaced by {@code ?}. Package-private: {@link ModelPack} is the entry point.
 */
final class PackPdf {

    private static final float MARGIN = 50;
    private static final float SIZE = 9;
    private static final float LEADING = 12;
    private static final int WRAP = 105;

    private PackPdf() {
    }

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> pack, String fingerprint) throws IOException {
        List<String[]> lines = new ArrayList<>(); // {style, text}: T title, H heading, B body
        lines.add(new String[]{"T", "Model documentation pack"});
        lines.add(new String[]{"B", "Format " + pack.get("format") + ", product " + pack.get("product") + ", entity "
                + pack.get("entity")});
        Map<String, Object> period = (Map<String, Object>) pack.get("period");
        lines.add(new String[]{"B", "Period " + period.get("from") + " to " + period.get("to")});
        lines.add(new String[]{"B", "Fingerprint of the JSON pack: " + fingerprint});
        lines.add(new String[]{"B", ""});

        section(lines, 0);
        for (Map<String, Object> e : list(pack.get("register"))) {
            Map<String, Object> m = (Map<String, Object>) e.get("model");
            body(lines, e.get("feature") + " (" + e.get("scope") + "): " + e.get("status")
                    + (Boolean.TRUE.equals(e.get("needsReview")) ? ", needs review" : "") + "; materiality "
                    + e.get("materiality") + " (" + e.get("materialityReason") + "); last validated "
                    + e.get("lastValidatedAt") + ", next review " + e.get("nextReviewDue") + "; model "
                    + (m == null ? "-" : m.get("name") + " " + m.get("version") + " (" + m.get("provider") + ", "
                    + m.get("runsWhere") + ") " + m.get("digest")) + "; configuration " + e.get("configFingerprint")
                    + "; owners " + e.get("vendorOwner") + " / " + e.get("customerOwner"));
        }
        section(lines, 1);
        for (Map<String, Object> u : list(pack.get("uses"))) {
            body(lines, u.get("feature") + ". Purpose: " + u.get("purpose") + " Design: " + u.get("designSummary")
                    + " Inputs and outputs: " + u.get("inputsAndOutputs") + " Human in the loop: "
                    + u.get("humanInTheLoop") + " Guardrails: " + u.get("guardrails") + " Known limits: "
                    + u.get("knownLimits"));
        }
        section(lines, 2);
        for (Map<String, Object> v : list(pack.get("validations"))) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> m : list(v.get("measures"))) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(m.get("measure")).append(' ').append(num(m.get("result")))
                        .append("HIGHER_IS_BETTER".equals(m.get("direction")) ? " >= " : " <= ")
                        .append(num(m.get("threshold"))).append(Boolean.TRUE.equals(m.get("passed")) ? " pass" : " fail");
            }
            Map<String, Object> ap = (Map<String, Object>) v.get("approval");
            Map<String, Object> ac = (Map<String, Object>) v.get("acceptance");
            body(lines, v.get("ranAt") + " " + v.get("validationId") + ": " + v.get("modelName") + " "
                    + v.get("modelVersion") + " " + v.get("digest") + ", test set " + v.get("testSet") + ", "
                    + (Boolean.TRUE.equals(v.get("passed")) ? "PASSED" : "FAILED") + " (" + sb + "), ran by "
                    + v.get("ranBy") + (ac == null ? "" : "; accepted by " + ac.get("by") + " " + ac.get("at")
                    + " (" + ac.get("results") + "): " + ac.get("reason"))
                    + (ap == null ? "; not approved" : "; approved by " + ap.get("by") + " " + ap.get("at")));
        }
        section(lines, 3);
        Map<String, Object> mon = (Map<String, Object>) pack.get("monitoring");
        for (Map<String, Object> t : list(mon.get("thresholds"))) {
            body(lines, "Pass mark " + t.get("measure") + " (" + t.get("setFor") + "): "
                    + ("HIGHER_IS_BETTER".equals(t.get("direction")) ? "at least " : "at most ") + num(t.get("value"))
                    + " over at least " + num(t.get("minSample")) + " items");
        }
        body(lines, list(mon.get("readings")).size() + " readings in the period.");
        for (Map<String, Object> d : list(mon.get("drifts"))) {
            Map<String, Object> a = (Map<String, Object>) d.get("action");
            body(lines, "Drift " + d.get("raisedAt") + ": " + d.get("measure") + " " + num(d.get("value"))
                    + " past " + num(d.get("threshold")) + " over " + num(d.get("samples")) + " items; "
                    + (a == null ? "needs review (no action yet)" : "action by " + a.get("by") + " " + a.get("at")
                    + ": " + a.get("note")));
        }
        section(lines, 4);
        Map<String, Object> ch = (Map<String, Object>) pack.get("changes");
        for (Map<String, Object> h : list(ch.get("models"))) {
            body(lines, h.get("at") + " model " + h.get("modelId") + " version " + num(h.get("version")) + " by "
                    + h.get("by") + " " + h.get("changedFields") + " " + h.get("recorded"));
        }
        for (Map<String, Object> h : list(ch.get("uses"))) {
            body(lines, h.get("at") + " use " + h.get("useId") + " version " + num(h.get("version")) + " by "
                    + h.get("by") + " " + h.get("changedFields") + " " + h.get("recorded"));
        }
        for (Map<String, Object> l : list(ch.get("live"))) {
            body(lines, l.get("at") + " " + ("ROLLED_BACK".equals(l.get("kind")) ? "rolled back" : "went live")
                    + " use " + l.get("useId") + " to validation " + l.get("toValidationId") + " by " + l.get("by"));
        }
        section(lines, 5);
        Map<String, Object> tp = (Map<String, Object>) pack.get("thirdParty");
        for (Map<String, Object> m : list(tp.get("vendorModels"))) {
            body(lines, "Vendor model: " + m.get("name") + " " + m.get("version") + " " + m.get("digest"));
        }
        for (Map<String, Object> m : list(tp.get("customerModels"))) {
            body(lines, "Customer model: " + m.get("name") + " " + m.get("version") + " " + m.get("digest"));
        }
        body(lines, String.valueOf(tp.get("statement")));
        for (Map<String, Object> r : list(tp.get("independentReviews"))) {
            body(lines, "Independent review: " + r.get("title") + ", " + r.get("reference")
                    + (r.get("sha256") == null ? "" : ", SHA-256 " + r.get("sha256")));
        }
        section(lines, 6);
        body(lines, String.valueOf(pack.get("dataStatement")));
        return write(lines);
    }

    private static void section(List<String[]> lines, int i) {
        lines.add(new String[]{"B", ""});
        lines.add(new String[]{"H", ModelPack.SECTIONS.get(i)});
    }

    private static void body(List<String[]> lines, String text) {
        for (String l : wrap(safe(text))) lines.add(new String[]{"B", l});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    private static String num(Object o) {
        return o instanceof BigDecimal b ? b.toPlainString() : String.valueOf(o);
    }

    private static byte[] write(List<String[]> lines) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Model documentation pack");
            info.setProducer("platform-model-governance");
            doc.setDocumentInformation(info);
            PDPageContentStream cs = null;
            float y = 0;
            try {
                for (String[] line : lines) {
                    float size = line[0].equals("T") ? 16 : line[0].equals("H") ? 12 : SIZE;
                    float lead = line[0].equals("B") ? LEADING : size + 8;
                    if (cs == null || y - lead < MARGIN) {
                        if (cs != null) cs.close();
                        PDPage page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                        y = page.getMediaBox().getHeight() - MARGIN;
                    }
                    y -= lead;
                    if (line[1].isEmpty()) continue;
                    cs.beginText();
                    cs.setFont(line[0].equals("B") ? regular : bold, size);
                    cs.newLineAtOffset(MARGIN, y);
                    cs.showText(line[1]);
                    cs.endText();
                }
            } finally {
                if (cs != null) cs.close();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Only characters the standard font's encoding can show. */
    static String safe(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if ((c >= 0x20 && c < 0x7f) || (c >= 0xa0 && c <= 0xff)) sb.append(c);
            else sb.append('?');
        }
        return sb.toString();
    }

    private static List<String> wrap(String s) {
        List<String> out = new ArrayList<>();
        String rest = s;
        while (rest.length() > WRAP) {
            int cut = rest.lastIndexOf(' ', WRAP);
            if (cut <= 0) cut = WRAP;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        out.add(rest);
        return out;
    }
}
