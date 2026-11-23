package com.documentplatform.fields.jdbc;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static com.documentplatform.fields.jdbc.FieldSchemaException.Problem.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fld_ tables exist only through the schema step; startup only checks them. */
class FieldSchemaTest {

    private static final String DML = "select, insert, update, delete";

    @Test
    void theTablesComeOnlyFromTheSchemaRun() {
        Pg empty = Pg.empty();
        FieldSchemaException e = assertThrows(FieldSchemaException.class, () -> FieldSchema.verify(empty.owner));
        assertEquals(FLD_TABLES_MISSING, e.problem());
        assertEquals("Field tables missing (expected version 1): none of the fld_ tables exist in the database "
                + "schema the app connects to; run the fields schema step (Flyway location db/platform/fields, "
                + "history table fld_schema_history) before starting the app.", e.getMessage());
        assertEquals(List.of(), empty.tables(), "the startup check created nothing");

        Pg migrated = Pg.migrated();
        assertEquals(List.of("fld_field_definition", "fld_field_definition_history", "fld_field_extension",
                "fld_record", "fld_schema_version", "fld_value"), migrated.tables());
        assertEquals(1, migrated.count("select max(version) from fld_schema_version"));
        assertEquals(0, migrated.count("select count(*) from fld_field_definition"), "no data from the schema run");
        assertEquals("db/platform/fields", FieldSchema.LOCATION);
        assertEquals("fld_schema_history", FieldSchema.HISTORY_TABLE);
        assertDoesNotThrow(() -> FieldSchema.verify(migrated.appUser(DML)), "a DML-only runtime user is enough");
    }

    @Test
    void startupRefusalCodes() {
        Pg older = Pg.migrated();
        older.exec("update fld_schema_version set version = 0");
        assertEquals(FLD_VERSION_OLDER, problem(older.owner));

        Pg newer = Pg.migrated();
        newer.exec("update fld_schema_version set version = 2");
        FieldSchemaException n = assertThrows(FieldSchemaException.class, () -> FieldSchema.verify(newer.owner));
        assertEquals(FLD_VERSION_NEWER, n.problem());
        assertTrue(n.getMessage().startsWith("Field tables at version 2, newer than this app expects (version 1)"));

        Pg noVersion = Pg.migrated();
        noVersion.exec("drop table fld_schema_version");
        assertEquals(FLD_VERSION_UNKNOWN, problem(noVersion.owner));

        Pg emptyVersion = Pg.migrated();
        emptyVersion.exec("delete from fld_schema_version");
        assertEquals(FLD_VERSION_UNKNOWN, problem(emptyVersion.owner));

        Pg incomplete = Pg.migrated();
        incomplete.exec("drop table fld_field_extension");
        FieldSchemaException i = assertThrows(FieldSchemaException.class, () -> FieldSchema.verify(incomplete.owner));
        assertEquals(FLD_TABLES_INCOMPLETE, i.problem());
        assertEquals("Field tables incomplete at version 1, missing: fld_field_extension; run the fields schema step "
                + "(Flyway location db/platform/fields, history table fld_schema_history) before starting the app.",
                i.getMessage());

        Pg readOnly = Pg.migrated();
        FieldSchemaException rights = assertThrows(FieldSchemaException.class,
                () -> FieldSchema.verify(readOnly.appUser("select")));
        assertEquals(FLD_PRIVILEGES_MISSING, rights.problem());
        assertTrue(rights.getMessage().startsWith("The app's database user lacks INSERT, UPDATE on "
                + "fld_field_definition"), rights.getMessage());

        Pg noVersionRead = Pg.migrated();
        noVersionRead.exec("revoke all on fld_schema_version from public");
        DataSource app = noVersionRead.appUser(DML);
        noVersionRead.exec("revoke select on fld_schema_version from " + noVersionRead.schema + "_app");
        assertEquals(FLD_PRIVILEGES_MISSING, problem(app));
    }

    /** case-fields-113: the erase deletes the record row, so the runtime user needs DELETE on fld_record. */
    @Test
    void aUserWithEveryRightButDeleteOnTheRecordTableIsRefused() {
        Pg db = Pg.migrated();
        DataSource app = db.appUser(DML);
        db.exec("revoke delete on fld_record from " + db.schema + "_app");
        FieldSchemaException e = assertThrows(FieldSchemaException.class, () -> FieldSchema.verify(app));
        assertEquals(FLD_PRIVILEGES_MISSING, e.problem());
        assertTrue(e.getMessage().startsWith("The app's database user lacks DELETE on fld_record"), e.getMessage());
    }

    @Test
    void theDatabaseRefusesDeletesAndKeyChanges() {
        Pg db = Pg.migrated();
        db.exec("""
                insert into fld_field_definition (definition_id, entity_uuid, product_code, field_key, label,
                    field_type, required, letter_case, strip, primary_flag, searchable, search_mode, sensitive,
                    multi_value, displayable, display_order, active, version, created_at, updated_at, updated_by)
                values ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000e1', 'PRODUCT_A',
                    'loan_number', 'Loan number', 'TEXT', false, 'PRESERVE', 'NONE', false, false, 'EXACT', false,
                    false, true, 0, true, 1, now(), now(), 'u-admin')""");
        db.exec("""
                insert into fld_field_definition_history (definition_id, version, change, changed_at, changed_by,
                    definition) values ('00000000-0000-0000-0000-000000000001', 1, 'CREATED', now(), 'u-admin', '{}')""");
        DataSource app = db.appUser(DML);
        for (String sql : List.of(
                "delete from fld_field_definition",
                "delete from fld_field_definition_history",
                "update fld_field_definition set field_key = 'loan_no'",
                "update fld_field_definition set entity_uuid = '00000000-0000-0000-0000-0000000000e2'",
                "update fld_field_definition_history set changed_by = 'someone'",
                "update fld_field_definition set sensitive = true, searchable = true, search_mode = 'PARTIAL'",
                "update fld_field_definition set field_type = 'MONEY'",
                "update fld_field_definition set primary_flag = true, active = false")) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Pg.exec(app, sql), sql);
            assertTrue(e.getCause().getMessage().startsWith("ERROR"), sql);
        }
        assertEquals(1, db.count("select count(*) from fld_field_definition where field_key = 'loan_number'"));
        Pg.exec(app, "update fld_field_definition set label = 'Loan no.', version = 2");
        assertEquals(1, db.count("select count(*) from fld_field_definition where label = 'Loan no.'"));
    }

    private static FieldSchemaException.Problem problem(DataSource ds) {
        return assertThrows(FieldSchemaException.class, () -> FieldSchema.verify(ds)).problem();
    }

    /** xc-schemas-4 (P037): the runtime user has data rights only; every DDL statement is refused by the database. */
    @Test
    void theRuntimeUserCannotRunDdl() throws Exception {
        javax.sql.DataSource app = Pg.migrated().appUser(DML);
        String table = FieldSchema.TABLES.get(0);
        for (String sql : java.util.List.of("create table fld_extra (x int)", "alter table " + table + " add column note text",
                "drop table " + table)) {
            try (java.sql.Connection c = app.getConnection(); java.sql.Statement s = c.createStatement()) {
                java.sql.SQLException e = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                        () -> s.execute(sql), sql);
                org.junit.jupiter.api.Assertions.assertEquals("42501", e.getSQLState(), sql + ": " + e.getMessage());
            }
        }
    }
}
