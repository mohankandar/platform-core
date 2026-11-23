package com.documentplatform.eraselog;

import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import com.documentplatform.eraselog.jdbc.EraseLogSchemaException;
import com.documentplatform.eraselog.jdbc.EraseLogSchemaException.Problem;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** xc-schemas-6, -10 (P037): every outcome of the erase log's startup check, each with its code. */
class EraseLogSchemaTest {

    private static Problem problem(DataSource ds) {
        return assertThrows(EraseLogSchemaException.class, () -> EraseLogSchema.verify(ds)).problem();
    }

    @Test
    void startupRefusalCodes() {
        Pg unknown = Pg.migrated();
        unknown.exec("drop table " + EraseLogSchema.VERSION_TABLE);
        assertEquals(Problem.ERL_VERSION_UNKNOWN, problem(unknown.owner), "no version table, but the tables");

        Pg empty = Pg.migrated();
        empty.exec("delete from " + EraseLogSchema.VERSION_TABLE);
        assertEquals(Problem.ERL_VERSION_UNKNOWN, problem(empty.owner), "an empty version table");

        Pg older = Pg.migrated();
        older.exec("update " + EraseLogSchema.VERSION_TABLE + " set version = 0");
        assertEquals(Problem.ERL_VERSION_OLDER, problem(older.owner));

        Pg newer = Pg.migrated();
        newer.exec("update " + EraseLogSchema.VERSION_TABLE + " set version = " + (EraseLogSchema.VERSION + 1));
        assertEquals(Problem.ERL_VERSION_NEWER, problem(newer.owner));

        Pg incomplete = Pg.migrated();
        incomplete.exec("drop table erl_position");
        assertEquals(Problem.ERL_TABLES_INCOMPLETE, problem(incomplete.owner));

        assertDoesNotThrow(() -> EraseLogSchema.verify(Pg.migrated().owner), "the schema step's tables pass");
    }

    @Test
    void aDatabaseThatFailsDuringTheCheckGivesCheckFailedWithoutItsText() {
        EraseLogSchemaException e = assertThrows(EraseLogSchemaException.class,
                () -> EraseLogSchema.verify(failingDataSource()));
        assertEquals(Problem.ERL_CHECK_FAILED, e.problem());
        assertEquals(e.problem(), e.platformCode());
        assertTrue(e.getMessage().contains("SQL state 08006"), e.getMessage());
        assertFalse(e.getMessage().contains("Planted-Db-Pw"), e.getMessage());
    }

    /** A DataSource whose connections fail on every statement, with a planted secret in the driver's text. */
    static DataSource failingDataSource() {
        Object connection = Proxy.newProxyInstance(EraseLogSchemaTest.class.getClassLoader(),
                new Class<?>[]{java.sql.Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("close")) return null;
                    throw new SQLException("connection lost, password=Planted-Db-Pw", "08006");
                });
        return (DataSource) Proxy.newProxyInstance(EraseLogSchemaTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> {
                    if (m.getName().equals("getConnection")) return connection;
                    throw new UnsupportedOperationException(m.getName());
                });
    }
}
