package com.documentplatform.fields;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Checks and normalises values against their field's rules. Every refusal has a stable {@link ValueError} code and
 * a plain message naming the field's label and the rule; no message, log or {@code toString()} ever contains the
 * value. Logs nothing. Thread-safe.
 */
public final class FieldValidator {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029&&[^\\t]]");
    private static final Pattern WHOLE = Pattern.compile("-?[0-9]+");
    private static final Pattern AMOUNT = Pattern.compile("-?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]+))?");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern UUID_FORM = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ZIP = Pattern.compile("([0-9]{5})(?:-?([0-9]{4}))?");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    /** USPS codes: the states, DC, the territories and the military post codes. */
    private static final Set<String> US_STATES = Set.of("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV",
            "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA",
            "WA", "WV", "WI", "WY", "DC", "AS", "GU", "MP", "PR", "VI", "UM", "AA", "AE", "AP");
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private FieldValidator() {
    }

    /** One value, or the reason it was refused. Exactly one of the two is set. */
    public record Checked(ValidValue valid, ValueProblem problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    /** Checks one value of the field. */
    public static Checked check(FieldSpec spec, FieldValue value) {
        return check(spec, value, null);
    }

    /**
     * Checks the values given for one field: one value, or for a multi-value field up to {@link FieldSpec#MAX_VALUES}
     * different ones. An empty list is refused if the field is required.
     *
     * @return the valid values, in order, and the problems (empty if all passed)
     */
    public static Result checkAll(FieldSpec spec, List<? extends FieldValue> values) {
        Objects.requireNonNull(spec, "spec");
        List<ValidValue> valid = new ArrayList<>();
        List<ValueProblem> problems = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            if (spec.required()) {
                problems.add(problem(spec, null, ValueError.VALUE_REQUIRED, "is required"));
            }
            return new Result(valid, problems);
        }
        if (!spec.multiValue() && values.size() > 1) {
            problems.add(problem(spec, null, ValueError.VALUE_NOT_MULTI, "takes one value"));
            return new Result(valid, problems);
        }
        if (values.size() > FieldSpec.MAX_VALUES) {
            problems.add(problem(spec, null, ValueError.VALUE_TOO_MANY,
                    "takes at most " + FieldSpec.MAX_VALUES + " values"));
            return new Result(valid, problems);
        }
        Set<FieldValue> seen = new HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            Checked c = check(spec, values.get(i), spec.multiValue() ? i : null);
            if (!c.ok()) {
                problems.add(c.problem());
            } else if (!seen.add(c.valid().normalised())) {
                problems.add(problem(spec, i, ValueError.VALUE_REPEATED, "has the same value twice"));
            } else {
                valid.add(c.valid());
            }
        }
        return new Result(problems.isEmpty() ? valid : List.of(), problems);
    }

    /** What {@link #checkAll} found. */
    public record Result(List<ValidValue> values, List<ValueProblem> problems) {
        public Result {
            values = List.copyOf(values);
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    static Checked check(FieldSpec spec, FieldValue value, Integer index) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(value, "value");
        if (spec.type() == FieldType.ADDRESS) {
            if (!(value instanceof FieldValue.Address a)) {
                return refused(spec, index, ValueError.VALUE_WRONG_SHAPE, "takes an address in parts");
            }
            return address(spec, a, index);
        }
        if (!(value instanceof FieldValue.Text t)) {
            return refused(spec, index, ValueError.VALUE_WRONG_SHAPE, "takes text, not an address");
        }
        String raw = t.value();
        if (raw.length() > FieldSpec.MAX_VALUE_LENGTH) {
            return refused(spec, index, ValueError.VALUE_TOO_LONG,
                    "has more than " + FieldSpec.MAX_VALUE_LENGTH + " characters");
        }
        if (CONTROL.matcher(raw).find()) {
            return refused(spec, index, ValueError.VALUE_CONTROL_CHARACTERS,
                    "must not contain line breaks or other control characters");
        }
        String display = Normalisation.spaces(raw);
        if (display.isEmpty()) {
            return refused(spec, index, ValueError.VALUE_EMPTY, "must not be blank");
        }
        String normalised;
        switch (spec.type()) {
            case TEXT, CUSTOM_PATTERN -> normalised = spec.normalisation().apply(display);
            case NUMBER -> {
                normalised = spec.normalisation().apply(display);
                if (!WHOLE.matcher(normalised).matches()) {
                    return refused(spec, index, ValueError.VALUE_NOT_A_NUMBER, "must be a whole number");
                }
                Checked range = range(spec, new BigDecimal(new BigInteger(normalised)), index);
                if (range != null) {
                    return range;
                }
            }
            case MONEY -> {
                Object amount = money(spec, display, index);
                if (amount instanceof Checked refusal) {
                    return refusal;
                }
                normalised = ((BigDecimal) amount).toPlainString();
            }
            case DATE -> {
                LocalDate d = date(display);
                if (d == null) {
                    return refused(spec, index, ValueError.VALUE_NOT_A_DATE,
                            "must be a date such as 2026-11-21 or 11/21/2026");
                }
                normalised = ISO.format(d);
            }
            case EMAIL -> {
                normalised = spec.normalisation().apply(display);
                if (!EMAIL.matcher(normalised).matches()) {
                    return refused(spec, index, ValueError.VALUE_NOT_AN_EMAIL, "must be an e-mail address");
                }
            }
            case UUID -> {
                if (!UUID_FORM.matcher(display).matches()) {
                    return refused(spec, index, ValueError.VALUE_NOT_A_UUID,
                            "must be a UUID such as 123e4567-e89b-12d3-a456-426614174000");
                }
                normalised = display.toLowerCase(Locale.ROOT);
            }
            case DROPDOWN -> {
                String given = spec.normalisation().apply(display);
                String option = null;
                for (String o : spec.options()) {
                    if (spec.normalisation().apply(o).equals(given)) {
                        option = o;
                        break;
                    }
                }
                if (option == null) {
                    return refused(spec, index, ValueError.VALUE_NOT_AN_OPTION, "must be one of the listed values");
                }
                display = option;
                normalised = given;
            }
            default -> throw new IllegalStateException("Unexpected type " + spec.type());
        }
        if (spec.type().takesLength()) {
            Checked length = length(spec, normalised, index);
            if (length != null) {
                return length;
            }
        }
        if (spec.pattern() != null && !pattern(spec.pattern()).matcher(normalised).matches()) {
            return refused(spec, index, ValueError.VALUE_PATTERN_MISMATCH, "does not have the expected format");
        }
        return new Checked(new ValidValue(new FieldValue.Text(display), new FieldValue.Text(normalised)), null);
    }

    private static Object money(FieldSpec spec, String display, Integer index) {
        Currency currency = Currency.getInstance(spec.currency());
        String v = display;
        for (String mark : new String[]{currency.getCurrencyCode(), currency.getSymbol(Locale.US)}) {
            if (v.startsWith(mark)) {
                v = v.substring(mark.length()).strip();
            } else if (v.startsWith("-" + mark)) {
                v = "-" + v.substring(mark.length() + 1).strip();
            }
            if (v.endsWith(mark)) {
                v = v.substring(0, v.length() - mark.length()).strip();
            }
        }
        var m = AMOUNT.matcher(v);
        if (!m.matches()) {
            return refused(spec, index, ValueError.VALUE_NOT_MONEY,
                    "must be an amount in " + spec.currency() + ", such as 1,250.00");
        }
        int decimals = currency.getDefaultFractionDigits();
        if (m.group(1) != null && m.group(1).length() > decimals) {
            return refused(spec, index, ValueError.VALUE_TOO_MANY_DECIMALS,
                    "has at most " + decimals + " decimals (" + spec.currency() + ")");
        }
        BigDecimal amount = new BigDecimal(v.replace(",", "")).setScale(decimals);
        if (amount.signum() == 0) {
            amount = BigDecimal.ZERO.setScale(decimals);
        }
        Checked range = range(spec, amount, index);
        return range != null ? range : amount;
    }

    private static LocalDate date(String v) {
        for (DateTimeFormatter f : new DateTimeFormatter[]{ISO, US_DATE}) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException next) {
                // try the other form
            }
        }
        return null;
    }

    private static Checked range(FieldSpec spec, BigDecimal value, Integer index) {
        if (spec.minValue() != null && value.compareTo(spec.minValue()) < 0) {
            return refused(spec, index, ValueError.VALUE_BELOW_MINIMUM,
                    "must be at least " + spec.minValue().toPlainString());
        }
        if (spec.maxValue() != null && value.compareTo(spec.maxValue()) > 0) {
            return refused(spec, index, ValueError.VALUE_ABOVE_MAXIMUM,
                    "must be at most " + spec.maxValue().toPlainString());
        }
        return null;
    }

    private static Checked length(FieldSpec spec, String normalised, Integer index) {
        int n = normalised.codePointCount(0, normalised.length());
        if (spec.exactLength() != null && n != spec.exactLength()) {
            return refused(spec, index, ValueError.VALUE_LENGTH_NOT_EXACT,
                    "must have exactly " + spec.exactLength() + " characters");
        }
        if (spec.minLength() != null && n < spec.minLength()) {
            return refused(spec, index, ValueError.VALUE_TOO_SHORT,
                    "must have at least " + spec.minLength() + " characters");
        }
        if (spec.maxLength() != null && n > spec.maxLength()) {
            return refused(spec, index, ValueError.VALUE_ABOVE_MAX_LENGTH,
                    "must have at most " + spec.maxLength() + " characters");
        }
        return null;
    }

    private static Checked address(FieldSpec spec, FieldValue.Address a, Integer index) {
        if (a.length() > FieldSpec.MAX_VALUE_LENGTH) {
            return refused(spec, index, ValueError.VALUE_TOO_LONG,
                    "has more than " + FieldSpec.MAX_VALUE_LENGTH + " characters in all");
        }
        for (String part : new String[]{a.street(), a.city(), a.state(), a.zip(), a.country()}) {
            if (part != null && CONTROL.matcher(part).find()) {
                return refused(spec, index, ValueError.VALUE_CONTROL_CHARACTERS,
                        "must not contain line breaks or other control characters");
            }
        }
        String country = tidy(a.country()).toUpperCase(Locale.ROOT);
        if (!country.isEmpty() && !country.equals(FieldValue.Address.US)) {
            return refused(spec, index, ValueError.ADDRESS_COUNTRY_NOT_SUPPORTED,
                    "only US addresses can be entered for now");
        }
        String street = tidy(a.street());
        String city = tidy(a.city());
        String state = tidy(a.state()).toUpperCase(Locale.ROOT);
        String zip = tidy(a.zip());
        if (street.isEmpty()) {
            return refused(spec, index, ValueError.ADDRESS_STREET_MISSING, "the street is missing");
        }
        if (city.isEmpty()) {
            return refused(spec, index, ValueError.ADDRESS_CITY_MISSING, "the city is missing");
        }
        if (!US_STATES.contains(state)) {
            return refused(spec, index, ValueError.ADDRESS_STATE_INVALID,
                    "the state must be a two-letter US state code such as NY");
        }
        var z = ZIP.matcher(zip);
        if (!z.matches()) {
            return refused(spec, index, ValueError.ADDRESS_ZIP_INVALID,
                    "the ZIP must be 5 digits or ZIP+4 such as 12345-6789");
        }
        String tidyZip = z.group(2) == null ? z.group(1) : z.group(1) + "-" + z.group(2);
        FieldValue.Address clean = new FieldValue.Address(street, city, state, tidyZip, FieldValue.Address.US);
        return new Checked(new ValidValue(clean, clean), null);
    }

    private static String tidy(String part) {
        return part == null ? "" : Normalisation.spaces(part);
    }

    private static Pattern pattern(String regex) {
        if (PATTERNS.size() > 1000) {
            PATTERNS.clear();
        }
        return PATTERNS.computeIfAbsent(regex, Pattern::compile);
    }

    private static Checked refused(FieldSpec spec, Integer index, ValueError code, String rule) {
        return new Checked(null, problem(spec, index, code, rule));
    }

    private static ValueProblem problem(FieldSpec spec, Integer index, ValueError code, String rule) {
        String sep = rule.startsWith("the ") || rule.startsWith("only ") ? ": " : " ";
        String which = index == null ? spec.label() : spec.label() + " (value " + (index + 1) + ")";
        return new ValueProblem(spec.key(), index, code, which + sep + rule);
    }
}
