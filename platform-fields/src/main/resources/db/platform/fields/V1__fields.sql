-- platform-fields schema, version 1.
--
-- Each app runs these files in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/fields and the history table fld_schema_history. Table names are unqualified: they are
-- created in the schema the app's connection uses.
--
-- What is stored: case field definitions per entity (entity_uuid) and product: key, label, type, rules, flags,
-- display order, on/off, version; every version in the history; the app's own settings per field. What is never
-- stored here: field values (they come with value storage, encrypted when sensitive), secrets, document data.
--
-- This is the only place the fld_ tables are created or changed. The app never runs DDL at startup: it checks
-- fld_schema_version and stops with a specific message if the tables are missing or at another version. At runtime
-- the app's database user needs only SELECT, INSERT, UPDATE and DELETE on the fld_ tables (SELECT on
-- fld_schema_version). Even so, the database itself refuses to delete a definition or a history entry, to change a
-- key, or to change a history entry: removing a field means switching it off.

-- Which version of these files was run. One row; a later file updates it.
create table fld_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);

insert into fld_schema_version (version) values (1);

-- One case field of one entity in one product.
create table fld_field_definition (
    definition_id uuid        primary key,
    entity_uuid   uuid        not null,
    product_code  text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    field_key     text        not null check (field_key ~ '^[A-Za-z][A-Za-z0-9_.-]{0,99}$'),
    label         text        not null check (length(label) between 1 and 200),
    field_type    text        not null check (field_type in ('TEXT', 'NUMBER', 'MONEY', 'DATE', 'EMAIL', 'UUID',
                                                             'DROPDOWN', 'ADDRESS', 'CUSTOM_PATTERN')),
    currency      text        check (currency ~ '^[A-Z]{3}$'),
    options       text[]      check (cardinality(options) between 1 and 100),
    required      boolean     not null,
    exact_length  integer     check (exact_length between 1 and 512),
    min_length    integer     check (min_length between 1 and 512),
    max_length    integer     check (max_length between 1 and 512),
    pattern       text        check (length(pattern) between 1 and 512),
    letter_case   text        not null check (letter_case in ('PRESERVE', 'UPPER', 'LOWER')),
    strip         text        not null check (strip in ('NONE', 'SEPARATORS', 'NON_DIGITS')),
    min_value     numeric,
    max_value     numeric,
    primary_flag  boolean     not null,
    searchable    boolean     not null,
    search_mode   text        not null check (search_mode in ('EXACT', 'PREFIX', 'PARTIAL')),
    sensitive     boolean     not null,
    multi_value   boolean     not null,
    displayable   boolean     not null,
    display_order integer     not null check (display_order between 0 and 10000),
    active        boolean     not null,
    version       integer     not null check (version >= 1),
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    updated_by    text        not null check (length(updated_by) between 1 and 200),
    check ((field_type = 'MONEY') = (currency is not null)),
    check ((field_type = 'DROPDOWN') = (options is not null)),
    check (field_type <> 'CUSTOM_PATTERN' or pattern is not null),
    check (exact_length is null or (min_length is null and max_length is null)),
    check (min_length is null or max_length is null or min_length <= max_length),
    check (min_value is null or max_value is null or min_value <= max_value),
    check (not sensitive or search_mode = 'EXACT'),
    check (searchable or search_mode = 'EXACT'),
    check (not primary_flag or (active and not multi_value))
);

-- One key per entity and product, compared ignoring capitals.
create unique index fld_field_definition_key on fld_field_definition (entity_uuid, product_code, lower(field_key));

-- Every version of every field, written with each change. Never changed or deleted.
create table fld_field_definition_history (
    definition_id uuid        not null references fld_field_definition (definition_id),
    version       integer     not null check (version >= 1),
    change        text        not null check (change in ('CREATED', 'IMPORTED', 'CHANGED', 'SWITCHED_OFF',
                                                         'SWITCHED_ON')),
    changed_at    timestamptz not null,
    changed_by    text        not null check (length(changed_by) between 1 and 200),
    definition    text        not null, -- the field as one object of the platform-fields/1 file
    primary key (definition_id, version)
);

-- The app's own settings for a field (for example "can be extracted"), by name. Not part of the shared model.
create table fld_field_extension (
    definition_id uuid        not null references fld_field_definition (definition_id),
    name          text        not null check (name ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    value         text        not null check (length(value) <= 1000),
    updated_at    timestamptz not null,
    primary key (definition_id, name)
);

-- Removing a field means switching it off: definitions and history entries are never deleted.
create function fld_refuse_delete() returns trigger language plpgsql as $$
begin
    raise exception 'fld: % rows are never deleted; switch the field off instead', tg_table_name
        using errcode = 'P0001';
end
$$;

create trigger fld_field_definition_no_delete before delete on fld_field_definition
    for each row execute function fld_refuse_delete();
create trigger fld_field_definition_history_no_delete before delete on fld_field_definition_history
    for each row execute function fld_refuse_delete();

-- A field's id, entity, product and key never change; history entries never change.
create function fld_refuse_key_change() returns trigger language plpgsql as $$
begin
    if new.definition_id <> old.definition_id or new.entity_uuid <> old.entity_uuid
            or new.product_code <> old.product_code or new.field_key <> old.field_key then
        raise exception 'fld: a field''s key never changes' using errcode = 'P0001';
    end if;
    return new;
end
$$;

create trigger fld_field_definition_key_fixed before update on fld_field_definition
    for each row execute function fld_refuse_key_change();

create function fld_refuse_update() returns trigger language plpgsql as $$
begin
    raise exception 'fld: % rows never change', tg_table_name using errcode = 'P0001';
end
$$;

create trigger fld_field_definition_history_fixed before update on fld_field_definition_history
    for each row execute function fld_refuse_update();
