package com.documentplatform.integration.intake;

/**
 * Where API polling is in the source's list, kept by the app between passes (one text value). {@link #START} means
 * the first page. The value is the source's own cursor, the next page's address or a page number, as the paging
 * says; it is not a secret, but it is kept out of logs.
 *
 * @param position the position, or null for the start
 */
public record CursorState(String position) {

    public static final CursorState START = new CursorState(null);
    public static final int MAX = 4000;

    public CursorState {
        if (position != null && (position.isEmpty() || position.length() > MAX
                || position.chars().anyMatch(c -> c < 0x20 || c == 0x7f))) {
            throw new IllegalArgumentException("A cursor state is 1-" + MAX + " characters without control characters");
        }
    }

    public static CursorState of(String position) {
        return position == null ? START : new CursorState(position);
    }

    public boolean atStart() {
        return position == null;
    }

    @Override
    public String toString() {
        return atStart() ? "CursorState[start]" : "CursorState[set]";
    }
}
