package com.documentplatform.licensing.jdbc;

import com.documentplatform.licensing.LicenceStore;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** {@link LicenceStore} on table {@code lic_licence}. DML only. */
public final class PostgresLicenceStore implements LicenceStore {

    private final DataSource ds;

    public PostgresLicenceStore(DataSource ds) {
        this.ds = Objects.requireNonNull(ds, "ds");
    }

    @Override
    public Optional<Stored> get(Slot slot) {
        return Sql.run(ds, "read licence", c -> {
            try (PreparedStatement ps = c.prepareStatement("select file, licence_id, version, tenant_id, starts_on, "
                    + "stored_at from lic_licence where slot = ?")) {
                ps.setString(1, slot.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Stored(rs.getString("file"), rs.getString("licence_id"),
                            rs.getLong("version"), rs.getString("tenant_id"),
                            rs.getObject("starts_on", LocalDate.class), Sql.instant(rs, "stored_at")));
                }
            }
        });
    }

    @Override
    public void put(Slot slot, Stored l) {
        Sql.run(ds, "store licence", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into lic_licence (slot, file, licence_id, version, "
                    + "tenant_id, starts_on, stored_at) values (?, ?, ?, ?, ?, ?, ?) on conflict (slot) do update set "
                    + "file = excluded.file, licence_id = excluded.licence_id, version = excluded.version, "
                    + "tenant_id = excluded.tenant_id, starts_on = excluded.starts_on, stored_at = excluded.stored_at")) {
                ps.setString(1, slot.name());
                ps.setString(2, l.file());
                ps.setString(3, l.licenceId());
                ps.setLong(4, l.version());
                ps.setString(5, l.tenantId());
                ps.setObject(6, l.startsOn());
                ps.setObject(7, Sql.ts(l.storedAt()));
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public void remove(Slot slot) {
        Sql.run(ds, "remove licence", c -> {
            try (PreparedStatement ps = c.prepareStatement("delete from lic_licence where slot = ?")) {
                ps.setString(1, slot.name());
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public long highestVersion(Slot slot) {
        return Sql.run(ds, "read highest licence version", c -> {
            try (PreparedStatement ps = c.prepareStatement("select version from lic_version_floor where slot = ?")) {
                ps.setString(1, slot.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public void promoteWaiting(String licenceId) {
        Sql.inTransaction(ds, "promote waiting licence", c -> {
            try (PreparedStatement lock = c.prepareStatement(
                    "select licence_id from lic_licence where slot = 'WAITING' for update")) {
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next() || !rs.getString(1).equals(licenceId)) {
                        return null; // promoted already by another node, or replaced
                    }
                }
            }
            try (PreparedStatement del = c.prepareStatement("delete from lic_licence where slot = 'CURRENT'");
                 PreparedStatement move = c.prepareStatement(
                         "update lic_licence set slot = 'CURRENT' where slot = 'WAITING'")) {
                del.executeUpdate();
                move.executeUpdate();
            }
            return null;
        });
    }
}
