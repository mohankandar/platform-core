package com.documentplatform.licensing;

import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.usage.ExportAnchor;
import com.documentplatform.licensing.usage.ExportCheck;
import com.documentplatform.licensing.usage.UsageExport;
import com.documentplatform.licensing.usage.UsageExportVerifier;
import com.documentplatform.licensing.usage.UsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.documentplatform.licensing.inmemory.TestLicences.PRODUCT;
import static com.documentplatform.licensing.inmemory.TestLicences.SLOT;
import static com.documentplatform.licensing.usage.ExportCheck.Problem.*;
import static org.junit.jupiter.api.Assertions.*;

class UsageExportTest {

    private static final YearMonth M9 = YearMonth.of(2026, 9);
    private static final YearMonth M10 = YearMonth.of(2026, 10);
    private static final YearMonth M11 = YearMonth.of(2026, 11);
    private static final Instant NOW = Instant.parse("2026-12-02T08:00:00Z");

    private final TestLicences licences = new TestLicences();
    private final Licence licence = licences.subscription(LicenceKind.PRODUCTION, LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-31"));
    private final InMemoryUsageLedger ledger = new InMemoryUsageLedger();

    @BeforeEach
    void fill() {
        UsageRecorder rec = new UsageRecorder(licence, ledger, Clock.fixed(NOW, ZoneOffset.UTC));
        for (YearMonth m : List.of(M9, M10, M11)) {
            rec.record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, m, 100, false);
            rec.record(SLOT, PRODUCT, AllowanceType.PAGES_PER_MONTH, m, 25, true);
            rec.record(SLOT, PRODUCT, AllowanceType.NAMED_SEATS, m, 8, false);
        }
    }

    private UsageExport export(YearMonth from, YearMonth to, ExportAnchor after) {
        return UsageExport.create(licence, ledger, from, to, after, NOW);
    }

    private List<String> threeMonthlyFiles() {
        UsageExport f1 = export(M9, M9, ExportAnchor.first());
        UsageExport f2 = export(M10, M10, f1.nextAnchor());
        UsageExport f3 = export(M11, M11, f2.nextAnchor());
        return List.of(f1.text(), f2.text(), f3.text());
    }

    @Test
    void aFileVerifiesOnItsOwnAndHoldsOnlyUsageFields() {
        UsageExport f = export(M9, M11, ExportAnchor.first());
        ExportCheck c = UsageExportVerifier.verify(f.text(), ExportAnchor.first());
        assertTrue(c.ok(), c.message());
        assertEquals(f.nextAnchor(), c.nextAnchor());
        Map<String, Object> file = Json.parseObject(f.text());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) file.get("records");
        assertEquals(9, records.size());
        for (Map<String, Object> r : records) {
            assertEquals(Set.of("ledgerSequence", "slotId", "product", "type", "period", "amount", "unit",
                    "billable", "recordedAt", "chain"), r.keySet());
        }
        String lower = f.text().toLowerCase();
        for (String word : List.of("price", "cost", "currency", "eur", "usd", "gbp", "document", "content")) {
            assertFalse(lower.contains("\"" + word), word);
        }
    }

    @Test
    void aRunOfMonthlyFilesIsContinuous() {
        ExportCheck c = UsageExportVerifier.verifySequence(threeMonthlyFiles(), ExportAnchor.first());
        assertTrue(c.ok(), c.message());
        assertEquals(3, c.nextAnchor().sequence());
        assertEquals(M11, c.nextAnchor().periodTo());
    }

    @Test
    void aMissingFileIsDetected() {
        List<String> files = threeMonthlyFiles();
        ExportCheck c = UsageExportVerifier.verifySequence(List.of(files.get(0), files.get(2)), ExportAnchor.first());
        assertEquals(EXPORT_START_MISMATCH, c.problem());
        assertEquals(1, c.fileIndex());
        assertTrue(c.message().startsWith("File 2: EXPORT_START_MISMATCH"), c.message());
    }

    @Test
    void theFirstFileMustStartFromGenesis() {
        List<String> files = threeMonthlyFiles();
        assertEquals(EXPORT_START_MISMATCH, UsageExportVerifier.verifySequence(files.subList(1, 3), ExportAnchor.first()).problem());
    }

    @Test
    void aGapInMonthsIsDetected() {
        UsageExport f1 = export(M9, M9, ExportAnchor.first());
        UsageExport f3 = export(M11, M11, f1.nextAnchor()); // October skipped
        ExportCheck c = UsageExportVerifier.verifySequence(List.of(f1.text(), f3.text()), ExportAnchor.first());
        assertEquals(EXPORT_PERIOD_GAP, c.problem());
        assertTrue(c.message().contains("the next month is 2026-10"), c.message());
    }

    @Test
    void aSequenceGapIsDetected() {
        UsageExport f1 = export(M9, M9, ExportAnchor.first());
        ExportAnchor skipped = new ExportAnchor(f1.nextAnchor().sequence() + 1, f1.nextAnchor().finalChainValue(), M9);
        UsageExport f2 = export(M10, M10, skipped);
        ExportCheck c = UsageExportVerifier.verifySequence(List.of(f1.text(), f2.text()), ExportAnchor.first());
        assertEquals(EXPORT_SEQUENCE_GAP, c.problem());
    }

    @Test
    void anEditedRecordIsDetectedAndLocated() {
        String text = export(M9, M11, ExportAnchor.first()).text();
        String edited = text.replaceFirst("\"amount\": 100", "\"amount\": 1");
        assertNotEquals(text, edited);
        ExportCheck c = UsageExportVerifier.verify(edited, null);
        assertEquals(EXPORT_RECORD_EDITED, c.problem());
        assertEquals(0, c.recordIndex());
        String flipped = text.replaceFirst("\"billable\": true", "\"billable\": false");
        assertEquals(EXPORT_RECORD_EDITED, UsageExportVerifier.verify(flipped, null).problem());
    }

    @Test
    void anEditedHeaderIsDetected() {
        String text = export(M9, M11, ExportAnchor.first()).text();
        String edited = text.replace("\"periodTo\": \"2026-11\"", "\"periodTo\": \"2026-12\"");
        assertEquals(EXPORT_HEADER_EDITED, UsageExportVerifier.verify(edited, null).problem());
    }

    @Test
    void aRemovedRecordIsDetected() {
        UsageExport f = export(M9, M9, ExportAnchor.first());
        @SuppressWarnings("unchecked")
        Map<String, Object> file = new java.util.LinkedHashMap<>(Json.parseObject(f.text()));
        @SuppressWarnings("unchecked")
        List<Object> records = new java.util.ArrayList<>((List<Object>) file.get("records"));
        records.remove(records.size() - 1); // the last one: no later record to break
        file.put("records", records);
        assertEquals(EXPORT_FOOTER_MISMATCH, UsageExportVerifier.verify(Json.pretty(file), null).problem());
        records.remove(0);
        file.put("records", records);
        ExportCheck c = UsageExportVerifier.verify(Json.pretty(file), null);
        assertEquals(EXPORT_RECORD_EDITED, c.problem());
        assertEquals(0, c.recordIndex());
    }

    @Test
    void aFileOfAnotherTenantIsDetected() {
        UsageExport f1 = export(M9, M9, ExportAnchor.first());
        Licence other = new Licence("LIC-OTHER-1", 1, "Other", "TEN-OTHER-1", List.of(PRODUCT),
                licence.entitySlots(), LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, licence.startsOn(),
                licence.expiresOn(), null, Deployment.HOSTED, licences.keyId(), licence.issuedAt());
        UsageExport f2 = UsageExport.create(other, ledger, M10, M10, f1.nextAnchor(), NOW);
        assertEquals(EXPORT_TENANT_MISMATCH,
                UsageExportVerifier.verifySequence(List.of(f1.text(), f2.text()), ExportAnchor.first()).problem());
    }

    @Test
    void aBrokenLedgerIsStatedInTheExportNotHidden() {
        ledger.overwriteForTest(2, ledger.records().get(1).withAmount(0));
        UsageExport f = export(M9, M11, ExportAnchor.first());
        assertTrue(UsageExportVerifier.verify(f.text(), null).ok(), "the file itself is intact");
        @SuppressWarnings("unchecked")
        Map<String, Object> chain = (Map<String, Object>) ((Map<String, Object>) Json.parseObject(f.text()).get("header")).get("ledgerChain");
        assertEquals(false, chain.get("intact"));
        assertEquals("USAGE_CHAIN_RECORD_EDITED", chain.get("problem"));
        assertEquals(2L, chain.get("brokenAtSequence"));
    }

    @Test
    void unreadableAndUnsupportedFiles() {
        assertEquals(EXPORT_MALFORMED, UsageExportVerifier.verify("{", null).problem());
        assertEquals(EXPORT_MALFORMED, UsageExportVerifier.verify("{\"format\":\"platform-usage-export/1\"}", null).problem());
        String text = export(M9, M9, ExportAnchor.first()).text();
        assertEquals(EXPORT_UNSUPPORTED_FORMAT,
                UsageExportVerifier.verify(text.replace("platform-usage-export/1", "platform-usage-export/9"), null).problem());
    }

    /** licensing-file-format-62: a record outside the header's months, re-chained so that nothing else is wrong. */
    @Test
    void aRecordOutsideTheHeadersPeriodIsDetected() {
        String text = export(M9, M10, ExportAnchor.first()).text();
        assertTrue(UsageExportVerifier.verify(rechained(text, -1, null), null).ok(), "re-chaining alone changes nothing");
        // Record 4 (index 3) is the first October one; moved to November, outside 2026-09..2026-10.
        String outside = rechained(text, 3, "2026-11");
        ExportCheck c = UsageExportVerifier.verify(outside, null);
        assertEquals(EXPORT_RECORD_OUTSIDE_PERIOD, c.problem(), c.message());
        assertEquals(3, c.recordIndex());
        assertTrue(c.message().contains("2026-11") && c.message().contains("2026-09..2026-10"), c.message());
        assertEquals(EXPORT_RECORD_OUTSIDE_PERIOD, UsageExportVerifier.verify(rechained(text, 0, "2026-08"), null)
                .problem(), "before the period too");
    }

    /** The file with record {@code index}'s period set to {@code period}, every chain value after it recomputed. */
    @SuppressWarnings("unchecked")
    private static String rechained(String text, int index, String period) {
        Map<String, Object> file = new java.util.LinkedHashMap<>(Json.parseObject(text));
        Map<String, Object> footer = new java.util.LinkedHashMap<>((Map<String, Object>) file.get("footer"));
        String chain = (String) footer.get("headerChainValue");
        List<Object> records = new java.util.ArrayList<>();
        List<Object> original = (List<Object>) file.get("records");
        for (int i = 0; i < original.size(); i++) {
            Map<String, Object> r = new java.util.LinkedHashMap<>((Map<String, Object>) original.get(i));
            r.remove("chain");
            if (i == index) {
                r.put("period", period);
            }
            chain = Ed25519.sha256Hex(chain + "\n" + Json.canonical(r));
            r.put("chain", chain);
            records.add(r);
        }
        footer.put("finalChainValue", chain);
        file.put("records", records);
        file.put("footer", footer);
        return Json.pretty(file);
    }

    @Test
    void anEmptyPeriodStillExportsAndChains() {
        UsageExport f1 = export(YearMonth.of(2026, 1), YearMonth.of(2026, 8), ExportAnchor.first());
        UsageExport f2 = export(M9, M9, f1.nextAnchor());
        assertTrue(UsageExportVerifier.verifySequence(List.of(f1.text(), f2.text()), ExportAnchor.first()).ok());
    }
}
