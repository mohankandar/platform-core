package com.documentplatform.monitoring;

/** The state of a dependency, as one check sees it. The gauge value is 0, 1 or 2. */
public enum CheckState {
    WORKING(0),
    HAVING_PROBLEMS(1),
    DOWN(2);

    private final int gauge;

    CheckState(int gauge) {
        this.gauge = gauge;
    }

    /** 0 working, 1 having problems, 2 down (the metric's value). */
    public int gauge() {
        return gauge;
    }
}
