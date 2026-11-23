-- platform-identity schema, version 4: platform-wide grants (docs/design/setup.md 8, P028 B5).
--
-- Run by the app's identity schema step (Flyway location classpath:db/platform/identity, history table
-- idn_schema_history) after V1 to V3. The app never runs DDL at startup; IdentitySchema.verify checks the version.
-- At runtime the app's database user needs SELECT, INSERT, UPDATE and DELETE on idn_platform_grant, as on the other
-- idn_ tables.
--
-- A platform-wide grant: for this customer and product, members of this group of this login system (issuer) get this
-- platform role (one of the product's platformRoles, for example an operations admin), in every entity. Loaded by
-- the setup command from the role mapping file's rows whose entity_id is "*". Values exactly as the login system
-- sends them; never a token, claim or secret.

update idn_schema_version set version = 4;

create table idn_platform_grant (
    tenant_id      text        not null references idn_customer (tenant_id),
    product_code   text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    issuer_uri     text        not null,
    external_group text        not null check (length(external_group) between 1 and 200),
    role           text        not null check (length(role) between 1 and 100),
    created_at     timestamptz not null default now(),
    primary key (tenant_id, product_code, issuer_uri, external_group, role)
);

create index idn_platform_grant_product_issuer on idn_platform_grant (product_code, issuer_uri);

-- The grant history also holds platform-wide grants: scope PLATFORM, with no entity. Rows written before V4 are
-- ENTITY rows. (Adding a column and loosening NOT NULL change no row: the insert-only triggers stay as they are.)
alter table idn_grant_history add column scope text not null default 'ENTITY'
    check (scope in ('ENTITY', 'PLATFORM'));
alter table idn_grant_history alter column slot_id drop not null;
alter table idn_grant_history alter column entity_uuid drop not null;
alter table idn_grant_history add constraint idn_grant_history_scope_entity
    check ((scope = 'ENTITY') = (slot_id is not null and entity_uuid is not null));

-- The app's audit page reads one customer's platform-wide changes for a product and period.
create index idn_grant_history_platform on idn_grant_history (tenant_id, product_code, changed_at)
    where scope = 'PLATFORM';
