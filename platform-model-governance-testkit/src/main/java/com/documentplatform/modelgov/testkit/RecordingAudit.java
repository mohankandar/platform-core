package com.documentplatform.modelgov.testkit;

import com.documentplatform.modelgov.Decisions;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelGovAudit;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.modelgov.ValidationRecord;

import java.util.ArrayList;
import java.util.List;

/** A {@link ModelGovAudit} that records each call as one line ({@code kind id by fields}), for tests. */
public final class RecordingAudit implements ModelGovAudit {

    private final List<String> lines = new ArrayList<>();

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    public synchronized long count(String kind) {
        return lines.stream().filter(l -> l.startsWith(kind + " ")).count();
    }

    private synchronized void add(String line) {
        lines.add(line);
    }

    @Override
    public void modelChanged(Model model, String changedBy, List<String> changedFields) {
        add("model " + model.modelId() + " v" + model.version() + " " + changedBy + " " + changedFields);
    }

    @Override
    public void useChanged(ModelUse use, String changedBy, List<String> changedFields) {
        add("use " + use.useId() + " v" + use.version() + " " + changedBy + " " + changedFields);
    }

    @Override
    public void thresholdSet(Threshold threshold, String setBy) {
        add("threshold " + threshold.useId() + " " + ModelGovAudit.entityOf(threshold.entity()) + " "
                + threshold.measure() + " " + setBy);
    }

    @Override
    public void validationRecorded(ValidationRecord record) {
        add("validation " + record.validationId() + " " + record.ranBy() + " passed=" + record.passed());
    }

    @Override
    public void approved(Decisions.Approval approval) {
        add("approved " + approval.validationId() + " " + approval.approvedBy());
    }

    @Override
    public void accepted(Decisions.Acceptance acceptance) {
        add("accepted " + acceptance.validationId() + " " + acceptance.acceptedBy());
    }

    @Override
    public void liveSwitched(Decisions.LiveChange change) {
        add("live " + change.kind() + " " + change.useId() + " " + change.entity() + " " + change.toValidationId()
                + " " + change.changedBy());
    }
}
