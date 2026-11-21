package com.documentplatform.licensing;

/**
 * What the licence is for. Usage is recorded under every kind; only {@link #PRODUCTION} usage can be billable.
 */
public enum LicenceKind {
    /** The short licence used while installing (at most 60 days). Usage is recorded, never billable. */
    INSTALL(false, false),
    /** The customer's live use. Usage is billable unless the app marks it free. */
    PRODUCTION(true, false),
    /**
     * A test or staging environment. Has its own tenant and slot IDs, never shared with production. Usage is recorded,
     * never billable. Apps show a "Test environment" marking when {@link #testEnvironment()} is true.
     */
    NON_PRODUCTION(false, true);

    private final boolean billable;
    private final boolean testEnvironment;

    LicenceKind(boolean billable, boolean testEnvironment) {
        this.billable = billable;
        this.testEnvironment = testEnvironment;
    }

    /** Whether usage recorded under this kind can be billable at all. */
    public boolean usageBillable() {
        return billable;
    }

    /** Whether the app must show its "Test environment" marking. */
    public boolean testEnvironment() {
        return testEnvironment;
    }
}
