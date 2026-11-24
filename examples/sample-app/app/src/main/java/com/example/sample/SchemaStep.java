package com.example.sample;

import org.flywaydb.core.Flyway;

import java.util.List;

/**
 * The app's schema step, as the schema owner: one Flyway run per module with its own history table, as each install guide
 * says. Settings from the environment: SCHEMA_DB_URL, SCHEMA_DB_USER, SCHEMA_DB_PASSWORD, SCHEMA_NAME.
 */
final class SchemaStep {

    private record Run(String location, String history) {
    }

    private static final List<Run> RUNS = List.of(
            new Run("db/platform/licensing", "lic_schema_history"),
            new Run("db/platform/identity", "idn_schema_history"),
            new Run("db/platform/notifications", "notif_schema_history"),
            new Run("db/platform/fields", "fld_schema_history"),
            new Run("db/platform/erase-log", "erl_schema_history"),
            new Run("db/platform/integration", "int_schema_history"),
            new Run("db/platform/modelgov", "mg_schema_history"));

    private SchemaStep() {
    }

    public static void main(String[] args) {
        for (Run run : RUNS) {
            var result = Flyway.configure()
                    .dataSource(System.getenv("SCHEMA_DB_URL"), System.getenv("SCHEMA_DB_USER"), System.getenv("SCHEMA_DB_PASSWORD"))
                    .schemas(System.getenv("SCHEMA_NAME")).defaultSchema(System.getenv("SCHEMA_NAME")).createSchemas(false)
                    .locations("classpath:" + run.location()).table(run.history())
                    // The schema holds the other runs' tables already.
                    .baselineOnMigrate(true).baselineVersion("0")
                    .load().migrate();
            System.out.println("schema " + run.location() + ": " + result.migrationsExecuted + " file(s) run, now at version "
                    + result.targetSchemaVersion);
        }
    }
}
