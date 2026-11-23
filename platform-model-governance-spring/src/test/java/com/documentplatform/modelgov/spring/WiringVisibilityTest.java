package com.documentplatform.modelgov.spring;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** P041 (xc-public-api-14): the monitoring check is wiring; apps get it as a {@code DependencyCheck} bean. */
class WiringVisibilityTest {

    @Test
    void theMonitoringCheckIsNotPublic() {
        assertFalse(Modifier.isPublic(ModelGovCheck.class.getModifiers()));
    }
}
