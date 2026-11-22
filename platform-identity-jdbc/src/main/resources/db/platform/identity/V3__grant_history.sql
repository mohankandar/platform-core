-- platform-identity schema, version 3: grant history, and at most one draft login connection per customer and product.
--
-- Run by the app's identity schema step (Flyway location classpath:db/platform/identity, history table
-- idn_schema_history) after V1 and V2. The app never runs DDL at startup; IdentitySchema.verify checks the version.
-- At runtime the app's database user needs SELECT and INSERT on idn_grant_history (the generic grant of SELECT,
-- INSERT, UPDATE and DELETE on the idn_ tables is enough: the triggers below refuse UPDATE and DELETE anyway).

update idn_schema_version set version = 3;

-- Every grant change, one row per entity and grant: added, removed, or one group's role changed in one step. Written
-- by the setup command in its transaction and by the app through GroupGrantStore, in the same transaction as the
-- change. Insert only: the triggers refuse UPDATE, DELETE and TRUNCATE, even for the table's owner. Groups and roles
-- exactly as the login system sends them; never a token, claim or secret.
create table idn_grant_history (
    history_id     bigint      generated always as identity primary key,
    tenant_id      text        not null,
    product_code   text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    slot_id        text        not null,
    entity_uuid    uuid        not null,
    issuer_uri     text        not null,
    external_group text        not null check (length(external_group) between 1 and 200),
    role_before    text        check (role_before is null or length(role_before) between 1 and 100),
    role_after     text        check (role_after is null or length(role_after) between 1 and 100),
    change         text        not null check (change in ('ADDED', 'REMOVED', 'CHANGED')),
    change_source  text        not null check (change_source in ('SETUP', 'APP')),
    changed_by     text        not null check (length(changed_by) between 1 and 200),
    changed_at     timestamptz not null default now(),
    -- The setup run that made the change; only for SETUP, and always for SETUP.
    setup_run_id   text        check (setup_run_id is null or length(setup_run_id) between 1 and 100),
    check ((change = 'ADDED') = (role_before is null)),
    check ((change = 'REMOVED') = (role_after is null)),
    check (change <> 'CHANGED' or role_before <> role_after),
    check ((change_source = 'SETUP') = (setup_run_id is not null))
);

-- The app's audit page reads one entity's changes for a period.
create index idn_grant_history_entity on idn_grant_history (entity_uuid, changed_at);

create function idn_grant_history_frozen() returns trigger language plpgsql as $$
begin
    raise exception 'idn_grant_history: rows are insert-only (never updated or deleted)'
        using errcode = 'check_violation';
end
$$;

create trigger idn_grant_history_no_change before update or delete on idn_grant_history
    for each row execute function idn_grant_history_frozen();

create trigger idn_grant_history_no_truncate before truncate on idn_grant_history
    for each statement execute function idn_grant_history_frozen();

-- Login connections: at most one DRAFT or VALIDATED connection per customer and product (the draft the IT Admin is
-- preparing and testing while people keep signing in through the ACTIVE one).
create unique index idn_login_connection_one_pending on idn_login_connection (tenant_id, product_code)
    where status in ('DRAFT', 'VALIDATED');
