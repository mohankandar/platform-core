-- platform-model-governance schema, version 1 (docs/design/model-governance.md, P033).
--
-- Each app runs these files in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/modelgov and the history table mg_schema_history. The app never runs DDL at startup:
-- ModelGovSchema.verify checks mg_schema_version. At runtime the app's database user needs SELECT, INSERT, UPDATE and
-- DELETE on the mg_ tables (SELECT on mg_schema_version); the insert-only tables refuse UPDATE and DELETE by trigger,
-- even for the schema owner.
--
-- What is stored: the model register (models with their exact digest, uses with materiality and status), its
-- history, pass marks, validation records with their measures (numbers), approvals, documented acceptances, what is
-- live per use and entity, and every live switch. Never document content, field values, tokens or secrets.

create table mg_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);

insert into mg_schema_version (version) values (1);

create function mg_insert_only() returns trigger language plpgsql as $$
begin
    raise exception '%: rows are insert-only (never updated or deleted)', tg_table_name
        using errcode = 'check_violation';
end
$$;

-- A model: name, provider (VENDOR or CUSTOMER), version, exact digest, where it runs.
create table mg_model (
    model_id         uuid        primary key,
    name             text        not null check (length(name) between 1 and 200),
    provider         text        not null check (provider in ('VENDOR', 'CUSTOMER')),
    model_version    text        not null check (length(model_version) between 1 and 100),
    digest_algorithm text        not null check (digest_algorithm ~ '^[a-z0-9][a-z0-9+._-]{0,31}$'),
    digest_value     text        not null check (digest_value ~ '^[0-9a-f]{16,128}$'),
    runs_where       text        not null check (runs_where in ('CUSTOMER_ENVIRONMENT', 'HOSTED')),
    version          bigint      not null check (version >= 1),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

create table mg_model_history (
    history_id       bigint      generated always as identity primary key,
    model_id         uuid        not null references mg_model (model_id),
    version          bigint      not null,
    name             text        not null,
    provider         text        not null,
    model_version    text        not null,
    digest_algorithm text        not null,
    digest_value     text        not null,
    runs_where       text        not null,
    changed_fields   text        not null,
    changed_by       text        not null check (length(changed_by) between 1 and 200),
    changed_at       timestamptz not null,
    unique (model_id, version)
);

-- A model use (the same model for two purposes is two uses). entity_uuid null = every entity of the customer.
create table mg_model_use (
    use_id                 uuid        primary key,
    product_code           text        not null check (product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    feature                text        not null check (length(feature) between 1 and 200),
    entity_uuid            uuid,
    purpose                text        not null check (length(purpose) between 1 and 2000),
    materiality            text        not null check (materiality in ('HIGH', 'MEDIUM', 'LOW')),
    materiality_reason     text        not null check (length(materiality_reason) between 1 and 2000),
    human_in_the_loop      text        not null check (length(human_in_the_loop) between 1 and 2000),
    vendor_owner           text        check (vendor_owner is null or length(vendor_owner) between 1 and 200),
    customer_owner         text        check (customer_owner is null or length(customer_owner) between 1 and 200),
    model_id               uuid        not null references mg_model (model_id),
    config_fingerprint     text        not null check (config_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    status                 text        not null check (status in ('PROPOSED', 'VALIDATED', 'LIVE', 'RETIRED')),
    last_validated_at      timestamptz,
    next_review_due        date,
    review_overdue_refuses boolean     not null default false,
    version                bigint      not null check (version >= 1),
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);

create index mg_model_use_product on mg_model_use (product_code);

create table mg_model_use_history (
    history_id         bigint      generated always as identity primary key,
    use_id             uuid        not null references mg_model_use (use_id),
    version            bigint      not null,
    model_id           uuid        not null,
    config_fingerprint text        not null,
    materiality        text        not null,
    status             text        not null,
    next_review_due    date,
    changed_fields     text        not null,
    changed_by         text        not null check (length(changed_by) between 1 and 200),
    changed_at         timestamptz not null,
    unique (use_id, version)
);

-- Pass marks: entity_uuid null = the app's default for the use; else the customer's (stricter) one for the entity.
create table mg_threshold (
    use_id      uuid        not null references mg_model_use (use_id),
    entity_uuid uuid,
    measure     text        not null check (measure ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    value       numeric     not null,
    direction   text        not null check (direction in ('HIGHER_IS_BETTER', 'LOWER_IS_BETTER')),
    set_by      text        not null check (length(set_by) between 1 and 200),
    set_at      timestamptz not null
);

create unique index mg_threshold_key on mg_threshold
    (use_id, coalesce(entity_uuid, '00000000-0000-0000-0000-000000000000'::uuid), measure);

-- A validation (insert only): the model as validated, the configuration fingerprint, the test set, pass or fail.
create table mg_validation (
    validation_id      uuid        primary key,
    use_id             uuid        not null references mg_model_use (use_id),
    entity_uuid        uuid        not null,
    model_id           uuid        not null references mg_model (model_id),
    model_name         text        not null,
    model_version      text        not null,
    provider           text        not null check (provider in ('VENDOR', 'CUSTOMER')),
    digest_algorithm   text        not null,
    digest_value       text        not null,
    config_fingerprint text        not null check (config_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    test_set_name      text        not null check (test_set_name ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    test_set_version   text        not null check (length(test_set_version) between 1 and 100),
    passed             boolean     not null,
    ran_by             text        not null check (length(ran_by) between 1 and 200),
    ran_at             timestamptz not null
);

create index mg_validation_use_entity on mg_validation (use_id, entity_uuid, ran_at);

-- Each measure of a validation (insert only), with the threshold in force copied in. Numbers only.
create table mg_validation_measure (
    validation_id uuid    not null references mg_validation (validation_id),
    measure       text    not null check (measure ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    result        numeric not null,
    threshold     numeric not null,
    direction     text    not null check (direction in ('HIGHER_IS_BETTER', 'LOWER_IS_BETTER')),
    passed        boolean not null,
    primary key (validation_id, measure)
);

-- The Model approver's approval (insert only), with the roles held then.
create table mg_approval (
    validation_id uuid        primary key references mg_validation (validation_id),
    approved_by   text        not null check (length(approved_by) between 1 and 200),
    roles         text        not null,
    approved_at   timestamptz not null
);

-- A documented acceptance (insert only) of a failed customer-model validation for a medium-materiality use.
create table mg_acceptance (
    validation_id uuid        primary key references mg_validation (validation_id),
    accepted_by   text        not null check (length(accepted_by) between 1 and 200),
    accepted_at   timestamptz not null,
    results       text        not null check (length(results) <= 4000),
    reason        text        not null check (length(reason) between 1 and 1000)
);

-- What is live per use and entity, and the rollback target.
create table mg_live (
    use_id                 uuid        not null references mg_model_use (use_id),
    entity_uuid            uuid        not null,
    validation_id          uuid        not null references mg_validation (validation_id),
    previous_validation_id uuid        references mg_validation (validation_id),
    version                bigint      not null check (version >= 1),
    changed_by             text        not null check (length(changed_by) between 1 and 200),
    changed_at             timestamptz not null,
    primary key (use_id, entity_uuid)
);

-- Every live switch (insert only).
create table mg_live_history (
    history_id         bigint      generated always as identity primary key,
    use_id             uuid        not null,
    entity_uuid        uuid        not null,
    kind               text        not null check (kind in ('WENT_LIVE', 'ROLLED_BACK')),
    from_validation_id uuid,
    to_validation_id   uuid        not null,
    changed_by         text        not null check (length(changed_by) between 1 and 200),
    changed_at         timestamptz not null
);

create index mg_live_history_use on mg_live_history (use_id, entity_uuid, history_id);

create trigger mg_validation_insert_only before update or delete on mg_validation
    for each row execute function mg_insert_only();
create trigger mg_validation_measure_insert_only before update or delete on mg_validation_measure
    for each row execute function mg_insert_only();
create trigger mg_approval_insert_only before update or delete on mg_approval
    for each row execute function mg_insert_only();
create trigger mg_acceptance_insert_only before update or delete on mg_acceptance
    for each row execute function mg_insert_only();
create trigger mg_live_history_insert_only before update or delete on mg_live_history
    for each row execute function mg_insert_only();
create trigger mg_validation_no_truncate before truncate on mg_validation
    for each statement execute function mg_insert_only();
create trigger mg_validation_measure_no_truncate before truncate on mg_validation_measure
    for each statement execute function mg_insert_only();
create trigger mg_approval_no_truncate before truncate on mg_approval
    for each statement execute function mg_insert_only();
create trigger mg_acceptance_no_truncate before truncate on mg_acceptance
    for each statement execute function mg_insert_only();
create trigger mg_live_history_no_truncate before truncate on mg_live_history
    for each statement execute function mg_insert_only();
