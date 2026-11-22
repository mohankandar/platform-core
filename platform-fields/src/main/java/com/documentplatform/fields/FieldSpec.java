package com.documentplatform.fields;

import com.documentplatform.fields.FieldDefinitionException.Code;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a case field is: the shared model both products use (key, label, type, rules, flags, display order, active).
 * App-only settings are not part of it; they attach to the field by key ({@link FieldStore#extensions}).
 *
 * <p>Every rule is checked when the spec is made; a spec that exists is valid. Make one with {@link #builder}.
 *
 * @param key          stable key, never changes
 * @param label        what people see, 1–200 characters; may change at any time
 * @param type         the kind of value; never changes once values exist
 * @param currency     MONEY only: the field's one ISO 4217 currency, for example {@code USD}
 * @param options      DROPDOWN only: 1–100 values to choose from, in the order shown
 * @param required     a value must be given
 * @param exactLength  TEXT, NUMBER, EMAIL, CUSTOM_PATTERN: exact number of characters of the normalised value
 * @param minLength    the same, at least
 * @param maxLength    the same, at most (values are never longer than 512 characters anyway)
 * @param pattern      a regular expression the whole normalised value must match; required for CUSTOM_PATTERN,
 *                     optional for TEXT, NUMBER and EMAIL
 * @param normalisation case and stripping, for TEXT, NUMBER, EMAIL, DROPDOWN and CUSTOM_PATTERN; NONE otherwise
 * @param minValue     NUMBER, DECIMAL and MONEY: lowest value allowed
 * @param maxValue     NUMBER, DECIMAL and MONEY: highest value allowed
 * @param primary      the field identifies the case (with any other primary fields); one value, active
 * @param searchable   people can search by it
 * @param searchMode   how it is searched; EXACT when not searchable; always EXACT for a sensitive field
 * @param sensitive    the value is stored only encrypted, never in logs, shown only to roles that need it;
 *                     searchable by exact match only. Name and address fields are sensitive by default.
 * @param multiValue   several values (for example two borrowers), up to {@link #MAX_VALUES}
 * @param displayable  shown with the case
 * @param displayOrder position in lists, 0–10000 (ties by key)
 * @param active       switched on; a removed field is switched off, never deleted
 */
public record FieldSpec(FieldKey key, String label, FieldType type, String currency, List<String> options,
                        boolean required, Integer exactLength, Integer minLength, Integer maxLength, String pattern,
                        Normalisation normalisation, BigDecimal minValue, BigDecimal maxValue,
                        boolean primary, boolean searchable, SearchMode searchMode, boolean sensitive,
                        boolean multiValue, boolean displayable, int displayOrder, boolean active) {

    /** The longest value, in characters. */
    public static final int MAX_VALUE_LENGTH = 512;
    /** The most values a DROPDOWN offers. */
    public static final int MAX_OPTIONS = 100;
    /** The most values a multi-value field takes. */
    public static final int MAX_VALUES = 50;
    public static final int MAX_LABEL_LENGTH = 200;
    public static final int MAX_DISPLAY_ORDER = 10_000;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern NAME_WORD = Pattern.compile(
            "name|names|firstname|lastname|middlename|fullname|givenname|familyname|surname|forename");
    private static final Pattern ADDRESS_WORD = Pattern.compile("address|addr|street");

    public FieldSpec {
        Objects.requireNonNull(key, "key");
        label = label(label);
        Objects.requireNonNull(type, "type");
        normalisation = normalisation == null ? Normalisation.NONE : normalisation;
        searchMode = searchMode == null ? SearchMode.EXACT : searchMode;
        String what = "Field " + key;

        if (type == FieldType.MONEY) {
            currency = currency(currency, what);
        } else if (currency != null) {
            throw notForType(what, "a currency", type);
        }

        if (type == FieldType.DROPDOWN) {
            options = options(options, normalisation, what);
        } else if (options != null && !options.isEmpty()) {
            throw notForType(what, "a list of values", type);
        } else {
            options = List.of();
        }

        if (!type.takesLength() && (exactLength != null || minLength != null || maxLength != null)) {
            throw notForType(what, "length rules", type);
        }
        lengths(exactLength, minLength, maxLength, what);

        if (pattern != null && !type.takesPattern()) {
            throw notForType(what, "a pattern", type);
        }
        if (type == FieldType.CUSTOM_PATTERN && (pattern == null || pattern.isEmpty())) {
            throw new FieldDefinitionException(Code.FIELD_PATTERN_REQUIRED, what + " needs its pattern");
        }
        if (pattern != null) {
            pattern(pattern, what);
        }

        if (!normalisation.isNone() && !type.takesNormalisation()) {
            throw notForType(what, "a normalisation", type);
        }

        if ((minValue != null || maxValue != null) && !type.takesRange()) {
            throw notForType(what, "a lowest or highest value", type);
        }
        minValue = bound(minValue, type, currency, what);
        maxValue = bound(maxValue, type, currency, what);
        if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
            throw new FieldDefinitionException(Code.FIELD_RANGE_INVALID,
                    what + ": the lowest value is above the highest");
        }

        if (sensitive && searchMode != SearchMode.EXACT) {
            throw new FieldDefinitionException(Code.FIELD_SENSITIVE_EXACT_ONLY,
                    what + " is sensitive, so it is searchable by exact match only");
        }
        if (!searchable && searchMode != SearchMode.EXACT) {
            throw new FieldDefinitionException(Code.FIELD_SEARCH_MODE_INVALID,
                    what + " is not searchable, so its search mode stays EXACT");
        }
        if (primary && multiValue) {
            throw new FieldDefinitionException(Code.FIELD_PRIMARY_MULTI_VALUE,
                    what + " is a primary identifier, so it takes one value");
        }
        if (primary && !active) {
            throw new FieldDefinitionException(Code.FIELD_PRIMARY_INACTIVE,
                    what + " is switched off, so it cannot be a primary identifier");
        }
        if (displayOrder < 0 || displayOrder > MAX_DISPLAY_ORDER) {
            throw new FieldDefinitionException(Code.FIELD_DISPLAY_ORDER_INVALID,
                    what + ": the display order is between 0 and " + MAX_DISPLAY_ORDER);
        }
    }

    /** A new spec with the defaults: optional, not searchable, displayable, active, sensitive as suggested. */
    public static Builder builder(String key, String label, FieldType type) {
        return new Builder(FieldKey.of(key), label, type);
    }

    /** A builder holding this spec's values, to change some of them. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Whether a field is sensitive by default: every ADDRESS field, and fields whose key or label names a name or an
     * address ({@code first_name}, "Borrower name", {@code mailing_address}). Only a default; the Business Admin
     * decides.
     */
    public static boolean suggestsSensitive(String key, String label, FieldType type) {
        if (type == FieldType.ADDRESS) {
            return true;
        }
        for (String text : new String[]{key, label}) {
            if (text == null) {
                continue;
            }
            String spaced = text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
            for (String word : spaced.split("[^a-z0-9]+")) {
                if (NAME_WORD.matcher(word).matches() || ADDRESS_WORD.matcher(word).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The number of decimals a MONEY value may have: the currency's. */
    public int moneyDecimals() {
        if (type != FieldType.MONEY) {
            throw new IllegalStateException("Not a MONEY field");
        }
        return Currency.getInstance(currency).getDefaultFractionDigits();
    }

    /** The compiled pattern, if any. */
    public Pattern compiledPattern() {
        return pattern == null ? null : Pattern.compile(pattern);
    }

    /** True if the two specs hold values of the same kind: same type and, for MONEY, the same currency. */
    public boolean sameValueKind(FieldSpec other) {
        return type == other.type && Objects.equals(currency, other.currency);
    }

    @Override
    public String toString() {
        return "FieldSpec[" + key + ", " + type + (currency == null ? "" : " " + currency) + (active ? "" : ", off")
                + "]";
    }

    // ---- checks ----

    private static FieldDefinitionException notForType(String what, String rule, FieldType type) {
        return new FieldDefinitionException(Code.FIELD_RULE_NOT_FOR_TYPE,
                what + ": a " + type + " field takes no " + rule);
    }

    static String label(String label) {
        String l = label == null ? "" : label.strip();
        if (l.isEmpty() || l.length() > MAX_LABEL_LENGTH || CONTROL.matcher(l).find()) {
            throw new FieldDefinitionException(Code.FIELD_LABEL_INVALID,
                    "A field label has 1 to " + MAX_LABEL_LENGTH + " characters and no control characters");
        }
        return l;
    }

    private static String currency(String code, String what) {
        if (code == null || code.isBlank()) {
            throw new FieldDefinitionException(Code.FIELD_CURRENCY_REQUIRED, what + " is MONEY and needs its currency");
        }
        if (!code.matches("[A-Z]{3}")) {
            throw new FieldDefinitionException(Code.FIELD_CURRENCY_INVALID,
                    what + ": the currency is a three-letter ISO 4217 code such as USD");
        }
        int decimals;
        try {
            decimals = Currency.getInstance(code).getDefaultFractionDigits();
        } catch (IllegalArgumentException unknown) {
            throw new FieldDefinitionException(Code.FIELD_CURRENCY_INVALID,
                    what + ": " + code + " is not an ISO 4217 currency");
        }
        if (decimals < 0) {
            throw new FieldDefinitionException(Code.FIELD_CURRENCY_INVALID,
                    what + ": the currency " + code + " has no fixed number of decimals");
        }
        return code;
    }

    private static List<String> options(List<String> given, Normalisation n, String what) {
        if (given == null || given.isEmpty()) {
            throw new FieldDefinitionException(Code.FIELD_OPTIONS_REQUIRED, what + " is a dropdown and needs values");
        }
        if (given.size() > MAX_OPTIONS) {
            throw new FieldDefinitionException(Code.FIELD_OPTIONS_TOO_MANY,
                    what + " has more than " + MAX_OPTIONS + " dropdown values");
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < given.size(); i++) {
            String o = given.get(i);
            if (o == null || o.isBlank() || o.length() > MAX_VALUE_LENGTH || CONTROL.matcher(o).find()) {
                throw new FieldDefinitionException(Code.FIELD_OPTION_INVALID, what + ": dropdown value " + (i + 1)
                        + " is blank, longer than " + MAX_VALUE_LENGTH + " characters or has control characters");
            }
            String kept = Normalisation.spaces(o);
            if (!seen.add(n.apply(kept))) {
                throw new FieldDefinitionException(Code.FIELD_OPTION_REPEATED,
                        what + ": dropdown value " + (i + 1) + " repeats an earlier one");
            }
            out.add(kept);
        }
        return List.copyOf(out);
    }

    private static void lengths(Integer exact, Integer min, Integer max, String what) {
        for (Integer l : new Integer[]{exact, min, max}) {
            if (l != null && (l < 1 || l > MAX_VALUE_LENGTH)) {
                throw new FieldDefinitionException(Code.FIELD_LENGTH_INVALID,
                        what + ": a length rule is between 1 and " + MAX_VALUE_LENGTH);
            }
        }
        if (exact != null && (min != null || max != null)) {
            throw new FieldDefinitionException(Code.FIELD_LENGTH_INVALID,
                    what + ": an exact length cannot go with a minimum or maximum length");
        }
        if (min != null && max != null && min > max) {
            throw new FieldDefinitionException(Code.FIELD_LENGTH_INVALID,
                    what + ": the minimum length is above the maximum");
        }
    }

    private static void pattern(String pattern, String what) {
        if (pattern.length() > MAX_VALUE_LENGTH) {
            throw new FieldDefinitionException(Code.FIELD_PATTERN_INVALID,
                    what + ": the pattern is longer than " + MAX_VALUE_LENGTH + " characters");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new FieldDefinitionException(Code.FIELD_PATTERN_INVALID,
                    what + ": the pattern is not a valid regular expression");
        }
    }

    private static BigDecimal bound(BigDecimal b, FieldType type, String currency, String what) {
        if (b == null) {
            return null;
        }
        if (type == FieldType.NUMBER) {
            try {
                return new BigDecimal(b.toBigIntegerExact());
            } catch (ArithmeticException notWhole) {
                throw new FieldDefinitionException(Code.FIELD_RANGE_INVALID,
                        what + ": the lowest and highest values of a NUMBER field are whole numbers");
            }
        }
        if (type == FieldType.DECIMAL) {
            return plain(b);
        }
        int decimals = Currency.getInstance(currency).getDefaultFractionDigits();
        if (b.stripTrailingZeros().scale() > decimals) {
            throw new FieldDefinitionException(Code.FIELD_RANGE_INVALID,
                    what + ": the lowest and highest values have at most " + decimals + " decimals");
        }
        return b.setScale(decimals);
    }

    /** A decimal without trailing zeros, never in exponent form ({@code 4.50} becomes {@code 4.5}). */
    static BigDecimal plain(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** Builds a {@link FieldSpec}; {@link #build()} checks every rule. */
    public static final class Builder {
        private final FieldKey key;
        private String label;
        private FieldType type;
        private String currency;
        private List<String> options;
        private boolean required;
        private Integer exactLength;
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private Normalisation normalisation = Normalisation.NONE;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private boolean primary;
        private boolean searchable;
        private SearchMode searchMode = SearchMode.EXACT;
        private Boolean sensitive;
        private boolean multiValue;
        private boolean displayable = true;
        private int displayOrder;
        private boolean active = true;

        private Builder(FieldKey key, String label, FieldType type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }

        private Builder(FieldSpec s) {
            this(s.key, s.label, s.type);
            currency = s.currency;
            options = s.options;
            required = s.required;
            exactLength = s.exactLength;
            minLength = s.minLength;
            maxLength = s.maxLength;
            pattern = s.pattern;
            normalisation = s.normalisation;
            minValue = s.minValue;
            maxValue = s.maxValue;
            primary = s.primary;
            searchable = s.searchable;
            searchMode = s.searchMode;
            sensitive = s.sensitive;
            multiValue = s.multiValue;
            displayable = s.displayable;
            displayOrder = s.displayOrder;
            active = s.active;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder type(FieldType type) {
            this.type = type;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder options(List<String> options) {
            this.options = options;
            return this;
        }

        public Builder options(String... options) {
            return options(List.of(options));
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder exactLength(Integer exactLength) {
            this.exactLength = exactLength;
            return this;
        }

        public Builder minLength(Integer minLength) {
            this.minLength = minLength;
            return this;
        }

        public Builder maxLength(Integer maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        public Builder normalisation(Normalisation normalisation) {
            this.normalisation = normalisation;
            return this;
        }

        public Builder normalisation(Normalisation.LetterCase letterCase, Normalisation.Strip strip) {
            return normalisation(Normalisation.of(letterCase, strip));
        }

        public Builder minValue(BigDecimal minValue) {
            this.minValue = minValue;
            return this;
        }

        public Builder maxValue(BigDecimal maxValue) {
            this.maxValue = maxValue;
            return this;
        }

        public Builder range(String min, String max) {
            this.minValue = min == null ? null : new BigDecimal(min);
            this.maxValue = max == null ? null : new BigDecimal(max);
            return this;
        }

        public Builder primary(boolean primary) {
            this.primary = primary;
            return this;
        }

        /** Searchable by exact match. */
        public Builder searchable(boolean searchable) {
            this.searchable = searchable;
            if (!searchable) {
                this.searchMode = SearchMode.EXACT;
            }
            return this;
        }

        /** Searchable in this mode. */
        public Builder searchable(SearchMode mode) {
            this.searchable = true;
            this.searchMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /** Sensitive or not; without this call, as {@link #suggestsSensitive} suggests. */
        public Builder sensitive(boolean sensitive) {
            this.sensitive = sensitive;
            return this;
        }

        public Builder multiValue(boolean multiValue) {
            this.multiValue = multiValue;
            return this;
        }

        public Builder displayable(boolean displayable) {
            this.displayable = displayable;
            return this;
        }

        public Builder displayOrder(int displayOrder) {
            this.displayOrder = displayOrder;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public FieldSpec build() {
            boolean s = sensitive != null ? sensitive : suggestsSensitive(key.value(), label, type);
            return new FieldSpec(key, label, type, currency, options, required, exactLength, minLength, maxLength,
                    pattern, normalisation, minValue, maxValue, primary, searchable, searchMode, s, multiValue,
                    displayable, displayOrder, active);
        }
    }
}
