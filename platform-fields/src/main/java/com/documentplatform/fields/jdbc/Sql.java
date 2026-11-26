package com.documentplatform.fields.jdbc;

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

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
