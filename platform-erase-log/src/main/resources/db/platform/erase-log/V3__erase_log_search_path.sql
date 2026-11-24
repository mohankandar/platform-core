-- platform-erase-log schema, version 3 (P045, SEC-34). Run by the app's schema step like the earlier versions, as the
-- schema owner. No new rights for the app's database user.

update erl_schema_version set version = 3, installed_at = now();

-- Every function of this schema looks names up in pg_catalog and this schema only, with pg_temp named last so it is
-- never searched first: a temporary table or function named like a catalog object or one of these tables cannot
-- change what a trigger checks (P045, SEC-34, for every schema).
do $$
declare
    f record;
begin
    for f in select p.oid::regprocedure as sig
             from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid = p.pronamespace
             where n.nspname = current_schema() and p.proname like 'erl\_%'
    loop
        execute pg_catalog.format('alter function %s set search_path = pg_catalog, %I, pg_temp', f.sig,
            current_schema());
    end loop;
end
$$;
