package com.documentplatform.eraselog;

import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P045, SEC-34, for this schema: every function of it (the triggers, and any SECURITY DEFINER function) has its
 * search_path fixed to pg_catalog, the schema, and pg_temp last, so a temporary object named like a catalog object or
 * one of these tables is never what a trigger or function finds.
 */
class SearchPathTest {

    @Test
    void everyFunctionOfTheSchemaSearchesPgCatalogFirstAndPgTempLast() throws Exception {
        Pg db = Pg.migrated();
        List<String> wrong = new ArrayList<>();
        int functions = 0;
        try (Connection c = db.owner.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select p.proname, p.proconfig, current_schema() from pg_catalog.pg_proc p "
                     + "join pg_catalog.pg_namespace n on n.oid = p.pronamespace where n.nspname = current_schema() "
                     + "and p.proname like 'erl\\_%'")) {
            while (rs.next()) {
                functions++;
                Array a = rs.getArray(2);
                List<String> config = a == null ? List.of() : Arrays.asList((String[]) a.getArray());
                if (!config.contains("search_path=pg_catalog, " + rs.getString(3) + ", pg_temp")) {
                    wrong.add(rs.getString(1) + " " + config);
                }
            }
        }
        assertFalse(functions == 0, "the schema has functions");
        assertEquals(List.of(), wrong, "functions without search_path = pg_catalog, <schema>, pg_temp");
    }
}
