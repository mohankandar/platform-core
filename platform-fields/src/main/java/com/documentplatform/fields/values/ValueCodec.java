package com.documentplatform.fields.values;

import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidValue;

import java.util.ArrayList;
import java.util.List;

/**
 * The text forms of a value: as stored (an address: its six parts separated by U+001F, a missing part empty), as
 * sealed (kind, display and normalised in one text), and as compared by search and the primary rule (lower case,
 * single spaces; an address: its parts with spaces).
 */
final class ValueCodec {

    static final char PART = '\u001f';
    private static final char FORMS = '\u001e';

    private ValueCodec() {
    }

    static String encode(FieldValue v) {
        if (v instanceof FieldValue.Text t) {
            return t.value();
        }
        FieldValue.Address a = (FieldValue.Address) v;
        StringBuilder b = new StringBuilder();
        String[] parts = {a.street(), a.street2(), a.city(), a.state(), a.zip(), a.country()};
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                b.append(PART);
            }
            b.append(parts[i] == null ? "" : parts[i]);
        }
        return b.toString();
    }

    static FieldValue decode(String text, boolean address) {
        if (!address) {
            return FieldValue.text(text);
        }
        String[] p = text.split(String.valueOf(PART), -1);
        if (p.length != 6) {
            throw new IllegalStateException("A stored address does not have six parts");
        }
        return new FieldValue.Address(blank(p[0]), blank(p[1]), blank(p[2]), blank(p[3]), blank(p[4]), blank(p[5]));
    }

    /** What search and the primary rule compare: the normalised value, lower case, single spaces. */
    static String search(ValidValue v) {
        FieldValue n = v.normalised();
        if (n instanceof FieldValue.Text t) {
            return Fingerprinter.canonical(t.value());
        }
        FieldValue.Address a = (FieldValue.Address) n;
        List<String> parts = new ArrayList<>();
        for (String p : new String[]{a.street(), a.street2(), a.city(), a.state(), a.zip(), a.country()}) {
            if (p != null && !p.isBlank()) {
                parts.add(p);
            }
        }
        return Fingerprinter.canonical(String.join(" ", parts));
    }

    /** The sealed payload: kind, display and normalised. */
    static String sealedForm(ValidValue v) {
        boolean address = v.display() instanceof FieldValue.Address;
        return (address ? "A" : "T") + encode(v.display()) + FORMS + encode(v.normalised());
    }

    static ValidValue fromSealedForm(String s) {
        if (s.isEmpty() || (s.charAt(0) != 'A' && s.charAt(0) != 'T')) {
            throw new IllegalStateException("A sealed value of an unknown kind");
        }
        boolean address = s.charAt(0) == 'A';
        int at = s.indexOf(FORMS);
        if (at < 0) {
            throw new IllegalStateException("A sealed value without its normalised form");
        }
        return new ValidValue(decode(s.substring(1, at), address), decode(s.substring(at + 1), address));
    }

    private static String blank(String s) {
        return s.isEmpty() ? null : s;
    }
}
