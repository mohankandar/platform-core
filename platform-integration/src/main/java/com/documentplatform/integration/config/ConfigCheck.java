package com.documentplatform.integration.config;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.safety.AddressGuard;
import com.documentplatform.integration.safety.Installation;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a configuration when it is saved, under the installation's rules (and, for {@code {field:<key>}}
 * placeholders, the entity's field list). Errors refuse the save; warnings (sign-in NONE on premises) are kept with the
 * version and given to the app's audit.
 */
public final class ConfigCheck {

    /** The result: errors refuse the save, warnings are recorded. */
    public record Report(List<ConfigProblem> errors, List<ConfigProblem> warnings) {
        public Report {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** Set only by the HTTP layer or the sign-in bundle. */
    public static final Set<String> RESERVED_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "set-cookie", "host", "content-length", "connection", "transfer-encoding", "expect", "upgrade", "te",
            "keep-alive", "trailer", "x-correlation-id");

    public static final int MAX_STEPS = 20;
    public static final int MAX_RULES = 100;
    private static final Pattern CLAIM = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]{0,99}");

    private ConfigCheck() {
    }

    /** Without field lists: {@code {field:<key>}} placeholders are not checked against the entity's fields. */
    public static Report check(IntegrationConfig c, Installation installation) {
        return check(c, installation, null);
    }

    /**
     * @param fieldLists the entity's field lists, or null to skip the field checks (the engine's run-time check, which
     *                   has no field list, uses this; a save always passes them)
     */
    public static Report check(IntegrationConfig c, Installation installation, FieldLists fieldLists) {
        List<ConfigProblem> errors = new ArrayList<>();
        List<ConfigProblem> warnings = new ArrayList<>();
        boolean api = c.kind() == Kind.API_PUSH || c.kind() == Kind.API_POLL;

        if (api && c.endpoint() == null) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_ADDRESS_INVALID, "endpoint", null));
        }
        if (c.endpoint() != null) {
            address(c.endpoint().baseAddress(), "endpoint.baseAddress", installation, errors);
        }

        if (c.kind() == Kind.FOLDER_EXPORT) {
            folder(c, installation, errors, fieldLists);
        } else {
            if (c.folder() != null) errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "folder", null));
            signIn(c, installation, errors, warnings);
        }
        settings(c, errors);

        if (c.errorCodePointer() != null && !Json.isPointer(c.errorCodePointer())) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "errorCodePointer", null));
        }
        if (c.rules().size() > MAX_RULES) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_RULE_INVALID, "rules", null));
        }
        for (String key : c.allowedSensitiveFields()) {
            if (!FieldKey.isValid(key)) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FIELD_UNKNOWN, "allowedSensitiveFields", null));
            }
        }

        if (c.kind() == Kind.API_PUSH) {
            steps(c, errors, fieldLists);
        } else if (!c.steps().isEmpty() && c.kind() != Kind.API_POLL) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, "steps", null));
        }

        if (c.health() != null) {
            HealthCheck h = c.health();
            try {
                Template t = Template.parse(h.path(), Template.Place.PATH);
                for (String p : t.placeholders()) {
                    if (!p.equals("correlationId")) {
                        errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, "health.path", p));
                    }
                }
            } catch (Template.Problem p) {
                errors.add(new ConfigProblem(p.code(), "health.path", p.placeholder()));
            }
            if (h.rules().size() > MAX_RULES) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_RULE_INVALID, "health.rules", null));
            }
        }
        return new Report(errors, warnings);
    }

    private static void address(URI uri, String where, Installation installation, List<ConfigProblem> errors) {
        if (!uri.getScheme().equalsIgnoreCase("https") && !installation.allowPlainHttp()) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_ADDRESS_NOT_SECURE, where, null));
        }
        // Names are resolved for each attempt; when saving only literal addresses and known names are checked.
        AddressGuard literalOnly = new AddressGuard(installation.allowInternalAddresses(), host -> {
            if (host.matches("[0-9.]+") || host.contains(":")) return InetAddress.getAllByName(host);
            throw new UnknownHostException();
        });
        Optional<IntegrationCode> blocked = literalOnly.check(uri.getHost(), true);
        if (blocked.isPresent()) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_ADDRESS_INVALID, where, null));
        }
    }

    /** The placeholders a folder name may use; a file name may also use the document ones. */
    private static boolean folderPlaceholder(String p, boolean fileName) {
        if (p.equals("package.id") || p.equals("package.documentCount") || p.startsWith("value.")
                || p.startsWith(Template.FIELD_PREFIX)) {
            return true;
        }
        return fileName && p.startsWith("doc.");
    }

    /**
     * Folder export: a target folder (absolute local or share path), no endpoint, steps or health call, no sign-in
     * bundle (the service account's rights), not on the hosted service; the folder and file name templates are one
     * path segment each, with fixed placeholders only and no sensitive field.
     */
    private static void folder(IntegrationConfig c, Installation installation, List<ConfigProblem> errors,
                               FieldLists fieldLists) {
        if (installation.hosted()) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FOLDER_HOSTED, "folder", null));
        }
        if (c.endpoint() != null) errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "endpoint", null));
        if (c.health() != null) errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "health", null));
        if (!(c.signIn() instanceof SignIn.None)) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "signIn", null));
        }
        FolderTarget f = c.folder();
        if (f == null) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FOLDER_INVALID, "folder", null));
            return;
        }
        if (!FolderTarget.validFolder(f.folder())) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FOLDER_INVALID, "folder.folder", null));
        }
        FieldSet fields = fieldLists == null ? null : fieldLists.fieldsOf(c.entity(), c.product()).orElse(null);
        nameTemplate(f.folderNameTemplate(), "folder.folderName", false, c, fields, fieldLists != null, errors);
        if (f.fileNameTemplate() != null) {
            nameTemplate(f.fileNameTemplate(), "folder.fileName", true, c, fields, fieldLists != null, errors);
        }
    }

    private static void nameTemplate(String text, String where, boolean fileName, IntegrationConfig c, FieldSet fields,
                                     boolean checkFields, List<ConfigProblem> errors) {
        Template t;
        try {
            t = Template.parse(text, Template.Place.NAME);
        } catch (Template.Problem p) {
            errors.add(new ConfigProblem(p.code() == IntegrationCode.INT_CONFIG_TEMPLATE_INVALID
                    ? IntegrationCode.INT_CONFIG_FOLDER_INVALID : p.code(), where, p.placeholder()));
            return;
        }
        for (String p : t.placeholders()) {
            if (!folderPlaceholder(p, fileName)) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, where, p));
            } else if (p.startsWith(Template.FIELD_PREFIX) && checkFields) {
                Optional<FieldSpec> spec = fields == null ? Optional.empty()
                        : fields.find(p.substring(Template.FIELD_PREFIX.length()));
                if (spec.isEmpty() || !spec.get().active()) {
                    errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FIELD_UNKNOWN, where, p));
                } else if (spec.get().sensitive()) {
                    // A name is seen by everyone with access to the folder: a sensitive value never goes there.
                    errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED, where, p));
                }
            }
        }
        // A sample rendering must be a valid name (literal text such as "manifest.json" or ".partial" is refused).
        try {
            String sample = t.fill(SAMPLE);
            if (!FolderTarget.validName(sample)
                    || sample.equalsIgnoreCase(FolderTarget.MANIFEST_FILE) && fileName) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FOLDER_INVALID, where, null));
            }
        } catch (Template.Problem p) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FOLDER_INVALID, where, p.placeholder()));
        }
    }

    private static void signIn(IntegrationConfig c, Installation installation, List<ConfigProblem> errors,
                               List<ConfigProblem> warnings) {
        SignIn s = c.signIn();
        if (s instanceof SignIn.None) {
            if (installation.hosted()) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_HOSTED, "signIn", null));
            } else if (!installation.allowSignInNone()) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_OFF, "signIn", null));
            } else {
                warnings.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_WARNING, "signIn", null));
            }
        } else if (s instanceof SignIn.OAuth2ClientCredentials o) {
            address(o.tokenAddress(), "signIn.tokenAddress", installation, errors);
        }
    }

    private static void settings(IntegrationConfig c, List<ConfigProblem> errors) {
        String claims = c.settings().get(IntegrationConfig.REQUIRED_TOKEN_CLAIMS);
        if (claims == null) return;
        String where = "settings." + IntegrationConfig.REQUIRED_TOKEN_CLAIMS;
        if (!(c.signIn() instanceof SignIn.OAuth2ClientCredentials)) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SETTING_INVALID, where, null));
            return;
        }
        for (String claim : c.requiredTokenClaims()) {
            if (!CLAIM.matcher(claim).matches()) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SETTING_INVALID, where, null));
            }
        }
    }

    private static void steps(IntegrationConfig c, List<ConfigProblem> errors, FieldLists fieldLists) {
        List<CallStep> steps = c.steps();
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, "steps", null));
            return;
        }
        String apiKeyHeader = c.signIn() instanceof SignIn.ApiKey k ? k.header().toLowerCase(Locale.ROOT) : null;
        FieldSet fields = fieldLists == null ? null : fieldLists.fieldsOf(c.entity(), c.product()).orElse(null);
        Map<String, CallStep> earlier = new HashMap<>();
        for (int n = 0; n < steps.size(); n++) {
            CallStep s = steps.get(n);
            String at = "steps[" + n + "]";
            if (earlier.containsKey(s.name())) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, at + ".name", null));
            }
            boolean perDoc = s.repeat() == CallStep.Repeat.EACH_DOCUMENT;
            if (s.body() == CallStep.Body.FILE && !perDoc) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, at + ".body", null));
            }
            if (s.body() == CallStep.Body.MULTIPART && perDoc) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_BODY_INVALID, at + ".body", null));
            }
            if (s.body() != CallStep.Body.NONE && (s.method() == HttpMethod.GET || s.method() == HttpMethod.HEAD)) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, at + ".body", null));
            }
            if (s.rules().size() > MAX_RULES) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_RULE_INVALID, at + ".rules", null));
            }
            // (template, where, whether document placeholders are allowed outside an each-block, whether it is JSON)
            List<Placed> templates = new ArrayList<>();
            parse(s.path(), Template.Place.PATH, at + ".path", perDoc, templates, errors);
            Set<String> seenHeaders = new HashSet<>();
            for (Map.Entry<String, String> h : s.headers().entrySet()) {
                String name = h.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                String hw = at + ".headers." + name;
                if (!SignIn.HEADER_NAME.matcher(name).matches() || !seenHeaders.add(lower)) {
                    errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_HEADER_INVALID, hw, null));
                    continue;
                }
                if (RESERVED_HEADERS.contains(lower) || lower.equals(apiKeyHeader)) {
                    errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_HEADER_RESERVED, hw, null));
                    continue;
                }
                parse(h.getValue(), Template.Place.HEADER, hw, perDoc, templates, errors);
            }
            if (s.body() == CallStep.Body.JSON || s.body() == CallStep.Body.MULTIPART) {
                Placed body = parse(s.bodyTemplate(), Template.Place.JSON, at + ".bodyTemplate", perDoc, templates,
                        errors);
                if (body != null) {
                    try {
                        Json.parse(body.template().fill(SAMPLE));
                        // And with every "omit when missing" member left out.
                        if (!body.template().optionalPlaceholders().isEmpty()) Json.parse(body.template().fill(OMITTED));
                    } catch (Json.Invalid | Template.Problem e) {
                        errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_BODY_NOT_JSON, at + ".bodyTemplate",
                                null));
                    }
                }
            }
            if (s.multipart() != null) {
                parse(s.multipart().fileNameTemplate(), Template.Place.HEADER, at + ".multipart.fileName", true,
                        templates, errors);
            }
            for (Placed t : templates) {
                Set<String> outside = t.template().placeholdersOutsideEach();
                for (String p : t.template().placeholders()) {
                    placeholder(p, s, earlier, t, outside.contains(p), errors);
                    if (p.startsWith(Template.FIELD_PREFIX) && fieldLists != null) field(p, c, fields, t, errors);
                }
            }
            for (Map.Entry<String, String> r : s.read().entrySet()) {
                if (!CallStep.NAME.matcher(r.getKey()).matches() || !Json.isPointer(r.getValue())) {
                    errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, at + ".read." + r.getKey(), null));
                }
            }
            for (String req : s.required()) {
                if (!s.read().containsKey(req)) {
                    errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, at + ".required." + req, null));
                }
            }
            if (s.integrity() != null) {
                CallStep.Integrity in = s.integrity();
                boolean ok = perDoc && s.body() == CallStep.Body.FILE
                        && (in.sha256Value() == null || s.read().containsKey(in.sha256Value()))
                        && (in.sizeValue() == null || s.read().containsKey(in.sizeValue()));
                if (!ok) errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_BODY_INVALID, at + ".integrity", null));
            }
            earlier.put(s.name(), s);
        }
    }

    /** A parsed template with where it is and whether document placeholders may stand outside an each-block. */
    private record Placed(Template template, String where, boolean documentContext) {
    }

    /** Sample values for checking that a JSON template renders to JSON: one fake document. */
    private static final Template.Values SAMPLE = new Template.Values() {
        @Override
        public Optional<String> text(String name) {
            return Optional.of(Template.NUMERIC.contains(name) ? "1" : "x");
        }

        @Override
        public Optional<String> json(String name) {
            return Optional.of("1");
        }

        @Override
        public List<Template.Values> documents() {
            return List.of(this);
        }
    };

    /** The sample values with every "omit when missing" member left out (two fake documents). */
    private static final Template.Values OMITTED = new Template.Values() {
        @Override
        public Optional<String> text(String name) {
            return SAMPLE.text(name);
        }

        @Override
        public Optional<String> json(String name) {
            return SAMPLE.json(name);
        }

        @Override
        public boolean has(String name) {
            return false;
        }

        @Override
        public List<Template.Values> documents() {
            return List.of(this, this);
        }
    };

    private static void placeholder(String p, CallStep s, Map<String, CallStep> earlier, Placed t, boolean outsideEach,
                                    List<ConfigProblem> errors) {
        boolean perDoc = s.repeat() == CallStep.Repeat.EACH_DOCUMENT;
        if (p.startsWith("doc.") && outsideEach && !t.documentContext()) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, t.where(), p));
        }
        if (p.startsWith("step.")) {
            String[] seg = p.split("\\.");
            CallStep from = earlier.get(seg[1]);
            boolean ok = from != null && from.read().containsKey(seg[2])
                    && (from.repeat() == CallStep.Repeat.ONCE || perDoc);
            if (!ok) errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEP_VALUE_UNKNOWN, t.where(), p));
        }
    }

    /** A {field:<key>} must exist in the entity's field list; a sensitive one must be allowed and go in JSON only. */
    private static void field(String p, IntegrationConfig c, FieldSet fields, Placed t, List<ConfigProblem> errors) {
        String key = p.substring(Template.FIELD_PREFIX.length());
        Optional<FieldSpec> spec = fields == null ? Optional.empty() : fields.find(key);
        if (spec.isEmpty() || !spec.get().active()) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_FIELD_UNKNOWN, t.where(), p));
            return;
        }
        if (spec.get().sensitive() && (!c.allowedSensitiveFields().contains(key)
                || t.template().place() != Template.Place.JSON)) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED, t.where(), p));
        }
    }

    private static Placed parse(String text, Template.Place place, String where, boolean documentContext,
                                List<Placed> into, List<ConfigProblem> errors) {
        try {
            Placed t = new Placed(Template.parse(text, place), where, documentContext);
            into.add(t);
            return t;
        } catch (Template.Problem p) {
            errors.add(new ConfigProblem(p.code(), where, p.placeholder()));
            return null;
        }
    }
}
