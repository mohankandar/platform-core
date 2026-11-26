package com.documentplatform.fields;

import com.documentplatform.fields.FieldDefinitionException.Code;
import com.documentplatform.fields.file.FieldImport;
import com.documentplatform.fields.file.FieldListFile;
import com.documentplatform.fields.file.ImportReport;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The app's API for case field definitions, with the rules for changing them:
 *
 * <ul>
 *   <li>A key is used once per entity and product (ignoring capitals) and never changes.</li>
 *   <li>The label, rules and flags may change at any time.</li>
 *   <li>The type (and a MONEY field's currency), the sensitive flag and the primary flag may change only while
 *       no value exists, as the app's {@link FieldValuesCheck} answers; after that, add a new field instead.
 *       Switching a field off still takes away its primary flag (a switched-off field is never primary).</li>
 *   <li>Removing a field switches it off; nothing is ever deleted. Its existing values stay.</li>
 *   <li>Every version is kept in the history, with who made it.</li>
 * </ul>
 *
 * <p>Logs keys, entity ids, versions and user ids only; never a value. Thread-safe if the store is.
 */
public final class FieldDefinitions {

    private static final System.Logger LOG = System.getLogger(FieldDefinitions.class.getName());
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern EXTENSION_NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    public static final int MAX_EXTENSION_LENGTH = 1000;

    private final FieldStore store;
    private final FieldValuesCheck values;
    private final Clock clock;

    /**
     * The app's answer; when its check fails, the change is refused with {@code FIELD_VALUES_CHECK_FAILED} (P037),
     * never let through: a locked property changes only when the app says no value exists.
     */
    private boolean valuesExist(FieldScope scope, FieldKey key) {
        try {
            return values.valuesExist(scope, key);
        } catch (RuntimeException e) {
            throw new FieldDefinitionException(Code.FIELD_VALUES_CHECK_FAILED, "Field " + key + ": the app's "
                    + "values check failed (" + e.getClass().getSimpleName() + "), so the type, sensitive and primary "
                    + "settings were not changed; try again later");
        }
    }

    public FieldDefinitions(FieldStore store, FieldValuesCheck values) {
        this(store, values, Clock.systemUTC());
    }

    public FieldDefinitions(FieldStore store, FieldValuesCheck values, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.values = Objects.requireNonNull(values, "values check");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** STUB P059 */
    public FieldDefinitions in(java.sql.Connection connection) {
        throw new UnsupportedOperationException("stub");
    }

    /** Every field, switched-off ones included, in display order. */
    public List<FieldDefinition> list(FieldScope scope) {
        return store.list(scope);
    }

    /** The fields that are switched on, in display order. */
    public List<FieldDefinition> active(FieldScope scope) {
        return store.list(scope).stream().filter(FieldDefinition::active).toList();
    }

    /** The field list for checking a case's values. */
    public FieldSet fieldSet(FieldScope scope) {
        return FieldSet.ofDefinitions(store.list(scope));
    }

    /** The field with exactly this key. */
    public Optional<FieldDefinition> find(FieldScope scope, FieldKey key) {
        return store.find(scope, key).filter(d -> d.key().equals(key));
    }

    /** Adds a field. */
    public FieldDefinition create(FieldScope scope, FieldSpec spec, String actor) {
        Objects.requireNonNull(spec, "spec");
        actor(actor);
        store.find(scope, spec.key()).ifPresent(d -> {
            throw taken(spec.key());
        });
        FieldDefinition created = store.create(scope, List.of(spec), FieldStore.Change.CREATED, actor, now()).get(0);
        log(created, FieldStore.Change.CREATED, actor);
        return created;
    }

    /**
     * Changes a field to {@code next}, if it is still at {@code expectedVersion}. The key must stay the same. The
     * field stays switched on or off as it is: use {@link #switchOff} and {@link #switchOn} for that. Nothing that
     * did not change makes a new version.
     */
    public FieldDefinition change(FieldScope scope, FieldSpec next, int expectedVersion, String actor) {
        Objects.requireNonNull(next, "next");
        actor(actor);
        Checked c = checked(scope, next, expectedVersion);
        if (c.effective.equals(c.current.spec())) {
            return c.current;
        }
        FieldDefinition changed = store.update(c.current, c.effective, FieldStore.Change.CHANGED, actor, now());
        log(changed, FieldStore.Change.CHANGED, actor);
        return changed;
    }

    /**
     * Saves a list of changes and new fields ({@link FieldChange}), <b>every one or none</b>, in one transaction. Each
     * is checked as {@link #change(FieldScope, FieldSpec, int, String)} and {@link #create} check it; the first refusal
     * is thrown and nothing is saved. A field that does not change makes no new version.
     *
     * @return the fields as they are now, in the order given
     */
    public List<FieldDefinition> change(FieldScope scope, List<FieldChange> changes, String actor) {
        actor(actor);
        List<FieldChange> all = List.copyOf(changes);
        Set<String> keys = new HashSet<>();
        FieldDefinition[] out = new FieldDefinition[all.size()];
        List<Integer> places = new ArrayList<>();
        List<FieldStore.Save> saves = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            FieldChange c = all.get(i);
            if (!keys.add(c.next().key().folded())) {
                throw taken(c.next().key());
            }
            if (c.expectedVersion() == 0) {
                store.find(scope, c.next().key()).ifPresent(d -> {
                    throw taken(c.next().key());
                });
                saves.add(new FieldStore.Save(null, c.next()));
                places.add(i);
                continue;
            }
            Checked k = checked(scope, c.next(), c.expectedVersion());
            out[i] = k.current;
            if (!k.effective.equals(k.current.spec())) {
                saves.add(new FieldStore.Save(k.current, k.effective));
                places.add(i);
            }
        }
        if (!saves.isEmpty()) {
            List<FieldDefinition> saved = store.saveAll(scope, saves, actor, now());
            for (int n = 0; n < saved.size(); n++) {
                out[places.get(n)] = saved.get(n);
                log(saved.get(n), saves.get(n).current() == null ? FieldStore.Change.CREATED
                        : FieldStore.Change.CHANGED, actor);
            }
        }
        return List.of(out);
    }

    /** A change that is allowed: the stored field, and the field as it would be saved. */
    private record Checked(FieldDefinition current, FieldSpec effective) {
    }

    private Checked checked(FieldScope scope, FieldSpec next, int expectedVersion) {
        FieldDefinition current = store.find(scope, next.key()).orElseThrow(() -> unknown(next.key()));
        if (!current.key().equals(next.key())) {
            throw new FieldDefinitionException(Code.FIELD_KEY_LOCKED,
                    "Field " + current.key() + ": a field's key never changes");
        }
        if (current.version() != expectedVersion) {
            throw meanwhile(current.key());
        }
        FieldSpec effective = next.active() == current.active() ? next : next.toBuilder().active(current.active())
                .build();
        if (effective.equals(current.spec())) {
            return new Checked(current, effective);
        }
        boolean kindChanges = !effective.sameValueKind(current.spec());
        boolean sensitiveChanges = effective.sensitive() != current.spec().sensitive();
        boolean primaryChanges = effective.primary() != current.spec().primary();
        if ((kindChanges || sensitiveChanges || primaryChanges) && valuesExist(scope, current.key())) {
            if (kindChanges) {
                throw new FieldDefinitionException(Code.FIELD_TYPE_LOCKED, "Field " + current.key()
                        + " already has values, so its type stays " + describe(current.spec())
                        + "; add a new field for the other type");
            }
            if (sensitiveChanges) {
                throw new FieldDefinitionException(Code.FIELD_SENSITIVE_LOCKED, "Field " + current.key()
                        + " already has values, so it stays " + (current.spec().sensitive() ? "sensitive"
                        : "not sensitive") + "; add a new field to store such values the other way");
            }
            throw new FieldDefinitionException(Code.FIELD_PRIMARY_LOCKED, "Field " + current.key()
                    + " already has values, so it stays " + (current.spec().primary() ? "a primary identifier"
                    : "not a primary identifier") + "; add a new field instead");
        }
        return new Checked(current, effective);
    }

    /** Changes the label only. */
    public FieldDefinition relabel(FieldScope scope, FieldKey key, String label, String actor) {
        FieldDefinition current = find(scope, key).orElseThrow(() -> unknown(key));
        return change(scope, current.spec().toBuilder().label(label).build(), current.version(), actor);
    }

    /**
     * Removes a field from use: switches it off (and it stops being a primary identifier). It stays stored, with
     * its values. Switching off a field that is off changes nothing.
     */
    public FieldDefinition switchOff(FieldScope scope, FieldKey key, String actor) {
        actor(actor);
        FieldDefinition current = find(scope, key).orElseThrow(() -> unknown(key));
        if (!current.active()) {
            return current;
        }
        FieldDefinition off = store.update(current, current.spec().toBuilder().active(false).primary(false).build(),
                FieldStore.Change.SWITCHED_OFF, actor, now());
        log(off, FieldStore.Change.SWITCHED_OFF, actor);
        return off;
    }

    /** Switches a field back on (not as a primary identifier; set that again with {@link #change}). */
    public FieldDefinition switchOn(FieldScope scope, FieldKey key, String actor) {
        actor(actor);
        FieldDefinition current = find(scope, key).orElseThrow(() -> unknown(key));
        if (current.active()) {
            return current;
        }
        FieldDefinition on = store.update(current, current.spec().toBuilder().active(true).build(),
                FieldStore.Change.SWITCHED_ON, actor, now());
        log(on, FieldStore.Change.SWITCHED_ON, actor);
        return on;
    }

    /** Every version of the field, oldest first. */
    public List<FieldStore.HistoryEntry> history(FieldScope scope, FieldKey key) {
        find(scope, key).orElseThrow(() -> unknown(key));
        return store.history(scope, key);
    }

    /** The app's own settings attached to the field, by name. */
    public Map<String, String> extensions(FieldScope scope, FieldKey key) {
        find(scope, key).orElseThrow(() -> unknown(key));
        return store.extensions(scope, key);
    }

    /**
     * Attaches one of the app's own settings to a field (for example {@code extractable=true}), or removes it with a
     * null value. Names: lower-case letters, digits, '_', '.', '-', up to 64; values up to 1000 characters.
     */
    public void putExtension(FieldScope scope, FieldKey key, String name, String value) {
        if (name == null || !EXTENSION_NAME.matcher(name).matches()) {
            throw new FieldDefinitionException(Code.FIELD_EXTENSION_INVALID, "An app setting's name has lower-case "
                    + "letters, digits, '_', '.' or '-', up to 64, and starts with a letter");
        }
        if (value != null && (value.length() > MAX_EXTENSION_LENGTH || CONTROL.matcher(value).find())) {
            throw new FieldDefinitionException(Code.FIELD_EXTENSION_INVALID, "The app setting " + name
                    + " has a value longer than " + MAX_EXTENSION_LENGTH + " characters or control characters");
        }
        find(scope, key).orElseThrow(() -> unknown(key));
        store.putExtension(scope, key, name, value, now());
    }

    /** The field list file ({@code platform-fields/1}) of this entity and product, for another product to import. */
    public String exportFile(FieldScope scope) {
        return FieldListFile.write(store.list(scope).stream().map(FieldDefinition::spec).toList(), now());
    }

    /** What importing the file would do, without changing anything. */
    public ImportReport previewImport(FieldScope scope, String fileText) {
        return FieldImport.plan(store.list(scope), FieldListFile.read(fileText).fields(), false);
    }

    /**
     * Imports a field list file: creates the fields whose key is new (all or none), leaves every existing field as
     * it is (never changes its type, nor anything else), and reports a conflict where the type differs.
     */
    public ImportReport importFile(FieldScope scope, String fileText, String actor) {
        actor(actor);
        List<FieldSpec> incoming = FieldListFile.read(fileText).fields();
        ImportReport plan = FieldImport.plan(store.list(scope), incoming, true);
        List<FieldSpec> toCreate = plan.toCreate();
        if (!toCreate.isEmpty()) {
            for (FieldDefinition d : store.create(scope, toCreate, FieldStore.Change.IMPORTED, actor, now())) {
                log(d, FieldStore.Change.IMPORTED, actor);
            }
        }
        LOG.log(Level.INFO, "Field list imported into {0} by {1}: {2} created, {3} unchanged, {4} conflicts", scope,
                actor, plan.created().size(), plan.unchanged().size(), plan.conflicts().size());
        return plan;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void actor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 200 || CONTROL.matcher(actor).find()) {
            throw new FieldDefinitionException(Code.FIELD_ACTOR_INVALID,
                    "A change names who made it: 1 to 200 characters, no control characters");
        }
    }

    private static String describe(FieldSpec s) {
        return s.currency() == null ? s.type().name() : s.type() + " (" + s.currency() + ")";
    }

    private static void log(FieldDefinition d, FieldStore.Change change, String actor) {
        LOG.log(Level.INFO, "Field {0} in {1}: {2} by {3}, version {4}", d.key(), d.scope(), change, actor,
                d.version());
    }

    private static FieldDefinitionException taken(FieldKey key) {
        return FieldDefinitionException.keyTaken(key);
    }

    private static FieldDefinitionException unknown(FieldKey key) {
        return FieldDefinitionException.unknown(key);
    }

    private static FieldDefinitionException meanwhile(FieldKey key) {
        return FieldDefinitionException.changedMeanwhile(key);
    }
}
