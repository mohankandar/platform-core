package com.documentplatform.eraselog.jdbc;

import com.documentplatform.eraselog.ChainPoint;
import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.ErasePosition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The applied position in the app's Postgres database ({@code erl_position}). {@link #withLock} holds a session-level
 * advisory lock per log on one connection, so erasures and the replay of all app instances apply entries one at a
 * time; each applied entry is committed at once (with whatever the handler did through the same connection), so a
 * long replay keeps its progress. Plain JDBC; the app's DML-only user is enough.
 */
public final class PostgresErasePosition implements ErasePosition {

    /** The advisory lock's first key: "ERL1". */
    private static final int LOCK_CLASS = 0x45524C31;

    private final DataSource dataSource;

    public PostgresErasePosition(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Position read(UUID logId) {
        try (Connection c = dataSource.getConnection()) {
            return read(c, logId).orElseGet(() -> Position.start(logId));
        } catch (SQLException e) {
            throw failed("read", e);
        }
    }

    @Override
    public List<UUID> otherLogs(UUID logId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select log_id from erl_position where log_id <> ? order by 1")) {
            ps.setObject(1, logId);
            List<UUID> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getObject(1, UUID.class));
                }
            }
            return out;
        } catch (SQLException e) {
            throw failed("read", e);
        }
    }

    @Override
    public <T> T withLock(UUID logId, Function<Locked, T> work) {
        Objects.requireNonNull(logId, "logId");
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            lock(c, logId, true);
            try {
                c.setAutoCommit(false);
                Position p = read(c, logId).orElseGet(() -> Position.start(logId));
                c.commit();
                return work.apply(new PgLocked(c, p));
            } finally {
                try {
                    c.rollback();
                    c.setAutoCommit(autoCommit);
                } finally {
                    try {
                        lock(c, logId, false);
                    } catch (SQLException unlockFailed) {
                        // SEC-49: never give a pooled connection back still holding the lock: end it instead.
                        try {
                            c.abort(Runnable::run);
                        } catch (SQLException | RuntimeException ignored) {
                            // the connection is unusable either way; the server frees the lock when it closes
                        }
                        throw unlockFailed;
                    }
                }
            }
        } catch (SQLException e) {
            throw failed("locked", e);
        }
    }

    private final class PgLocked implements Locked {
        private final Connection c;
        private Position position;

        PgLocked(Connection c, Position position) {
            this.c = c;
            this.position = position;
        }

        @Override
        public Position position() {
            return position;
        }

        @Override
        public <T> T applyAndAdvance(EraseEntry entry, Supplier<T> apply) {
            if (entry.seq() != position.appliedSeq() + 1 || !entry.prev().equals(position.applied().hash())) {
                throw new IllegalStateException("entry " + entry.seq() + " is not the next after "
                        + position.appliedSeq());
            }
            T result;
            try {
                result = apply.get();
            } catch (RuntimeException e) {
                rollback();
                throw e;
            }
            try {
                upsert(entry.point());
                c.commit();
            } catch (SQLException e) {
                rollback();
                throw failed("written", e);
            }
            position = new Position(position.logId(), entry.point(), position.replayRequired(),
                    position.restoreDetected(), position.verifiedAt());
            return result;
        }

        @Override
        public void markRestoreDetected(Instant at) {
            update("update erl_position set replay_required = true, restore_detected_at = coalesce(restore_detected_at,"
                    + " ?), updated_at = now() where log_id = ?", at);
            position = new Position(position.logId(), position.applied(), true,
                    position.restoreDetected() == null ? at : position.restoreDetected(), position.verifiedAt());
        }

        @Override
        public void verified(Instant at) {
            update("update erl_position set replay_required = false, restore_detected_at = null, verified_at = ?, "
                    + "updated_at = now() where log_id = ?", at);
            position = new Position(position.logId(), position.applied(), false, null, at);
        }

        @Override
        public Optional<Connection> connection() {
            return Optional.of(c);
        }

        private void update(String sql, Instant at) {
            try {
                upsert(position.applied());
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setTimestamp(1, Timestamp.from(at));
                    ps.setObject(2, position.logId());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                rollback();
                throw failed("written", e);
            }
        }

        /** Makes the row (first use) and moves it to {@code applied}. */
        private void upsert(ChainPoint applied) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("insert into erl_position (log_id, applied_seq, applied_hash)"
                    + " values (?, ?, ?) on conflict (log_id) do update set applied_seq = excluded.applied_seq, "
                    + "applied_hash = excluded.applied_hash, updated_at = now()")) {
                ps.setObject(1, position.logId());
                ps.setLong(2, applied.seq());
                ps.setString(3, applied.hash());
                ps.executeUpdate();
            }
        }

        private void rollback() {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // the connection is closed after the lock anyway
            }
        }
    }

    private static Optional<Position> read(Connection c, UUID logId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select applied_seq, applied_hash, replay_required, "
                + "restore_detected_at, verified_at from erl_position where log_id = ?")) {
            ps.setObject(1, logId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Position(logId, new ChainPoint(rs.getLong(1), rs.getString(2)),
                        rs.getBoolean(3), instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5))));
            }
        }
    }

    private static void lock(Connection c, UUID logId, boolean take) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(take ? "select pg_advisory_lock(?, ?)"
                : "select pg_advisory_unlock(?, ?)")) {
            ps.setInt(1, LOCK_CLASS);
            ps.setInt(2, logId.hashCode());
            ps.executeQuery().close();
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static EraseLogException failed(String what, SQLException e) {
        return new EraseLogException(EraseLogError.ERASE_POSITION_FAILED, "the erase log's applied position (table "
                + "erl_position) could not be " + what + " (SQL state " + e.getSQLState() + ")");
    }
}
