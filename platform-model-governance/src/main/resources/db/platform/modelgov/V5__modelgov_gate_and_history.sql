-- platform-model-governance schema, version 5 (P045, security findings SEC-6, SEC-33, SEC-34).
--
-- Run by the app's schema step like the earlier versions (location classpath:db/platform/modelgov, history table
-- mg_schema_history), as the schema owner. No new rights for the app's database user.
--
-- SEC-6: what is live for a use and entity is always a validation of that same use and entity, and an approved one,
-- in the database too: a row of mg_live written by hand (or by app code going round ModelGovernance) cannot point at
-- another use's validation or at one without an approval. The gate checks the same again when the model starts.
-- SEC-33: the register's history (mg_model_history, mg_model_use_history) is insert-only, like the decisions.
-- SEC-34: every function of this schema looks names up in pg_catalog and this schema only, never in pg_temp, so a
-- temporary table named like a catalog table (pg_roles) cannot fool the guard of mg_measure.

update mg_schema_version set version = 5;

-- SEC-6
alter table mg_validation add constraint mg_validation_of_use_entity unique (validation_id, use_id, entity_uuid);
alter table mg_live add constraint mg_live_validation_of_this_use
    foreign key (validation_id, use_id, entity_uuid) references mg_validation (validation_id, use_id, entity_uuid);

create function mg_live_approved() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from mg_approval a where a.validation_id = new.validation_id) then
        raise exception 'mg_live: validation % is not approved; only an approved validation goes live',
            new.validation_id using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger mg_live_approved before insert or update on mg_live
    for each row execute function mg_live_approved();

-- SEC-33
create trigger mg_model_history_insert_only before update or delete on mg_model_history
    for each row execute function mg_insert_only();
create trigger mg_model_history_no_truncate before truncate on mg_model_history
    for each statement execute function mg_insert_only();
create trigger mg_model_use_history_insert_only before update or delete on mg_model_use_history
    for each row execute function mg_insert_only();
create trigger mg_model_use_history_no_truncate before truncate on mg_model_use_history
    for each statement execute function mg_insert_only();

-- SEC-34: the guard with every catalog name qualified, and the owner compared by OID.
create or replace function mg_measure_guard() returns trigger language plpgsql as $$
declare
    purge_before timestamptz := nullif(pg_catalog.current_setting('mg.purge_before', true), '')::timestamptz;
    owner_oid oid := (select c.relowner from pg_catalog.pg_class c where c.oid = tg_relid);
begin
    if tg_op = 'DELETE' and purge_before is not null and old.reported_at < purge_before
            and owner_oid = (select r.oid from pg_catalog.pg_roles r where r.rolname = current_user) then
        return old;
    end if;
    raise exception '%: rows are insert-only (readings are removed only by the retention purge)', tg_table_name
        using errcode = 'check_violation';
end
$$;

-- SEC-34: pg_catalog first, this schema, and pg_temp last (named, so it is never searched first) for every mg_
-- function, the SECURITY DEFINER purge included.
do $$
declare
    f record;
begin
    for f in select p.oid::regprocedure as sig
             from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid = p.pronamespace
             where n.nspname = current_schema() and p.proname like 'mg\_%'
    loop
        execute pg_catalog.format('alter function %s set search_path = pg_catalog, %I, pg_temp', f.sig,
            current_schema());
    end loop;
end
$$;
