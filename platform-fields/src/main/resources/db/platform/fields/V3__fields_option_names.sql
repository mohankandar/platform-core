-- platform-fields schema, version 3 (P059, LV-28). Run by the app's schema step like versions 1 and 2, as the schema
-- owner, after version 2. No new rights for the app's database user.
--
-- A dropdown value may have a display name: option_names holds them in the order of options, null for a value
-- without one. Rows written before keep option_names null: every value shows its code, as before. Only the code is
-- stored, matched and searched in fld_value; a name is configuration and can change without touching any value.

update fld_schema_version set version = 3, installed_at = now();

alter table fld_field_definition add column option_names text[];

alter table fld_field_definition add constraint fld_field_definition_option_names check (
    option_names is null or (options is not null and cardinality(option_names) = cardinality(options)));
