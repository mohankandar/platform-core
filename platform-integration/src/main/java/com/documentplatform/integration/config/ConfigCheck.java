package com.documentplatform.integration.config;

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

/**
 * Checks a configuration when it is saved, under the installation's rules. Errors refuse the save; warnings (sign-in
 * NONE on premises) are kept with the version and given to the app's audit.
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

    private ConfigCheck() {
    }

    public static Report check(IntegrationConfig c, Installation installation) {
        List<ConfigProblem> errors = new ArrayList<>();
        List<ConfigProblem> warnings = new ArrayList<>();
        boolean api = c.kind() == Kind.API_PUSH || c.kind() == Kind.API_POLL;

        if (api && c.endpoint() == null) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_ADDRESS_INVALID, "endpoint", null));
        }
        if (c.endpoint() != null) {
            address(c.endpoint().baseAddress(), "endpoint.baseAddress", installation, errors);
        }

        signIn(c, installation, errors, warnings);

        if (c.errorCodePointer() != null && !Json.isPointer(c.errorCodePointer())) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "errorCodePointer", null));
        }
        if (c.rules().size() > MAX_RULES) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_RULE_INVALID, "rules", null));
        }

        if (c.kind() == Kind.API_PUSH) {
            steps(c, errors);
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

    private static void steps(IntegrationConfig c, List<ConfigProblem> errors) {
        List<CallStep> steps = c.steps();
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, "steps", null));
            return;
        }
        String apiKeyHeader = c.signIn() instanceof SignIn.ApiKey k ? k.header().toLowerCase(Locale.ROOT) : null;
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
            if (s.body() != CallStep.Body.NONE && (s.method() == HttpMethod.GET || s.method() == HttpMethod.HEAD)) {
                errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEPS_INVALID, at + ".body", null));
            }
            List<Template> templates = new ArrayList<>();
            List<String> wheres = new ArrayList<>();
            parse(s.path(), Template.Place.PATH, at + ".path", templates, wheres, errors);
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
                parse(h.getValue(), Template.Place.HEADER, hw, templates, wheres, errors);
            }
            if (s.body() == CallStep.Body.JSON) {
                Template body = parse(s.bodyTemplate(), Template.Place.JSON, at + ".bodyTemplate", templates, wheres,
                        errors);
                if (body != null) {
                    try {
                        Json.parse(body.render(name -> Optional.of(Template.NUMERIC.contains(name) ? "1" : "x")));
                    } catch (Json.Invalid | Template.Problem e) {
                        errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_BODY_NOT_JSON, at + ".bodyTemplate",
                                null));
                    }
                }
            }
            for (int k = 0; k < templates.size(); k++) {
                for (String p : templates.get(k).placeholders()) {
                    placeholder(p, s, earlier, wheres.get(k), errors);
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
            earlier.put(s.name(), s);
        }
    }

    private static void placeholder(String p, CallStep s, Map<String, CallStep> earlier, String where,
                                    List<ConfigProblem> errors) {
        boolean perDoc = s.repeat() == CallStep.Repeat.EACH_DOCUMENT;
        if (p.startsWith("doc.") && !perDoc) {
            errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, where, p));
        }
        if (p.startsWith("step.")) {
            String[] seg = p.split("\\.");
            CallStep from = earlier.get(seg[1]);
            boolean ok = from != null && from.read().containsKey(seg[2])
                    && (from.repeat() == CallStep.Repeat.ONCE || perDoc);
            if (!ok) errors.add(new ConfigProblem(IntegrationCode.INT_CONFIG_STEP_VALUE_UNKNOWN, where, p));
        }
    }

    private static Template parse(String text, Template.Place place, String where, List<Template> into,
                                  List<String> wheres, List<ConfigProblem> errors) {
        try {
            Template t = Template.parse(text, place);
            into.add(t);
            wheres.add(where);
            return t;
        } catch (Template.Problem p) {
            errors.add(new ConfigProblem(p.code(), where, p.placeholder()));
            return null;
        }
    }
}
