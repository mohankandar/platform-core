package com.documentplatform.licensing.usage;

import java.util.Optional;

/**
 * The result of verifying one usage export file or a sequence of them.
 *
 * @param fileIndex   in a sequence, the 0-based index of the file found wrong (0 for a single file)
 * @param recordIndex the 0-based index of the record found wrong, or -1 when the problem is not one record
 * @param nextAnchor  when ok: the anchor after the last file checked
 */
public record ExportCheck(boolean ok, Problem problem, int fileIndex, int recordIndex, String message,
                          ExportAnchor nextAnchor) {

    /** Stable codes. */
    public enum Problem implements com.documentplatform.errors.PlatformCode {
        /** Not a readable usage export (bad JSON, missing or unknown fields). */
        EXPORT_MALFORMED,
        /** A format this version does not read. */
        EXPORT_UNSUPPORTED_FORMAT,
        /** The file does not start from the previous file's final chain value: a file is missing or was replaced. */
        EXPORT_START_MISMATCH,
        /** The sequence number is not the previous one plus 1: a file is missing or repeated. */
        EXPORT_SEQUENCE_GAP,
        /** The first month is not the month after the previous file's last month: months are missing or overlap. */
        EXPORT_PERIOD_GAP,
        /** The file belongs to a different tenant from the files before it. */
        EXPORT_TENANT_MISMATCH,
        /** The header was changed after export. */
        EXPORT_HEADER_EDITED,
        /** A record was changed, removed or inserted after export. */
        EXPORT_RECORD_EDITED,
        /** A record's month is outside the file's months. */
        EXPORT_RECORD_OUTSIDE_PERIOD,
        /** The footer does not match the records (count or final value): records were removed at the end or the footer edited. */
        EXPORT_FOOTER_MISMATCH
    }

    static ExportCheck ok(ExportAnchor next, String message) {
        return new ExportCheck(true, null, 0, -1, message, next);
    }

    static ExportCheck fail(Problem problem, int recordIndex, String message) {
        return new ExportCheck(false, problem, 0, recordIndex, problem.name() + ": " + message, null);
    }

    ExportCheck inFile(int index) {
        return new ExportCheck(ok, problem, index, recordIndex,
                ok ? message : "File " + (index + 1) + ": " + message, nextAnchor);
    }

    public Optional<Problem> problemCode() {
        return Optional.ofNullable(problem);
    }
}
