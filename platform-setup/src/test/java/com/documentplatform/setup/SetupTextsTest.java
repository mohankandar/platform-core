package com.documentplatform.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * setup-1: what setup says to people says "customer" and "entity", never "tenant". Names of fields, properties and
 * options that hold the word ({@code tenantId}, {@code platform.licensing.tenant-id}, {@code --tenant-id}) are names,
 * not prose, and stay.
 */
class SetupTextsTest {

    /** "tenant" as a word: not part of a longer name such as tenantId, tenant-id or TENANT_ID. */
    private static final Pattern TENANT_WORD = Pattern.compile("(?i)(?<![\\w.-])tenant(?![\\w-])");

    @Test
    void noRefusalTextSaysTenant() {
        for (SetupRefusal r : SetupRefusal.values()) {
            for (String text : List.of(r.userMessage(), r.cause(), r.fix(), r.action())) {
                assertFalse(TENANT_WORD.matcher(text).find(), r.name() + " says tenant: " + text);
            }
        }
        assertTrue(SetupRefusal.SETUP_CONFIG_OTHER_TENANT.cause().contains("customer ID"),
                SetupRefusal.SETUP_CONFIG_OTHER_TENANT.cause());
    }

    @Test
    void theConfigFragmentSaysCustomerId(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(ConfigFragment.DEFAULT_NAME);
        ConfigFragment.write(file, "TEN-TEST-0001", ConfigFragment.State.MISSING);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("# Written by the platform setup command: this installation's customer ID, from the"
                + " licence.", "platform.licensing.tenant-id=TEN-TEST-0001"), lines);
        for (String line : lines) {
            assertFalse(TENANT_WORD.matcher(line).find(), "says tenant: " + line);
        }
    }

    @Test
    void theWordCheckFindsTheWordAndLeavesNamesAlone() {
        for (String prose : List.of("another customer's tenant ID", "customer (tenant) ID", "Tenant ID", "a tenant.")) {
            assertTrue(TENANT_WORD.matcher(prose).find(), prose);
        }
        for (String name : List.of("the customer (tenantId)", "platform.licensing.tenant-id", "--tenant-id",
                "SETUP_WRONG_TENANT", "TEN-TEST-0001")) {
            assertFalse(TENANT_WORD.matcher(name).find(), name);
        }
    }
}
