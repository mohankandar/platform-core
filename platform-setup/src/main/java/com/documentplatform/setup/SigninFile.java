package com.documentplatform.setup;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 *     "PRODUCT_A": {"browserClientId": "…", "apiAudience": "api://product-a", "roleMappingFile": "roles-a.csv"}
 *   },
 *   "timeZone": "America/New_York",
 *   "entityTimeZones": {"ENT-…": "America/Chicago"}
 * }
 * </pre>
 *
 * {@code roleMappingFile} is relative to the sign-in file's folder (or absolute). Only mode {@code direct} is
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

    /** One product's part of the sign-in file. */
    public record Product(String browserClientId, String apiAudience, String roleMappingFile) {
    }

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
                Fields p = products.object(code).only(Set.of("browserClientId", "apiAudience", "roleMappingFile"));
                out.put(code, new Product(text(p.string("browserClientId"), "products." + code + ".browserClientId", 200),
                        text(p.string("apiAudience"), "products." + code + ".apiAudience", 200),
                        text(p.string("roleMappingFile"), "products." + code + ".roleMappingFile", 500)));
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
