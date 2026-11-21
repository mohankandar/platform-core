package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.documentplatform.licensing.usage.ExportCheck.Problem.*;

/** Checks usage export files ({@link UsageExport}): each on its own, and a run of them for continuity. */
public final class UsageExportVerifier {

    private static final Set<String> FILE = Set.of("format", "header", "records", "footer");
    private static final Set<String> HEADER = Set.of("tenantId", "licenceId", "sequence", "periodFrom", "periodTo",
            "startChainValue", "exportedAt", "ledgerChain");
    private static final Set<String> RECORD = Set.of("ledgerSequence", "slotId", "product", "type", "period",
            "amount", "unit", "billable", "recordedAt", "chain");
    private static final Set<String> FOOTER = Set.of("headerChainValue", "recordCount", "finalChainValue");

    private UsageExportVerifier() {
    }

    /**
     * Checks a run of files in the order they were exported. The first must continue from {@code start}
     * ({@link ExportAnchor#first()} for the very first file ever); each next one from the file before it.
     */
    public static ExportCheck verifySequence(List<String> files, ExportAnchor start) {
        ExportAnchor anchor = start;
        String tenant = null;
        for (int i = 0; i < files.size(); i++) {
            ExportCheck check = verify(files.get(i), anchor);
            if (!check.ok()) {
                return check.inFile(i);
            }
            String fileTenant = tenantOf(files.get(i));
            if (tenant != null && !tenant.equals(fileTenant)) {
                return ExportCheck.fail(EXPORT_TENANT_MISMATCH, -1,
                        "Tenant " + fileTenant + " follows files of tenant " + tenant + ".").inFile(i);
            }
            tenant = fileTenant;
            anchor = check.nextAnchor();
        }
        return ExportCheck.ok(anchor, files.size() + " export file(s) are intact and continuous.");
    }

    /**
     * Checks one file. With {@code previous}, also checks it continues from that anchor; with null, checks only that
     * the file is intact in itself.
     */
    public static ExportCheck verify(String text, ExportAnchor previous) {
        try {
            return check(text, previous);
        } catch (JsonException | IllegalArgumentException e) {
            return ExportCheck.fail(EXPORT_MALFORMED, -1, "The file is not a readable usage export (" + e.getMessage() + ").");
        }
    }

    private static ExportCheck check(String text, ExportAnchor previous) {
        Fields file = Fields.of(Json.parse(text), "export");
        if (!UsageExport.FORMAT.equals(file.string("format"))) {
            return ExportCheck.fail(EXPORT_UNSUPPORTED_FORMAT, -1, "This version reads " + UsageExport.FORMAT + " only.");
        }
        file.only(FILE);
        Fields header = file.object("header").only(HEADER);
        Fields footer = file.object("footer").only(FOOTER);
        long sequence = header.integer("sequence");
        YearMonth from = month(header.string("periodFrom"));
        YearMonth to = month(header.string("periodTo"));
        String start = header.string("startChainValue");
        header.string("tenantId");
        header.string("exportedAt");
        header.object("ledgerChain");

        String chain = UsageExport.link(start, header.map());
        if (!chain.equals(footer.string("headerChainValue"))) {
            return ExportCheck.fail(EXPORT_HEADER_EDITED, -1, "The header no longer matches its chain value.");
        }
        List<Object> records = file.list("records");
        for (int i = 0; i < records.size(); i++) {
            Fields r = Fields.of(records.get(i), "records[" + i + "]").only(RECORD);
            Map<String, Object> line = new LinkedHashMap<>(r.map());
            String recorded = (String) line.remove("chain");
            if (recorded == null) {
                throw new JsonException("records[" + i + "].chain is missing");
            }
            chain = UsageExport.link(chain, line);
            if (!chain.equals(recorded)) {
                return ExportCheck.fail(EXPORT_RECORD_EDITED, i, "Record " + (i + 1)
                        + " (ledger record " + r.integer("ledgerSequence") + ") was changed, or a record before it"
                        + " was removed or inserted.");
            }
            YearMonth period = month(r.string("period"));
            if (period.isBefore(from) || period.isAfter(to)) {
                return ExportCheck.fail(EXPORT_RECORD_OUTSIDE_PERIOD, i, "Record " + (i + 1) + " is for " + period
                        + ", outside " + from + ".." + to + ".");
            }
        }
        if (footer.integer("recordCount") != records.size() || !chain.equals(footer.string("finalChainValue"))) {
            return ExportCheck.fail(EXPORT_FOOTER_MISMATCH, -1, "The footer says " + footer.integer("recordCount")
                    + " record(s) and a final value that the " + records.size() + " record(s) present do not give.");
        }
        if (previous != null) {
            if (!start.equals(previous.finalChainValue())) {
                return ExportCheck.fail(EXPORT_START_MISMATCH, -1, "The file does not start from the final chain value"
                        + " of export " + previous.sequence() + ": a file is missing or was replaced.");
            }
            if (sequence != previous.sequence() + 1) {
                return ExportCheck.fail(EXPORT_SEQUENCE_GAP, -1, "Export " + sequence + " follows export "
                        + previous.sequence() + ".");
            }
            if (previous.periodTo() != null && !from.equals(previous.periodTo().plusMonths(1))) {
                return ExportCheck.fail(EXPORT_PERIOD_GAP, -1, "The file starts at " + from + "; the previous file"
                        + " ended at " + previous.periodTo() + ", so the next month is " + previous.periodTo().plusMonths(1) + ".");
            }
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("periodTo is before periodFrom");
        }
        return ExportCheck.ok(new ExportAnchor(sequence, chain, to), "The export file is intact (" + records.size()
                + " record(s), " + from + ".." + to + ").");
    }

    private static String tenantOf(String text) {
        return Fields.of(Json.parse(text), "export").object("header").string("tenantId");
    }

    private static YearMonth month(String text) {
        try {
            YearMonth m = YearMonth.parse(text);
            if (!m.toString().equals(text)) {
                throw new IllegalArgumentException("a month must be written YYYY-MM");
            }
            return m;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("a month must be written YYYY-MM");
        }
    }
}
