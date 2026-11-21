package com.documentplatform.fields.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC helpers of the Postgres store. */
final class Sql {

    /** SQL state of a unique-index violation. */
    static final String UNIQUE_VIOLATION = "23505";

    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private Sql() {
    }

    static <T> T run(DataSource ds, String operation, Work<T> work) {
        try (Connection c = ds.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new FieldStoreException(operation, e);
        }
    }

    /** Runs the work in one transaction; a runtime exception (such as a refusal) rolls it back and is rethrown. */
    static <T> T inTransaction(DataSource ds, String operation, Work<T> work) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new FieldStoreException(operation, e);
        }
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
