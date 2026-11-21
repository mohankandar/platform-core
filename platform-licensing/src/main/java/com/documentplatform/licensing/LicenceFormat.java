package com.documentplatform.licensing;

import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The licence file format {@value #FORMAT}. Documented in {@code docs/design/licensing-file-format.md}; the Platform
 * Console must produce the same bytes.
 *
 * <pre>
 * {
 *   "format": "platform-licence/1",
 *   "licence": { ...payload... },
 *   "signature": { "algorithm": "Ed25519", "payloadSha256": "&lt;hex&gt;", "value": "&lt;Base64, 64 bytes&gt;" }
 * }
 * </pre>
 *
 * The signed message is the UTF-8 bytes of {@code "platform-licence/1\n" + canonical(payload)} (see {@link Json}).
 * {@code payloadSha256} is the SHA-256 of that same message; it is not a security control (the signature is) but it
 * tells an edited payload ({@code LICENCE_TAMPERED}) from a signature not made by the key ({@code LICENCE_FORGED}).
 */
public final class LicenceFormat {

    public static final String FORMAT = "platform-licence/1";

    static final Set<String> ENVELOPE = Set.of("format", "licence", "signature");
    static final Set<String> SIGNATURE = Set.of("algorithm", "payloadSha256", "value");
    static final Set<String> PAYLOAD = Set.of("licenceId", "version", "customer", "tenantId", "products",
            "entitySlots", "type", "kind", "startsOn", "expiresOn", "updatesUntil", "deployment", "keyId", "issuedAt");
    static final Set<String> SLOT = Set.of("slotId", "allowances");
    static final Set<String> ALLOWANCE = Set.of("product", "type", "amount");

    private LicenceFormat() {
    }

    /** The payload object of a licence. */
    public static Map<String, Object> payload(Licence l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("licenceId", l.licenceId());
        m.put("version", l.version());
        m.put("customer", l.customer());
        m.put("tenantId", l.tenantId());
        m.put("products", l.products());
        List<Object> slots = new ArrayList<>();
        for (EntitySlot s : l.entitySlots()) {
            List<Object> allowances = new ArrayList<>();
            for (Allowance a : s.allowances()) {
                allowances.add(Map.of("product", a.product(), "type", a.type().name(), "amount", a.amount()));
            }
            slots.add(Map.of("slotId", s.slotId(), "allowances", allowances));
        }
        m.put("entitySlots", slots);
        m.put("type", l.type().name());
        m.put("kind", l.kind().name());
        m.put("startsOn", l.startsOn().toString());
        if (l.expiresOn() != null) {
            m.put("expiresOn", l.expiresOn().toString());
        }
        if (l.updatesUntil() != null) {
            m.put("updatesUntil", l.updatesUntil().toString());
        }
        m.put("deployment", l.deployment().name());
        m.put("keyId", l.keyId());
        m.put("issuedAt", l.issuedAt().toString());
        return m;
    }

    /** The exact bytes that are signed. */
    public static byte[] signedMessage(Object payload) {
        return (FORMAT + "\n" + Json.canonical(payload)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a payload. Strict: unknown fields, missing fields and values outside the model are refused.
     *
     * @throws JsonException         for a field of the wrong shape
     * @throws IllegalArgumentException for content the model refuses
     */
    public static Licence fromPayload(Fields p) {
        p.only(PAYLOAD);
        List<String> products = new ArrayList<>();
        for (Object o : p.list("products")) {
            if (!(o instanceof String s)) {
                throw new JsonException("licence.products must hold strings");
            }
            products.add(s);
        }
        List<EntitySlot> slots = new ArrayList<>();
        for (Object o : p.list("entitySlots")) {
            Fields s = Fields.of(o, "licence.entitySlots[]").only(SLOT);
            List<Allowance> allowances = new ArrayList<>();
            for (Object a : s.list("allowances")) {
                Fields af = Fields.of(a, "licence.entitySlots[].allowances[]").only(ALLOWANCE);
                allowances.add(new Allowance(af.string("product"),
                        enumValue(AllowanceType.class, af.string("type"), "allowance type"), af.integer("amount")));
            }
            slots.add(new EntitySlot(s.string("slotId"), allowances));
        }
        return new Licence(
                p.string("licenceId"),
                p.integer("version"),
                p.string("customer"),
                p.string("tenantId"),
                products,
                slots,
                enumValue(LicenceType.class, p.string("type"), "type"),
                enumValue(LicenceKind.class, p.string("kind"), "kind"),
                date(p.string("startsOn"), "startsOn"),
                p.has("expiresOn") ? date(p.string("expiresOn"), "expiresOn") : null,
                p.has("updatesUntil") ? date(p.string("updatesUntil"), "updatesUntil") : null,
                enumValue(Deployment.class, p.string("deployment"), "deployment"),
                p.string("keyId"),
                instant(p.string("issuedAt")));
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String name, String what) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " is not one of " + List.of(type.getEnumConstants()));
        }
    }

    static LocalDate date(String text, String what) {
        try {
            LocalDate d = LocalDate.parse(text);
            if (!d.toString().equals(text)) {
                throw new IllegalArgumentException(what + " must be a date written YYYY-MM-DD");
            }
            return d;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " must be a date written YYYY-MM-DD");
        }
    }

    static Instant instant(String text) {
        try {
            Instant i = Instant.parse(text);
            if (!i.toString().equals(text)) {
                throw new IllegalArgumentException("issuedAt must be a UTC time written as Instant.toString()");
            }
            return i;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("issuedAt must be a UTC time such as 2026-11-21T10:00:00Z");
        }
    }
}
