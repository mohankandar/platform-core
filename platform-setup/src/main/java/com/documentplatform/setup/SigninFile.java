package com.documentplatform.setup;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The sign-in file ({@value #FORMAT}): which login system the customer's people sign in with, and per product the
 * browser client ID, the API audience and the role mapping file. It holds no secrets, no database settings and no
 * prices: any field not listed here refuses the file, so a secret pasted into it is never read or stored.
 *
 * <pre>
 * {
 *   "format": "platform-signin/1",
 *   "tenantId": "TEN-…",
 *   "mode": "direct",
 *   "loginSystem": {"type": "OIDC", "issuer": "https://login.example/oauth2/default", "providerId": "okta"},
 *   "products": {
 *     "PRODUCT_A": {"browserClientId": "…", "apiAudience": "api://product-a", "roleMappingFile": "roles-a.csv",
 *                   "clientBindingsFile": "client-bindings-product-a.csv",
 *                   "sources": [{"key": "loan-system", "name": "Loan servicing system"}]}
 *   },
 *   "timeZone": "America/New_York",
 *   "entityTimeZones": {"ENT-…": "America/Chicago"}
 * }
 * </pre>
 *
 * {@code roleMappingFile} and {@code clientBindingsFile} are relative to the sign-in file's folder (or absolute).
 * {@code clientBindingsFile} and {@code sources} are optional (no file: the product has no machine callers); older
 * files without them load as before. Only mode {@code direct} is
 * supported in this version.
 *
 * <p>{@code timeZone} (the customer's) and {@code entityTimeZones} (per entity slot) are optional defaults, IANA
 * names: setup stores one only where none is stored yet, and never changes a zone already set (for example in the
 * app). A name that is not an IANA name refuses the file ({@code SETUP_TIME_ZONE_INVALID}).
 */
public record SigninFile(String tenantId, String mode, String loginType, URI issuer, String providerId,
                         Map<String, Product> products, ZoneId timeZone, Map<String, ZoneId> entityTimeZones) {

    public static final String FORMAT = "platform-signin/1";
    public static final String DIRECT = "direct";

    /**
     * One product's part of the sign-in file.
     *
     * @param clientBindingsFile the integration client-binding file ({@code client-bindings-<product>.csv}), relative
     *                           to the sign-in file's folder; null when the product has no machine callers
     * @param sources            the product's sending systems (key and name), for the binding file's {@code source}
     *                           column; empty when none are listed
     */
    public record Product(String browserClientId, String apiAudience, String roleMappingFile,
                          String clientBindingsFile, List<Source> sources) {
        public Product {
            sources = List.copyOf(sources);
        }

        public Set<String> sourceKeys() {
            Set<String> out = new java.util.LinkedHashSet<>();
            for (Source s : sources) out.add(s.key());
            return out;
        }
    }

    /** A sending system of a product: its key ({@code [a-z0-9][a-z0-9._-]{0,63}}) and a name for people. */
    public record Source(String key, String name) {
    }

    /** Most sources listed for one product. */
    public static final int MAX_SOURCES = 200;

    public SigninFile {
        products = Map.copyOf(products);
        entityTimeZones = java.util.Collections.unmodifiableMap(new TreeMap<>(entityTimeZones));
    }

    public Optional<Product> product(String code) {
        return Optional.ofNullable(products.get(code));
    }

    /** @throws SetupException SETUP_SIGNIN_INVALID or SETUP_MODE_NOT_SUPPORTED, naming the field, never the value */
    public static SigninFile parse(String json) {
        try {
            Fields f = Fields.of(Json.parse(json), "sign-in file");
            if (!f.has("format") || !FORMAT.equals(f.string("format"))) {
                throw invalid("format must be " + FORMAT);
            }
            String mode = f.string("mode");
            if (!DIRECT.equals(mode)) {
                if ("hub".equals(mode)) {
                    throw new SetupException(SetupRefusal.SETUP_MODE_NOT_SUPPORTED,
                            "The sign-in file asks for mode \"hub\"; this version supports \"direct\" only.");
                }
                throw invalid("mode must be \"direct\"");
            }
            f.only(Set.of("format", "tenantId", "mode", "loginSystem", "products", "timeZone", "entityTimeZones"));
            String tenant = f.string("tenantId");
            if (!EntityUuid.isTenantId(tenant)) throw invalid("tenantId must be TEN- followed by 1 to 60 of A-Z 0-9 -");

            Fields login = f.object("loginSystem").only(Set.of("type", "issuer", "providerId"));
            String type = login.string("type");
            if (!"OIDC".equals(type)) throw invalid("loginSystem.type must be \"OIDC\"");
            URI issuer = issuer(login.string("issuer"));
            String providerId = text(login.string("providerId"), "loginSystem.providerId", 200);

            Fields products = f.object("products");
            if (products.map().isEmpty()) throw invalid("products must name at least one product");
            Map<String, Product> out = new LinkedHashMap<>();
            for (String code : products.map().keySet()) {
                try {
                    LoginConnection.requireProductCode(code);
                } catch (IllegalArgumentException e) {
                    throw invalid("products: a key is not a product code (2 to 32 of A-Z 0-9 _)");
                }
                Fields p = products.object(code).only(Set.of("browserClientId", "apiAudience", "roleMappingFile",
                        "clientBindingsFile", "sources"));
                String bindings = p.has("clientBindingsFile")
                        ? text(p.string("clientBindingsFile"), "products." + code + ".clientBindingsFile", 500) : null;
                out.put(code, new Product(text(p.string("browserClientId"), "products." + code + ".browserClientId", 200),
                        text(p.string("apiAudience"), "products." + code + ".apiAudience", 200),
                        text(p.string("roleMappingFile"), "products." + code + ".roleMappingFile", 500),
                        bindings, p.has("sources") ? sources(p.list("sources"), "products." + code + ".sources")
                        : List.of()));
            }
            ZoneId customerZone = f.has("timeZone") ? zone(f.string("timeZone"), "timeZone") : null;
            Map<String, ZoneId> entityZones = new TreeMap<>();
            if (f.has("entityTimeZones")) {
                Fields z = f.object("entityTimeZones");
                for (String slot : z.map().keySet()) {
                    if (!EntityUuid.isSlotId(slot)) {
                        throw invalid("entityTimeZones: a key is not an entity slot ID (ENT- followed by 1 to 60 of "
                                + "A-Z 0-9 -)");
                    }
                    entityZones.put(slot, zone(z.string(slot), "entityTimeZones." + slot));
                }
            }
            return new SigninFile(tenant, mode, type, issuer, providerId, out, customerZone, entityZones);
        } catch (JsonException e) {
            throw invalid(e.getMessage());
        }
    }

    private static List<Source> sources(List<Object> raw, String field) {
        if (raw.size() > MAX_SOURCES) throw invalid(field + " lists more than " + MAX_SOURCES + " sources");
        List<Source> out = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String at = field + "[" + i + "]";
            Fields s = Fields.of(raw.get(i), at).only(Set.of("key", "name"));
            String key = s.string("key");
            if (!MachineBinding.SOURCE_KEY.matcher(key).matches()) {
                throw invalid(at + ".key must be 1 to 64 of a-z 0-9 . _ - starting with a letter or digit");
            }
            if (!keys.add(key)) throw invalid(at + ".key \"" + key + "\" is listed twice");
            out.add(new Source(key, text(s.string("name"), at + ".name", 200)));
        }
        return out;
    }

    private static URI issuer(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getUserInfo() != null || value.length() > 2000) {
                throw invalid("loginSystem.issuer must be the login system's https issuer address, without query");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw invalid("loginSystem.issuer is not an address");
        }
    }

    private static ZoneId zone(String value, String field) {
        return EntityTimeZones.parse(value).orElseThrow(() -> new SetupException(SetupRefusal.SETUP_TIME_ZONE_INVALID,
                "The sign-in file is not valid: " + field + " is not an IANA time zone name such as "
                        + "America/New_York."));
    }

    private static String text(String value, String field, int max) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > max) throw invalid(field + " must be 1 to " + max + " characters");
        if (!value.equals(value.strip())) throw invalid(field + " starts or ends with a space");
        if (value.chars().anyMatch(Character::isISOControl)) throw invalid(field + " contains a control character");
        return value;
    }

    private static SetupException invalid(String cause) {
        return new SetupException(SetupRefusal.SETUP_SIGNIN_INVALID, "The sign-in file is not valid: " + cause + ".");
    }
}
