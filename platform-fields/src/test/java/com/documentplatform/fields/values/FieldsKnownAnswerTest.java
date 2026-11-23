package com.documentplatform.fields.values;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidValue;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Known answers of what platform-fields puts into its sealed and fingerprinted values (P038): the record context that
 * binds a record's items ({@code fld/} + Base64url of SHA-256 of entity, product, record type and record ID, joined
 * with {@code |}; computed with openssl:
 * {@code printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee|PRODUCT_A|case|c1' | openssl dgst -sha256 -binary}), the
 * fingerprint context, and the sealed form of a value (kind letter, display form, U+001E, normalised
 * form; address parts joined with U+001F). A change of any of them makes stored values unreadable, so it fails here.
 * The crypto layer's own formats are pinned by platform-crypto's {@code KnownAnswerTest}.
 */
class FieldsKnownAnswerTest {

    static final FieldScope SCOPE = FieldScope.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "PRODUCT_A");

    @Test
    void theContextsAreFixed() {
        assertEquals("fld/aGBqgE2I4g2BaeeXDoDVzt-vwnfOY7J9CkX7FLHs7x4",
                FieldValues.recordContext(RecordRef.of(SCOPE, "case", "c1")));
        assertEquals("fld:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:PRODUCT_A:borrower_name",
                FieldValues.fingerprintContext(SCOPE, FieldKey.of("Borrower_Name")));
    }

    @Test
    void theSealedFormOfAValueIsFixed() {
        assertEquals("TJane Example\u001ejane example", ValueCodec.sealedForm(new ValidValue(
                FieldValue.text("Jane Example"), FieldValue.text("jane example"))));
        FieldValue.Address shown = new FieldValue.Address("1 Main St", null, "Springfield", "IL", "62701", null);
        FieldValue.Address normal = new FieldValue.Address("1 main st", null, "springfield", "IL", "62701", null);
        String sealed = ValueCodec.sealedForm(new ValidValue(shown, normal));
        assertEquals("A1 Main St\u001f\u001fSpringfield\u001fIL\u001f62701\u001f\u001e"
                + "1 main st\u001f\u001fspringfield\u001fIL\u001f62701\u001f", sealed);
        ValidValue back = ValueCodec.fromSealedForm(sealed);
        assertEquals(shown, back.display());
        assertEquals(normal, back.normalised());
    }
}
