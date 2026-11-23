package com.documentplatform.modelgov;

import com.documentplatform.errors.PlatformCode;

/**
 * The model-governance codes (texts in {@code ModelGovCode.codes.properties}): the change gate's refusals, the gate
 * at model start, and the register's own checks. The app raises its IT notice from them.
 */
public enum ModelGovCode implements PlatformCode {
    MOD_UNKNOWN,
    MOD_VALUE_INVALID,
    MOD_VERSION_CONFLICT,
    MOD_THRESHOLD_LOOSER,
    MOD_THRESHOLD_MISSING,
    MOD_APPROVER_ROLE,
    MOD_APPROVER_CONFLICT,
    MOD_VALIDATION_FAILED,
    MOD_ALREADY_APPROVED,
    MOD_CUSTOMER_MODEL_BLOCKED,
    MOD_ACCEPTANCE_NOT_APPLICABLE,
    MOD_REASON_REQUIRED,
    MOD_NOT_VALIDATED,
    MOD_NOT_APPROVED,
    MOD_NOT_LIVE,
    MOD_DIGEST_MISMATCH,
    MOD_CONFIG_CHANGED,
    /** Where the model runs changed since the live validation (P039, design section 4): validate and approve there. */
    MOD_LOCATION_CHANGED,
    MOD_RETIRED,
    MOD_REVIEW_OVERDUE,
    MOD_NO_ROLLBACK_TARGET,
    MOD_WRONG_USE,
    MOD_DRIFT,
    MOD_DRIFT_UNHANDLED,
    MOD_REVIEW_DUE,
    MOD_ACTION_ALREADY_RECORDED
}
