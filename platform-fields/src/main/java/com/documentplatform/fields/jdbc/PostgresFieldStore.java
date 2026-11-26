package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.file.FieldListFile;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.fields.jdbc.Sql.instant;
import static com.documentplatform.fields.jdbc.Sql.ts;

/**
 * Field definitions in Postgres (tables {@code fld_field_definition}, {@code fld_field_definition_history},
 * {@code fld_field_extension}). Plain JDBC; the app gives its runtime {@link DataSource}.
 *
 * <p>Keys are unique per entity and product ignoring capitals (a unique index), a change is written only if the
 * field is still at the version read ({@code where version = ?}), and the database refuses deletes and key changes.
 */
public final class PostgresFieldStore implements FieldStore {

    private static final String COLUMNS = "definition_id, entity_uuid, product_code, field_key, label, field_type, "
            + "currency, options, required, exact_length, min_length, max_length, pattern, letter_case, strip, "
            + "min_value, max_value, primary_flag, searchable, search_mode, sensitive, multi_value, displayable, "
            + "display_order, active, version, created_at, updated_at, updated_by";

    private final Db db;

    /** Each call in a transaction of its own on the app's data source. */
    public PostgresFieldStore(DataSource dataSource) {
        this.db = Db.own(Objects.requireNonNull(dataSource, "dataSource"), FieldStoreException::new);
    }

    private PostgresFieldStore(Db db) {
        this.db = db;
    }

    /**
     * A store whose calls run in the app's transaction on {@code c} (P059, LV-25.2): it never commits, rolls back
     * the whole transaction, changes auto-commit or the isolation, or closes the connection; the app does. Each
     * write runs in a savepoint, so a refused write leaves the app's transaction usable. Every call is refused with
     * {@code FIELD_TRANSACTION_UNSUITABLE}, before anything is written, unless auto-commit is off and the isolation
     * is READ COMMITTED. Use it only in that transaction and on the thread that owns it.
     */
    public static PostgresFieldStore onConnection(Connection c) {
        return new PostgresFieldStore(Db.bound(c, FieldStoreException::new, why -> new FieldDefinitionException(
                FieldDefinitionException.Code.FIELD_TRANSACTION_UNSUITABLE, "Field definitions: " + why)));
    }

    /** {@link #onConnection}. */
    @Override
    public FieldStore joined(Connection c) {
        return onConnection(c);
    }

    @Override
    public List<FieldDefinition> list(FieldScope scope) {
        return db.read("list field definitions", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from fld_field_definition "
                    + "where entity_uuid = ? and product_code = ? order by display_order, field_key")) {
                ps.setObject(1, scope.entity());
                ps.setString(2, scope.product());
                List<FieldDefinition> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(definition(rs));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public Optional<FieldDefinition> find(FieldScope scope, FieldKey key) {
        return db.read("read a field definition", c -> find(c, scope, key));
    }

    @Override
    public List<FieldDefinition> create(FieldScope scope, List<FieldSpec> specs, Change change, String actor,
                                        Instant at) {
        return db.write("save field definitions", c -> {
            List<FieldDefinition> out = new ArrayList<>();
            for (FieldSpec spec : specs) {
                out.add(create(c, scope, spec, change, actor, at));
            }
            return out;
        });
    }

    @Override
    public List<FieldDefinition> saveAll(FieldScope scope, List<Save> saves, String actor, Instant at) {
        for (Save s : saves) {
            requireSameKey(s.current(), s.next());
        }
        return db.write("save a list of field definitions", c -> {
            // Rows are locked in key order, so two lists saved at once wait for each other instead of deadlocking.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < saves.size(); i++) {
                order.add(i);
            }
            order.sort(java.util.Comparator.comparing(i -> saves.get(i).next().key().folded()));
            FieldDefinition[] out = new FieldDefinition[saves.size()];
            for (int i : order) {
                Save s = saves.get(i);
                out[i] = s.current() == null ? create(c, scope, s.next(), Change.CREATED, actor, at)
                        : update(c, s.current(), s.next(), Change.CHANGED, actor, at);
            }
            return List.of(out);
        });
    }

    private static FieldDefinition create(Connection c, FieldScope scope, FieldSpec spec, Change change, String actor,
                                          Instant at) throws SQLException {
        FieldDefinition d = new FieldDefinition(UUID.randomUUID(), scope, spec, 1, at, at, actor);
        try (PreparedStatement ps = c.prepareStatement("insert into fld_field_definition (" + COLUMNS
                + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?)")) {
            ps.setObject(1, d.id());
            ps.setObject(2, scope.entity());
            ps.setString(3, scope.product());
            ps.setString(4, spec.key().value());
            int i = bindSpec(c, ps, 5, spec);
            ps.setInt(i++, 1);
            ps.setObject(i++, ts(at));
            ps.setObject(i++, ts(at));
            ps.setString(i, actor);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (Sql.UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw FieldDefinitionException.keyTaken(spec.key());
            }
            throw e;
        }
        history(c, d, change);
        return d;
    }

    @Override
    public FieldDefinition update(FieldDefinition current, FieldSpec next, Change change, String actor, Instant at) {
        requireSameKey(current, next);
        return db.write("change a field definition", c -> update(c, current, next, change, actor, at));
    }

    private static void requireSameKey(FieldDefinition current, FieldSpec next) {
        if (current != null && !current.key().equals(next.key())) {
            throw new FieldDefinitionException(FieldDefinitionException.Code.FIELD_KEY_LOCKED,
                    "Field " + current.key() + ": a field's key never changes");
        }
    }

    private static FieldDefinition update(Connection c, FieldDefinition current, FieldSpec next, Change change,
                                          String actor, Instant at) throws SQLException {
        FieldDefinition d;
        try (PreparedStatement ps = c.prepareStatement("""
                update fld_field_definition set label = ?, field_type = ?, currency = ?, options = ?,
                    required = ?, exact_length = ?, min_length = ?, max_length = ?, pattern = ?, letter_case = ?,
                    strip = ?, min_value = ?, max_value = ?, primary_flag = ?, searchable = ?, search_mode = ?,
                    sensitive = ?, multi_value = ?, displayable = ?, display_order = ?, active = ?,
                    version = version + 1, updated_at = ?, updated_by = ?
                where definition_id = ? and version = ?
                returning\s""" + COLUMNS)) {
            int i = bindSpec(c, ps, 1, next);
            ps.setObject(i++, ts(at));
            ps.setString(i++, actor);
            ps.setObject(i++, current.id());
            ps.setInt(i, current.version());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    boolean exists = find(c, current.scope(), current.key()).isPresent();
                    throw exists ? FieldDefinitionException.changedMeanwhile(current.key())
                            : FieldDefinitionException.unknown(current.key());
                }
                d = definition(rs);
            }
        }
        history(c, d, change);
        return d;
    }

    @Override
    public List<HistoryEntry> history(FieldScope scope, FieldKey key) {
        return db.read("read a field's history", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    select h.version, h.change, h.changed_at, h.changed_by, h.definition
                    from fld_field_definition_history h join fld_field_definition d using (definition_id)
                    where d.entity_uuid = ? and d.product_code = ? and lower(d.field_key) = lower(?)
                    order by h.version""")) {
                ps.setObject(1, scope.entity());
                ps.setString(2, scope.product());
                ps.setString(3, key.value());
                List<HistoryEntry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new HistoryEntry(rs.getInt("version"), Change.valueOf(rs.getString("change")),
                                instant(rs, "changed_at"), rs.getString("changed_by"),
                                FieldListFile.fromJsonText(rs.getString("definition"))));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public Map<String, String> extensions(FieldScope scope, FieldKey key) {
        return db.read("read a field's app settings", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    select e.name, e.value from fld_field_extension e join fld_field_definition d using (definition_id)
                    where d.entity_uuid = ? and d.product_code = ? and lower(d.field_key) = lower(?)
                    order by e.name""")) {
                ps.setObject(1, scope.entity());
                ps.setString(2, scope.product());
                ps.setString(3, key.value());
                Map<String, String> out = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public void putExtension(FieldScope scope, FieldKey key, String name, String value, Instant at) {
        db.write("save a field's app setting", c -> {
            FieldDefinition d = find(c, scope, key).orElseThrow(() -> FieldDefinitionException.unknown(key));
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "delete from fld_field_extension where definition_id = ? and name = ?")) {
                    ps.setObject(1, d.id());
                    ps.setString(2, name);
                    return ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into fld_field_extension (definition_id, name, value, updated_at) values (?, ?, ?, ?)
                    on conflict (definition_id, name) do update set value = excluded.value,
                        updated_at = excluded.updated_at""")) {
                ps.setObject(1, d.id());
                ps.setString(2, name);
                ps.setString(3, value);
                ps.setObject(4, ts(at));
                return ps.executeUpdate();
            }
        });
    }

    private static Optional<FieldDefinition> find(Connection c, FieldScope scope, FieldKey key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from fld_field_definition "
                + "where entity_uuid = ? and product_code = ? and lower(field_key) = lower(?)")) {
            ps.setObject(1, scope.entity());
            ps.setString(2, scope.product());
            ps.setString(3, key.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(definition(rs)) : Optional.empty();
            }
        }
    }

    private static void history(Connection c, FieldDefinition d, Change change) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                insert into fld_field_definition_history (definition_id, version, change, changed_at, changed_by,
                    definition) values (?, ?, ?, ?, ?, ?)""")) {
            ps.setObject(1, d.id());
            ps.setInt(2, d.version());
            ps.setString(3, change.name());
            ps.setObject(4, ts(d.updatedAt()));
            ps.setString(5, d.updatedBy());
            ps.setString(6, FieldListFile.toJsonText(d.spec()));
            ps.executeUpdate();
        }
    }

    /** Binds the spec's columns from label to active; returns the next parameter index. */
    private static int bindSpec(Connection c, PreparedStatement ps, int i, FieldSpec s) throws SQLException {
        ps.setString(i++, s.label());
        ps.setString(i++, s.type().name());
        ps.setString(i++, s.currency());
        if (s.options().isEmpty()) {
            ps.setNull(i++, Types.ARRAY);
        } else {
            Array options = c.createArrayOf("text", s.options().toArray());
            ps.setArray(i++, options);
        }
        ps.setBoolean(i++, s.required());
        setInt(ps, i++, s.exactLength());
        setInt(ps, i++, s.minLength());
        setInt(ps, i++, s.maxLength());
        ps.setString(i++, s.pattern());
        ps.setString(i++, s.normalisation().letterCase().name());
        ps.setString(i++, s.normalisation().strip().name());
        ps.setBigDecimal(i++, s.minValue());
        ps.setBigDecimal(i++, s.maxValue());
        ps.setBoolean(i++, s.primary());
        ps.setBoolean(i++, s.searchable());
        ps.setString(i++, s.searchMode().name());
        ps.setBoolean(i++, s.sensitive());
        ps.setBoolean(i++, s.multiValue());
        ps.setBoolean(i++, s.displayable());
        ps.setInt(i++, s.displayOrder());
        ps.setBoolean(i++, s.active());
        return i;
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static FieldDefinition definition(ResultSet rs) throws SQLException {
        Array a = rs.getArray("options");
        List<String> options = a == null ? List.of() : List.of((String[]) a.getArray());
        FieldSpec spec = new FieldSpec(FieldKey.of(rs.getString("field_key")), rs.getString("label"),
                FieldType.valueOf(rs.getString("field_type")), rs.getString("currency"), options,
                rs.getBoolean("required"), getInt(rs, "exact_length"), getInt(rs, "min_length"),
                getInt(rs, "max_length"), rs.getString("pattern"),
                Normalisation.of(Normalisation.LetterCase.valueOf(rs.getString("letter_case")),
                        Normalisation.Strip.valueOf(rs.getString("strip"))),
                rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"), rs.getBoolean("primary_flag"),
                rs.getBoolean("searchable"), SearchMode.valueOf(rs.getString("search_mode")),
                rs.getBoolean("sensitive"), rs.getBoolean("multi_value"), rs.getBoolean("displayable"),
                rs.getInt("display_order"), rs.getBoolean("active"));
        return new FieldDefinition(rs.getObject("definition_id", UUID.class),
                new FieldScope(rs.getObject("entity_uuid", UUID.class), rs.getString("product_code")), spec,
                rs.getInt("version"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("updated_by"));
    }
}
