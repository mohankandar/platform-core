-- The app's database user (DML only), exactly the rights each install guide lists. Run after the schema step, as the schema
-- owner, with the search path on the app's schema. Each block names its guide.

-- docs/install/licensing.md section 1
grant select, insert, update, delete on lic_licence, lic_usage, lic_chain_head, lic_notice_sent to app_runtime;
grant select, insert, update on lic_last_seen to app_runtime;
grant select, insert on lic_clock_reset to app_runtime;
grant select on lic_version_floor, lic_schema_version to app_runtime;

-- docs/install/identity.md section 1
grant select, insert, update, delete on idn_customer, idn_entity, idn_login_connection, idn_group_grant,
    idn_machine_binding, idn_platform_grant to app_runtime;
grant select, insert on idn_login_connection_history, idn_machine_binding_history, idn_grant_history to app_runtime;
grant select on idn_schema_version to app_runtime;

-- docs/install/notifications.md section 4
grant select, insert, update, delete on notif_tenant, notif_destination, notif_event_settings, notif_outbox,
    notif_summary_item, notif_condition, notif_approval_request to app_runtime;
grant select on notif_schema_version to app_runtime;

-- docs/install/fields.md section 1
grant select, insert, update, delete on fld_field_definition, fld_field_definition_history, fld_field_extension,
    fld_record, fld_value to app_runtime;
grant select on fld_schema_version to app_runtime;

-- docs/install/erase-log.md section 2
grant select, insert, update on erl_position to app_runtime;
grant select on erl_schema_version to app_runtime;

-- docs/install/integration.md section 1
grant select, insert, update on int_integration, int_credential to app_runtime;
grant select, insert on int_integration_version, int_credential_change to app_runtime;
grant select, insert, update, delete on int_callback_outbox to app_runtime;
grant select on int_schema_version to app_runtime;

-- docs/install/model-governance.md section 1
grant select, insert, update, delete on mg_model, mg_model_use, mg_threshold, mg_live, mg_monitor_threshold
    to app_runtime;
grant select, insert on mg_model_history, mg_model_use_history, mg_validation, mg_validation_measure, mg_approval,
    mg_acceptance, mg_live_history, mg_drift, mg_drift_action, mg_review_notice to app_runtime;
grant select, insert on mg_measure to app_runtime;
grant execute on function mg_purge_measures(timestamptz) to app_runtime;
grant select on mg_schema_version to app_runtime;
