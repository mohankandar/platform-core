package com.documentplatform.integration.config;

import com.documentplatform.integration.internal.SlotIds;
import com.documentplatform.notifications.secret.SecretRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The naming rule for an integration's secret references (P044, SEC-10): every secret an integration names — sign-in
 * passwords, keys, tokens and client credentials, a callback's signing secret, a keystore's password — is kept under
 * its own entity:
 *
 * <pre>int/&lt;entity&gt;/&lt;name&gt;</pre>
 *
 * where {@code <entity>} is the integration's entity UUID (lower case) or its licence slot ID ({@code ENT-…}), for
 * example {@code int/ENT-0001/api-client-secret}. A reference under another entity, or under no entity, is refused at
 * save ({@code INT_CONFIG_SECRET_SCOPE}, naming the field) and at use, before the secret store is asked; so one
 * entity's integration can never name, and send, another entity's secret. Entity slot IDs are unique across customers
 * (they come from the licence), so this also keeps customers apart on a shared service.
 */
public final class SecretScope {

    /** The first segment of every integration secret reference. */
    public static final String ROOT = "int";

    private SecretScope() {
    }

    /** Whether {@code ref} is under {@code entity}: {@code int/<entity UUID>/…} or {@code int/<slot ID>/…}. */
    public static boolean allows(UUID entity, SecretRef ref) {
        if (entity == null || ref == null) return false;
        String[] parts = ref.value().split("/", 3);
        if (parts.length < 3 || !ROOT.equals(parts[0]) || parts[2].isEmpty()) return false;
        String scope = parts[1];
        if (scope.equals(entity.toString())) return true;
        return SlotIds.isSlotId(scope) && SlotIds.entityUuid(scope).equals(entity);
    }

    /** The reference for {@code name} under {@code entity} by its UUID: {@code int/<entity UUID>/<name>}. */
    public static SecretRef of(UUID entity, String name) {
        return SecretRef.of(ROOT + "/" + entity + "/" + name);
    }

    /** Every secret reference in {@code c}, by the field that holds it. */
    public static Map<String, SecretRef> references(IntegrationConfig c) {
        Map<String, SecretRef> out = new java.util.LinkedHashMap<>();
        signIn(c.signIn(), "signIn", out);
        if (c.callback() != null && c.callback().signingSecret() != null) {
            out.put("callback.signingSecret", c.callback().signingSecret());
        }
        return out;
    }

    /** The fields of {@code c} whose secret reference is not under its entity. */
    public static List<String> outOfScope(IntegrationConfig c) {
        List<String> out = new ArrayList<>();
        references(c).forEach((field, ref) -> {
            if (!allows(c.entity(), ref)) out.add(field);
        });
        return out;
    }

    private static void signIn(SignIn s, String at, Map<String, SecretRef> out) {
        switch (s) {
            case null -> {
            }
            case SignIn.Basic b -> out.put(at + ".password", b.password());
            case SignIn.ApiKey k -> out.put(at + ".key", k.key());
            case SignIn.BearerFixed t -> out.put(at + ".token", t.token());
            case SignIn.OAuth2ClientCredentials o -> {
                if (o.credential() != null) out.put(at + ".credential", o.credential());
            }
            case SignIn.MutualTls m -> signIn(m.with(), at + ".with", out);
            default -> {
            }
        }
    }
}
