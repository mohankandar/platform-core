package com.documentplatform.integration.jdbc;

import com.documentplatform.integration.callback.CallbackEntry;
import com.documentplatform.integration.callback.CallbackOutbox;
import com.documentplatform.integration.callback.CallbackState;

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

/**
 * The callback outbox in Postgres ({@code int_callback_outbox}), plain JDBC, DML only. Claims use
 * {@code FOR UPDATE SKIP LOCKED} with a lease, so several app instances never send one callback at the same time, and
 * a callback claimed by an instance that stopped is claimed again when its lease ends.
 */
public final class PostgresCallbackOutbox implements CallbackOutbox {

    private static final String COLUMNS = "id, integration_id, entity_uuid, product, body, state, attempts, created_at, "
            + "window_from, next_attempt_at, claimed_until, last_outcome, last_code, last_status, finished_at";
    private static final String DUE = "((state = 'PENDING' and next_attempt_at <= ?) or (state = 'SENDING' and "
            + "claimed_until <= ?))";

    private final DataSource dataSource;

    public PostgresCallbackOutbox(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean enqueue(CallbackEntry e) {
        return update("insert into int_callback_outbox (id, integration_id, entity_uuid, product, body, state, "
                + "attempts, created_at, window_from, next_attempt_at) values (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?) "
                + "on conflict (id) do nothing", e.id(), e.integrationId(), e.entity(), e.product(), e.body(),
                ts(e.createdAt()), ts(e.windowFrom()), ts(e.nextAttemptAt())) == 1;
    }

    @Override
    public List<CallbackEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
        String sql = "with due as (select id from int_callback_outbox where " + DUE
                + " order by next_attempt_at, seq limit ? for update skip locked) "
                + "update int_callback_outbox o set state = 'SENDING', claimed_until = ? from due where o.id = due.id "
                + "returning o.*";
        List<CallbackEntry> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, ts(now));
            ps.setTimestamp(2, ts(now));
            ps.setInt(3, limit);
            ps.setTimestamp(4, ts(leaseUntil));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
        } catch (SQLException e) {
            throw failed(e);
        }
        out.sort((a, b) -> a.nextAttemptAt().compareTo(b.nextAttemptAt()));
        return out;
    }

    @Override
    public void markSent(UUID id, int attempts, String outcome, String code, int status, Instant at) {
        update("update int_callback_outbox set state = 'SENT', attempts = ?, last_outcome = ?, last_code = ?, "
                + "last_status = ?, finished_at = ?, claimed_until = null where id = ?", attempts, outcome, code, status,
                ts(at), id);
    }

    @Override
    public void markRetry(UUID id, int attempts, String outcome, String code, int status, Instant nextAttemptAt) {
        update("update int_callback_outbox set state = 'PENDING', attempts = ?, last_outcome = ?, last_code = ?, "
                + "last_status = ?, next_attempt_at = ?, claimed_until = null where id = ?", attempts, outcome, code,
                status, ts(nextAttemptAt), id);
    }

    @Override
    public void markGaveUp(UUID id, int attempts, String outcome, String code, int status, Instant at) {
        update("update int_callback_outbox set state = 'GAVE_UP', attempts = ?, last_outcome = ?, last_code = ?, "
                + "last_status = ?, finished_at = ?, claimed_until = null where id = ?", attempts, outcome, code,
                status, ts(at), id);
    }

    @Override
    public boolean resend(UUID id, Instant now) {
        return update("update int_callback_outbox set state = 'PENDING', window_from = ?, next_attempt_at = ?, "
                + "finished_at = null, claimed_until = null where id = ? and state = 'GAVE_UP'", ts(now), ts(now), id)
                == 1;
    }

    @Override
    public Optional<CallbackEntry> find(UUID id) {
        List<CallbackEntry> l = query("select " + COLUMNS + " from int_callback_outbox where id = ?", id);
        return l.stream().findFirst();
    }

    @Override
    public List<CallbackEntry> gaveUp(UUID entity, String product, int limit) {
        return query("select " + COLUMNS + " from int_callback_outbox where state = 'GAVE_UP' and entity_uuid = ? "
                + "and product = ? order by finished_at desc, seq desc limit ?", entity, product, limit);
    }

    @Override
    public int purgeFinishedBefore(Instant before) {
        return update("delete from int_callback_outbox where state in ('SENT', 'GAVE_UP') and finished_at < ?",
                ts(before));
    }

    private int update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private List<CallbackEntry> query(String sql, Object... args) {
        List<CallbackEntry> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        } catch (SQLException e) {
            throw failed(e);
        }
    }

    private static CallbackEntry row(ResultSet rs) throws SQLException {
        return new CallbackEntry(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("entity_uuid", UUID.class), rs.getString("product"), rs.getString("body"),
                CallbackState.valueOf(rs.getString("state")), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("window_from").toInstant(),
                rs.getTimestamp("next_attempt_at").toInstant(), instant(rs.getTimestamp("claimed_until")),
                rs.getString("last_outcome"), rs.getString("last_code"), rs.getInt("last_status"),
                instant(rs.getTimestamp("finished_at")));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static IllegalStateException failed(SQLException e) {
        // SQL state only: the statement's values (a body) are not repeated.
        return new IllegalStateException("Callback outbox: the database refused the request (SQL state "
                + e.getSQLState() + ")");
    }
}
