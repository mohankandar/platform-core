package com.documentplatform.integration.profile;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.integration.config.CallStep;
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
 * A ready-made integration profile, loaded from data ({@code profiles/<name>/profile.json}, format
 * {@value Profiles#FORMAT}) by {@link Profiles#load}. It is the part of a configuration that a published API fixes:
 * steps, classification rules, health check, limits. The app completes it with what only the installation knows
 * ({@link #complete}): the entity and product, the address, the sign-in, the case field mapping and the profile's
 * settings. The result is an ordinary {@link IntegrationConfig}, saved and versioned like any other.
 */
public final class Profile {

    /** A per-document value the profile's templates use ({@code {doc.attr.<name>}}): allowed values and default. */
    public record DocumentAttribute(String name, List<String> values, String defaultValue) {
    }

    /** A profile setting: allowed values, the default, and the engine settings each value stands for. */
    public record Setting(String name, String defaultValue, Map<String, Map<String, String>> values) {
    }

    /**
     * What the app adds.
     *
     * @param fieldMapping   the API's field name → the platform-fields key whose value it gets (for example
     *                       {@code contractNumber → contract_number}); typed by the field's type
     * @param allowedSensitiveFields the sensitive fields among them that may be sent (else refused)
     * @param settings       the profile's settings ({@code senderIdentity → LOOKUP}); missing ones take the default
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
            fieldMapping = fieldMapping == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(fieldMapping));
            allowedSensitiveFields = allowedSensitiveFields == null ? Set.of() : Set.copyOf(allowedSensitiveFields);
            settings = settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    private static final Pattern EXTERNAL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private final String name;
    private final String description;
    private final String specFile;
    private final String apiVersion;
    private final Kind kind;
    private final List<String> signInTypes;
    private final String errorCodePointer;
    private final boolean sendCorrelationId;
    private final IntegrationConfig.TestMode testMode;
    private final Limits limits;
    private final List<CallStep> steps;
    private final List<Rule> rules;
    private final HealthCheck health;
    private final String mappingStep;
    private final String mappingObject;
    private final Map<String, DocumentAttribute> attributes;
    private final Map<String, Setting> settings;

    Profile(String name, String description, String specFile, String apiVersion, Kind kind, List<String> signInTypes,
            String errorCodePointer, boolean sendCorrelationId, IntegrationConfig.TestMode testMode, Limits limits,
            List<CallStep> steps, List<Rule> rules, HealthCheck health, String mappingStep, String mappingObject,
            Map<String, DocumentAttribute> attributes, Map<String, Setting> settings) {
        this.name = name;
        this.description = description;
        this.specFile = specFile;
        this.apiVersion = apiVersion;
        this.kind = kind;
        this.signInTypes = List.copyOf(signInTypes);
        this.errorCodePointer = errorCodePointer;
        this.sendCorrelationId = sendCorrelationId;
        this.testMode = testMode;
        this.limits = limits;
        this.steps = List.copyOf(steps);
        this.rules = List.copyOf(rules);
        this.health = health;
        this.mappingStep = mappingStep;
        this.mappingObject = mappingObject;
        this.attributes = Map.copyOf(attributes);
        this.settings = Map.copyOf(settings);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** The file name of the pinned API specification next to the profile, or null. */
    public String specFile() {
        return specFile;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public List<CallStep> steps() {
        return steps;
    }

    public List<Rule> rules() {
        return rules;
    }

    public HealthCheck health() {
        return health;
    }

    public Limits limits() {
        return limits;
    }

    public List<String> signInTypes() {
        return signInTypes;
    }

    public Map<String, DocumentAttribute> documentAttributes() {
        return attributes;
    }

    public Map<String, Setting> settings() {
        return settings;
    }

    /**
     * The completed configuration: the profile's steps, rules, health check and limits with the app's entity,
     * product, address, sign-in, field mapping and settings. Save it with {@code IntegrationConfigs}.
     *
     * @throws IllegalArgumentException for a sign-in type the profile does not take, an unknown setting or value, or
     *                                  a field mapping that is not valid (names, never values)
     */
    public IntegrationConfig complete(Completion c) {
        if (!signInTypes.isEmpty() && !signInTypes.contains(c.signIn().type())) {
            throw new IllegalArgumentException("Profile " + name + " takes sign-in " + signInTypes + ", not "
                    + c.signIn().type());
        }
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String key : c.settings().keySet()) {
            if (!settings.containsKey(key)) throw new IllegalArgumentException("Profile " + name + " has no setting " + key);
        }
        Map<String, String> engine = new LinkedHashMap<>();
        for (Setting s : settings.values()) {
            String value = c.settings().getOrDefault(s.name(), s.defaultValue());
            Map<String, String> means = s.values().get(value);
            if (means == null) {
                throw new IllegalArgumentException("Setting " + s.name() + " of profile " + name + " takes "
                        + s.values().keySet());
            }
            chosen.put(s.name(), value);
            engine.putAll(means);
        }
        Map<String, String> all = new LinkedHashMap<>(chosen);
        all.putAll(engine);

        List<CallStep> completed = new ArrayList<>();
        for (CallStep s : steps) {
            completed.add(s.name().equals(mappingStep) ? mapped(s, c.fieldMapping()) : s);
        }
        if (mappingStep == null && !c.fieldMapping().isEmpty()) {
            throw new IllegalArgumentException("Profile " + name + " takes no field mapping");
        }
        return new IntegrationConfig(UUID.randomUUID(), c.entity(), c.product(), kind, c.name(), true, c.endpoint(),
                c.signIn(), limits, completed, errorCodePointer, rules, health, testMode, sendCorrelationId, name,
                c.allowedSensitiveFields(), all);
    }

    /** The mapping step with {@code "<object>":{}} filled: one {@code "<name>":{field:<key>}} per mapped field. */
    private CallStep mapped(CallStep s, Map<String, String> mapping) {
        String marker = Json.quote(mappingObject) + ":{}";
        if (s.bodyTemplate() == null || !s.bodyTemplate().contains(marker)) {
            throw new IllegalStateException("Profile " + name + ": step " + s.name() + " has no " + marker);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (!EXTERNAL_NAME.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("A mapped name is 1-100 of A-Z a-z 0-9 _ . -");
            }
            if (!FieldKey.isValid(e.getValue())) throw new IllegalArgumentException("A mapped field key is not valid");
            if (!b.isEmpty()) b.append(',');
            b.append(Json.quote(e.getKey())).append(":{field:").append(e.getValue()).append('}');
        }
        String body = s.bodyTemplate().replace(marker, Json.quote(mappingObject) + ":{" + b + "}");
        return new CallStep(s.name(), s.method(), s.path(), s.headers(), s.body(), body, s.repeat(), s.read(),
                s.required(), s.rules(), s.multipart(), s.integrity());
    }

    /**
     * A delivery with each document's attributes checked and completed: a missing one takes the profile's default, a
     * value the profile does not list is refused.
     */
    public Delivery withDocumentDefaults(Delivery d) {
        List<Delivery.Document> docs = new ArrayList<>();
        for (Delivery.Document doc : d.documents()) {
            Delivery.Document out = doc;
            for (DocumentAttribute a : attributes.values()) {
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
        return "Profile[" + name + ", API " + apiVersion + "]";
    }
}
