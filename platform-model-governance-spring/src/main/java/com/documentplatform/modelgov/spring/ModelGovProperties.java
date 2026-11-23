package com.documentplatform.modelgov.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code platform.model-governance.*}: the roles of the change gate (named by the app's configuration; none is built
 * in), the review interval per materiality, how long readings are kept, and the background worker.
 */
@ConfigurationProperties(prefix = ModelGovProperties.PREFIX)
public class ModelGovProperties {

    public static final String PREFIX = "platform.model-governance";

    /** Whether the wiring is on (default true). */
    private boolean enabled = true;
    /** The roles that may approve (the Model approver; a compliance officer where configured). Required. */
    private List<String> approverRoles = new ArrayList<>();
    /** The roles an approver may never also hold (the Business Admin role). */
    private List<String> forbiddenRoles = new ArrayList<>();
    private final ReviewEvery reviewEvery = new ReviewEvery();
    /** How long monitoring readings are kept (at least 30 days; default 400 days). */
    private Duration measureRetention = Duration.ofDays(400);
    private final Worker worker = new Worker();

    /** How long a validation lasts before the next review is due, per materiality (whole days). */
    public static class ReviewEvery {
        private Duration high = Duration.ofDays(90);
        private Duration medium = Duration.ofDays(180);
        private Duration low = Duration.ofDays(365);

        public Duration getHigh() {
            return high;
        }

        public void setHigh(Duration high) {
            this.high = high;
        }

        public Duration getMedium() {
            return medium;
        }

        public void setMedium(Duration medium) {
            this.medium = medium;
        }

        public Duration getLow() {
            return low;
        }

        public void setLow(Duration low) {
            this.low = low;
        }
    }

    /** The background worker: review notices and the reading purge. */
    public static class Worker {
        private boolean enabled = true;
        private Duration every = Duration.ofHours(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getEvery() {
            return every;
        }

        public void setEvery(Duration every) {
            this.every = every;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getApproverRoles() {
        return approverRoles;
    }

    public void setApproverRoles(List<String> approverRoles) {
        this.approverRoles = approverRoles;
    }

    public List<String> getForbiddenRoles() {
        return forbiddenRoles;
    }

    public void setForbiddenRoles(List<String> forbiddenRoles) {
        this.forbiddenRoles = forbiddenRoles;
    }

    public ReviewEvery getReviewEvery() {
        return reviewEvery;
    }

    public Duration getMeasureRetention() {
        return measureRetention;
    }

    public void setMeasureRetention(Duration measureRetention) {
        this.measureRetention = measureRetention;
    }

    public Worker getWorker() {
        return worker;
    }
}
