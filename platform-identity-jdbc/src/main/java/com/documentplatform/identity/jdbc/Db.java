package com.documentplatform.identity.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Where a store gets its connection: from a DataSource (one connection per call, closed after it), or a connection
 * the caller owns (never closed here; when the caller has a transaction open, the work joins it).
 */
final class Db {

    private final DataSource ds;
    private final Connection fixed;

    private Db(DataSource ds, Connection fixed) {
        this.ds = ds;
        this.fixed = fixed;
    }

    static Db of(DataSource ds) {
        return new Db(Objects.requireNonNull(ds, "ds"), null);
    }

    static Db of(Connection c) {
        return new Db(null, Objects.requireNonNull(c, "connection"));
    }

    <T> T read(String operation, Sql.Work<T> work) {
        if (fixed != null) {
            try {
                return work.run(fixed);
            } catch (SQLException e) {
                throw new IdentityStoreException(operation, e);
            }
        }
        return Sql.run(ds, operation, work);
    }

    <T> T inTransaction(String operation, Sql.Work<T> work) {
        if (fixed != null) {
            try {
                return Sql.inTransaction(fixed, work);
            } catch (SQLException e) {
                throw new IdentityStoreException(operation, e);
            }
        }
        return Sql.inTransaction(ds, operation, work);
    }
}
