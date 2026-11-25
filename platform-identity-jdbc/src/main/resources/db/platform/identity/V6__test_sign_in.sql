-- platform-identity schema, version 6 (P056, LV-23). Run by the app's schema step like the earlier versions, as the
-- schema owner. One nullable column; no new rights (the app's user already has UPDATE on this table).

update idn_schema_version set version = 6;

-- The version of the connection for which a test sign-in with a real token passed. Replacing the ACTIVE connection
-- needs it to be the draft's current version. Drafts validated before this version have none: their admin runs the
-- test sign-in once more, with a token. Nothing else changes for them.
alter table idn_login_connection add column test_sign_in_version bigint
    check (test_sign_in_version is null or test_sign_in_version >= 1);
