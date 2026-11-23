package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.usage.ChainHead;
import com.documentplatform.licensing.usage.ExportAnchor;
import com.documentplatform.licensing.usage.UsageChain;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageExport;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Known answers of the signed and chained licensing formats (P038, licensing-file-format-27), against
 * {@code docs/design/licensing-file-format.md}: the document's sample licence verifies with its published key; the
 * signed message is {@code "platform-licence/1" LF canonical(payload)}, its SHA-256 the document's
 * {@code payloadSha256}; a ledger record's hash and the export's chain values are SHA-256 of the canonical forms the
 * document gives. The canonical texts below are written by hand from section 1 (keys sorted, no white space) and the
 * hashes were computed outside Java with {@code openssl dgst -sha256}, so the issuing side (any language) can produce
 * the same bytes.
 */
class FormatsKnownAnswerTest {

    /** Section 2's sample, as printed in the document. */
    static final String SAMPLE = """
            {
              "format": "platform-licence/1",
              "licence": {
                "customer": "Example Customer Ltd",
                "deployment": "ON_PREM",
                "entitySlots": [
                  {
                    "allowances": [
                      { "amount": 10000, "product": "PRODUCT_A", "type": "PAGES_PER_MONTH" },
                      { "amount": 25, "product": "PRODUCT_A", "type": "NAMED_SEATS" }
                    ],
                    "slotId": "ENT-9NKK-T9AZ-47YX-Y175"
                  },
                  {
                    "allowances": [ { "amount": 500, "product": "PRODUCT_A", "type": "STORAGE" } ],
                    "slotId": "ENT-EJVF-2055-5QZP-1RCP"
                  }
                ],
                "expiresOn": "2027-11-30",
                "issuedAt": "2026-11-21T10:00:00Z",
                "keyId": "test-sample-01",
                "kind": "PRODUCTION",
                "licenceId": "LIC-YN19-Q159-CW3X-GHAS",
                "products": [ "PRODUCT_A" ],
                "startsOn": "2026-12-01",
                "tenantId": "TEN-G0D2-1PAP-WYDC-KYT1",
                "type": "SUBSCRIPTION",
                "version": 1
              },
              "signature": {
                "algorithm": "Ed25519",
                "payloadSha256": "50286018358d22f235fd52a9b558caa11430e0535eea10bb33b793c25c78f3ea",
                "value": "X2f+KznDwQfmd0HKkqEJsciF/zgfht5/fNpyUQd/KTr9uJk1D/B73KewBWDm/+0h1ySKkdK5f6gpvU/G66/rAQ=="
              }
            }
            """;

    /** Its public key, as the document's trusted-keys file. */
    static final String KEYS = """
            { "format": "platform-licence-keys/1",
              "keys": [ { "algorithm": "Ed25519", "keyId": "test-sample-01",
                          "publicKey": "MCowBQYDK2VwAyEAGAK4ENFFDSCloKEjiehEskki1ND+E9kBdPxlPov5/aQ=" } ] }
            """;

    /** canonical(payload), by hand. */
    static final String CANONICAL_PAYLOAD = "{\"customer\":\"Example Customer Ltd\",\"deployment\":\"ON_PREM\","
            + "\"entitySlots\":[{\"allowances\":[{\"amount\":10000,\"product\":\"PRODUCT_A\",\"type\":\"PAGES_PER_MONTH\"},"
            + "{\"amount\":25,\"product\":\"PRODUCT_A\",\"type\":\"NAMED_SEATS\"}],\"slotId\":\"ENT-9NKK-T9AZ-47YX-Y175\"},"
            + "{\"allowances\":[{\"amount\":500,\"product\":\"PRODUCT_A\",\"type\":\"STORAGE\"}],"
            + "\"slotId\":\"ENT-EJVF-2055-5QZP-1RCP\"}],\"expiresOn\":\"2027-11-30\",\"issuedAt\":\"2026-11-21T10:00:00Z\","
            + "\"keyId\":\"test-sample-01\",\"kind\":\"PRODUCTION\",\"licenceId\":\"LIC-YN19-Q159-CW3X-GHAS\","
            + "\"products\":[\"PRODUCT_A\"],\"startsOn\":\"2026-12-01\",\"tenantId\":\"TEN-G0D2-1PAP-WYDC-KYT1\","
            + "\"type\":\"SUBSCRIPTION\",\"version\":1}";
    /** openssl: SHA-256 of "platform-licence/1" LF CANONICAL_PAYLOAD; the document's payloadSha256. */
    static final String PAYLOAD_SHA256 = "50286018358d22f235fd52a9b558caa11430e0535eea10bb33b793c25c78f3ea";
    /** openssl: SHA-256 of the canonical first ledger record (900 pages, 2026-11). */
    static final String RECORD_HASH = "2540c8ea97ff273dc85dcf1eb9c7ec13f663ab747f0ecf6e10d5c1a324b586fd";
    /** openssl: SHA-256(64 zeros LF canonical(header)) of the first export of that record. */
    static final String HEADER_CHAIN = "8a3ff1deba64ac6a94bff578599c763a878b7578c625e001daeff8701570361a";
    /** openssl: SHA-256(HEADER_CHAIN LF canonical(record without chain)). */
    static final String RECORD_CHAIN = "2326c1b8c53317033dda238ebc553f21a5d270f0cc80506bbf87656743bd4957";

    @Test
    void theDocumentsSampleLicenceVerifiesAndItsSignedMessageIsFixed() {
        Licence l = new LicenceVerifier(TrustedKeys.parse(KEYS)).verify(SAMPLE, "TEN-G0D2-1PAP-WYDC-KYT1");
        assertEquals("LIC-YN19-Q159-CW3X-GHAS", l.licenceId());
        assertEquals("test-sample-01", l.keyId());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) Json.parseObject(SAMPLE).get("licence");
        assertEquals(CANONICAL_PAYLOAD, Json.canonical(payload), "the code's canonical form is the document's");
        assertEquals(PAYLOAD_SHA256, Ed25519.sha256Hex("platform-licence/1\n" + CANONICAL_PAYLOAD));
        // The signature covers the canonical payload, not the file's white space or key order.
        LicenceException e = assertThrows(LicenceException.class, () -> new LicenceVerifier(TrustedKeys.parse(KEYS))
                .verify(SAMPLE.replace("\"amount\": 25,", "\"amount\": 26,"), "TEN-G0D2-1PAP-WYDC-KYT1"));
        assertEquals(LicenceRefusal.LICENCE_TAMPERED, e.code());
    }

    @Test
    void aLedgerRecordsHashAndTheExportChainAreFixed() {
        UsageRecord r = UsageChain.link((ChainHead) null, new UsageEntry("ENT-9NKK-T9AZ-47YX-Y175", "PRODUCT_A",
                AllowanceType.PAGES_PER_MONTH, YearMonth.of(2026, 11), 900, true, Instant.parse("2026-11-21T10:00:00Z")));
        assertEquals(UsageChain.GENESIS, r.previousHash());
        assertEquals(RECORD_HASH, r.hash(), "the record hash is the document's (computed with openssl)");

        Licence l = new LicenceVerifier(TrustedKeys.parse(KEYS)).verify(SAMPLE, "TEN-G0D2-1PAP-WYDC-KYT1");
        UsageExport export = UsageExport.create(l, new OneRecordLedger(r), YearMonth.of(2026, 11), YearMonth.of(2026, 11),
                ExportAnchor.first(), Instant.parse("2026-12-01T08:00:00Z"));
        @SuppressWarnings("unchecked")
        Map<String, Object> footer = (Map<String, Object>) Json.parseObject(export.text()).get("footer");
        assertEquals(HEADER_CHAIN, footer.get("headerChainValue"), "the header chain value is the document's");
        assertEquals(RECORD_CHAIN, footer.get("finalChainValue"), "the record chain value is the document's");
    }

    /** A ledger holding one record. */
    record OneRecordLedger(UsageRecord record) implements UsageLedger {
        @Override public UsageRecord append(UsageEntry entry) { throw new UnsupportedOperationException(); }
        @Override public List<UsageRecord> records() { return new ArrayList<>(List.of(record)); }
        @Override public Optional<ChainHead> head() { return Optional.of(new ChainHead(record.sequence(), record.hash())); }
        @Override public Snapshot snapshot() { return new Snapshot(records(), head()); }
    }
}
