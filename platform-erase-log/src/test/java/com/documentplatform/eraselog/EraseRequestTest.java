package com.documentplatform.eraselog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An entry holds ids only: anything that looks like a value is refused, and the message never repeats it. */
class EraseRequestTest {

    static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final Instant AT = Instant.parse("2026-11-22T10:00:00Z");

    static EraseRequest request(String recordId, ErasedBy by, String auditId) {
        return new EraseRequest(ENTITY, "PRODUCT_A", "business_record", recordId, "case-fields", AT, by, auditId);
    }

    @Test
    void idsAreAccepted() {
        EraseRequest r = request("3f2a9c1e-0000-4000-8000-000000000001", ErasedBy.rule("RET-7Y"), "AUD-000123");
        assertEquals("case-fields", r.kind());
        request("case:2026/11/0042", ErasedBy.person("actor-1234"), "audit:5f0e");
    }

    @Test
    void anEntryWithAValueIsRefused() {
        String[] values = {"Philippa Quillfeather", "742 Evergreen Terrace", "philippa@example.com",
                "pcv1.AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRo", "pci1.AQIDBAUGBwgJ", "pck1.AQIDBAUG",
                "f1.k9dJ3mQ0aZx7Lr2tYw8uVb4nC6eS1hGfPq", "vault:v1:abcdef", "name=Philippa", "Smith, John"};
        int pinned = 0;
        for (String v : values) {
            // erase-log-31 (P040): a value that fits the ID form is refused as a value, not as a malformed ID.
            if (EraseRequest.RECORD_ID.matcher(v).matches()) {
                pinned++;
                assertEquals(EraseLogError.ERASE_ENTRY_VALUE_REFUSED, assertThrows(EraseLogException.class,
                        () -> request(v, ErasedBy.rule("RET-7Y"), "AUD-1")).code(), v);
            }
            if (EraseRequest.ID.matcher(v).matches()) {
                assertEquals(EraseLogError.ERASE_ENTRY_VALUE_REFUSED, assertThrows(EraseLogException.class,
                        () -> request("rec-1", ErasedBy.rule("RET-7Y"), v)).code(), v);
            }
        }
        assertTrue(pinned >= 3, "the e-mail, sealed, fingerprint and ciphertext samples fit the ID form: " + pinned);
        for (String v : values) {
            refused(v, () -> request(v, ErasedBy.rule("RET-7Y"), "AUD-1"));
            refused(v, () -> request("rec-1", ErasedBy.person(v), "AUD-1"));
            refused(v, () -> request("rec-1", ErasedBy.rule("RET-7Y"), v));
        }
        assertEquals(EraseLogError.ERASE_ENTRY_INVALID, assertThrows(EraseLogException.class,
                () -> new EraseRequest(ENTITY, "product a", "business_record", "rec-1", "case-fields", AT,
                        ErasedBy.rule("R"), "A")).code());
        assertEquals(EraseLogError.ERASE_ENTRY_INVALID, assertThrows(EraseLogException.class,
                () -> new EraseRequest(ENTITY, "PRODUCT_A", "business_record", "rec-1", "Document", AT,
                        ErasedBy.rule("R"), "A")).code());
        assertEquals(EraseLogError.ERASE_ENTRY_INVALID, assertThrows(EraseLogException.class,
                () -> new EraseRequest(ENTITY, "PRODUCT_A", "business_record", "rec-1", "document", null,
                        ErasedBy.rule("R"), "A")).code());
    }

    private static void refused(String value, Executable call) {
        EraseLogException e = assertThrows(EraseLogException.class, call, value);
        assertFalse(e.getMessage().contains(value), "the message never repeats it: " + e.getMessage());
        assertFalse(e.getMessage().toLowerCase().contains("philippa"), e.getMessage());
        if (!e.code().equals(EraseLogError.ERASE_ENTRY_INVALID)) {
            assertEquals(EraseLogError.ERASE_ENTRY_VALUE_REFUSED, e.code(), value);
        }
    }
}
