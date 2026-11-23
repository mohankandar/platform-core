package com.documentplatform.outbound.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P041: the failure analyzer is package-private; Spring Boot still creates it from {@code spring.factories}. */
class FailureAnalyzerRegistrationTest {

    @Test
    void springBootCreatesThePackagePrivateFailureAnalyzer() {
        List<FailureAnalyzer> all = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(FailureAnalyzer.class, null, (type, name, failure) -> { });
        assertTrue(all.stream().anyMatch(a -> a instanceof OutboundFailureAnalyzer), all.toString());
    }

    /** P041 (xc-public-api-14): wiring only, not for apps. */
    @Test
    void theWiringTypesAreNotPublic() {
        for (Class<?> c : List.<Class<?>>of(OutboundFailureAnalyzer.class, OutboundSettings.class)) {
            assertFalse(Modifier.isPublic(c.getModifiers()), c.getName());
        }
    }
}
