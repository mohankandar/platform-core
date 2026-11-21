package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.json.Json;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes a usage export file ({@value #FORMAT}) for any range of months: tamper-evident, no document data, no prices.
 * Documented in {@code docs/design/licensing-file-format.md}.
 *
 * <pre>
 * { "format": "platform-usage-export/1",
 *   "header":  { tenantId, licenceId, sequence, periodFrom, periodTo, startChainValue, exportedAt, ledgerChain },
 *   "records": [ { ledgerSequence, slotId, product, type, period, amount, unit, billable, recordedAt, chain }, ... ],
 *   "footer":  { headerChainValue, recordCount, finalChainValue } }
 * </pre>
 *
 * Chain: {@code c0 = startChainValue} (the previous file's {@code finalChainValue}, or 64 zeros for the first file);
 * {@code headerChainValue = SHA-256(c0 + "\n" + canonical(header))}; for each record
 * {@code chain = SHA-256(previous + "\n" + canonical(record without "chain"))}; {@code finalChainValue} is the last
 * value. Hex, lower case; strings hashed as UTF-8. Because each file starts from the previous file's final value and
 * carries the next sequence number and the next month, a missing file, a gap in months or an edited record is found
 * by {@link UsageExportVerifier}.
 */
public final class UsageExport {

    public static final String FORMAT = "platform-usage-export/1";

    private final String text;
    private final ExportAnchor nextAnchor;

    private UsageExport(String text, ExportAnchor nextAnchor) {
        this.text = text;
        this.nextAnchor = nextAnchor;
    }

    /** The file text (UTF-8 when written). */
    public String text() {
        return text;
    }

    /** What the next export continues from; keep it. */
    public ExportAnchor nextAnchor() {
        return nextAnchor;
    }

    /**
     * Exports the ledger records whose period is in {@code from}..{@code to}, in ledger order. The ledger's own chain
     * is checked and its result written into the header, so a broken ledger is visible to the receiver; the export is
     * never refused.
     *
     * @param after the anchor of the previous export ({@link ExportAnchor#first()} for the first one); {@code from}
     *              should be the month after {@code after.periodTo()}, otherwise the receiver sees a gap or overlap
     */
    public static UsageExport create(Licence licence, UsageLedger ledger, YearMonth from, YearMonth to,
                                     ExportAnchor after, Instant exportedAt) {
        Objects.requireNonNull(licence, "licence");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(exportedAt, "exportedAt");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("periodTo is before periodFrom");
        }
        UsageLedger.Snapshot snapshot = ledger.snapshot();
        List<UsageRecord> all = snapshot.records();
        ChainCheck ledgerCheck = UsageChain.verify(all, snapshot.head());

        Map<String, Object> ledgerChain = new LinkedHashMap<>();
        ledgerChain.put("intact", ledgerCheck.intact());
        ledgerChain.put("recordCount", (long) all.size());
        if (!ledgerCheck.intact()) {
            ledgerChain.put("problem", ledgerCheck.problem().name());
            ledgerChain.put("brokenAtSequence", ledgerCheck.brokenAtSequence());
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("tenantId", licence.tenantId());
        header.put("licenceId", licence.licenceId());
        header.put("sequence", after.sequence() + 1);
        header.put("periodFrom", from.toString());
        header.put("periodTo", to.toString());
        header.put("startChainValue", after.finalChainValue());
        header.put("exportedAt", exportedAt.toString());
        header.put("ledgerChain", ledgerChain);

        String chain = link(after.finalChainValue(), header);
        String headerChain = chain;
        List<Object> records = new ArrayList<>();
        for (UsageRecord r : all) {
            if (r.period().isBefore(from) || r.period().isAfter(to)) {
                continue;
            }
            Map<String, Object> line = recordLine(r);
            chain = link(chain, line);
            line.put("chain", chain);
            records.add(line);
        }
        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("headerChainValue", headerChain);
        footer.put("recordCount", (long) records.size());
        footer.put("finalChainValue", chain);

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        file.put("header", header);
        file.put("records", records);
        file.put("footer", footer);
        return new UsageExport(Json.pretty(file), new ExportAnchor(after.sequence() + 1, chain, to));
    }

    static Map<String, Object> recordLine(UsageRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ledgerSequence", r.sequence());
        m.put("slotId", r.slotId());
        m.put("product", r.product());
        m.put("type", r.type().name());
        m.put("period", r.period().toString());
        m.put("amount", r.amount());
        m.put("unit", r.unit());
        m.put("billable", r.billable());
        m.put("recordedAt", r.recordedAt().toString());
        return m;
    }

    static String link(String previous, Object value) {
        return Ed25519.sha256Hex(previous + "\n" + Json.canonical(value));
    }
}
