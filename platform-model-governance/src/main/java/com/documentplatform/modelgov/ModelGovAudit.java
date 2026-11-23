package com.documentplatform.modelgov;

import java.util.List;
import java.util.UUID;

/**
 * The app's audit, told of every register change and every gate decision (as integration's {@code IntegrationAudit}).
 * IDs, names, codes and field names only; never content.
 */
public interface ModelGovAudit {

    void modelChanged(Model model, String changedBy, List<String> changedFields);

    void useChanged(ModelUse use, String changedBy, List<String> changedFields);

    void thresholdSet(Threshold threshold, String setBy);

    void validationRecorded(ValidationRecord record);

    void approved(Decisions.Approval approval);

    void accepted(Decisions.Acceptance acceptance);

    void liveSwitched(Decisions.LiveChange change);

    /** No audit (tests). */
    ModelGovAudit NONE = new ModelGovAudit() {
        @Override
        public void modelChanged(Model model, String changedBy, List<String> changedFields) {
        }

        @Override
        public void useChanged(ModelUse use, String changedBy, List<String> changedFields) {
        }

        @Override
        public void thresholdSet(Threshold threshold, String setBy) {
        }

        @Override
        public void validationRecorded(ValidationRecord record) {
        }

        @Override
        public void approved(Decisions.Approval approval) {
        }

        @Override
        public void accepted(Decisions.Acceptance acceptance) {
        }

        @Override
        public void liveSwitched(Decisions.LiveChange change) {
        }
    };

    /** For a use whose {@code entity} is null: the entity the decision is about (a helper for apps' audit rows). */
    static String entityOf(UUID entity) {
        return entity == null ? "every entity" : entity.toString();
    }
}
