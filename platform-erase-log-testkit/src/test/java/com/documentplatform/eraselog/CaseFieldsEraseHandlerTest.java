package com.documentplatform.eraselog;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.InMemoryValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.LegalHolds;
import com.documentplatform.fields.values.RecordRef;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The built-in case-fields handler on platform-fields: legal holds refuse before logging; verify knows a new record. */
class CaseFieldsEraseHandlerTest {

    static final FieldScope SCOPE = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            "PRODUCT_A");
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    static final Instant ERASED_AT = Instant.parse("2026-11-10T10:00:00Z");

    final InMemoryFieldStore fieldStore = new InMemoryFieldStore();
    final InMemoryValueStore valueStore = new InMemoryValueStore();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    final FieldValues values;

    CaseFieldsEraseHandlerTest() {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(CUSTOMER);
        new FieldDefinitions(fieldStore, valueStore, clock).create(SCOPE, FieldSpec.builder("loan_number",
                "Loan number", FieldType.TEXT).primary(true).sensitive(false).build(), "u");
        values = new FieldValues(CUSTOMER, fieldStore, valueStore, keys, clock);
    }

    void claim(String id) {
        values.claim(RecordRef.of(SCOPE, "business_record", id), Map.of("loan_number",
                List.of(FieldValue.text("LN-" + id))));
    }

    static EraseRequest request(String id) {
        return new EraseRequest(SCOPE.entity(), "PRODUCT_A", "business_record", id, "case-fields", ERASED_AT,
                ErasedBy.rule("RET-7Y"), "AUD-" + id);
    }

    @Test
    void aRecordUnderLegalHoldIsRefusedBeforeAnythingIsLogged() {
        claim("c1");
        LegalHolds holds = r -> r.recordId().equals("c1");
        com.documentplatform.eraselog.testkit.InMemoryEraseLogStore store =
                new com.documentplatform.eraselog.testkit.InMemoryEraseLogStore();
        EraseLog log = new EraseLog(store, clock);
        Eraser eraser = new Eraser(log, new com.documentplatform.eraselog.testkit.InMemoryErasePosition(),
                EraseHandlers.of(new CaseFieldsEraseHandler(values, valueStore, holds)));
        EraseLogException held = assertThrows(EraseLogException.class, () -> eraser.erase(request("c1")));
        assertEquals(EraseLogError.ERASE_HANDLER_FAILED, held.code());
        assertEquals("VALUES_LEGAL_HOLD", held.handlerCode().orElseThrow());
        assertEquals(0, log.head().seq(), "nothing logged");

        Eraser unsure = new Eraser(log, new com.documentplatform.eraselog.testkit.InMemoryErasePosition(),
                EraseHandlers.of(new CaseFieldsEraseHandler(values, valueStore, r -> {
                    throw new IllegalStateException("holds service down");
                })));
        assertEquals("VALUES_LEGAL_HOLD_UNKNOWN", assertThrows(EraseLogException.class,
                () -> unsure.erase(request("c1"))).handlerCode().orElseThrow());
        assertEquals(0, log.head().seq());
    }

    @Test
    void verifySeesTheOldRecordAsReadableAndANewOneUnderTheSameIdAsGone() {
        CaseFieldsEraseHandler handler = new CaseFieldsEraseHandler(values, valueStore);
        EraseEntry entry = EraseEntry.link(UUID.randomUUID(), ChainPoint.START, ERASED_AT, request("c1"));
        EraseHandler.Context none = Optional::<Connection>empty;
        claim("c1");
        assertEquals(EraseHandler.Verified.READABLE, handler.verify(entry), "made before the erasure: still there");
        assertEquals(EraseHandler.Applied.ERASED, handler.apply(entry, none));
        assertEquals(EraseHandler.Verified.GONE, handler.verify(entry));
        assertEquals(EraseHandler.Applied.ALREADY_GONE, handler.apply(entry, none), "safe to run twice");
        now.set(ERASED_AT.plusSeconds(3600));
        claim("c1");
        assertEquals(EraseHandler.Verified.GONE, handler.verify(entry), "a new record made after the erasure");
    }
}
