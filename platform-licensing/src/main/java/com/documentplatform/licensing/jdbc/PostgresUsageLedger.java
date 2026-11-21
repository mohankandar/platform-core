package com.documentplatform.licensing.jdbc;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.usage.ChainHead;
import com.documentplatform.licensing.usage.UsageChain;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UsageLedger} on tables {@code lic_usage} and {@code lic_chain_head}. Each append locks the head row, links the
 * record to it, inserts the record and moves the head, in one transaction, so concurrent appends (also from several
 * nodes) queue up and never share a sequence number. DML only.
 */
public final class PostgresUsageLedger implements UsageLedger {

    private static final String COLUMNS = "sequence, slot_id, product, type, period, amount, unit, billable, "
            + "recorded_at, previous_hash, hash";

    private final DataSource ds;

    public PostgresUsageLedger(DataSource ds) {
        this.ds = Objects.requireNonNull(ds, "ds");
    }

    @Override
    public UsageRecord append(UsageEntry entry) {
        return Sql.inTransaction(ds, "append usage", c -> {
            // Make sure the head row exists, then lock it: appends serialise on this row.
            try (PreparedStatement ensure = c.prepareStatement("insert into lic_chain_head (id, sequence, hash) "
                    + "values (1, 0, ?) on conflict (id) do nothing")) {
                ensure.setString(1, UsageChain.GENESIS);
                ensure.executeUpdate();
            }
            ChainHead head;
            try (PreparedStatement lock = c.prepareStatement(
                    "select sequence, hash from lic_chain_head where id = 1 for update");
                 ResultSet rs = lock.executeQuery()) {
                rs.next();
                long seq = rs.getLong(1);
                head = seq == 0 ? null : new ChainHead(seq, rs.getString(2));
            }
            UsageRecord r = UsageChain.link(head, entry);
            try (PreparedStatement ins = c.prepareStatement("insert into lic_usage (" + COLUMNS
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ins.setLong(1, r.sequence());
                ins.setString(2, r.slotId());
                ins.setString(3, r.product());
                ins.setString(4, r.type().name());
                ins.setString(5, r.period().toString());
                ins.setLong(6, r.amount());
                ins.setString(7, r.unit());
                ins.setBoolean(8, r.billable());
                ins.setObject(9, Sql.ts(r.recordedAt()));
                ins.setString(10, r.previousHash());
                ins.setString(11, r.hash());
                ins.executeUpdate();
            }
            try (PreparedStatement move = c.prepareStatement(
                    "update lic_chain_head set sequence = ?, hash = ?, updated_at = now() where id = 1")) {
                move.setLong(1, r.sequence());
                move.setString(2, r.hash());
                move.executeUpdate();
            }
            return r;
        });
    }

    @Override
    public List<UsageRecord> records() {
        return Sql.run(ds, "read usage", PostgresUsageLedger::readRecords);
    }

    @Override
    public Optional<ChainHead> head() {
        return Sql.run(ds, "read chain head", PostgresUsageLedger::readHead);
    }

    @Override
    public Snapshot snapshot() {
        return Sql.inTransaction(ds, "read usage snapshot", Connection.TRANSACTION_REPEATABLE_READ,
                c -> new Snapshot(readRecords(c), readHead(c)));
    }

    @Override
    public long usage(String slotId, String product, AllowanceType type, YearMonth period) {
        return aggregate(slotId, product, type, period, false);
    }

    @Override
    public long billableUsage(String slotId, String product, AllowanceType type, YearMonth period) {
        return aggregate(slotId, product, type, period, true);
    }

    private long aggregate(String slotId, String product, AllowanceType type, YearMonth period, boolean billableOnly) {
        String fn = type.aggregation() == AllowanceType.Aggregation.SUM ? "sum" : "max";
        return Sql.run(ds, "read usage total", c -> {
            try (PreparedStatement ps = c.prepareStatement("select coalesce(" + fn + "(amount), 0) from lic_usage "
                    + "where slot_id = ? and product = ? and type = ? and period = ?"
                    + (billableOnly ? " and billable" : ""))) {
                ps.setString(1, slotId);
                ps.setString(2, product);
                ps.setString(3, type.name());
                ps.setString(4, period.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static List<UsageRecord> readRecords(Connection c) throws SQLException {
        List<UsageRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select " + COLUMNS + " from lic_usage order by sequence");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new UsageRecord(rs.getLong("sequence"), rs.getString("slot_id"), rs.getString("product"),
                        AllowanceType.valueOf(rs.getString("type")), YearMonth.parse(rs.getString("period")),
                        rs.getLong("amount"), rs.getString("unit"), rs.getBoolean("billable"),
                        Sql.instant(rs, "recorded_at"), rs.getString("previous_hash"), rs.getString("hash")));
            }
        }
        return out;
    }

    private static Optional<ChainHead> readHead(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select sequence, hash from lic_chain_head where id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || rs.getLong(1) == 0) {
                return Optional.empty();
            }
            return Optional.of(new ChainHead(rs.getLong(1), rs.getString(2)));
        }
    }
}
