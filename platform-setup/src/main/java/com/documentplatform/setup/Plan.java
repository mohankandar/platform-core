package com.documentplatform.setup;

import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.LoginConnection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What setup will do (or did): one item per record, in the order it is applied. {@link #items()} is what the person
 * sees before anything is written; the other fields are what {@link SetupRun#apply} writes.
 */
public final class Plan {

    public enum Action {
        CREATE("create"),
        UNCHANGED("unchanged"),
        UPDATE("update"),
        /** Changed in the app since the last setup: left as it is unless --replace-connection is given. */
        CONFLICT("conflict"),
        /** A grant not in the mapping file, removed because --remove-unlisted-grants was given. */
        REMOVE("remove"),
        /** A grant not in the mapping file, kept because --remove-unlisted-grants was not given. */
        NOT_REMOVED("not removed");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The item is not done without a flag (exit code 3). */
        public boolean needsFlag() {
            return this == CONFLICT || this == NOT_REMOVED;
        }
    }

    /** One line of the plan: the action and what it is about (IDs, product, group and role; never secrets). */
    public record Item(Action action, String text) {
        public String line() {
            return String.format("  %-11s %s", action.label(), text);
        }
    }

    enum ConnectionStep { NONE, INSERT, UPDATE }

    private final List<Item> items = new ArrayList<>();

    // What apply writes.
    Set<String> licensedSlots = Set.of();
    ConnectionStep connectionStep = ConnectionStep.NONE;
    LoginConnection connection;
    long connectionExpectedVersion;
    final Map<String, Set<Grant>> grantsToAdd = new TreeMap<>();
    final Map<String, Set<Grant>> grantsToRemove = new TreeMap<>();
    ConfigFragment.State config;

    void add(Action action, String text) {
        items.add(new Item(action, text));
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    /** Items that are not done without a flag. */
    public boolean needsFlag() {
        return items.stream().anyMatch(i -> i.action().needsFlag());
    }

    public Map<Action, Integer> counts() {
        Map<Action, Integer> out = new EnumMap<>(Action.class);
        for (Action a : Action.values()) out.put(a, 0);
        for (Item i : items) out.merge(i.action(), 1, Integer::sum);
        return out;
    }
}
