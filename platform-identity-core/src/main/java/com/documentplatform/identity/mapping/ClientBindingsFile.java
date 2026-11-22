package com.documentplatform.identity.mapping;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.MachineBinding;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads an integration client-binding file, {@code client-bindings-<product>.csv} ({@code docs/design/setup.md} 6.1):
 * UTF-8, comma-separated, one product per file, exact header {@value #HEADER}, one binding per line.
 *
 * <ul>
 *   <li>{@code client_id}: 1–255 characters, no spaces, commas, quotes or control characters (compared as given)</li>
 *   <li>{@code entity_slot}: a licence slot ID ({@code ENT-…})</li>
 *   <li>{@code source}: {@code [a-z0-9][a-z0-9._-]{0,63}}, listed under the product's {@code sources}</li>
 *   <li>{@code description}: optional, at most 200 characters</li>
 * </ul>
 *
 * Spreadsheet output is accepted: a byte-order mark, CRLF or LF line ends, empty lines, and RFC 4180 quoted values
 * (for a description with a comma). There are no comment lines: a line starting with {@code #} is a binding like any
 * other. Each client ID appears once. Values that look like a secret are refused: the file holds client IDs only.
 * Every problem is reported with its line number and a code; messages never repeat a value that looks like a secret.
 */
public final class ClientBindingsFile {

    public static final String HEADER = "client_id,entity_slot,source,description";

    /** One problem, at a line (1-based; 0 for the whole file). */
    public record Problem(int line, Code code, String text) {
        public String display() {
            return (line > 0 ? "line " + line + ": " : "") + code + ": " + text;
        }
    }

    public enum Code {
        NOT_UTF8, HEADER_WRONG, BAD_QUOTES, WRONG_COLUMN_COUNT, CLIENT_ID_INVALID, SLOT_INVALID, SOURCE_INVALID,
        DESCRIPTION_INVALID, SECRET_LIKE, DUPLICATE_CLIENT_ID, SLOT_UNKNOWN, SOURCE_UNKNOWN, CLIENT_BOUND_ELSEWHERE
    }

    /** One binding line. {@code description} is null when the column is empty. */
    public record Row(int line, String clientId, String slotId, String source, String description) {
    }

    /** The rows when there are no errors; otherwise the errors (and the rows that were readable). */
    public record Result(List<Row> rows, List<Problem> errors) {
        public Result {
            rows = List.copyOf(rows);
            errors = List.copyOf(errors);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.");
    private static final Pattern SECRET_WORD = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|private[_-]?key|bearer|authorization)\\s*[:=]");
    private static final Pattern LONG_RUN = Pattern.compile("[A-Za-z0-9+/=_-]{40,}");

    private ClientBindingsFile() {
    }

    /**
     * Reads the file.
     *
     * @param sources the source keys the sign-in file lists for the product
     */
    public static Result read(byte[] content, Set<String> sources) {
        List<Row> rows = new ArrayList<>();
        List<Problem> errors = new ArrayList<>();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            errors.add(new Problem(0, Code.NOT_UTF8, "the file is not UTF-8 text; save it as \"CSV UTF-8\""));
            return new Result(rows, errors);
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\r\n|\n|\r", -1);
        if (lines.length == 0 || !lines[0].equals(HEADER)) {
            errors.add(new Problem(1, Code.HEADER_WRONG, "the first line must be exactly " + HEADER));
            return new Result(rows, errors);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int line = i + 1;
            if (lines[i].isEmpty()) continue;
            List<String> f = fields(lines[i]);
            if (f == null) {
                errors.add(new Problem(line, Code.BAD_QUOTES, "quotes are not closed or a quoted value is followed by "
                        + "more text"));
                continue;
            }
            if (f.size() != 4) {
                errors.add(new Problem(line, Code.WRONG_COLUMN_COUNT, "4 columns expected (" + HEADER + "), found "
                        + f.size()));
                continue;
            }
            String client = f.get(0);
            String slot = f.get(1);
            String source = f.get(2);
            String description = f.get(3).isEmpty() ? null : f.get(3);
            int before = errors.size();
            if (looksSecret(client) || (description != null && looksSecret(description))) {
                errors.add(new Problem(line, Code.SECRET_LIKE, "a value looks like a secret (a token, key or password); "
                        + "the file holds client IDs only, never secrets"));
                continue;
            }
            if (!MachineBinding.CLIENT_ID.matcher(client).matches()) {
                errors.add(new Problem(line, Code.CLIENT_ID_INVALID, "client_id must be 1 to 255 characters without "
                        + "spaces, commas, quotes or control characters"));
            }
            if (!EntityUuid.isSlotId(slot)) {
                errors.add(new Problem(line, Code.SLOT_INVALID, "entity_slot must be a slot ID: ENT- followed by 1 to 60 "
                        + "of A-Z 0-9 -"));
            }
            if (!MachineBinding.SOURCE_KEY.matcher(source).matches()) {
                errors.add(new Problem(line, Code.SOURCE_INVALID, "source must be 1 to 64 of a-z 0-9 . _ - starting with a "
                        + "letter or digit"));
            } else if (!sources.contains(source)) {
                errors.add(new Problem(line, Code.SOURCE_UNKNOWN, "source \"" + source + "\" is not listed under the "
                        + "product's \"sources\" in the sign-in file"));
            }
            if (description != null && (description.length() > MachineBinding.DESCRIPTION_MAX
                    || description.chars().anyMatch(Character::isISOControl))) {
                errors.add(new Problem(line, Code.DESCRIPTION_INVALID, "description must be at most 200 characters "
                        + "without control characters"));
            }
            if (errors.size() > before) continue;
            Integer first = seen.putIfAbsent(client, line);
            if (first != null) {
                errors.add(new Problem(line, Code.DUPLICATE_CLIENT_ID, "client \"" + client + "\" is already on line "
                        + first + "; each client ID binds to exactly one entity and one source"));
                continue;
            }
            rows.add(new Row(line, client, slot, source, description));
        }
        return new Result(rows, errors);
    }

    /** RFC 4180 fields of one line; null when the quotes are broken. Nothing is trimmed. */
    private static List<String> fields(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (true) {
            if (i < n && s.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && s.charAt(i + 1) == '"') {
                            b.append('"');
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        b.append(c);
                        i++;
                    }
                }
                if (!closed || (i < n && s.charAt(i) != ',')) return null;
            } else {
                while (i < n && s.charAt(i) != ',') {
                    if (s.charAt(i) == '"') return null;
                    b.append(s.charAt(i++));
                }
            }
            out.add(b.toString());
            b.setLength(0);
            if (i < n && s.charAt(i) == ',') {
                i++;
            } else {
                return out;
            }
        }
    }

    /**
     * A token (JWT), a PEM key, "password=…"-style text, or a long run of key-like characters (40 or more of
     * A-Z a-z 0-9 + / = _ - with upper case, lower case and digits mixed). Client IDs of the common login systems
     * (Okta, Entra GUIDs, Auth0, Keycloak names) are shorter or not mixed like that.
     */
    static boolean looksSecret(String v) {
        if (JWT.matcher(v).find() || v.contains("-----BEGIN") || SECRET_WORD.matcher(v).find()) return true;
        var m = LONG_RUN.matcher(v);
        while (m.find()) {
            String run = m.group();
            if (run.chars().anyMatch(Character::isUpperCase) && run.chars().anyMatch(Character::isLowerCase)
                    && run.chars().anyMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }
}
