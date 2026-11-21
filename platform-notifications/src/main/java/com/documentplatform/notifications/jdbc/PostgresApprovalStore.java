package com.documentplatform.notifications.jdbc;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.approval.ApprovalAction;
import com.documentplatform.notifications.approval.ApprovalRequest;
import com.documentplatform.notifications.approval.ApprovalStore;
import com.documentplatform.notifications.approval.Decision;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.notifications.jdbc.Sql.instant;
import static com.documentplatform.notifications.jdbc.Sql.ts;

/**
 * Requests and their decision in Postgres (table {@code notif_approval_request}).
 *
 * <p>First decision wins, enforced by the database: the decision is written by one {@code UPDATE ... WHERE decision
 * IS NULL AND expires_at > now}. Of two such updates at the same moment, the second waits for the first's row lock,
 * then finds the row decided and changes nothing (also across app instances).
 */
public final class PostgresApprovalStore implements ApprovalStore {

    private final DataSource ds;

    public PostgresApprovalStore(DataSource dataSource) {
        this.ds = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void create(ApprovalRequest r) {
        Sql.run(ds, "save an approval request", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    insert into notif_approval_request (tenant_id, request_id, entity_id, request_type, requester,
                        identifier, link, created_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, r.tenant().value());
                ps.setObject(2, r.id());
                ps.setString(3, r.entity().value());
                ps.setString(4, r.type());
                ps.setString(5, r.requester());
                ps.setString(6, r.identifier() == null ? null : r.identifier().value());
                ps.setString(7, r.link() == null ? null : r.link().path());
                ps.setObject(8, ts(r.createdAt()));
                ps.setObject(9, ts(r.expiresAt()));
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public Optional<ApprovalRequest> find(TenantId tenant, UUID id) {
        return Sql.run(ds, "read an approval request", c -> read(c, tenant, id));
    }

    @Override
    public Stored decide(TenantId tenant, UUID id, Decision d, Instant now) {
        return Sql.run(ds, "store an approval decision", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    update notif_approval_request
                    set decision = ?, decided_by = ?, decided_by_name = ?, decided_at = ?, reason = ?
                    where tenant_id = ? and request_id = ? and decision is null and expires_at > ?
                    returning *""")) {
                ps.setString(1, d.action() == ApprovalAction.APPROVE ? "APPROVED" : "DECLINED");
                ps.setString(2, d.byUserId());
                ps.setString(3, d.byName());
                ps.setObject(4, ts(d.at()));
                ps.setString(5, d.reason());
                ps.setString(6, tenant.value());
                ps.setObject(7, id);
                ps.setObject(8, ts(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Stored(Stored.Kind.DECIDED, request(rs));
                    }
                }
            }
            Optional<ApprovalRequest> current = read(c, tenant, id);
            if (current.isEmpty()) {
                return new Stored(Stored.Kind.UNKNOWN, null);
            }
            return new Stored(current.get().decision() != null ? Stored.Kind.ALREADY_DECIDED : Stored.Kind.EXPIRED,
                    current.get());
        });
    }

    @Override
    public int purgeExpiredBefore(Instant before) {
        return Sql.run(ds, "purge expired approval requests", c -> {
            try (PreparedStatement ps = c.prepareStatement("delete from notif_approval_request where expires_at < ?")) {
                ps.setObject(1, ts(before));
                return ps.executeUpdate();
            }
        });
    }

    /**
     * One statement: the oldest untold expired requests are locked with {@code SKIP LOCKED}, so two instances at the
     * same moment claim different rows, and marked told.
     */
    @Override
    public List<ApprovalRequest> claimExpiredUndecided(Set<String> types, Instant now, int limit) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        return Sql.run(ds, "claim expired approval requests", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    update notif_approval_request set expiry_told_at = ?
                    where (tenant_id, request_id) in (
                        select tenant_id, request_id from notif_approval_request
                        where decision is null and expiry_told_at is null and expires_at <= ?
                          and request_type = any (?)
                        order by expires_at
                        limit ?
                        for update skip locked)
                    returning *""")) {
                ps.setObject(1, ts(now));
                ps.setObject(2, ts(now));
                ps.setArray(3, c.createArrayOf("text", types.toArray()));
                ps.setInt(4, limit);
                List<ApprovalRequest> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(request(rs));
                    }
                }
                out.sort(Comparator.comparing(ApprovalRequest::expiresAt));
                return out;
            }
        });
    }

    private static Optional<ApprovalRequest> read(Connection c, TenantId tenant, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select * from notif_approval_request where tenant_id = ? and request_id = ?")) {
            ps.setString(1, tenant.value());
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(request(rs)) : Optional.empty();
            }
        }
    }

    private static ApprovalRequest request(ResultSet rs) throws SQLException {
        String identifier = rs.getString("identifier");
        String link = rs.getString("link");
        String decision = rs.getString("decision");
        Decision d = decision == null ? null : new Decision(
                decision.equals("APPROVED") ? ApprovalAction.APPROVE : ApprovalAction.DECLINE,
                rs.getString("decided_by"), rs.getString("decided_by_name"), instant(rs, "decided_at"),
                rs.getString("reason"));
        return new ApprovalRequest(TenantId.of(rs.getString("tenant_id")), rs.getObject("request_id", UUID.class),
                EntityId.of(rs.getString("entity_id")), rs.getString("request_type"), rs.getString("requester"),
                identifier == null ? null : RecordIdentifier.of(identifier), link == null ? null : AppLink.of(link),
                instant(rs, "created_at"), instant(rs, "expires_at"), d);
    }
}
