package com.documentplatform.appcheck;

import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.integration.jdbc.IntegrationSchema;
import com.documentplatform.licensing.jdbc.LicensingSchema;
import com.documentplatform.modelgov.jdbc.ModelGovSchema;
import com.documentplatform.notifications.jdbc.NotificationSchema;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** P047: an app that cannot reach its database says so in Boot's failure report with the module's code, not in a stack trace. */
class FailureReportsTest {

    @Test
    void everyModuleReportsItsCodeWhenTheDatabaseCannotBeReached() {
        PGSimpleDataSource nowhere = new PGSimpleDataSource();
        nowhere.setURL("jdbc:postgresql://127.0.0.1:1/none");
        Map<String, Consumer<DataSource>> checks = Map.of("IDN_CHECK_FAILED", IdentitySchema::verify,
                "LIC_CHECK_FAILED", LicensingSchema::verify, "ERL_CHECK_FAILED", EraseLogSchema::verify,
                "INT_CHECK_FAILED", IntegrationSchema::verify, "MG_CHECK_FAILED", ModelGovSchema::verify,
                "CHECK_FAILED", NotificationSchema::verify);
        checks.forEach((code, verify) -> {
            RuntimeException refused = assertThrows(RuntimeException.class, () -> verify.accept(nowhere), code);
            FailureAnalysis a = SpringFactoriesLoader.forDefaultResourceLocation().load(FailureAnalyzer.class,
                            SpringFactoriesLoader.ArgumentResolver.none(), (type, name, failure) -> { }).stream()
                    .filter(f -> f.getClass().getName().startsWith("com.documentplatform."))
                    .map(f -> f.analyze(new IllegalStateException("bean", refused))).filter(Objects::nonNull)
                    .findFirst().orElseThrow(() -> new AssertionError("no report for " + code));
            assertTrue(a.getDescription().contains(code), code + ": " + a.getDescription());
            assertTrue(a.getDescription().contains("08001"), a.getDescription());
            assertNotNull(a.getAction());
        });
    }
}
