-- platform-identity schema, version 1.
--
-- Each app runs these files in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/identity and the history table idn_schema_history. Table names are unqualified: they are
-- created in the schema the app's connection uses.
--
-- What is stored: customers (tenant ID from the licence, name from the licence, time zone), entity slots (slot ID, the
-- entity UUID derived from it, status, the name the Business Admin gave it, who activated it and when, its own time
-- zone), each customer's login-system connection per product (provider, issuer, client ID, API audience, a
-- reference to where a secret is kept) and its history, and group-to-role grants per entity slot and product. What
-- is never stored: secrets (only references such as vault:kv/path#field), tokens, claims, passwords, document data.
--
-- This is the only place the idn_ tables are created or changed. The app never runs DDL at startup: it checks
-- idn_schema_version and stops with a specific message if the tables are missing or at another version. At runtime
-- the app's database user needs only SELECT, INSERT, UPDATE and DELETE on the idn_ tables (SELECT on
-- idn_schema_version). Nothing in the code deletes customers or entity slots.

-- Which version of these files was run. One row; a later file updates it.
create table idn_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);

insert into idn_schema_version (version) values (1);

-- The customer: the licence's tenant (TEN-...). One licence per customer.
create table idn_customer (
    tenant_id  text        primary key check (tenant_id ~ '^TEN-[A-Z0-9-]{1,60}$'),
    name       text        not null check (length(name) between 1 and 200),
    -- The customer's time zone (IANA name, e.g. America/New_York): every entity without its own follows it. Null
    -- until the setup command or the app sets it.
    time_zone  text        check (time_zone ~ '^[A-Za-z][A-Za-z0-9_+/-]{0,63}$'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- The customer's entity slots (ENT-...). entity_uuid is derived from slot_id (name-based UUID from
-- 'platform-entity|' || slot_id) and is the key of the app's own entity row, which the app creates when the Business
-- Admin activates the slot. A slot dropped from a newer licence becomes NOT_IN_LICENCE; rows are never deleted.
create table idn_entity (
    slot_id      text        primary key check (slot_id ~ '^ENT-[A-Z0-9-]{1,60}$'),
    entity_uuid  uuid        not null unique,
    tenant_id    text        not null references idn_customer (tenant_id),
    status       text        not null check (status in ('NOT_ACTIVATED', 'ACTIVE', 'SUSPENDED', 'NOT_IN_LICENCE')),
    display_name text        check (display_name is null or length(display_name) between 1 and 200),
    activated_by text,
    activated_at timestamptz,
    -- While NOT_IN_LICENCE: the status the slot had when it left the licence (ACTIVE or SUSPENDED), so it comes back
    -- as it was; null when it had never been activated. Always null in the other statuses.
    status_before_removal text check (status_before_removal in ('ACTIVE', 'SUSPENDED')),
    -- The entity's own time zone (IANA name); null = it follows the customer's (idn_customer.time_zone). Entities can
    -- be separate companies in different places.
    time_zone    text        check (time_zone ~ '^[A-Za-z][A-Za-z0-9_+/-]{0,63}$'),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    -- An activated slot has a name and an activation; a slot never activated has neither.
    check ((activated_at is null) = (display_name is null) and (activated_at is null) = (activated_by is null)),
    check (status not in ('ACTIVE', 'SUSPENDED') or activated_at is not null),
    check (status <> 'NOT_ACTIVATED' or activated_at is null),
    check (status = 'NOT_IN_LICENCE' or status_before_removal is null),
    check (status_before_removal is null or activated_at is not null)
);

create index idn_entity_tenant on idn_entity (tenant_id);

-- Names are unique within a customer, ignoring case, so nobody confuses two entities.
create unique index idn_entity_name_per_customer on idn_entity (tenant_id, lower(display_name))
    where display_name is not null;

-- The customer's login-system connection for a product. All entities of the customer share it. At most one ACTIVE
-- connection per customer and product; the issuer is not unique across customers (a shared login hub).
-- secret_reference names where a secret is kept; it is never the secret.
create table idn_login_connection (
    connection_id         uuid        primary key,
    tenant_id             text        not null references idn_customer (tenant_id),
    product_code          text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    provider_id           text        not null check (length(provider_id) between 1 and 200),
    provider_type         text        not null check (provider_type in ('OIDC', 'SAML', 'LDAP_AD')),
    issuer_uri            text        not null check (length(issuer_uri) between 1 and 2000),
    browser_client_id     text        check (browser_client_id is null or length(browser_client_id) between 1 and 200),
    api_audience          text        not null check (length(api_audience) between 1 and 200),
    secret_reference      text        check (secret_reference is null or (secret_reference ~ '^[a-z][a-z0-9+.-]*:\S+$'
                                          and length(secret_reference) <= 500)),
    status                text        not null check (status in ('DRAFT', 'VALIDATED', 'ACTIVE', 'DISABLED')),
    configuration_version bigint      not null check (configuration_version >= 1),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

create unique index idn_login_connection_one_active on idn_login_connection (tenant_id, product_code)
    where status = 'ACTIVE';

create index idn_login_connection_lookup on idn_login_connection (provider_id, issuer_uri, api_audience)
    where status = 'ACTIVE';

-- Every version of every connection, with who changed it (SETUP, APP or MIGRATION). Insert only.
create table idn_login_connection_history (
    history_id            bigint      generated always as identity primary key,
    connection_id         uuid        not null references idn_login_connection (connection_id),
    configuration_version bigint      not null,
    tenant_id             text        not null,
    product_code          text        not null,
    provider_id           text        not null,
    provider_type         text        not null,
    issuer_uri            text        not null,
    browser_client_id     text,
    api_audience          text        not null,
    secret_reference      text,
    status                text        not null,
    change_source         text        not null check (change_source in ('SETUP', 'APP', 'MIGRATION')),
    changed_by            text        not null,
    changed_at            timestamptz not null default now(),
    unique (connection_id, configuration_version)
);

-- Group-to-role grants: in this entity slot, for this product, members of this group of this login system (issuer)
-- get this role. Values exactly as the login system sends them. Grants of one product never apply to another.
create table idn_group_grant (
    slot_id        text        not null references idn_entity (slot_id),
    product_code   text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    issuer_uri     text        not null,
    external_group text        not null check (length(external_group) between 1 and 200),
    role           text        not null check (length(role) between 1 and 100),
    created_at     timestamptz not null default now(),
    primary key (slot_id, product_code, issuer_uri, external_group, role)
);

create index idn_group_grant_product_issuer on idn_group_grant (product_code, issuer_uri);
