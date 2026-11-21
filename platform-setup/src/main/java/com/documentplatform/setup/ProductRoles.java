package com.documentplatform.setup;

import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The product's role list ({@value #FORMAT}), shipped by each product in its distribution. Setup reads it for the
 * product given with {@code --product}; the command itself knows no product and no role names.
 *
 * <pre>
 * {"format": "platform-product-roles/1", "productCode": "PRODUCT_A",
 *  "roles": ["READER", "EDITOR"], "platformRoles": ["PLATFORM_ADMIN"]}
 * </pre>
 *
 * {@code roles} may be granted to groups per entity; {@code platformRoles} (optional) reach platform-wide screens and
 * are never granted through a mapping file. A role written in another case is refused with its canonical spelling.
 */
public final class ProductRoles implements EntityRoleCatalog {

    public static final String FORMAT = "platform-product-roles/1";
    private static final Pattern ROLE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");

    private final String productCode;
    private final Set<String> roles;
    private final Set<String> platformRoles;

    private ProductRoles(String productCode, Set<String> roles, Set<String> platformRoles) {
        this.productCode = productCode;
        this.roles = Set.copyOf(roles);
        this.platformRoles = Set.copyOf(platformRoles);
    }

    /** @throws SetupException SETUP_PRODUCT_ROLES_INVALID, naming the field */
    public static ProductRoles parse(String json, String expectedProduct) {
        try {
            Fields f = Fields.of(Json.parse(json), "product-roles file")
                    .only(Set.of("format", "productCode", "roles", "platformRoles"));
            if (!FORMAT.equals(f.string("format"))) throw invalid("format must be " + FORMAT);
            String product = f.string("productCode");
            try {
                LoginConnection.requireProductCode(product);
            } catch (IllegalArgumentException e) {
                throw invalid("productCode is not a product code");
            }
            if (!product.equals(expectedProduct)) {
                throw invalid("it is for product " + product + ", but setup runs for " + expectedProduct);
            }
            Set<String> roles = names(f.list("roles"), "roles");
            if (roles.isEmpty()) throw invalid("roles must list at least one role");
            Set<String> platform = f.has("platformRoles") ? names(f.list("platformRoles"), "platformRoles") : Set.of();
            Set<String> seen = new java.util.HashSet<>();
            for (String r : concat(roles, platform)) {
                if (!seen.add(r.toUpperCase(Locale.ROOT))) throw invalid("role " + r + " is listed twice");
            }
            return new ProductRoles(product, roles, platform);
        } catch (JsonException e) {
            throw invalid(e.getMessage());
        }
    }

    public String productCode() {
        return productCode;
    }

    public Set<String> roles() {
        return roles;
    }

    @Override
    public Optional<String> canonicalEntityRole(String configuredName) {
        if (configuredName == null) return Optional.empty();
        for (String r : concat(roles, platformRoles)) {
            if (r.equalsIgnoreCase(configuredName)) return Optional.of(r);
        }
        return Optional.empty();
    }

    @Override
    public boolean isPlatformRole(String canonicalRole) {
        return platformRoles.contains(canonicalRole);
    }

    private static Set<String> names(List<Object> list, String field) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || !ROLE.matcher(s).matches()) {
                throw invalid(field + " must hold role names (a letter, then up to 99 of A-Z a-z 0-9 _ . -)");
            }
            out.add(s);
        }
        return out;
    }

    private static List<String> concat(Set<String> a, Set<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static SetupException invalid(String cause) {
        return new SetupException(SetupRefusal.SETUP_PRODUCT_ROLES_INVALID,
                "The product-roles file is not valid: " + cause + ".");
    }
}
