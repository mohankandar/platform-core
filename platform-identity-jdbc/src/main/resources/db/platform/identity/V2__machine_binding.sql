-- platform-identity schema, version 2: integration client bindings (docs/design/setup.md 6 and 6.1).
--
-- Run by the app's identity schema step (Flyway location classpath:db/platform/identity, history table
-- idn_schema_history) after V1. The app never runs DDL at startup; IdentitySchema.verify checks the version.
-- At runtime the app's database user needs SELECT, INSERT, UPDATE and DELETE on the idn_ tables, as for V1; nothing
-- in the code deletes a binding or a history row.
--
-- Which machine client (the verified token's client ID) may deliver to which entity, as which sending system
-- (source), per customer and product. Client IDs only, never secrets. Loaded by the setup command from
-- client-bindings-<product>.csv; later edited by the IT Admin's integration page (the same table). A binding applies
-- only while it is ACTIVE, its entity is ACTIVE and the customer's login connection for the product is ACTIVE.

update idn_schema_version set version = 2;

create table idn_machine_binding (
    binding_id   uuid        primary key,
    tenant_id    text        not null references idn_customer (tenant_id),
    product_code text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    -- Exactly as the customer's login system issues it; compared as given (case-sensitive).
    client_id    text        not null check (client_id ~ '^[^[:space:],"''[:cntrl:]]{1,255}$'),
    slot_id      text        not null references idn_entity (slot_id),
    entity_uuid  uuid        not null references idn_entity (entity_uuid),
    source_key   text        not null check (source_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    description  text        check (description is null or length(description) between 1 and 200),
    status       text        not null check (status in ('ACTIVE', 'INACTIVE')),
    version      bigint      not null check (version >= 1),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

-- One binding per customer, product and client ID (switched off, never deleted, so at most one is ACTIVE).
create unique index idn_machine_binding_client on idn_machine_binding (tenant_id, product_code, client_id);

-- The lookup at sign-in: an ACTIVE binding by product and client ID.
create index idn_machine_binding_active on idn_machine_binding (product_code, client_id) where status = 'ACTIVE';

-- Every version of every binding, with who changed it (SETUP, APP or MIGRATION). Insert only.
create table idn_machine_binding_history (
    history_id    bigint      generated always as identity primary key,
    binding_id    uuid        not null references idn_machine_binding (binding_id),
    version       bigint      not null,
    tenant_id     text        not null,
    product_code  text        not null,
    client_id     text        not null,
    slot_id       text        not null,
    source_key    text        not null,
    description   text,
    status        text        not null,
    change_source text        not null check (change_source in ('SETUP', 'APP', 'MIGRATION')),
    changed_by    text        not null,
    changed_at    timestamptz not null default now(),
    unique (binding_id, version)
);
