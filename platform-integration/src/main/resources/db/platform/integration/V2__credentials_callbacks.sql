-- platform-integration schema, version 2: client certificates (mutual TLS) with rotation and history, the callback
-- kind, and the callback outbox.
--
-- Run by the app's schema step like V1 (Flyway location classpath:db/platform/integration, history table
-- int_schema_history). At runtime the app's database user additionally needs only:
--   SELECT, INSERT, UPDATE         on int_credential
--   SELECT, INSERT                 on int_credential_change
--   SELECT, INSERT, UPDATE, DELETE on int_callback_outbox
--
-- What is stored: each integration's client certificates as shown to people (subject, issuer, serial, SHA-256
-- fingerprint, validity), an uploaded certificate's chain and private key only sealed under the customer's keys
-- (platform-crypto, 'pcv1.' text; dropped when the certificate is retired), a keystore's path, alias and password
-- reference (never the password); every change of them with who and when; callbacks waiting to be sent (the JSON
-- body the app gave, never document content).

update int_schema_version set version = 2, installed_at = now();

-- The callback kind.
alter table int_integration drop constraint int_integration_kind_check;
alter table int_integration add constraint int_integration_kind_check
    check (kind in ('API_PUSH', 'FOLDER_EXPORT', 'API_POLL', 'WATCHED_FOLDER', 'CALLBACK'));

-- Client certificates: at most one CURRENT and one NEXT per integration.
create table int_credential (
    id                    uuid        primary key,
    integration_id        uuid        not null references int_integration (id),
    entity_uuid           uuid        not null,
    product               text        not null,
    state                 text        not null check (state in ('CURRENT', 'NEXT', 'RETIRED')),
    source                text        not null check (source in ('UPLOADED', 'KEYSTORE')),
    subject               text        not null,
    issuer                text        not null,
    serial                text        not null,
    sha256                text        not null check (sha256 ~ '^[0-9a-f]{64}$'),
    valid_from            timestamptz not null,
    valid_to              timestamptz not null,
    -- Uploaded: the chain and the private key, sealed under the customer's keys. Null once retired.
    sealed                text        check (sealed is null or sealed like 'pcv1.%'),
    keystore_path         text,
    keystore_password_ref text,
    keystore_alias        text,
    switch_at             timestamptz,
    added_by              text        not null,
    added_at              timestamptz not null,
    retired_at            timestamptz,
    check ((source = 'KEYSTORE') = (keystore_path is not null and keystore_password_ref is not null
                                    and keystore_alias is not null)),
    check (source = 'KEYSTORE' or state = 'RETIRED' or sealed is not null),
    check (source = 'UPLOADED' or sealed is null),
    check (switch_at is null or state = 'NEXT'),
    check ((state = 'RETIRED') = (retired_at is not null))
);
create unique index int_credential_one_current on int_credential (integration_id) where state = 'CURRENT';
create unique index int_credential_one_next on int_credential (integration_id) where state = 'NEXT';
create index int_credential_active on int_credential (state) where state in ('CURRENT', 'NEXT');

create function int_credential_rules() returns trigger language plpgsql as $$
begin
    if old.state = 'RETIRED' then
        raise exception 'int_credential: a retired certificate never changes' using errcode = 'check_violation';
    end if;
    if new.integration_id <> old.integration_id or new.entity_uuid <> old.entity_uuid
            or new.product <> old.product or new.source <> old.source or new.added_at <> old.added_at
            or new.added_by <> old.added_by
            or (new.state = 'CURRENT' and old.state = 'NEXT' and new.sha256 <> old.sha256) then
        raise exception 'int_credential: integration, entity, product, source and who added it never change'
            using errcode = 'check_violation';
    end if;
    if old.state = 'CURRENT' and new.state = 'NEXT' then
        raise exception 'int_credential: a current certificate never goes back to next'
            using errcode = 'check_violation';
    end if;
    if new.sealed is not null and new.sealed is distinct from old.sealed then
        raise exception 'int_credential: a sealed certificate is never replaced (add a new one)'
            using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger int_credential_rules before update on int_credential
    for each row execute function int_credential_rules();

create trigger int_credential_no_delete before delete on int_credential
    for each row execute function int_no_delete();

-- Every change of an integration's certificates, numbered per integration. Never changed or deleted.
create table int_credential_change (
    integration_id uuid        not null references int_integration (id),
    version        integer     not null check (version >= 1),
    change         text        not null check (change in ('UPLOADED', 'KEYSTORE_SET', 'KEYSTORE_RENEWED',
                                                          'SWITCH_SCHEDULED', 'SWITCHED', 'SWITCHED_AT_TIME',
                                                          'NEXT_DISCARDED')),
    credential_id  uuid        not null references int_credential (id),
    sha256         text        not null check (sha256 ~ '^[0-9a-f]{64}$'),
    changed_by     text        not null,
    changed_at     timestamptz not null,
    primary key (integration_id, version)
);

create function int_credential_change_frozen() returns trigger language plpgsql as $$
begin
    raise exception 'int_credential_change: a stored change never changes' using errcode = 'check_violation';
end
$$;

create trigger int_credential_change_frozen before update or delete on int_credential_change
    for each row execute function int_credential_change_frozen();

-- Callbacks waiting to be sent (the notifications outbox pattern). A claim is a lease (claimed_until).
create table int_callback_outbox (
    id              uuid        primary key,
    seq             bigint      generated always as identity,
    integration_id  uuid        not null references int_integration (id),
    entity_uuid     uuid        not null,
    product         text        not null,
    body            text        not null,
    state           text        not null check (state in ('PENDING', 'SENDING', 'SENT', 'GAVE_UP')),
    attempts        integer     not null default 0 check (attempts >= 0),
    created_at      timestamptz not null,
    -- The give-up window starts here: when it was created, or when it was last resent.
    window_from     timestamptz not null,
    next_attempt_at timestamptz not null,
    claimed_until   timestamptz,
    last_outcome    text,
    last_code       text,
    last_status     integer     not null default 0,
    finished_at     timestamptz,
    check ((state in ('SENT', 'GAVE_UP')) = (finished_at is not null)),
    check (state = 'SENDING' or claimed_until is null)
);
create index int_callback_outbox_due on int_callback_outbox (next_attempt_at, seq) where state in ('PENDING', 'SENDING');
create index int_callback_outbox_gave_up on int_callback_outbox (entity_uuid, product, finished_at)
    where state = 'GAVE_UP';
create index int_callback_outbox_finished on int_callback_outbox (finished_at) where state in ('SENT', 'GAVE_UP');
