-- platform-integration schema, version 1.
--
-- Each app runs this file in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/integration and the history table int_schema_history. Table names are unqualified: they are
-- created in the schema the app's connection uses.
--
-- The app never runs DDL at startup: it checks int_schema_version (IntegrationSchema.verify) and stops with a specific
-- message if the tables are missing or at another version. At runtime the app's database user needs only:
--   SELECT, INSERT, UPDATE on int_integration
--   SELECT, INSERT         on int_integration_version
--   SELECT                 on int_schema_version
--
-- What is stored: each integration's configuration, every version of it, with who and when, and the names of the
-- fields each version changed. Secrets are never stored here, only references into the secret store.

-- Which version of these files was run. One row; a later file updates it.
create table int_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);
insert into int_schema_version (version) values (1);

-- One row per integration: its current version.
create table int_integration (
    id          uuid        primary key,
    entity_uuid uuid        not null,
    product     text        not null check (product ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    kind        text        not null check (kind in ('API_PUSH', 'FOLDER_EXPORT', 'API_POLL', 'WATCHED_FOLDER')),
    direction   text        not null check (direction in ('OUTBOUND', 'INBOUND')),
    name        text        not null,
    enabled     boolean     not null,
    version     integer     not null check (version >= 1),
    created_by  text        not null,
    created_at  timestamptz not null,
    changed_by  text        not null,
    changed_at  timestamptz not null
);
create index int_integration_scope on int_integration (entity_uuid, product);

-- Every version, kept for the audit. Never changed or deleted.
create table int_integration_version (
    integration_id uuid        not null references int_integration (id),
    version        integer     not null check (version >= 1),
    config         jsonb       not null,
    changed_by     text        not null,
    changed_at     timestamptz not null,
    -- Field names only (signIn.credential, steps[0].path, ...), never values.
    changed_fields text[]      not null,
    -- Codes of the warnings given when saved (for example INT_CONFIG_SIGN_IN_NONE_WARNING).
    warnings       text[]      not null,
    primary key (integration_id, version)
);

create function int_integration_forward() returns trigger language plpgsql as $$
begin
    if new.version <> old.version + 1 then
        raise exception 'int_integration: the version moves up by one (% to %)', old.version, new.version
            using errcode = 'check_violation';
    end if;
    if new.entity_uuid <> old.entity_uuid or new.product <> old.product or new.kind <> old.kind
            or new.created_at <> old.created_at or new.created_by <> old.created_by then
        raise exception 'int_integration: entity, product, kind and creation never change'
            using errcode = 'check_violation';
    end if;
    if not exists (select 1 from int_integration_version v where v.integration_id = new.id
                   and v.version = new.version) then
        raise exception 'int_integration: version % has no history row', new.version
            using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger int_integration_forward before update on int_integration
    for each row execute function int_integration_forward();

create function int_no_delete() returns trigger language plpgsql as $$
begin
    raise exception '%: rows are never deleted', tg_table_name using errcode = 'check_violation';
end
$$;

create trigger int_integration_no_delete before delete on int_integration
    for each row execute function int_no_delete();

create function int_version_frozen() returns trigger language plpgsql as $$
begin
    raise exception 'int_integration_version: a stored version never changes' using errcode = 'check_violation';
end
$$;

create trigger int_integration_version_frozen before update or delete on int_integration_version
    for each row execute function int_version_frozen();
