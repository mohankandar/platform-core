package com.documentplatform.modelgov.jdbc;

import com.documentplatform.modelgov.Decisions;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelDigest;
import com.documentplatform.modelgov.ModelGovStore;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Monitoring;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.modelgov.ValidationRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ModelGovStore} on the {@code mg_} tables (plain JDBC, DML only). Every change and its history row are one
 * transaction; versions are checked in the update itself. Validations, measures, approvals, acceptances and live
 * switches are only ever inserted (the tables' triggers refuse anything else).
 */
public final class PostgresModelGovStore implements ModelGovStore {

    /** The store could not read or write; the operation is named, never a value. */
    public static final class StoreException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {
        StoreException(String operation, SQLException e) {
            super(ModelGovStoreCode.MG_STORE_FAILED.name() + ": Model-governance store: "
                    + operation + " failed (SQL state " + e.getSQLState() + ")", e);
        }

        /** {@code MG_STORE_FAILED}: the code for monitoring and the app's error handler. */
        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
            return ModelGovStoreCode.MG_STORE_FAILED;
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final String MODEL_COLUMNS = "model_id, name, provider, model_version, digest_algorithm, "
            + "digest_value, runs_where, version";
    private static final String USE_COLUMNS = "use_id, product_code, feature, entity_uuid, purpose, materiality, "
            + "materiality_reason, human_in_the_loop, vendor_owner, customer_owner, model_id, config_fingerprint, "
            + "status, last_validated_at, next_review_due, review_overdue_refuses, review_every_days, version";
    private static final String VALIDATION_COLUMNS = "validation_id, use_id, entity_uuid, model_id, model_name, "
            + "model_version, provider, digest_algorithm, digest_value, config_fingerprint, test_set_name, "
            + "test_set_version, passed, ran_by, ran_at";

    private final DataSource ds;

    public PostgresModelGovStore(DataSource ds) {
        this.ds = Objects.requireNonNull(ds, "ds");
    }

    private <T> T tx(String op, Work<T> w) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = w.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new StoreException(op, e);
        }
    }

    private <T> T read(String op, Work<T> w) {
        try (Connection c = ds.getConnection()) {
            return w.run(c);
        } catch (SQLException e) {
            throw new StoreException(op, e);
        }
    }

    // ---- models

    @Override
    public Model insertModel(Model m, Change change) {
        return tx("insert model", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_model (" + MODEL_COLUMNS
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindModel(ps, m);
                ps.executeUpdate();
            }
            modelHistory(c, m, change);
            return m;
        });
    }

    @Override
    public Optional<Model> updateModel(Model m, long expectedVersion, Change change) {
        Model next = withModelVersion(m, expectedVersion + 1);
        return tx("update model", c -> {
            try (PreparedStatement ps = c.prepareStatement("update mg_model set name = ?, provider = ?, "
                    + "model_version = ?, digest_algorithm = ?, digest_value = ?, runs_where = ?, version = ?, "
                    + "updated_at = now() where model_id = ? and version = ?")) {
                ps.setString(1, next.name());
                ps.setString(2, next.provider().name());
                ps.setString(3, next.modelVersion());
                ps.setString(4, next.digest().algorithm());
                ps.setString(5, next.digest().value());
                ps.setString(6, next.runsWhere().name());
                ps.setLong(7, next.version());
                ps.setObject(8, next.modelId());
                ps.setLong(9, expectedVersion);
                if (ps.executeUpdate() != 1) return Optional.<Model>empty();
            }
            modelHistory(c, next, change);
            return Optional.of(next);
        });
    }

    @Override
    public Optional<Model> model(UUID id) {
        return read("read model", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + MODEL_COLUMNS + " from mg_model where model_id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(model(rs)) : Optional.<Model>empty();
                }
            }
        });
    }

    @Override
    public List<HistoryRow> modelHistory(UUID id) {
        return history("mg_model_history", "model_id", id);
    }

    // ---- uses

    @Override
    public ModelUse insertUse(ModelUse u, Change change) {
        return tx("insert use", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_model_use (" + USE_COLUMNS
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindUse(ps, u);
                ps.executeUpdate();
            }
            useHistory(c, u, change);
            return u;
        });
    }

    @Override
    public Optional<ModelUse> updateUse(ModelUse u, long expectedVersion, Change change) {
        return tx("update use", c -> {
            ModelUse next = u.atVersion(expectedVersion + 1);
            try (PreparedStatement ps = c.prepareStatement("update mg_model_use set product_code = ?, feature = ?, "
                    + "entity_uuid = ?, purpose = ?, materiality = ?, materiality_reason = ?, human_in_the_loop = ?, "
                    + "vendor_owner = ?, customer_owner = ?, model_id = ?, config_fingerprint = ?, status = ?, "
                    + "last_validated_at = ?, next_review_due = ?, review_overdue_refuses = ?, review_every_days = ?, "
                    + "version = ?, updated_at = now() where use_id = ? and version = ?")) {
                int i = 1;
                ps.setString(i++, next.product());
                ps.setString(i++, next.feature());
                ps.setObject(i++, next.entity());
                ps.setString(i++, next.purpose());
                ps.setString(i++, next.materiality().name());
                ps.setString(i++, next.materialityReason());
                ps.setString(i++, next.humanInTheLoop());
                ps.setString(i++, next.vendorOwner());
                ps.setString(i++, next.customerOwner());
                ps.setObject(i++, next.modelId());
                ps.setString(i++, next.configFingerprint());
                ps.setString(i++, next.status().name());
                ps.setTimestamp(i++, ts(next.lastValidatedAt()));
                ps.setDate(i++, next.nextReviewDue() == null ? null : Date.valueOf(next.nextReviewDue()));
                ps.setBoolean(i++, next.reviewOverdueRefuses());
                ps.setObject(i++, next.reviewEveryDays(), java.sql.Types.INTEGER);
                ps.setLong(i++, next.version());
                ps.setObject(i++, next.useId());
                ps.setLong(i, expectedVersion);
                if (ps.executeUpdate() != 1) return Optional.<ModelUse>empty();
            }
            useHistory(c, next, change);
            return Optional.of(next);
        });
    }

    @Override
    public Optional<ModelUse> use(UUID id) {
        return read("read use", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + USE_COLUMNS + " from mg_model_use where use_id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(use(rs)) : Optional.<ModelUse>empty();
                }
            }
        });
    }

    @Override
    public List<HistoryRow> useHistory(UUID id) {
        return history("mg_model_use_history", "use_id", id);
    }

    // ---- thresholds

    @Override
    public void putThreshold(Threshold t, Change change) {
        tx("set threshold", c -> {
            try (PreparedStatement del = c.prepareStatement("delete from mg_threshold where use_id = ? and "
                    + "coalesce(entity_uuid, '00000000-0000-0000-0000-000000000000'::uuid) = "
                    + "coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid) and measure = ?")) {
                del.setObject(1, t.useId());
                del.setObject(2, t.entity());
                del.setString(3, t.measure());
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("insert into mg_threshold (use_id, entity_uuid, measure, "
                    + "value, direction, set_by, set_at) values (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, t.useId());
                ps.setObject(2, t.entity());
                ps.setString(3, t.measure());
                ps.setBigDecimal(4, t.value());
                ps.setString(5, t.direction().name());
                ps.setString(6, change.changedBy());
                ps.setTimestamp(7, ts(change.changedAt()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Threshold> thresholds(UUID useId) {
        return read("read thresholds", c -> {
            List<Threshold> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select entity_uuid, measure, value, direction from "
                    + "mg_threshold where use_id = ? order by measure, entity_uuid nulls first")) {
                ps.setObject(1, useId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Threshold(useId, rs.getObject(1, UUID.class), rs.getString(2),
                                rs.getBigDecimal(3), Threshold.Direction.valueOf(rs.getString(4))));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    // ---- validations and decisions

    @Override
    public void insertValidation(ValidationRecord v) {
        tx("insert validation", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_validation (" + VALIDATION_COLUMNS
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int i = 1;
                ps.setObject(i++, v.validationId());
                ps.setObject(i++, v.useId());
                ps.setObject(i++, v.entity());
                ps.setObject(i++, v.modelId());
                ps.setString(i++, v.modelName());
                ps.setString(i++, v.modelVersion());
                ps.setString(i++, v.provider().name());
                ps.setString(i++, v.digest().algorithm());
                ps.setString(i++, v.digest().value());
                ps.setString(i++, v.configFingerprint());
                ps.setString(i++, v.testSetName());
                ps.setString(i++, v.testSetVersion());
                ps.setBoolean(i++, v.passed());
                ps.setString(i++, v.ranBy());
                ps.setTimestamp(i, ts(v.ranAt()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("insert into mg_validation_measure (validation_id, "
                    + "measure, result, threshold, direction, passed) values (?, ?, ?, ?, ?, ?)")) {
                for (ValidationRecord.MeasureResult m : v.measures()) {
                    ps.setObject(1, v.validationId());
                    ps.setString(2, m.measure());
                    ps.setBigDecimal(3, m.result());
                    ps.setBigDecimal(4, m.threshold());
                    ps.setString(5, m.direction().name());
                    ps.setBoolean(6, m.passed());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public Optional<ValidationRecord> validation(UUID id) {
        return read("read validation", c -> {
            try (PreparedStatement ps = c.prepareStatement("select " + VALIDATION_COLUMNS
                    + " from mg_validation where validation_id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<ValidationRecord>empty();
                    return Optional.of(validation(c, rs));
                }
            }
        });
    }

    @Override
    public List<UUID> validationEntities(UUID useId) {
        return read("read validated entities", c -> {
            List<UUID> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select distinct entity_uuid from mg_validation "
                    + "where use_id = ? order by entity_uuid")) {
                ps.setObject(1, useId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getObject(1, UUID.class));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public List<ValidationRecord> validations(UUID useId, UUID entity) {
        return read("read validations", c -> {
            List<ValidationRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select " + VALIDATION_COLUMNS + " from mg_validation "
                    + "where use_id = ? and entity_uuid = ? order by ran_at, validation_id")) {
                ps.setObject(1, useId);
                ps.setObject(2, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(validation(c, rs));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public void insertApproval(Decisions.Approval a) {
        tx("insert approval", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_approval (validation_id, approved_by, "
                    + "roles, approved_at) values (?, ?, ?, ?)")) {
                ps.setObject(1, a.validationId());
                ps.setString(2, a.approvedBy());
                ps.setString(3, String.join(",", new java.util.TreeSet<>(a.roles())));
                ps.setTimestamp(4, ts(a.approvedAt()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Decisions.Approval> approval(UUID id) {
        return read("read approval", c -> {
            try (PreparedStatement ps = c.prepareStatement("select approved_by, roles, approved_at from mg_approval "
                    + "where validation_id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<Decisions.Approval>empty();
                    Set<String> roles = rs.getString(2).isEmpty() ? Set.of()
                            : new LinkedHashSet<>(Arrays.asList(rs.getString(2).split(",")));
                    return Optional.of(new Decisions.Approval(id, rs.getString(1), roles, instant(rs.getTimestamp(3))));
                }
            }
        });
    }

    @Override
    public void insertAcceptance(Decisions.Acceptance a) {
        tx("insert acceptance", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_acceptance (validation_id, accepted_by, "
                    + "accepted_at, results, reason) values (?, ?, ?, ?, ?)")) {
                ps.setObject(1, a.validationId());
                ps.setString(2, a.acceptedBy());
                ps.setTimestamp(3, ts(a.acceptedAt()));
                ps.setString(4, a.results());
                ps.setString(5, a.reason());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Decisions.Acceptance> acceptance(UUID id) {
        return read("read acceptance", c -> {
            try (PreparedStatement ps = c.prepareStatement("select accepted_by, accepted_at, results, reason from "
                    + "mg_acceptance where validation_id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<Decisions.Acceptance>empty();
                    return Optional.of(new Decisions.Acceptance(id, rs.getString(1), instant(rs.getTimestamp(2)),
                            rs.getString(3), rs.getString(4)));
                }
            }
        });
    }

    @Override
    public Optional<Decisions.Live> live(UUID useId, UUID entity) {
        return read("read live", c -> {
            try (PreparedStatement ps = c.prepareStatement("select validation_id, previous_validation_id, version, "
                    + "changed_by, changed_at from mg_live where use_id = ? and entity_uuid = ?")) {
                ps.setObject(1, useId);
                ps.setObject(2, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<Decisions.Live>empty();
                    return Optional.of(new Decisions.Live(useId, entity, rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), instant(rs.getTimestamp(5))));
                }
            }
        });
    }

    @Override
    public boolean switchLive(Decisions.Live next, long expectedVersion, Decisions.LiveChange change) {
        return tx("switch live", c -> {
            int n;
            if (expectedVersion == 0) {
                try (PreparedStatement ps = c.prepareStatement("insert into mg_live (use_id, entity_uuid, "
                        + "validation_id, previous_validation_id, version, changed_by, changed_at) values "
                        + "(?, ?, ?, ?, ?, ?, ?) on conflict do nothing")) {
                    ps.setObject(1, next.useId());
                    ps.setObject(2, next.entity());
                    ps.setObject(3, next.validationId());
                    ps.setObject(4, next.previousValidationId());
                    ps.setLong(5, next.version());
                    ps.setString(6, next.changedBy());
                    ps.setTimestamp(7, ts(next.changedAt()));
                    n = ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("update mg_live set validation_id = ?, "
                        + "previous_validation_id = ?, version = ?, changed_by = ?, changed_at = ? "
                        + "where use_id = ? and entity_uuid = ? and version = ?")) {
                    ps.setObject(1, next.validationId());
                    ps.setObject(2, next.previousValidationId());
                    ps.setLong(3, next.version());
                    ps.setString(4, next.changedBy());
                    ps.setTimestamp(5, ts(next.changedAt()));
                    ps.setObject(6, next.useId());
                    ps.setObject(7, next.entity());
                    ps.setLong(8, expectedVersion);
                    n = ps.executeUpdate();
                }
            }
            if (n != 1) {
                c.rollback();
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("insert into mg_live_history (use_id, entity_uuid, kind, "
                    + "from_validation_id, to_validation_id, changed_by, changed_at) values (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, change.useId());
                ps.setObject(2, change.entity());
                ps.setString(3, change.kind().name());
                ps.setObject(4, change.fromValidationId());
                ps.setObject(5, change.toValidationId());
                ps.setString(6, change.changedBy());
                ps.setTimestamp(7, ts(change.changedAt()));
                ps.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public List<Decisions.LiveChange> liveHistory(UUID useId, UUID entity) {
        return read("read live history", c -> {
            List<Decisions.LiveChange> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select kind, from_validation_id, to_validation_id, "
                    + "changed_by, changed_at from mg_live_history where use_id = ? and entity_uuid = ? "
                    + "order by history_id")) {
                ps.setObject(1, useId);
                ps.setObject(2, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Decisions.LiveChange(useId, entity, Decisions.LiveChange.Kind.valueOf(
                                rs.getString(1)), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                                rs.getString(4), instant(rs.getTimestamp(5))));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    // ---- rows

    private static void bindModel(PreparedStatement ps, Model m) throws SQLException {
        ps.setObject(1, m.modelId());
        ps.setString(2, m.name());
        ps.setString(3, m.provider().name());
        ps.setString(4, m.modelVersion());
        ps.setString(5, m.digest().algorithm());
        ps.setString(6, m.digest().value());
        ps.setString(7, m.runsWhere().name());
        ps.setLong(8, m.version());
    }

    private static Model model(ResultSet rs) throws SQLException {
        return new Model(rs.getObject(1, UUID.class), rs.getString(2), Model.Provider.valueOf(rs.getString(3)),
                rs.getString(4), new ModelDigest(rs.getString(5), rs.getString(6)),
                Model.RunsWhere.valueOf(rs.getString(7)), rs.getLong(8));
    }

    private static Model withModelVersion(Model m, long v) {
        return new Model(m.modelId(), m.name(), m.provider(), m.modelVersion(), m.digest(), m.runsWhere(), v);
    }

    private static void bindUse(PreparedStatement ps, ModelUse u) throws SQLException {
        int i = 1;
        ps.setObject(i++, u.useId());
        ps.setString(i++, u.product());
        ps.setString(i++, u.feature());
        ps.setObject(i++, u.entity());
        ps.setString(i++, u.purpose());
        ps.setString(i++, u.materiality().name());
        ps.setString(i++, u.materialityReason());
        ps.setString(i++, u.humanInTheLoop());
        ps.setString(i++, u.vendorOwner());
        ps.setString(i++, u.customerOwner());
        ps.setObject(i++, u.modelId());
        ps.setString(i++, u.configFingerprint());
        ps.setString(i++, u.status().name());
        ps.setTimestamp(i++, ts(u.lastValidatedAt()));
        ps.setDate(i++, u.nextReviewDue() == null ? null : Date.valueOf(u.nextReviewDue()));
        ps.setBoolean(i++, u.reviewOverdueRefuses());
        ps.setObject(i++, u.reviewEveryDays(), java.sql.Types.INTEGER);
        ps.setLong(i, u.version());
    }

    private static ModelUse use(ResultSet rs) throws SQLException {
        Date due = rs.getDate(15);
        return new ModelUse(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                rs.getString(5), ModelUse.Materiality.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getObject(11, UUID.class), rs.getString(12),
                ModelUse.Status.valueOf(rs.getString(13)), instant(rs.getTimestamp(14)),
                due == null ? null : due.toLocalDate(), rs.getBoolean(16), rs.getObject(17, Integer.class),
                rs.getLong(18));
    }

    private static ValidationRecord validation(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        List<ValidationRecord.MeasureResult> measures = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select measure, result, threshold, direction, passed from "
                + "mg_validation_measure where validation_id = ? order by measure")) {
            ps.setObject(1, id);
            try (ResultSet m = ps.executeQuery()) {
                while (m.next()) {
                    measures.add(new ValidationRecord.MeasureResult(m.getString(1), m.getBigDecimal(2),
                            m.getBigDecimal(3), Threshold.Direction.valueOf(m.getString(4)), m.getBoolean(5)));
                }
            }
        }
        return new ValidationRecord(id, rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), Model.Provider.valueOf(rs.getString(7)),
                new ModelDigest(rs.getString(8), rs.getString(9)), rs.getString(10), rs.getString(11), rs.getString(12),
                measures, rs.getBoolean(13), rs.getString(14), instant(rs.getTimestamp(15)));
    }

    private static void modelHistory(Connection c, Model m, Change ch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into mg_model_history (model_id, version, name, "
                + "provider, model_version, digest_algorithm, digest_value, runs_where, changed_fields, changed_by, "
                + "changed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, m.modelId());
            ps.setLong(2, m.version());
            ps.setString(3, m.name());
            ps.setString(4, m.provider().name());
            ps.setString(5, m.modelVersion());
            ps.setString(6, m.digest().algorithm());
            ps.setString(7, m.digest().value());
            ps.setString(8, m.runsWhere().name());
            ps.setString(9, String.join(",", ch.changedFields()));
            ps.setString(10, ch.changedBy());
            ps.setTimestamp(11, ts(ch.changedAt()));
            ps.executeUpdate();
        }
    }

    private static void useHistory(Connection c, ModelUse u, Change ch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into mg_model_use_history (use_id, version, model_id, "
                + "config_fingerprint, materiality, status, next_review_due, review_every_days, changed_fields, "
                + "changed_by, changed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, u.useId());
            ps.setLong(2, u.version());
            ps.setObject(3, u.modelId());
            ps.setString(4, u.configFingerprint());
            ps.setString(5, u.materiality().name());
            ps.setString(6, u.status().name());
            ps.setDate(7, u.nextReviewDue() == null ? null : Date.valueOf(u.nextReviewDue()));
            ps.setObject(8, u.reviewEveryDays(), java.sql.Types.INTEGER);
            ps.setString(9, String.join(",", ch.changedFields()));
            ps.setString(10, ch.changedBy());
            ps.setTimestamp(11, ts(ch.changedAt()));
            ps.executeUpdate();
        }
    }

    private static final String MODEL_RECORDED = "name, provider, model_version, digest_algorithm, digest_value, "
            + "runs_where";
    private static final String USE_RECORDED = "model_id, config_fingerprint, materiality, status, next_review_due, "
            + "review_every_days";

    private List<HistoryRow> history(String table, String key, UUID id) {
        boolean models = table.equals("mg_model_history");
        return read("read history", c -> {
            List<HistoryRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select version, changed_by, changed_at, changed_fields, "
                    + (models ? MODEL_RECORDED : USE_RECORDED) + " from " + table + " where " + key
                    + " = ? order by version")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String f = rs.getString(4);
                        java.util.Map<String, String> rec = new java.util.LinkedHashMap<>();
                        if (models) {
                            rec.put("name", rs.getString(5));
                            rec.put("provider", rs.getString(6));
                            rec.put("modelVersion", rs.getString(7));
                            rec.put("digest", rs.getString(8) + ":" + rs.getString(9));
                            rec.put("runsWhere", rs.getString(10));
                        } else {
                            rec.put("modelId", rs.getString(5));
                            rec.put("configFingerprint", rs.getString(6));
                            rec.put("materiality", rs.getString(7));
                            rec.put("status", rs.getString(8));
                            if (rs.getDate(9) != null) rec.put("nextReviewDue", rs.getDate(9).toLocalDate().toString());
                            if (rs.getObject(10) != null) rec.put("reviewEveryDays", String.valueOf(rs.getInt(10)));
                        }
                        out.add(new HistoryRow(rs.getLong(1), rs.getString(2), instant(rs.getTimestamp(3)),
                                f.isEmpty() ? List.of() : List.of(f.split(",")), rec));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    // ---- the register as a list, monitoring (P034)

    @Override
    public List<ModelUse> uses(String product) {
        return read("read uses", c -> {
            List<ModelUse> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select " + USE_COLUMNS + " from mg_model_use"
                    + (product == null ? "" : " where product_code = ?") + " order by product_code, feature, use_id")) {
                if (product != null) ps.setString(1, product);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(use(rs));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public List<ModelUse> usesOfModel(UUID modelId) {
        return read("read uses", c -> {
            List<ModelUse> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select " + USE_COLUMNS + " from mg_model_use where "
                    + "model_id = ? order by product_code, feature, use_id")) {
                ps.setObject(1, modelId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(use(rs));
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public void putMonitorThreshold(Monitoring.MonitorThreshold t, Change change) {
        tx("set monitoring threshold", c -> {
            try (PreparedStatement del = c.prepareStatement("delete from mg_monitor_threshold where use_id = ? and "
                    + "coalesce(entity_uuid, '00000000-0000-0000-0000-000000000000'::uuid) = "
                    + "coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid) and measure = ?")) {
                del.setObject(1, t.useId());
                del.setObject(2, t.entity());
                del.setString(3, t.measure());
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("insert into mg_monitor_threshold (use_id, entity_uuid, "
                    + "measure, value, direction, min_sample, set_by, set_at) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, t.useId());
                ps.setObject(2, t.entity());
                ps.setString(3, t.measure());
                ps.setBigDecimal(4, t.value());
                ps.setString(5, t.direction().name());
                ps.setLong(6, t.minSample());
                ps.setString(7, change.changedBy());
                ps.setTimestamp(8, ts(change.changedAt()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Monitoring.MonitorThreshold> monitorThresholds(UUID useId) {
        return read("read monitoring thresholds", c -> {
            List<Monitoring.MonitorThreshold> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select entity_uuid, measure, value, direction, min_sample "
                    + "from mg_monitor_threshold where use_id = ? order by measure, entity_uuid nulls first")) {
                ps.setObject(1, useId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Monitoring.MonitorThreshold(useId, rs.getObject(1, UUID.class), rs.getString(2),
                                rs.getBigDecimal(3), Threshold.Direction.valueOf(rs.getString(4)), rs.getLong(5)));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public void insertReading(Monitoring.Reading r) {
        tx("insert reading", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_measure (reading_id, use_id, entity_uuid, "
                    + "measure, value, period_from, period_to, samples, reported_by, reported_at) values "
                    + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, r.readingId());
                ps.setObject(2, r.useId());
                ps.setObject(3, r.entity());
                ps.setString(4, r.measure());
                ps.setBigDecimal(5, r.value());
                ps.setTimestamp(6, ts(r.period().from()));
                ps.setTimestamp(7, ts(r.period().to()));
                ps.setLong(8, r.period().samples());
                ps.setString(9, r.reportedBy());
                ps.setTimestamp(10, ts(r.reportedAt()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Monitoring.Reading> readings(UUID useId, UUID entity, Instant from, Instant to) {
        return read("read readings", c -> {
            List<Monitoring.Reading> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select reading_id, entity_uuid, measure, value, "
                    + "period_from, period_to, samples, reported_by, reported_at from mg_measure where use_id = ? "
                    + (entity == null ? "" : "and entity_uuid = ? ")
                    + "and reported_at >= ? and reported_at < ? order by reported_at, reading_id")) {
                int i = 1;
                ps.setObject(i++, useId);
                if (entity != null) ps.setObject(i++, entity);
                ps.setTimestamp(i++, ts(from));
                ps.setTimestamp(i, ts(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Monitoring.Reading(rs.getObject(1, UUID.class), useId, rs.getObject(2, UUID.class),
                                rs.getString(3), rs.getBigDecimal(4), new Monitoring.MeasurePeriod(
                                instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)), rs.getLong(7)),
                                rs.getString(8), instant(rs.getTimestamp(9))));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public int purgeReadings(Instant before) {
        return tx("purge readings", c -> {
            // As the schema owner, with the 30-day floor, through the database's own function (schema V3, P037).
            try (PreparedStatement ps = c.prepareStatement("select " + ModelGovSchema.PURGE_FUNCTION + "(?)")) {
                ps.setTimestamp(1, ts(before));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public void insertDrift(Monitoring.Drift d) {
        tx("insert drift", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_drift (drift_id, use_id, entity_uuid, "
                    + "measure, value, threshold, direction, samples, reading_id, raised_at) values "
                    + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, d.driftId());
                ps.setObject(2, d.useId());
                ps.setObject(3, d.entity());
                ps.setString(4, d.measure());
                ps.setBigDecimal(5, d.value());
                ps.setBigDecimal(6, d.threshold());
                ps.setString(7, d.direction().name());
                ps.setLong(8, d.samples());
                ps.setObject(9, d.readingId());
                ps.setTimestamp(10, ts(d.raisedAt()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Monitoring.Drift> drifts(UUID useId, UUID entity) {
        return read("read drifts", c -> {
            List<Monitoring.Drift> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select drift_id, entity_uuid, measure, value, threshold, "
                    + "direction, samples, reading_id, raised_at from mg_drift where use_id = ? "
                    + (entity == null ? "" : "and entity_uuid = ? ") + "order by raised_at, drift_id")) {
                ps.setObject(1, useId);
                if (entity != null) ps.setObject(2, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Monitoring.Drift(rs.getObject(1, UUID.class), useId, rs.getObject(2, UUID.class),
                                rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                                Threshold.Direction.valueOf(rs.getString(6)), rs.getLong(7),
                                rs.getObject(8, UUID.class), instant(rs.getTimestamp(9))));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public void insertDriftAction(Monitoring.DriftAction a) {
        tx("insert drift action", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_drift_action (drift_id, note, acted_by, "
                    + "acted_at) values (?, ?, ?, ?)")) {
                ps.setObject(1, a.driftId());
                ps.setString(2, a.note());
                ps.setString(3, a.actedBy());
                ps.setTimestamp(4, ts(a.actedAt()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Monitoring.DriftAction> driftAction(UUID driftId) {
        return read("read drift action", c -> {
            try (PreparedStatement ps = c.prepareStatement("select note, acted_by, acted_at from mg_drift_action "
                    + "where drift_id = ?")) {
                ps.setObject(1, driftId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<Monitoring.DriftAction>empty();
                    return Optional.of(new Monitoring.DriftAction(driftId, rs.getString(1), rs.getString(2),
                            instant(rs.getTimestamp(3))));
                }
            }
        });
    }

    @Override
    public boolean insertReviewNotice(UUID useId, java.time.LocalDate due, Monitoring.ReviewStage stage, Instant at) {
        return tx("insert review notice", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into mg_review_notice (use_id, due_date, stage, "
                    + "raised_at) values (?, ?, ?, ?) on conflict do nothing")) {
                ps.setObject(1, useId);
                ps.setDate(2, Date.valueOf(due));
                ps.setString(3, stage.name());
                ps.setTimestamp(4, ts(at));
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
