package com.documentplatform.notifications.approval;

/** What an approver decides. */
public enum ApprovalAction {
    APPROVE("Approve", "approved"),
    DECLINE("Decline", "declined");

    private final String label;
    private final String pastTense;

    ApprovalAction(String label, String pastTense) {
        this.label = label;
        this.pastTense = pastTense;
    }

    /** The link's label in a message: "Approve" or "Decline". */
    public String label() {
        return label;
    }

    /** "approved" or "declined", for "Already approved by ...". */
    public String pastTense() {
        return pastTense;
    }
}
