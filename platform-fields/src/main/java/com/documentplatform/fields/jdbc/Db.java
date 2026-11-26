package com.documentplatform.fields.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Objects;
import java.util.function.Function;

/**
 * Where a store's calls run (P059, LV-25.2): in the store's own transaction on its {@link DataSource} (the default,
 * as before 1.3.0), or in the app's transaction on a connection the app handed over ({@code onConnection}).
 *
 * <p>On the app's connection the store never calls {@code commit}, a full {@code rollback}, {@code setAutoCommit},
 * {@code setTransactionIsolation} or {@code close}. Every call first checks that auto-commit is off and the isolation
 * is READ COMMITTED (the retry after a version conflict and the read after {@code on conflict do nothing} must see the
 * other writer's commit); otherwise it is refused before anything is written. Each write runs in its own savepoint,
 * rolled back to when the write fails, so a refused write leaves the app's transaction usable.
 */
final class Db {

    /** Turns a driver failure into the store's own exception (SQL state only, never the driver's text). */
    interface Failure {
        RuntimeException of(String operation, SQLException e);
    }

    private final DataSource ds;
    private final Connection bound;
    private final Failure failed;
    private final Function<String, RuntimeException> unsuitable;

    private Db(DataSource ds, Connection bound, Failure failed, Function<String, RuntimeException> unsuitable) {
        this.ds = ds;
        this.bound = bound;
        this.failed = failed;
        this.unsuitable = unsuitable;
    }

    /** The store's own transactions on its data source. */
    static Db own(DataSource ds, Failure failed) {
        return new Db(Objects.requireNonNull(ds, "dataSource"), null, failed, null);
    }

    /** The app's transaction on its connection; {@code unsuitable} makes the refusal for a reason. */
    static Db bound(Connection c, Failure failed, Function<String, RuntimeException> unsuitable) {
        return new Db(null, Objects.requireNonNull(c, "connection"), failed, unsuitable);
    }

    boolean isBound() {
        return bound != null;
    }

    /** A read: on a connection of its own, or on the app's. */
    <T> T read(String operation, Sql.Work<T> work) {
        if (bound != null) {
            try {
                requireSuitable(operation);
                return work.run(bound);
            } catch (SQLException e) {
                throw failed.of(operation, e);
            }
        }
        try (Connection c = ds.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw failed.of(operation, e);
        }
    }

    /**
     * A write, all or nothing: in a transaction of its own, or in a savepoint of the app's transaction. A runtime
     * exception (such as a refusal) undoes the work and is rethrown.
     */
    <T> T write(String operation, Sql.Work<T> work) {
        if (bound != null) {
            return inSavepoint(operation, work);
        }
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
            throw failed.of(operation, e);
        }
    }

    private <T> T inSavepoint(String operation, Sql.Work<T> work) {
        Savepoint savepoint;
        try {
            requireSuitable(operation);
            savepoint = bound.setSavepoint();
        } catch (SQLException e) {
            throw failed.of(operation, e);
        }
        try {
            T result = work.run(bound);
            bound.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                bound.rollback(savepoint);
            } catch (SQLException ignored) {
                // The connection is lost or the app's transaction is over: the app's own rollback or commit says so.
            }
            if (e instanceof SQLException s) {
                throw failed.of(operation, s);
            }
            throw (RuntimeException) e;
        }
    }

    private void requireSuitable(String operation) throws SQLException {
        if (bound.getAutoCommit()) {
            throw unsuitable.apply("could not " + operation + " in the app's transaction: auto-commit is on, so "
                    + "there is no transaction to join; set auto-commit off (or run inside the app's transaction "
                    + "manager) before handing the connection over");
        }
        int level = bound.getTransactionIsolation();
        if (level != Connection.TRANSACTION_READ_COMMITTED) {
            throw unsuitable.apply("could not " + operation + " in the app's transaction: its isolation is "
                    + isolation(level) + ", and the field stores join only READ COMMITTED (the database's default); "
                    + "open the transaction at READ COMMITTED");
        }
    }

    private static String isolation(int level) {
        return switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            case Connection.TRANSACTION_NONE -> "NONE";
            default -> "level " + level;
        };
    }
}
