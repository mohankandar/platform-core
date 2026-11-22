-- platform-erase-log schema, version 1.
--
-- Each app runs this file in its own schema step, as a separate Flyway run with the location
-- classpath:db/platform/erase-log and the history table erl_schema_history. Table names are unqualified: they are
-- created in the schema the app's connection uses.
--
-- What is stored here, in the app's own database: only the erase log's APPLIED POSITION (up to which log entry the
-- erasures are in this database). The erase log itself is NOT here: it is kept outside this database, on storage
-- backed up separately, so that restoring this database cannot roll it back. A restored database brings back an older
-- position, and that is how the app sees a restore.
--
-- The app never runs DDL at startup: it checks erl_schema_version and stops with a specific message if the table is
-- missing or at another version. At runtime the app's database user needs only SELECT, INSERT and UPDATE on
-- erl_position (SELECT on erl_schema_version). The database itself refuses to move a position back.

-- Which version of these files was run. One row; a later file updates it.
create table erl_schema_version (
    version      integer     primary key,
    installed_at timestamptz not null default now()
);
insert into erl_schema_version (version) values (1);

-- One row per erase log this database is protected by (normally exactly one).
create table erl_position (
    log_id              uuid        primary key,
    -- The last log entry applied in this database (0: none) and its hash.
    applied_seq         bigint      not null check (applied_seq >= 0),
    applied_hash        char(64)    not null check (applied_hash ~ '^[0-9a-f]{64}$'),
    -- Set when the app saw this database behind its log (a restore); cleared by a verified replay.
    replay_required     boolean     not null default false,
    restore_detected_at timestamptz,
    verified_at         timestamptz,
    updated_at          timestamptz not null default now()
);

create function erl_position_forward() returns trigger language plpgsql as $$
begin
    if new.applied_seq < old.applied_seq then
        raise exception 'erl_position: the applied position never moves back (log %, % to %)',
            old.log_id, old.applied_seq, new.applied_seq using errcode = 'check_violation';
    end if;
    if new.log_id <> old.log_id then
        raise exception 'erl_position: the log ID never changes' using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger erl_position_forward before update on erl_position
    for each row execute function erl_position_forward();

create function erl_position_no_delete() returns trigger language plpgsql as $$
begin
    raise exception 'erl_position: a position is never deleted' using errcode = 'check_violation';
end
$$;

create trigger erl_position_no_delete before delete on erl_position
    for each row execute function erl_position_no_delete();
