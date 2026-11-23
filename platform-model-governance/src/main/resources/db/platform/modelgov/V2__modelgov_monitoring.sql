-- platform-model-governance schema, version 2: ongoing monitoring and re-validation (P034, design section 5).
--
-- Run by the app's schema step after V1 (same Flyway location and history table). New runtime rights: SELECT,
-- INSERT, UPDATE and DELETE on mg_monitor_threshold, SELECT and INSERT on the insert-only tables mg_measure (plus
-- DELETE for the retention purge, which the trigger allows only inside a purge), mg_drift, mg_drift_action and
-- mg_review_notice. Numbers, IDs and names only: never document content or values.

update mg_schema_version set version = 2;

-- A use's own review interval in days (null = the materiality's default of the app's settings).
alter table mg_model_use add column review_every_days integer
    check (review_every_days is null or review_every_days between 1 and 3650);
alter table mg_model_use_history add column review_every_days integer;

-- Monitoring pass marks: entity_uuid null = the app's default; else the customer's (stricter) one for the entity.
create table mg_monitor_threshold (
    use_id      uuid        not null references mg_model_use (use_id),
    entity_uuid uuid,
    measure     text        not null check (measure ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    value       numeric     not null,
    direction   text        not null check (direction in ('HIGHER_IS_BETTER', 'LOWER_IS_BETTER')),
    min_sample  bigint      not null check (min_sample >= 1),
    set_by      text        not null check (length(set_by) between 1 and 200),
    set_at      timestamptz not null
);

create unique index mg_monitor_threshold_key on mg_monitor_threshold
    (use_id, coalesce(entity_uuid, '00000000-0000-0000-0000-000000000000'::uuid), measure);

-- One reading (insert only; removed only by the retention purge).
create table mg_measure (
    reading_id  uuid        primary key,
    use_id      uuid        not null references mg_model_use (use_id),
    entity_uuid uuid        not null,
    measure     text        not null check (measure ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    value       numeric     not null,
    period_from timestamptz not null,
    period_to   timestamptz not null check (period_to >= period_from),
    samples     bigint      not null check (samples >= 0),
    reported_by text        not null check (length(reported_by) between 1 and 200),
    reported_at timestamptz not null
);

create index mg_measure_use on mg_measure (use_id, entity_uuid, reported_at);
create index mg_measure_reported on mg_measure (reported_at);

-- A reading past its pass mark (insert only), with the pass mark copied in.
create table mg_drift (
    drift_id    uuid        primary key,
    use_id      uuid        not null references mg_model_use (use_id),
    entity_uuid uuid        not null,
    measure     text        not null,
    value       numeric     not null,
    threshold   numeric     not null,
    direction   text        not null check (direction in ('HIGHER_IS_BETTER', 'LOWER_IS_BETTER')),
    samples     bigint      not null,
    reading_id  uuid        not null,
    raised_at   timestamptz not null
);

create index mg_drift_use on mg_drift (use_id, entity_uuid, raised_at);

-- What was done about a drift (insert only, one per drift).
create table mg_drift_action (
    drift_id  uuid        primary key references mg_drift (drift_id),
    note      text        not null check (length(note) between 1 and 2000),
    acted_by  text        not null check (length(acted_by) between 1 and 200),
    acted_at  timestamptz not null
);

-- Review notices raised (insert only): one per use, due date and stage.
create table mg_review_notice (
    use_id    uuid        not null references mg_model_use (use_id),
    due_date  date        not null,
    stage     text        not null check (stage in ('IN_30_DAYS', 'IN_7_DAYS', 'OVERDUE')),
    raised_at timestamptz not null,
    primary key (use_id, due_date, stage)
);

-- Readings: never updated; deleted only by the retention purge, which sets mg.purge_before for its transaction
-- (set_config('mg.purge_before', <instant>, true)) and removes only rows reported before it.
create function mg_measure_guard() returns trigger language plpgsql as $$
declare
    purge_before timestamptz := nullif(current_setting('mg.purge_before', true), '')::timestamptz;
begin
    if tg_op = 'DELETE' and purge_before is not null and old.reported_at < purge_before then
        return old;
    end if;
    raise exception '%: rows are insert-only (readings are removed only by the retention purge)', tg_table_name
        using errcode = 'check_violation';
end
$$;

create trigger mg_measure_insert_only before update or delete on mg_measure
    for each row execute function mg_measure_guard();
create trigger mg_measure_no_truncate before truncate on mg_measure
    for each statement execute function mg_insert_only();
create trigger mg_drift_insert_only before update or delete on mg_drift
    for each row execute function mg_insert_only();
create trigger mg_drift_no_truncate before truncate on mg_drift
    for each statement execute function mg_insert_only();
create trigger mg_drift_action_insert_only before update or delete on mg_drift_action
    for each row execute function mg_insert_only();
create trigger mg_drift_action_no_truncate before truncate on mg_drift_action
    for each statement execute function mg_insert_only();
create trigger mg_review_notice_insert_only before update or delete on mg_review_notice
    for each row execute function mg_insert_only();
create trigger mg_review_notice_no_truncate before truncate on mg_review_notice
    for each statement execute function mg_insert_only();
