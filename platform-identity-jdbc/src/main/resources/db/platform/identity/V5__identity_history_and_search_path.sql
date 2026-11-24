-- platform-identity schema, version 5 (P045, SEC-33, SEC-34). Run by the app's schema step like the earlier versions,
-- as the schema owner. The app's database user needs only SELECT and INSERT on the two history tables below (the
-- install guide no longer grants UPDATE or DELETE on them).

update idn_schema_version set version = 5;

-- SEC-33: the history of login connections and of machine (integration client) bindings is insert-only, even for the
-- table's owner, like the grant history (V3).
create function idn_history_insert_only() returns trigger language plpgsql as $$
begin
    raise exception '%: rows are insert-only (never updated or deleted)', tg_table_name
        using errcode = 'check_violation';
end
$$;

create trigger idn_login_connection_history_insert_only before update or delete on idn_login_connection_history
    for each row execute function idn_history_insert_only();
create trigger idn_login_connection_history_no_truncate before truncate on idn_login_connection_history
    for each statement execute function idn_history_insert_only();
create trigger idn_machine_binding_history_insert_only before update or delete on idn_machine_binding_history
    for each row execute function idn_history_insert_only();
create trigger idn_machine_binding_history_no_truncate before truncate on idn_machine_binding_history
    for each statement execute function idn_history_insert_only();

-- Every function of this schema looks names up in pg_catalog and this schema only, with pg_temp named last so it is
-- never searched first: a temporary table or function named like a catalog object or one of these tables cannot
-- change what a trigger checks (P045, SEC-34, for every schema).
do $$
declare
    f record;
begin
    for f in select p.oid::regprocedure as sig
             from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid = p.pronamespace
             where n.nspname = current_schema() and p.proname like 'idn\_%'
    loop
        execute pg_catalog.format('alter function %s set search_path = pg_catalog, %I, pg_temp', f.sig,
            current_schema());
    end loop;
end
$$;
