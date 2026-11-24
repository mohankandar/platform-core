-- platform-licensing schema, version 2 (P045, SEC-13). Run by the app's schema step as the schema owner.
--
-- The app's database user can no longer undo the clock rule or bring back an older licence:
--   lic_last_seen      only moves forward; only the schema owner (a signed reset, licence apply-reset) moves it back;
--                      nobody deletes it;
--   lic_clock_reset    insert only, so an applied reset can never be applied again;
--   lic_version_floor  new: the highest licence version ever in each slot, kept by the database itself. The app's user
--                      needs only SELECT on it.
-- New right for the app's database user: SELECT on lic_version_floor. No longer needed: DELETE on lic_last_seen, UPDATE
-- and DELETE on lic_clock_reset.

update lic_schema_version set version = 2, installed_at = now();

create table lic_version_floor (
    slot    text   primary key check (slot in ('CURRENT', 'WAITING')),
    version bigint not null
);

insert into lic_version_floor (slot, version) select slot, version from lic_licence;

create function lic_last_seen_guard() returns trigger language plpgsql as $$
declare
    owner_name name;
begin
    if tg_op <> 'UPDATE' then
        raise exception 'the latest time seen is never removed' using errcode = '42501';
    end if;
    if new.id <> old.id or new.last_seen < old.last_seen then
        select pg_catalog.pg_get_userbyid(c.relowner) into owner_name
            from pg_catalog.pg_class c where c.oid = tg_relid;
        if current_user <> owner_name then
            raise exception 'only the schema owner applies a clock reset' using errcode = '42501';
        end if;
    end if;
    return new;
end
$$;

create trigger lic_last_seen_forward before update or delete on lic_last_seen
    for each row execute function lic_last_seen_guard();
create trigger lic_last_seen_no_truncate before truncate on lic_last_seen
    for each statement execute function lic_last_seen_guard();

create function lic_insert_only() returns trigger language plpgsql as $$
begin
    raise exception 'this table is insert only' using errcode = '42501';
end
$$;

create trigger lic_clock_reset_insert_only before update or delete on lic_clock_reset
    for each row execute function lic_insert_only();
create trigger lic_clock_reset_no_truncate before truncate on lic_clock_reset
    for each statement execute function lic_insert_only();

-- Runs as the schema owner, so the app's user needs no right to write the floor.
create function lic_keep_version_floor() returns trigger language plpgsql security definer as $$
begin
    insert into lic_version_floor as f (slot, version) values (new.slot, new.version)
        on conflict (slot) do update set version = greatest(f.version, excluded.version);
    return null;
end
$$;

revoke execute on function lic_keep_version_floor() from public;

create trigger lic_licence_version_floor after insert or update on lic_licence
    for each row execute function lic_keep_version_floor();

create function lic_floor_never_lower() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        if new.slot = old.slot and new.version >= old.version then
            return new;
        end if;
    end if;
    raise exception 'the highest licence version is never lowered' using errcode = '42501';
end
$$;

create trigger lic_version_floor_guard before update or delete on lic_version_floor
    for each row execute function lic_floor_never_lower();
create trigger lic_version_floor_no_truncate before truncate on lic_version_floor
    for each statement execute function lic_floor_never_lower();

-- Names are looked up in pg_catalog and this schema only, pg_temp last (P045, SEC-34).
do $$
declare
    f record;
begin
    for f in select p.oid::regprocedure as sig
             from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid = p.pronamespace
             where n.nspname = current_schema() and p.proname like 'lic\_%'
    loop
        execute pg_catalog.format('alter function %s set search_path = pg_catalog, %I, pg_temp', f.sig,
            current_schema());
    end loop;
end
$$;
