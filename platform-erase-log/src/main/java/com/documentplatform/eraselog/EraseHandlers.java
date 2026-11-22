package com.documentplatform.eraselog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The handlers the app registered, one per kind. */
public final class EraseHandlers {

    private final Map<String, EraseHandler> byKind;

    private EraseHandlers(Map<String, EraseHandler> byKind) {
        this.byKind = Map.copyOf(byKind);
    }

    /** @throws IllegalArgumentException for two handlers of one kind, or a kind not of the kind form */
    public static EraseHandlers of(Collection<? extends EraseHandler> handlers) {
        Map<String, EraseHandler> m = new LinkedHashMap<>();
        for (EraseHandler h : handlers) {
            String kind = h.kind();
            if (kind == null || !EraseRequest.KIND.matcher(kind).matches()) {
                throw new IllegalArgumentException("A handler kind is 1 to 40 of a-z 0-9 - starting with a letter");
            }
            if (m.put(kind, h) != null) {
                throw new IllegalArgumentException("Two erase handlers for kind " + kind);
            }
        }
        return new EraseHandlers(m);
    }

    public static EraseHandlers of(EraseHandler... handlers) {
        return of(java.util.List.of(handlers));
    }

    public Optional<EraseHandler> find(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    EraseHandler require(EraseEntry entry) {
        return find(entry.kind()).orElseThrow(() -> new EraseLogException(EraseLogError.ERASE_HANDLER_MISSING,
                "no handler is registered for kind " + entry.kind() + " (entry " + entry.seq() + ")"));
    }

    public Set<String> kinds() {
        return byKind.keySet();
    }
}
