package com.documentplatform.identity.mapping;

/**
 * One reason a role mapping file is refused. {@code line} is the line in the file (1-based), or 0 for the file as a
 * whole. The message is written for the installer who edits the file.
 */
public record MappingError(int line, Code code, String message) {

    public enum Code implements com.documentplatform.errors.PlatformCode {
        NOT_UTF8,
        BAD_QUOTES,
        VALUE_SPANS_LINES,
        UNSUPPORTED_FORMAT,
        BAD_HEADER,
        WRONG_COLUMN_COUNT,
        BLANK_VALUE,
        INVISIBLE_CHARACTER,
        NOT_AN_ENTITY_ID,
        UNKNOWN_ENTITY,
        ENTITY_NOT_ACTIVE,
        ENTITY_NAME_MISMATCH,
        CONFLICTING_ENTITY_NAMES,
        NO_ACTIVE_LOGIN_SYSTEM,
        UNKNOWN_ROLE,
        ROLE_NOT_WRITTEN_CANONICALLY,
        PLATFORM_ROLE,
        GROUP_TOO_LONG,
        DUPLICATE_ROW,
        NO_ROWS,
        /** The file names an entity by UUID where only slot IDs ({@code ENT-…}) are accepted. */
        SLOT_ID_REQUIRED,
        /** The slot is known, but the licence in force does not list it. */
        SLOT_NOT_IN_LICENCE,
        /** A {@code # Product:} comment names another product than the one the file is read for. */
        WRONG_PRODUCT,
        /** A platform role on an entity row: platform roles are granted on a row with entity_id {@code *}. */
        PLATFORM_ROLE_ON_ENTITY,
        /** An entity role on a {@code *} row: a platform-wide row takes only the product's platform roles. */
        ENTITY_ROLE_PLATFORM_WIDE
    }

    /** The message as an installer sees it: {@code line 6: unknown role "DOCUMENT_PROCESOR"}. */
    public String display() {
        return line > 0 ? "line " + line + ": " + message : message;
    }
}
