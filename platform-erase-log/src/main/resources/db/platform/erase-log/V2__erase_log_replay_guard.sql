-- platform-erase-log schema, version 2 (P044, security finding SEC-49).
--
-- Run by the app's schema step like version 1 (location classpath:db/platform/erase-log, history table
-- erl_schema_history). No new rights for the app's database user.
--
-- A restore, once recorded (replay_required = true), is cleared only together with a newer verified_at: that is what
-- the verified erase replay writes. A bare "update erl_position set replay_required = false" (a mistake, or a hand
-- trying to skip the replay) is refused, so the system stays blocked until the replay has really run.

create function erl_position_replay_guard() returns trigger language plpgsql as $$
begin
    if old.replay_required and not new.replay_required
            and (new.verified_at is null or (old.verified_at is not null and new.verified_at <= old.verified_at)) then
        raise exception 'erl_position: a recorded restore is cleared only by a verified erase replay (log %)',
            old.log_id using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger erl_position_replay_guard before update on erl_position
    for each row execute function erl_position_replay_guard();

update erl_schema_version set version = 2, installed_at = now();
