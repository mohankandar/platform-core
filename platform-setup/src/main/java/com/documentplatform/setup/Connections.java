package com.documentplatform.setup;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Set;
import java.util.logging.Logger;

/** DataSources for the command line. */
final class Connections {

    private Connections() {
    }

    /** One new connection per call over {@link DriverManager}, no pool. */
    static DataSource driverManager(String url, String user, String password) {
        return new Simple() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }
        };
    }

    /**
     * Hands out the setup's own connection, inside its one open transaction, to stores that take a DataSource (the
     * licensing stores). The stores' own commit, rollback, auto-commit, isolation and close calls do nothing, so their
     * writes join the setup's transaction and are committed or rolled back with it.
     */
    static DataSource joined(Connection c) {
        // rollback(Savepoint) is passed on; only the whole-transaction calls are ignored.
        Set<String> noArgs = Set.of("close", "commit", "rollback");
        Set<String> oneArg = Set.of("setAutoCommit", "setTransactionIsolation");
        Connection proxy = (Connection) Proxy.newProxyInstance(Connections.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, method, args) -> {
                    int n = args == null ? 0 : args.length;
                    if (n == 0 && noArgs.contains(method.getName()) || n == 1 && oneArg.contains(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(c, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new Simple() {
            @Override
            public Connection getConnection() {
                return proxy;
            }
        };
    }

    private abstract static class Simple implements DataSource {
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
