package com.documentplatform.modelgov.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P045, SEC-33: these history tables are insert-only, even for the table's owner. Before, the runtime user could update
 * or delete their rows (for example a "where the model runs" row, which silenced MOD_LOCATION_CHANGED for older
 * validations), and nothing in the database stopped it.
 */
class HistoryInsertOnlyTest {

    static final List<String> TABLES = List.of("mg_model_history", "mg_model_use_history");

    @Test
    void historyRowsAreNeverUpdatedDeletedOrTruncated() throws Exception {
        Pg db = Pg.migrated();
        try (Connection c = db.owner.getConnection(); Statement s = c.createStatement()) {
            for (String table : TABLES) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute("truncate " + table + " cascade"),
                        table);
                assertTrue(e.getMessage().contains("insert-only"), e.getMessage());
                try (ResultSet rs = s.executeQuery("select count(*) from pg_catalog.pg_trigger t join "
                        + "pg_catalog.pg_class r on r.oid = t.tgrelid where r.relname = '" + table + "' and r.relnamespace = "
                        + "(select oid from pg_catalog.pg_namespace where nspname = current_schema()) and "
                        + "not t.tgisinternal and (t.tgtype & 24) = 24")) { // a row trigger for UPDATE and DELETE
                    rs.next();
                    assertEquals(1, rs.getInt(1), table + " has no trigger refusing UPDATE and DELETE");
                }
            }
        }
    }
}
