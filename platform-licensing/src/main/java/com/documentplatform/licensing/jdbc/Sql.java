package com.documentplatform.licensing.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC helpers shared by the Postgres stores. */
final class Sql {

    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private Sql() {
    }

    static <T> T run(DataSource ds, String operation, Work<T> work) {
        try (Connection c = ds.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new LicensingStoreException(operation, e);
        }
    }

    static <T> T inTransaction(DataSource ds, String operation, int isolation, Work<T> work) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            int level = c.getTransactionIsolation();
            c.setAutoCommit(false);
            c.setTransactionIsolation(isolation);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
                c.setTransactionIsolation(level);
            }
        } catch (SQLException e) {
            throw new LicensingStoreException(operation, e);
        }
    }

    static <T> T inTransaction(DataSource ds, String operation, Work<T> work) {
        return inTransaction(ds, operation, Connection.TRANSACTION_READ_COMMITTED, work);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
