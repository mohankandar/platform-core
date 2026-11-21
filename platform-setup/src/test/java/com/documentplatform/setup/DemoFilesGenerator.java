package com.documentplatform.setup;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceSigner;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.TrustedKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Makes the demo licence and its development trusted-keys file in {@code demo/}. Runs only when asked:
 *
 * <pre>mvn -pl platform-setup test -Dtest=DemoFilesGenerator -Dsetup.demo.regenerate=true</pre>
 *
 * A new development key pair is generated each time; its private key is thrown away after signing, so it exists
 * nowhere. Only the development public key is written, to {@code demo/development-trusted-keys.json}, which no
 * production installation lists: a production install refuses the demo licence with LICENCE_UNKNOWN_KEY_ID.
 */
class DemoFilesGenerator {

    static final String DEV_KEY_ID = "dev-demo-1";

    @Test
    @EnabledIfSystemProperty(named = "setup.demo.regenerate", matches = "true")
    void writeDemoLicence() throws Exception {
        Path demo = Path.of("demo");
        KeyPair dev = Ed25519.generate();
        List<Allowance> a = List.of(new Allowance("PRODUCT_A", AllowanceType.PAGES_PER_MONTH, 1000),
                new Allowance("PRODUCT_B", AllowanceType.PAGES_PER_MONTH, 1000));
        Licence licence = new Licence("LIC-DEMO-0001", 1, "Demo Customer Ltd", "TEN-DEMO-0001",
                List.of("PRODUCT_A", "PRODUCT_B"),
                List.of(new EntitySlot("ENT-DEMO-0001", a), new EntitySlot("ENT-DEMO-0002", a)),
                LicenceType.PERPETUAL, LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"), null,
                LocalDate.parse("2036-12-31"), Deployment.ON_PREM, DEV_KEY_ID, Instant.parse("2026-11-21T00:00:00Z"));
        Files.writeString(demo.resolve("licence-demo.json"), LicenceSigner.sign(licence, dev.getPrivate()),
                StandardCharsets.UTF_8);
        Files.writeString(demo.resolve("development-trusted-keys.json"),
                TrustedKeys.of(Map.of(DEV_KEY_ID, dev.getPublic())).toJson(), StandardCharsets.UTF_8);
    }
}
