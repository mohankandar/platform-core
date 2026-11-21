-- platform-licensing schema, version 1.
--
-- Each app runs these files in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/licensing and the history table lic_schema_history. Table names are unqualified: they are
-- created in the schema the app's connection uses.
--
-- What is stored: the signed licence files as received (customer name, IDs, products, allowances, dates), the latest
-- time seen, the IDs of applied last-seen resets, the usage ledger (slot, product, type, month, amount, unit, billable
-- flag, hashes) and its chain head, and the keys of licensing notifications already published. What is never
-- stored: secrets or key material (apps hold public keys only, in config), prices, document data.
--
-- This is the only place the lic_ tables are created or changed. The app never runs DDL at startup: it checks
-- lic_schema_version and stops with a specific message if the tables are missing or at another version. At runtime
-- the app's database user needs only SELECT, INSERT, UPDATE and DELETE on the lic_ tables (SELECT on
-- lic_schema_version).

-- Which version of these files was run. One row; a later file updates it.
create table lic_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);

insert into lic_schema_version (version) values (1);

-- The licence in force (CURRENT) and a renewal stored before its start date (WAITING). The file column is the signed
-- licence exactly as received; it is verified on every read. The other columns are copies for people reading the
-- table and are never trusted.
create table lic_licence (
    slot       text        primary key check (slot in ('CURRENT', 'WAITING')),
    file       text        not null,
    licence_id text        not null,
    version    bigint      not null check (version >= 1),
    tenant_id  text        not null,
    starts_on  date        not null,
    stored_at  timestamptz not null
);

-- The latest time this installation has seen. One row (id = 1). Only moves forward, except through a signed reset.
create table lic_last_seen (
    id         smallint    primary key check (id = 1),
    last_seen  timestamptz not null,
    updated_at timestamptz not null default now()
);

-- Signed last-seen resets already applied: each works once.
create table lic_clock_reset (
    reset_id   text        primary key,
    reset_to   timestamptz not null,
    applied_at timestamptz not null default now()
);

-- The usage ledger: append only, hash chained (see docs/design/licensing-file-format.md section 5).
create table lic_usage (
    sequence      bigint      primary key check (sequence >= 1),
    slot_id       text        not null,
    product       text        not null,
    type          text        not null
        check (type in ('PAGES_PER_MONTH', 'STORAGE', 'CONCURRENT_SEATS', 'NAMED_SEATS')),
    period        text        not null check (period ~ '^[0-9]{4}-[0-9]{2}$'),
    amount        bigint      not null check (amount >= 0),
    unit          text        not null,
    billable      boolean     not null,
    recorded_at   timestamptz not null,
    previous_hash text        not null,
    hash          text        not null
);

create index lic_usage_slot_period on lic_usage (slot_id, period);

-- The newest link of the usage chain, moved in the same transaction as every append. One row (id = 1).
create table lic_chain_head (
    id         smallint    primary key check (id = 1),
    sequence   bigint      not null,
    hash       text        not null,
    updated_at timestamptz not null default now()
);

-- Licensing notifications already published (P011), so a restart or a second app instance never publishes one twice.
-- The ID names what the notice is (event, tenant, slot, licence or month, band), never message content.
create table lic_notice_sent (
    notice_id   text        primary key,
    recorded_at timestamptz not null
);
