-- platform-model-governance schema, version 4: a validation records where the model ran (P039, design section 4: a
-- change of where a model runs goes through the gate). Rows recorded before stay without it (null); the gate then
-- reads the change from the model's history. No new runtime rights.

update mg_schema_version set version = 4;

alter table mg_validation add column runs_where text
    check (runs_where is null or runs_where in ('CUSTOMER_ENVIRONMENT', 'HOSTED'));
