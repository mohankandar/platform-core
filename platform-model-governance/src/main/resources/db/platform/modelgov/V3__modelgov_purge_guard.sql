-- platform-model-governance schema, version 3: the retention purge of readings runs only as the schema owner, with a
-- 30-day floor (P037, gap model-governance-84).
--
-- Before: any user with DELETE on mg_measure could set mg.purge_before for its own transaction and delete readings
-- of any age. Now the guard allows a delete only when the table's owner runs it, which happens only inside
-- mg_purge_measures (SECURITY DEFINER, owned by the schema owner), and that function never removes readings younger
-- than 30 days, whatever it is asked. Runtime rights: the app's user no longer needs DELETE on mg_measure (it may be
-- revoked); it needs EXECUTE on mg_purge_measures(timestamptz), which is revoked from PUBLIC here and granted by the
-- DBA (docs/install/model-governance.md). Numbers, IDs and names only: never document content or values.

update mg_schema_version set version = 3;

create or replace function mg_measure_guard() returns trigger language plpgsql as $$
declare
    purge_before timestamptz := nullif(current_setting('mg.purge_before', true), '')::timestamptz;
    owner_oid oid := (select relowner from pg_class where oid = tg_relid);
begin
    if tg_op = 'DELETE' and purge_before is not null and old.reported_at < purge_before
            and owner_oid = (select oid from pg_roles where rolname = current_user) then
        return old;
    end if;
    raise exception '%: rows are insert-only (readings are removed only by the retention purge)', tg_table_name
        using errcode = 'check_violation';
end
$$;

-- The retention purge: removes the readings reported before least(before, 30 days before the database's time).
create function mg_purge_measures(before timestamptz) returns integer
    language plpgsql security definer set search_path from current as $$
declare
    cutoff timestamptz := least(before, now() - interval '30 days');
    removed integer;
begin
    perform set_config('mg.purge_before', cutoff::text, true);
    delete from mg_measure where reported_at < cutoff;
    get diagnostics removed = row_count;
    perform set_config('mg.purge_before', '', true);
    return removed;
end
$$;

revoke all on function mg_purge_measures(timestamptz) from public;
