package com.documentplatform.modelgov;

import com.documentplatform.errors.SafeText;

import java.util.Map;
import java.util.regex.Pattern;

/** The value rules of the register: lengths, names, and no secret-looking text. Package-private. */
final class Values {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private Values() {
    }

    static String text(String field, String v, int max, boolean required) {
        if (v == null || v.isBlank()) {
            if (required) throw invalid(field, "required");
            return null;
        }
        if (v.length() > max) throw invalid(field, "at most " + max + " characters");
        if (v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) throw invalid(field, "no control characters");
        if (!SafeText.scrub(v).equals(v)) throw invalid(field, "it looks like a secret or token");
        return v;
    }

    static String name(String field, String v) {
        if (v == null || !NAME.matcher(v).matches()) throw invalid(field, "a name [a-z][a-z0-9_.-]{0,63}");
        return v;
    }

    static String product(String v) {
        if (v == null || !PRODUCT.matcher(v).matches()) throw invalid("product", "a product code [A-Z][A-Z0-9_]{1,31}");
        return v;
    }

    static ModelGovException invalid(String field, String rule) {
        return new ModelGovException(ModelGovCode.MOD_VALUE_INVALID, Map.of("field", field, "rule", rule));
    }
}
