package com.documentplatform.setup;

import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The product's role list ({@value #FORMAT}), shipped by each product in its distribution. Setup reads it for the
 * product given with {@code --product}; the command itself knows no product and no role names.
 *
 * <pre>
 * {"format": "platform-product-roles/1", "productCode": "PRODUCT_A",
 *  "roles": ["READER", "EDITOR"], "platformRoles": ["PLATFORM_ADMIN"],
 *  "aliases": {"Reviewer": "READER"}, "customerKeys": "required"}
 * </pre>
 *
 * {@code roles} may be granted to groups per entity; {@code platformRoles} (optional) reach platform-wide screens and
 * are granted for the customer and product only on a mapping row with {@code entity_id} {@code *} (since P028), never
 * on an entity's row ({@code SETUP_PLATFORM_ROLE_ON_ENTITY}); a {@code *} row takes only platform roles
 * ({@code SETUP_ENTITY_ROLE_PLATFORM_WIDE}). A role written in another case is refused with its canonical spelling.
 *
 * <p>{@code aliases} (optional): other names for roles, for example the role names a customer's identity system uses.
 * A role mapping file may use an alias; it is resolved when the file is read and stored as the role (the plan shows
 * {@code alias -> role}). An alias may not equal a role or another alias (ignoring case), and its target must be one of
 * {@code roles}.
 *
 * <p>{@code customerKeys} (optional): {@code "required"} when the product keeps sensitive values and needs the
 * customer's keys, so {@code --no-customer-keys} is refused (SETUP_CUSTOMER_KEYS_REQUIRED); {@code "optional"} (the
 * default when missing) allows it. Files without these fields load as before.
 */
final class ProductRoles implements EntityRoleCatalog {

    public static final String FORMAT = "platform-product-roles/1";
    private static final Pattern ROLE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");
    /** Most aliases one file may give. */
    public static final int MAX_ALIASES = 500;

    private final String productCode;
    private final Set<String> roles;
    private final Set<String> platformRoles;
    private final Map<String, String> aliases;
    private final boolean customerKeysRequired;

    private ProductRoles(String productCode, Set<String> roles, Set<String> platformRoles, Map<String, String> aliases,
                         boolean customerKeysRequired) {
        this.productCode = productCode;
        this.roles = Set.copyOf(roles);
        this.platformRoles = Set.copyOf(platformRoles);
        this.aliases = Map.copyOf(aliases);
        this.customerKeysRequired = customerKeysRequired;
    }

    /** @throws SetupException SETUP_PRODUCT_ROLES_INVALID, naming the field */
    public static ProductRoles parse(String json, String expectedProduct) {
        try {
            Fields f = Fields.of(Json.parse(json), "product-roles file")
                    .only(Set.of("format", "productCode", "roles", "platformRoles", "aliases", "customerKeys"));
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
            Set<String> seen = new HashSet<>();
            for (String r : concat(roles, platform)) {
                if (!seen.add(r.toUpperCase(Locale.ROOT))) throw invalid("role " + r + " is listed twice");
            }
            Map<String, String> aliases = f.has("aliases") ? aliases(f.object("aliases"), roles, seen) : Map.of();
            boolean keysRequired = false;
            if (f.has("customerKeys")) {
                String keys = f.string("customerKeys");
                if (!keys.equals("required") && !keys.equals("optional")) {
                    throw invalid("customerKeys must be \"required\" or \"optional\"");
                }
                keysRequired = keys.equals("required");
            }
            return new ProductRoles(product, roles, platform, aliases, keysRequired);
        } catch (JsonException e) {
            throw invalid(e.getMessage());
        }
    }

    private static Map<String, String> aliases(Fields a, Set<String> roles, Set<String> roleNamesUpper) {
        if (a.map().size() > MAX_ALIASES) throw invalid("aliases gives more than " + MAX_ALIASES + " aliases");
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String alias : a.map().keySet()) {
            if (!ROLE.matcher(alias).matches()) {
                throw invalid("aliases: a name is not a role name (a letter, then up to 99 of A-Z a-z 0-9 _ . -)");
            }
            String upper = alias.toUpperCase(Locale.ROOT);
            if (roleNamesUpper.contains(upper)) throw invalid("alias " + alias + " is also a role");
            if (!seen.add(upper)) throw invalid("alias " + alias + " is given twice");
            String target = a.string(alias);
            if (!roles.contains(target)) {
                throw invalid("alias " + alias + " must stand for one of roles (written exactly), not "
                        + (ROLE.matcher(target).matches() ? target : "that value"));
            }
            out.put(alias, target);
        }
        return out;
    }

    public String productCode() {
        return productCode;
    }

    public Set<String> roles() {
        return roles;
    }

    /** Alias → role. */
    public Map<String, String> aliases() {
        return aliases;
    }

    /** Whether the product needs the customer's keys ({@code "customerKeys": "required"}). */
    public boolean customerKeysRequired() {
        return customerKeysRequired;
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

    @Override
    public Optional<String> aliasTarget(String name) {
        return Optional.ofNullable(name == null ? null : aliases.get(name));
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
