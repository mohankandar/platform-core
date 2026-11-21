-- platform-notifications schema, version 1.
--
-- Each app runs these files in its own schema step (for example Flyway with the location
-- classpath:db/platform/notifications and its own history table). Table names are unqualified: they are created in
-- the schema the app's connection uses.
--
-- What is stored: ids, the admins' settings, and the text of outgoing messages, which obeys the content rule (event
-- name, record identifier, counts, times, link into the app). What is never stored: document content, redacted values,
-- e-mail addresses (recipients are kept as roles and looked up at send time) and secrets (mail passwords and webhook
-- addresses stay in the secret store; destinations are referenced by id).
--
-- This is the only place the notif_ tables are created or changed. The app never runs DDL at startup: it checks
-- notif_schema_version and stops with a specific message if the tables are missing or at another version. At runtime
-- the app's database user needs only SELECT, INSERT, UPDATE and DELETE on the notif_ tables.

-- Which version of these files was run. One row; a later file updates it.
create table notif_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);

insert into notif_schema_version (version) values (1);

-- Channels the IT Admin connected for a tenant (TenantSettings).
create table notif_tenant (
    tenant_id    text        primary key,
    app_base_url text        not null,
    zone_id      text        not null,
    updated_at   timestamptz not null default now()
);

create table notif_destination (
    tenant_id      text not null references notif_tenant (tenant_id) on delete cascade,
    destination_id text not null,
    channel        text not null check (channel in ('EMAIL', 'TEAMS', 'SLACK')),
    primary key (tenant_id, destination_id)
);

-- Admins' choices per event type: technical events per tenant (entity_id = ''), business events per entity.
create table notif_event_settings (
    tenant_id            text        not null,
    level                text        not null check (level in ('TECHNICAL', 'BUSINESS')),
    entity_id            text        not null,
    event_type           text        not null,
    enabled              boolean     not null,
    frequency            text        not null
        check (frequency in ('IMMEDIATELY', 'EVERY_HOUR', 'EVERY_4_HOURS', 'ONCE_A_DAY')),
    daily_at             time,
    recipient_roles      text[]      not null,
    destinations         text[]      not null,
    alert_after_seconds  bigint,
    repeat_every_seconds bigint,
    thresholds           text        not null, -- name=value,name=value
    updated_at           timestamptz not null default now(),
    primary key (tenant_id, level, entity_id, event_type),
    check ((level = 'TECHNICAL') = (entity_id = '')),
    check ((frequency = 'ONCE_A_DAY') = (daily_at is not null)),
    check ((alert_after_seconds is null) = (repeat_every_seconds is null))
);

-- Outbox: every outgoing message, saved before it is sent. message_key is the idempotency key.
create table notif_outbox (
    message_key     text        primary key,
    seq             bigint      generated always as identity, -- first in, first out among messages due together
    tenant_id       text        not null,
    destination_id  text        not null,
    channel         text        not null check (channel in ('EMAIL', 'TEAMS', 'SLACK')),
    entity_id       text,
    recipient_roles text[]      not null,
    event_types     text[]      not null,
    message_kind    text        not null,
    subject         text        not null,
    body_lines      text[]      not null,
    link            text,
    state           text        not null check (state in ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts        integer     not null default 0,
    created_at      timestamptz not null,
    next_attempt_at timestamptz not null,
    claimed_until   timestamptz,
    last_failure    text,
    finished_at     timestamptz,
    -- A message asking for a decision names its request; its one-click links are made at send time, never stored.
    action_request  text,
    -- A message to one person (for example the requester, told the outcome): their user id, never their address.
    recipient_user  text,
    check (recipient_user is null or (channel = 'EMAIL' and cardinality(recipient_roles) = 0))
);

create index notif_outbox_due_idx on notif_outbox (next_attempt_at) where state in ('PENDING', 'SENDING');
create index notif_outbox_finished_idx on notif_outbox (finished_at) where state in ('SENT', 'FAILED');

-- Occurrences waiting for their summary. A bucket is (tenant, destination, schedule, entity, roles, due_at).
create table notif_summary_item (
    item_key        text        primary key,
    seq             bigint      generated always as identity,
    tenant_id       text        not null,
    destination_id  text        not null,
    frequency       text        not null,
    daily_at        time,
    entity_id       text,
    recipient_roles text[]      not null,
    due_at          timestamptz not null,
    event_type      text        not null,
    message_kind    text        not null,
    identifier      text,
    counts          text        not null, -- name=value,name=value
    times           text        not null, -- name=instant,name=instant
    link            text,
    occurred_at     timestamptz,
    raised_at       timestamptz not null,
    since           timestamptz,
    failures        bigint      not null
);

create index notif_summary_item_due_idx on notif_summary_item (due_at, seq);

-- Continuous conditions that are going on. entity_id / identifier are '' when the condition has none.
create table notif_condition (
    tenant_id     text        not null,
    event_type    text        not null,
    entity_id     text        not null,
    identifier    text        not null,
    since         timestamptz not null,
    failures      bigint      not null,
    alerts        integer     not null default 0,
    last_alert_at timestamptz,
    counts        text        not null,
    times         text        not null,
    link          text,
    occurred_at   timestamptz,
    updated_at    timestamptz not null,
    primary key (tenant_id, event_type, entity_id, identifier)
);

-- Requests waiting for a decision through one-click links (approval.Approvals), and the decision. The first decision
-- wins: it is written only while decision is null and the request has not expired, so of two decisions at the same
-- moment exactly one is stored. The app keeps its own audit of every decision. No tokens are stored: links are signed
-- and checked against this row. The reason of a decline is the decider's own text: shown on the app's page, never put
-- in a message or a log.
create table notif_approval_request (
    tenant_id       text        not null,
    request_id      uuid        not null,
    entity_id       text        not null,
    request_type    text        not null,
    requester       text        not null, -- user id
    identifier      text,
    link            text,
    created_at      timestamptz not null,
    expires_at      timestamptz not null,
    decision        text check (decision in ('APPROVED', 'DECLINED')),
    decided_by      text,                 -- user id
    decided_by_name text,
    decided_at      timestamptz,
    reason          text,
    expiry_told_at  timestamptz,          -- when the requester was told it expired undecided (if the type says so)
    primary key (tenant_id, request_id),
    check (expires_at > created_at),
    check ((decision is null) = (decided_by is null) and (decision is null) = (decided_by_name is null)
        and (decision is null) = (decided_at is null)),
    check (decision is distinct from 'DECLINED' or reason is not null),
    check (decided_by is distinct from requester),
    check (expiry_told_at is null or decision is null)
);

create index notif_approval_request_expires_idx on notif_approval_request (expires_at);
create index notif_approval_request_untold_idx on notif_approval_request (expires_at)
    where decision is null and expiry_told_at is null;
