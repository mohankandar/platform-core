package com.documentplatform.integration.store;

import com.documentplatform.integration.result.AttemptResult;

/**
 * The app's audit, supplied by the app: one entry per configuration change and per Test action. Gets IDs, versions,
 * field names and codes only, never a secret or a value.
 */
public interface IntegrationAudit {

    /** A configuration version was saved. */
    void changed(ConfigChange change);

    /** A Test action ran (with fake data). */
    void tested(AttemptResult result, String by);

    /** An audit that records nothing (for tools and tests that do not need one). */
    IntegrationAudit NONE = new IntegrationAudit() {
        @Override
        public void changed(ConfigChange change) {
        }

        @Override
        public void tested(AttemptResult result, String by) {
        }
    };
}
