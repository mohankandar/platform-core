package com.documentplatform.identity.mapping;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityRoleCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and validates a role mapping file: one row per grant, {@code entity_id,entity_name,group,role}. The whole
 * file is checked before anything is written; any error refuses the whole file, and every error names its line.
 * Values are never trimmed or repaired: a group that almost matches is a sign-in fault nobody can see, so invisible
 * differences (leading or trailing spaces, non-breaking spaces, control characters) refuse the file.
 *
 * <p>{@code entity_id} is the entity's UUID, or, when the file is read with {@link SlotTargets}, the entity's slot ID
 * from the licence ({@code ENT-…}); the row's entity is then the UUID derived from the slot ID
 * ({@link EntityUuid#fromSlotId}). For a slot ID, {@code entity_name} may be left empty (naming belongs to the
 * Business Admin); when given it must match. When the file is read for a product code, a {@code # Product:} comment
 * naming another product refuses the file, and every row carries the product code.
 */
public final class RoleMappingFile {

    public static final List<String> HEADER = List.of("entity_id", "entity_name", "group", "role");
    /** Longest group name accepted; the identity claim reader refuses longer values. */
    public static final int MAX_GROUP_LENGTH = 200;
    private static final Pattern FORMAT_COMMENT = Pattern.compile("^\\s*Format:\\s*(.*?)\\s*$");
    private static final Pattern PRODUCT_COMMENT = Pattern.compile("^\\s*Product:\\s*(.*?)\\s*$");

    /** The result: either {@code rows} (valid file) or {@code errors} (refused file), never both. */
    public record Result(List<MappingRow> rows, List<MappingError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private final String formatId;
    private final String productCode;
    private final EntityRoleCatalog roles;
    private final MappingTargets targets;
    private final SlotTargets slots;

    /**
     * Reads files that name entities by UUID, without a product code.
     *
     * @param formatId the product's format name, for example {@code product-role-mapping/1}; a {@code # Format:}
     *                 comment naming anything else refuses the file (no such comment is accepted)
     */
    public RoleMappingFile(String formatId, EntityRoleCatalog roles, MappingTargets targets) {
        this(formatId, null, roles, Objects.requireNonNull(targets, "targets"), null);
    }

    /**
     * Reads files for one product that name entities by UUID ({@code targets}), by slot ID ({@code slots}), or both.
     *
     * @param productCode the product the grants are for (as in the licence), or null for none
     * @param targets     answers for UUIDs; null when only slot IDs are accepted
     * @param slots       answers for slot IDs; null when only UUIDs are accepted
     */
    public RoleMappingFile(String formatId, String productCode, EntityRoleCatalog roles, MappingTargets targets,
                           SlotTargets slots) {
        this.formatId = Objects.requireNonNull(formatId, "formatId");
        this.productCode = productCode;
        this.roles = Objects.requireNonNull(roles, "roles");
        if (targets == null && slots == null) throw new IllegalArgumentException("targets or slots is required");
        this.targets = targets;
        this.slots = slots;
    }

    /** Reads files for one product that name entities by slot ID only (the setup command's input). */
    public static RoleMappingFile forSlots(String formatId, String productCode, EntityRoleCatalog roles,
                                           SlotTargets slots) {
        return new RoleMappingFile(formatId, Objects.requireNonNull(productCode, "productCode"), roles, null,
                Objects.requireNonNull(slots, "slots"));
    }

    public Result read(byte[] content) {
        MappingCsv.Parsed parsed = MappingCsv.parse(Objects.requireNonNull(content, "content"));
        List<MappingError> errors = new ArrayList<>(parsed.errors());

        for (MappingCsv.Comment comment : parsed.comments()) {
            Matcher format = FORMAT_COMMENT.matcher(comment.text());
            if (format.matches() && !format.group(1).equals(formatId)) {
                errors.add(new MappingError(comment.line(), MappingError.Code.UNSUPPORTED_FORMAT,
                        "this file is format \"" + format.group(1) + "\"; this version reads only \"" + formatId + "\""));
            }
            Matcher product = PRODUCT_COMMENT.matcher(comment.text());
            if (productCode != null && product.matches() && !product.group(1).equals(productCode)) {
                errors.add(new MappingError(comment.line(), MappingError.Code.WRONG_PRODUCT,
                        "this file is for product \"" + product.group(1) + "\"; it is being read for \"" + productCode + "\""));
            }
        }

        List<MappingCsv.Record> records = parsed.records();
        if (records.isEmpty()) {
            if (errors.isEmpty()) errors.add(new MappingError(0, MappingError.Code.BAD_HEADER,
                    "the file is empty; the first line must be " + String.join(",", HEADER)));
            return new Result(List.of(), List.copyOf(errors));
        }
        MappingCsv.Record header = records.get(0);
        if (!header.fields().equals(HEADER)) {
            errors.add(new MappingError(header.line(), MappingError.Code.BAD_HEADER,
                    "the first line must be exactly " + String.join(",", HEADER)));
            return new Result(List.of(), List.copyOf(errors));
        }

        List<MappingRow> rows = new ArrayList<>();
        Map<EntityId, String> namesInFile = new HashMap<>();
        Map<EntityId, Integer> nameLines = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        Map<EntityId, Optional<MappingTargets.Target>> described = new HashMap<>();
        Map<String, Optional<SlotTargets.Slot>> describedSlots = new HashMap<>();

        for (MappingCsv.Record record : records.subList(1, records.size())) {
            int line = record.line();
            List<String> f = record.fields();
            if (f.size() != HEADER.size()) {
                errors.add(new MappingError(line, MappingError.Code.WRONG_COLUMN_COUNT,
                        "expected 4 values (entity_id,entity_name,group,role), found " + f.size()));
                continue;
            }
            boolean rowOk = true;
            boolean slotRow = slots != null && EntityUuid.isSlotId(f.get(0));
            for (int c = 0; c < f.size(); c++) {
                String value = f.get(c);
                if (value.isEmpty() && c == 1 && slotRow) continue; // the name is optional for a slot ID
                if (value.isEmpty()) {
                    errors.add(new MappingError(line, MappingError.Code.BLANK_VALUE, HEADER.get(c) + " is empty"));
                    rowOk = false;
                } else {
                    Optional<String> invisible = invisibleProblem(value);
                    if (invisible.isPresent()) {
                        errors.add(new MappingError(line, MappingError.Code.INVISIBLE_CHARACTER,
                                HEADER.get(c) + " \"" + printable(value) + "\" " + invisible.get()));
                        rowOk = false;
                    }
                }
            }
            if (!rowOk) continue;

            String rawId = f.get(0), name = f.get(1), group = f.get(2), role = f.get(3);
            EntityId entity;
            Optional<MappingTargets.Target> target = Optional.empty();
            if (slotRow) {
                entity = EntityId.of(EntityUuid.fromSlotId(rawId));
                if (!checkSlot(line, rawId, name, describedSlots.computeIfAbsent(rawId, slots::describe), errors)) {
                    rowOk = false;
                }
            } else {
                try {
                    entity = EntityId.of(UUID.fromString(rawId));
                } catch (IllegalArgumentException notUuid) {
                    errors.add(new MappingError(line, MappingError.Code.NOT_AN_ENTITY_ID, "entity_id \"" + rawId + "\" is not an entity id"));
                    continue;
                }
                if (targets == null) {
                    errors.add(new MappingError(line, MappingError.Code.SLOT_ID_REQUIRED,
                            "entity_id must be the entity's slot ID from the licence (ENT-...), not " + rawId));
                    continue;
                }
                target = described.computeIfAbsent(entity, targets::describe);
            }

            if (slotRow) {
                // checked by checkSlot
            } else if (target.isEmpty()) {
                errors.add(new MappingError(line, MappingError.Code.UNKNOWN_ENTITY, "entity " + rawId + " does not exist in this installation"));
                rowOk = false;
            } else {
                if (!target.get().active()) {
                    errors.add(new MappingError(line, MappingError.Code.ENTITY_NOT_ACTIVE, "entity \"" + target.get().name() + "\" is not active"));
                    rowOk = false;
                }
                if (!target.get().name().equals(name)) {
                    errors.add(new MappingError(line, MappingError.Code.ENTITY_NAME_MISMATCH,
                            "entity_name \"" + name + "\" does not match the entity's name \"" + target.get().name() + "\""));
                    rowOk = false;
                }
                if (!target.get().activeLoginConnection()) {
                    errors.add(new MappingError(line, MappingError.Code.NO_ACTIVE_LOGIN_SYSTEM,
                            "entity \"" + target.get().name() + "\" has no active login system, so its grants could never apply"));
                    rowOk = false;
                }
            }
            String earlierName = namesInFile.putIfAbsent(entity, name);
            if (earlierName != null && !earlierName.equals(name)) {
                errors.add(new MappingError(line, MappingError.Code.CONFLICTING_ENTITY_NAMES,
                        "entity " + rawId + " is named \"" + name + "\" here but \"" + earlierName + "\" on line " + nameLines.get(entity)));
                rowOk = false;
            }
            nameLines.putIfAbsent(entity, line);

            String alias = null;
            Optional<String> aliased = roles.aliasTarget(role);
            if (aliased.isPresent()) {
                alias = role;
                role = aliased.get();
            }
            Optional<String> canonical = roles.canonicalEntityRole(role);
            if (canonical.isEmpty()) {
                errors.add(new MappingError(line, MappingError.Code.UNKNOWN_ROLE, "unknown role \"" + role + "\""));
                rowOk = false;
            } else if (!canonical.get().equals(role)) {
                errors.add(new MappingError(line, MappingError.Code.ROLE_NOT_WRITTEN_CANONICALLY,
                        "role \"" + role + "\" must be written exactly as \"" + canonical.get() + "\""));
                rowOk = false;
            } else if (roles.isPlatformRole(role)) {
                errors.add(new MappingError(line, MappingError.Code.PLATFORM_ROLE,
                        "role \"" + role + "\" is the platform role; it is set for the installation, never in this file"));
                rowOk = false;
            }

            if (group.length() > MAX_GROUP_LENGTH) {
                errors.add(new MappingError(line, MappingError.Code.GROUP_TOO_LONG,
                        "group is " + group.length() + " characters; the most accepted is " + MAX_GROUP_LENGTH));
                rowOk = false;
            }

            String key = entity + "\u0000" + group + "\u0000" + role;
            Integer firstLine = seen.putIfAbsent(key, line);
            if (firstLine != null) {
                errors.add(new MappingError(line, MappingError.Code.DUPLICATE_ROW, "the same row as line " + firstLine));
                rowOk = false;
            }
            if (rowOk) {
                rows.add(new MappingRow(line, entity, name.isEmpty() ? null : name, group, role,
                        slotRow ? rawId : null, productCode, alias));
            }
        }
        if (records.size() == 1 && errors.isEmpty()) {
            errors.add(new MappingError(0, MappingError.Code.NO_ROWS,
                    "the file has a header but no rows; an entity's mappings are changed only through its rows"));
        }
        return errors.isEmpty() ? new Result(List.copyOf(rows), List.of()) : new Result(List.of(), List.copyOf(errors));
    }

    private static boolean checkSlot(int line, String slotId, String name, Optional<SlotTargets.Slot> slot,
                                     List<MappingError> errors) {
        if (slot.isEmpty()) {
            errors.add(new MappingError(line, MappingError.Code.UNKNOWN_ENTITY,
                    "entity slot " + slotId + " is not in the licence"));
            return false;
        }
        boolean ok = true;
        if (!slot.get().inLicence()) {
            errors.add(new MappingError(line, MappingError.Code.SLOT_NOT_IN_LICENCE,
                    "entity slot " + slotId + " is not in the licence in force"));
            ok = false;
        }
        if (!name.isEmpty()) {
            String actual = slot.get().name();
            if (actual == null) {
                errors.add(new MappingError(line, MappingError.Code.ENTITY_NAME_MISMATCH, "entity slot " + slotId
                        + " has no name yet; leave entity_name empty (the Business Admin names the entity)"));
                ok = false;
            } else if (!actual.equals(name)) {
                errors.add(new MappingError(line, MappingError.Code.ENTITY_NAME_MISMATCH,
                        "entity_name \"" + name + "\" does not match the entity's name \"" + actual + "\""));
                ok = false;
            }
        }
        return ok;
    }

    /** Leading or trailing spaces, non-breaking or zero-width spaces, and control characters are refused. */
    static Optional<String> invisibleProblem(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return Optional.of("contains a control character (code " + (int) c + ")");
            if (c == '\u00A0' || c == '\u2007' || c == '\u202F') return Optional.of("contains a non-breaking space");
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u2060' || c == '\uFEFF') {
                return Optional.of("contains an invisible zero-width character");
            }
        }
        if (Character.isWhitespace(value.charAt(0)) || Character.isSpaceChar(value.charAt(0))) {
            return Optional.of("starts with a space");
        }
        char last = value.charAt(value.length() - 1);
        if (Character.isWhitespace(last) || Character.isSpaceChar(last)) return Optional.of("ends with a space");
        return Optional.empty();
    }

    private static String printable(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isISOControl(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F' || (c >= '\u200B' && c <= '\u200D') || c == '\u2060' || c == '\uFEFF') {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
