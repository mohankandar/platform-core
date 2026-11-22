-- platform-fields, optional: partial ("contains") search on values that are not sensitive.
--
-- Not a Flyway file. The fields schema step (V1__fields.sql) already does this when pg_trgm can be installed; run this
-- later, as the owner of the fld_ tables, once the DBA has made the extension available (create extension pg_trgm, or
-- allow the owner to create it). Safe to run again. Until it has run, partial search works as prefix search and the
-- app's search status names this file.
do $$
declare
    ext_schema text;
begin
    create extension if not exists pg_trgm;
    select n.nspname into ext_schema from pg_extension e join pg_namespace n on n.oid = e.extnamespace
        where e.extname = 'pg_trgm';
    if to_regclass('fld_value_partial') is null then
        execute format('create index fld_value_partial on fld_value using gin (search_value %I.gin_trgm_ops) '
                       'where search_value is not null', ext_schema);
    end if;
end
$$;
