-- platform-fields schema, version 1.
--
-- Each app runs these files in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/fields and the history table fld_schema_history. Table names are unqualified: they are
-- created in the schema the app's connection uses.
--
-- What is stored: case field definitions per entity (entity_uuid) and product: key, label, type, rules, flags,
-- display order, on/off, version; every version in the history; the app's own settings per field; the field values
-- of the app's records (fld_record, fld_value). A sensitive value is never stored readably: only sealed (AES-256-GCM
-- under the record's envelope, whose data key is wrapped by the customer's key in the key service) and, when it is
-- searchable or primary, as a keyed fingerprint (HMAC computed in the key service). What is never stored here:
-- secrets, plaintext keys, document data.
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
    field_type    text        not null check (field_type in ('TEXT', 'NUMBER', 'DECIMAL', 'MONEY', 'DATE',
                                                             'DATE_TIME', 'EMAIL', 'UUID',
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

-- ---- Field values ----

-- One record of the app (a business record, a case) that field values belong to: the app's record type and ID in one
-- entity and product, of one customer. identity: a hash of the record's primary field values (the normalised value
-- of a non-sensitive field, the fingerprint of a sensitive one, never a readable sensitive value); one record per
-- identity in each entity, product and record type, so the same primary value(s) always lead to the same record.
-- envelope: the record's wrapped data key (pck1.), when the record has sensitive values.
create table fld_record (
    record_uuid  uuid        primary key,
    customer_id  uuid        not null,
    entity_uuid  uuid        not null,
    product_code text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    record_type  text        not null check (record_type ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    record_id    text        not null check (record_id ~ '^[A-Za-z0-9_.:@/-]{1,200}$'),
    identity     text        check (identity ~ '^[A-Za-z0-9_-]{43}$'),
    envelope     text        check (envelope ~ '^pck1\.[A-Za-z0-9_-]+$'),
    version      integer     not null check (version >= 1),
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

create unique index fld_record_ref on fld_record (entity_uuid, product_code, record_type, record_id);

-- The primary rule: one record per primary value (set) per entity, product and record type. Enforced here, so it
-- holds with any number of concurrent writers.
create unique index fld_record_identity on fld_record (entity_uuid, product_code, record_type, identity)
    where identity is not null;

-- One value of one field of a record (a multi-value field has one row per value, position 0..49).
-- Not sensitive: display_value and normalised_value as given and as normalised (an address: its parts separated by
-- U+001F), search_value = the normalised value in lower case with single spaces (used for exact, prefix and partial
-- search and for the primary rule). Sensitive: only sealed_value (pci1., sealed under the record's envelope) and, if
-- the field is searchable or primary, fingerprint (f<version>.<HMAC>); the readable columns stay empty, which the
-- checks below enforce. The scope columns repeat the record's, for the search indexes.
create table fld_value (
    record_uuid      uuid     not null references fld_record (record_uuid),
    customer_id      uuid     not null,
    entity_uuid      uuid     not null,
    product_code     text     not null,
    record_type      text     not null,
    field_key        text     not null check (field_key ~ '^[A-Za-z][A-Za-z0-9_.-]{0,99}$'),
    position         smallint not null check (position between 0 and 49),
    sensitive        boolean  not null,
    display_value    text     check (length(display_value) <= 1024),
    normalised_value text     check (length(normalised_value) <= 1024),
    search_value     text     check (length(search_value) <= 1024),
    sealed_value     text     check (sealed_value ~ '^pci1\.[A-Za-z0-9_-]+$'),
    fingerprint      text     check (fingerprint ~ '^f[1-9][0-9]{0,8}\.[A-Za-z0-9_-]{43}$'),
    primary key (record_uuid, field_key, position),
    check (sensitive = (sealed_value is not null)),
    check (not sensitive or (display_value is null and normalised_value is null and search_value is null)),
    check (sensitive or (display_value is not null and normalised_value is not null and search_value is not null
                         and fingerprint is null))
);

-- Exact and prefix search on values that are not sensitive (text_pattern_ops serves = and LIKE 'abc%').
create index fld_value_search on fld_value (entity_uuid, product_code, record_type, field_key,
                                            search_value text_pattern_ops) where search_value is not null;

-- Exact search on sensitive values, by fingerprint.
create index fld_value_fingerprint on fld_value (entity_uuid, product_code, record_type, field_key, fingerprint)
    where fingerprint is not null;

create index fld_value_field on fld_value (entity_uuid, product_code, lower(field_key));

-- Partial search ("contains") uses a trigram index (extension pg_trgm, shipped with PostgreSQL). Created here when the
-- extension is installed or can be installed by the user running this step; otherwise this step goes on without it,
-- partial search works as prefix search, and the app's search status says so. The DBA can add it later with
-- db/platform/fields-optional/partial-search.sql (same statements).
do $$
declare
    ext_schema text;
begin
    begin
        create extension if not exists pg_trgm;
    exception when others then
        raise notice 'fld: pg_trgm is not available (%); partial search will work as prefix search', sqlerrm;
    end;
    select n.nspname into ext_schema from pg_extension e join pg_namespace n on n.oid = e.extnamespace
        where e.extname = 'pg_trgm';
    if ext_schema is not null then
        execute format('create index fld_value_partial on fld_value using gin (search_value %I.gin_trgm_ops) '
                       'where search_value is not null', ext_schema);
    end if;
end
$$;
