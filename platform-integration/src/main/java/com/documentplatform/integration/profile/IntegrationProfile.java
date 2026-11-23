package com.documentplatform.integration.profile;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.Direction;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HealthCheck;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A checked integration profile ({@code platform-integration-profile/1}): what a published API fixes — steps (or a
 * packaging preset, already expanded), sign-in types, health check, classification rules, error-code pointer, limit
 * defaults, the app values and case fields it expects, per-document attributes, settings and notes for IT. Built only
 * by {@link ProfileValidator} (or {@link ProfileLoader}), so every profile here passed the format's checks.
 *
 * <p>The installation completes it ({@link #complete}) with what only it knows: the entity and product, the address,
 * the sign-in, the case field mapping and the settings. The result is an ordinary {@link IntegrationConfig}; save it
 * with {@code IntegrationConfigs}, which applies the normal save checks.
 */
public final class IntegrationProfile {

    /** An app value the templates use ({@code {value.<name>}}). */
    public record Value(String name, boolean required, String description) {
    }

    /** A case field the API expects, by the API's name. */
    public record ExpectedField(String name, boolean required, String description) {
    }

    /** Where mapped case fields go, which ones the API expects, and whether others may be mapped. */
    public record Fields(String step, String object, List<ExpectedField> expected, boolean others) {
        public Fields {
            expected = List.copyOf(expected);
        }
    }

    /** A per-document value ({@code {doc.attr.<name>}}): allowed values and default. */
    public record DocumentAttribute(String name, List<String> values, String defaultValue) {
        public DocumentAttribute {
            values = List.copyOf(values);
        }
    }

    /** A setting the installation chooses: its default and the engine settings each value stands for. */
    public record Setting(String name, String defaultValue, Map<String, Map<String, String>> values) {
        public Setting {
            values = Map.copyOf(values);
        }
    }

    /**
     * What the installation adds.
     *
     * @param fieldMapping the API's field name → the platform-fields key whose value it gets
     * @param allowedSensitiveFields the sensitive fields among them that may be sent (else refused)
     * @param settings     the profile's settings by name; missing ones take the default
     */
    public record Completion(UUID entity, String product, String name, Endpoint endpoint, SignIn signIn,
                             Map<String, String> fieldMapping, Set<String> allowedSensitiveFields,
                             Map<String, String> settings) {
        public Completion {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(signIn, "signIn");
            fieldMapping = fieldMapping == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fieldMapping));
            allowedSensitiveFields = allowedSensitiveFields == null ? Set.of() : Set.copyOf(allowedSensitiveFields);
            settings = settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    private static final Pattern EXTERNAL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    final String name;
    final String version;
    final String apiTitle;
    final String apiVersion;
    final String description;
    final Direction direction;
    final Kind kind;
    final List<CallStep> steps;
    final com.documentplatform.integration.config.PollSource poll;
    final List<String> signInTypes;
    final List<String> installationSupplies;
    final String apiKeyHeader;
    final HealthCheck health;
    final List<Rule> rules;
    final String errorCodePointer;
    final boolean sendCorrelationId;
    final IntegrationConfig.TestMode testMode;
    final Limits limits;
    final List<Value> values;
    final Fields fields;
    final List<DocumentAttribute> attributes;
    final Map<String, Setting> settings;
    final List<String> notes;

    IntegrationProfile(String name, String version, String apiTitle, String apiVersion, String description,
                       Direction direction, Kind kind, List<CallStep> steps,
                       com.documentplatform.integration.config.PollSource poll, List<String> signInTypes,
                       List<String> installationSupplies, String apiKeyHeader, HealthCheck health, List<Rule> rules,
                       String errorCodePointer, boolean sendCorrelationId, IntegrationConfig.TestMode testMode,
                       Limits limits, List<Value> values, Fields fields, List<DocumentAttribute> attributes,
                       Map<String, Setting> settings, List<String> notes) {
        this.name = name;
        this.version = version;
        this.apiTitle = apiTitle;
        this.apiVersion = apiVersion;
        this.description = description;
        this.direction = direction;
        this.kind = kind;
        this.steps = List.copyOf(steps);
        this.poll = poll;
        this.signInTypes = List.copyOf(signInTypes);
        this.installationSupplies = List.copyOf(installationSupplies);
        this.apiKeyHeader = apiKeyHeader;
        this.health = health;
        this.rules = List.copyOf(rules);
        this.errorCodePointer = errorCodePointer;
        this.sendCorrelationId = sendCorrelationId;
        this.testMode = testMode;
        this.limits = limits;
        this.values = List.copyOf(values);
        this.fields = fields;
        this.attributes = List.copyOf(attributes);
        this.settings = Map.copyOf(settings);
        this.notes = List.copyOf(notes);
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String apiTitle() {
        return apiTitle;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public String description() {
        return description;
    }

    public Direction direction() {
        return direction;
    }

    public Kind kind() {
        return kind;
    }

    /** The steps (a packaging preset already expanded); empty for API polling. */
    public List<CallStep> steps() {
        return steps;
    }

    /** How the source is polled (kind {@code API_POLL}); else null. */
    public com.documentplatform.integration.config.PollSource poll() {
        return poll;
    }

    public List<String> signInTypes() {
        return signInTypes;
    }

    public List<String> installationSupplies() {
        return installationSupplies;
    }

    /** The API key header, for profiles that allow API_KEY sign-in; else null. */
    public String apiKeyHeader() {
        return apiKeyHeader;
    }

    public HealthCheck health() {
        return health;
    }

    public List<Rule> rules() {
        return rules;
    }

    public String errorCodePointer() {
        return errorCodePointer;
    }

    public Limits limits() {
        return limits;
    }

    public List<Value> values() {
        return values;
    }

    /** Where mapped case fields go, or null when the profile takes none. */
    public Fields fields() {
        return fields;
    }

    public List<DocumentAttribute> documentAttributes() {
        return attributes;
    }

    public Map<String, Setting> settings() {
        return settings;
    }

    public List<String> notes() {
        return notes;
    }

    /**
     * The completed configuration.
     *
     * @throws IllegalArgumentException for a sign-in type the profile does not take, an unknown setting or value, a
     *                                  required field not mapped, or a mapping the profile does not take (names only,
     *                                  never values)
     */
    public IntegrationConfig complete(Completion c) {
        // Mutual TLS: the profile lists MUTUAL_TLS, and the type used together with it when there is one.
        boolean takes = c.signIn() instanceof com.documentplatform.integration.config.SignIn.MutualTls m
                ? signInTypes.contains(m.type()) && (m.with() == null || signInTypes.contains(m.with().type()))
                : signInTypes.contains(c.signIn().type());
        if (!takes) {
            throw new IllegalArgumentException("Profile " + name + " takes sign-in " + signInTypes + ", not "
                    + c.signIn().type());
        }
        for (String key : c.settings().keySet()) {
            if (!settings.containsKey(key)) throw new IllegalArgumentException("Profile " + name + " has no setting " + key);
        }
        Map<String, String> all = new LinkedHashMap<>();
        Map<String, String> engine = new LinkedHashMap<>();
        for (Setting s : settings.values()) {
            String value = c.settings().getOrDefault(s.name(), s.defaultValue());
            Map<String, String> means = s.values().get(value);
            if (means == null) {
                throw new IllegalArgumentException("Setting " + s.name() + " of profile " + name + " takes "
                        + s.values().keySet());
            }
            all.put(s.name(), value);
            engine.putAll(means);
        }
        all.putAll(engine);

        if (fields == null && !c.fieldMapping().isEmpty()) {
            throw new IllegalArgumentException("Profile " + name + " takes no case fields");
        }
        if (kind == Kind.API_POLL) {
            return new IntegrationConfig(UUID.randomUUID(), c.entity(), c.product(), kind, c.name(), true,
                    c.endpoint(), c.signIn(), limits, List.of(), errorCodePointer, rules, health, testMode,
                    sendCorrelationId, name, Set.of(), all, null, null, poll, null);
        }
        List<CallStep> completed = new ArrayList<>();
        for (CallStep s : steps) {
            completed.add(fields != null && s.name().equals(fields.step()) ? mapped(s, c.fieldMapping()) : s);
        }
        return new IntegrationConfig(UUID.randomUUID(), c.entity(), c.product(), kind, c.name(), true, c.endpoint(),
                c.signIn(), limits, completed, errorCodePointer, rules, health, testMode, sendCorrelationId, name,
                c.allowedSensitiveFields(), all);
    }

    /** The fields step with {@code "<object>":{}} filled: one {@code "<name>":{field:<key>}} per mapped field. */
    private CallStep mapped(CallStep s, Map<String, String> mapping) {
        for (ExpectedField f : fields.expected()) {
            if (f.required() && !mapping.containsKey(f.name())) {
                throw new IllegalArgumentException("Profile " + name + " needs the field " + f.name() + " mapped");
            }
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            boolean listed = fields.expected().stream().anyMatch(f -> f.name().equals(e.getKey()));
            if (!listed && !fields.others()) {
                throw new IllegalArgumentException("Profile " + name + " does not take the field " + e.getKey());
            }
            if (!EXTERNAL_NAME.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("A mapped name is 1-100 of A-Z a-z 0-9 _ . -");
            }
            if (!FieldKey.isValid(e.getValue())) throw new IllegalArgumentException("A mapped field key is not valid");
            if (!b.isEmpty()) b.append(',');
            b.append(Json.quote(e.getKey())).append(":{field:").append(e.getValue()).append('}');
        }
        String marker = marker(fields.object());
        String body = s.bodyTemplate().replace(marker, Json.quote(fields.object()) + ":{" + b + "}");
        return new CallStep(s.name(), s.method(), s.path(), s.headers(), s.body(), body, s.repeat(), s.read(),
                s.required(), s.rules(), s.multipart(), s.integrity());
    }

    static String marker(String object) {
        return Json.quote(object) + ":{}";
    }

    /**
     * A delivery checked against the profile: every required app value is there, and each document's attributes are
     * completed (a missing one takes the default) and checked (a value the profile does not list is refused).
     *
     * @throws IllegalArgumentException naming the value or attribute, never a value
     */
    public Delivery prepare(Delivery d) {
        for (Value v : values) {
            if (v.required() && !d.values().containsKey(v.name())) {
                throw new IllegalArgumentException("Profile " + name + " needs the value " + v.name());
            }
        }
        List<Delivery.Document> docs = new ArrayList<>();
        for (Delivery.Document doc : d.documents()) {
            Delivery.Document out = doc;
            for (DocumentAttribute a : attributes) {
                String v = doc.attributes().get(a.name());
                if (v == null) {
                    out = out.withAttribute(a.name(), a.defaultValue());
                } else if (!a.values().contains(v)) {
                    throw new IllegalArgumentException("Document attribute " + a.name() + " takes " + a.values());
                }
            }
            docs.add(out);
        }
        return new Delivery(d.packageId(), d.correlationId(), docs, d.values(), d.resume(), d.fields());
    }

    @Override
    public String toString() {
        return "IntegrationProfile[" + name + " " + version + ", API " + apiTitle + " " + apiVersion + "]";
    }
}
