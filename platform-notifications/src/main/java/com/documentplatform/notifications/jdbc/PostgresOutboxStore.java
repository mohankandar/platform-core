package com.documentplatform.notifications.jdbc;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.OutboxEntry;
import com.documentplatform.notifications.OutboxState;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.documentplatform.notifications.jdbc.Sql.instant;
import static com.documentplatform.notifications.jdbc.Sql.textArray;
import static com.documentplatform.notifications.jdbc.Sql.ts;

/**
 * The outbox in Postgres (table {@code notif_outbox}). Claims use {@code FOR UPDATE SKIP LOCKED}, so several app
 * instances can work the same outbox without sending a message twice.
 */
public final class PostgresOutboxStore implements OutboxStore {

    private static final String DUE = "((state = 'PENDING' and next_attempt_at <= ?) or (state = 'SENDING' and claimed_until <= ?))";

    private final DataSource ds;

    public PostgresOutboxStore(DataSource dataSource) {
        this.ds = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean enqueue(OutboxEntry e) {
        return Sql.run(ds, "save an outbox message", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into notif_outbox (message_key, tenant_id, destination_id, channel, entity_id,
                        recipient_roles, event_types, message_kind, subject, body_lines, link, state, attempts,
                        created_at, next_attempt_at, claimed_until, last_failure, finished_at, action_request,
                        recipient_user)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?)
                    on conflict (message_key) do nothing""")) {
                Message m = e.message();
                ps.setString(1, e.key());
                ps.setString(2, e.tenant().value());
                ps.setString(3, e.destination().value());
                ps.setString(4, e.channel().name());
                ps.setString(5, e.entity() == null ? null : e.entity().value());
                ps.setArray(6, textArray(c, e.recipientRoles().stream().map(RoleId::value).toList()));
                ps.setArray(7, textArray(c, e.eventTypes().stream().map(EventTypeId::value).toList()));
                ps.setString(8, m.kind().name());
                ps.setString(9, m.subject());
                ps.setArray(10, c.createArrayOf("text", m.lines().toArray(String[]::new)));
                ps.setString(11, m.link() == null ? null : m.link().toString());
                ps.setString(12, e.state().name());
                ps.setInt(13, e.attempts());
                ps.setObject(14, ts(e.createdAt()));
                ps.setObject(15, ts(e.nextAttemptAt()));
                ps.setString(16, e.lastFailure() == null ? null : e.lastFailure().name());
                ps.setObject(17, ts(e.finishedAt()));
                ps.setString(18, m.actionRequest());
                ps.setString(19, e.recipientUser());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<OutboxEntry> claim(String key, Instant now, Instant leaseUntil) {
        return Sql.run(ds, "claim an outbox message", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "update notif_outbox set state = 'SENDING', claimed_until = ? where message_key = ? and " + DUE
                            + " returning *")) {
                ps.setObject(1, ts(leaseUntil));
                ps.setString(2, key);
                ps.setObject(3, ts(now));
                ps.setObject(4, ts(now));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(entry(rs)) : Optional.<OutboxEntry>empty();
                }
            }
        });
    }

    @Override
    public List<OutboxEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
        return Sql.run(ds, "claim due outbox messages", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    with claimed as (update notif_outbox set state = 'SENDING', claimed_until = ?
                    where message_key in (
                        select message_key from notif_outbox where %s
                        order by next_attempt_at, seq limit ? for update skip locked)
                    returning *)
                    select * from claimed order by next_attempt_at, seq""".formatted(DUE))) {
                ps.setObject(1, ts(leaseUntil));
                ps.setObject(2, ts(now));
                ps.setObject(3, ts(now));
                ps.setInt(4, limit);
                List<OutboxEntry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(entry(rs));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public void markSent(String key, int attempts, Instant at) {
        update("record a sent message", """
                update notif_outbox set state = 'SENT', attempts = ?, finished_at = ?, claimed_until = null
                where message_key = ?""", attempts, ts(at), key);
    }

    @Override
    public void markRetry(String key, int attempts, Instant nextAttemptAt, FailureCode failure) {
        update("schedule a retry", """
                update notif_outbox set state = 'PENDING', attempts = ?, next_attempt_at = ?, last_failure = ?,
                    claimed_until = null
                where message_key = ?""", attempts, ts(nextAttemptAt), failure.name(), key);
    }

    @Override
    public void markFailed(String key, int attempts, FailureCode failure, Instant at) {
        update("record a failed message", """
                update notif_outbox set state = 'FAILED', attempts = ?, last_failure = ?, finished_at = ?,
                    claimed_until = null
                where message_key = ?""", attempts, failure.name(), ts(at), key);
    }

    @Override
    public Optional<OutboxEntry> find(String key) {
        return Sql.run(ds, "read an outbox message", c -> {
            try (PreparedStatement ps = c.prepareStatement("select * from notif_outbox where message_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(entry(rs)) : Optional.<OutboxEntry>empty();
                }
            }
        });
    }

    @Override
    public int purgeFinishedBefore(Instant before) {
        return Sql.run(ds, "purge finished outbox messages", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "delete from notif_outbox where state in ('SENT', 'FAILED') and finished_at < ?")) {
                ps.setObject(1, ts(before));
                return ps.executeUpdate();
            }
        });
    }

    private void update(String operation, String sql, Object... args) {
        Sql.run(ds, operation, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    private static OutboxEntry entry(ResultSet rs) throws SQLException {
        String entity = rs.getString("entity_id");
        String link = rs.getString("link");
        String failure = rs.getString("last_failure");
        Message message = new Message(MessageKind.valueOf(rs.getString("message_kind")), rs.getString("subject"),
                Sql.list(rs, "body_lines", s -> s), link == null ? null : URI.create(link),
                rs.getString("action_request"), List.of());
        return new OutboxEntry(rs.getString("message_key"), TenantId.of(rs.getString("tenant_id")),
                DestinationId.of(rs.getString("destination_id")), ChannelKind.valueOf(rs.getString("channel")),
                entity == null ? null : EntityId.of(entity),
                new HashSet<>(Sql.list(rs, "recipient_roles", RoleId::of)),
                new HashSet<>(Sql.list(rs, "event_types", EventTypeId::of)), message,
                OutboxState.valueOf(rs.getString("state")), rs.getInt("attempts"), instant(rs, "created_at"),
                instant(rs, "next_attempt_at"), failure == null ? null : FailureCode.valueOf(failure),
                instant(rs, "finished_at"), rs.getString("recipient_user"));
    }
}
