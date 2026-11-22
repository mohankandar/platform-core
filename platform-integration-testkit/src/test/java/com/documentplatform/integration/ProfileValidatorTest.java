package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileLoader;
import com.documentplatform.integration.profile.ProfileProblem;
import com.documentplatform.integration.profile.ProfileValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.documentplatform.integration.ProfileFiles.*;
import static org.junit.jupiter.api.Assertions.*;

/** The profile format's validator: the two made-up profiles pass, and one refusal per code, with its pointer. */
class ProfileValidatorTest {

    private static List<String> problems(String file, Consumer<Map<String, Object>> change) {
        return problems(changed(file, change));
    }

    private static List<String> problems(String text) {
        ProfileValidator.Result r = ProfileValidator.validate(text);
        assertNull(r.profile());
        return r.problems().stream().map(p -> p.code() + " " + p.pointer()).toList();
    }

    @Test
    void bothMadeUpProfilesPass() {
        IntegrationProfile acme = profile(ACME);
        assertEquals("1.2.0", acme.version());
        assertEquals("Acme Archive API", acme.apiTitle());
        assertEquals(2, acme.steps().size());
        assertEquals(List.of("OAUTH2_CLIENT_CREDENTIALS"), acme.signInTypes());
        assertEquals(2, acme.notes().size());
        IntegrationProfile dms = profile(DMS);
        assertEquals(1, dms.steps().size(), "the MULTIPART preset expanded to one step");
        assertEquals(CallStep.Body.MULTIPART, dms.steps().get(0).body());
        assertEquals("/error/code", dms.errorCodePointer());
        assertTrue(ProfileValidator.schema().contains("platform-integration-profile/1"));
    }

    @Test
    void oneRefusalPerCode(@TempDir Path dir) throws Exception {
        assertEquals(List.of("INT_PROFILE_UNREADABLE "), ProfileLoader.check(dir.resolve("missing.json")).problems()
                .stream().map(p -> p.code() + " " + p.pointer()).toList());
        Files.write(dir.resolve("latin1.json"), new byte[]{(byte) 0xe9});
        assertEquals(IntegrationCode_INT_PROFILE_UNREADABLE(), ProfileLoader.check(dir.resolve("latin1.json"))
                .problems().get(0).code().name());

        assertEquals(List.of("INT_PROFILE_NOT_JSON "), problems("{not json"));
        assertEquals(List.of("INT_PROFILE_FORMAT_UNKNOWN /format"), problems(ACME + ".profile.json",
                m -> m.put("format", "platform-integration-profile/2")));
        assertEquals(List.of("INT_PROFILE_SCHEMA /baseAddress"), problems(ACME + ".profile.json",
                m -> m.put("baseAddress", "x")), "an address field is not part of the format");
        assertEquals(List.of("INT_PROFILE_SCHEMA /version"), problems(ACME + ".profile.json",
                m -> m.remove("version")));
        assertEquals(List.of("INT_PROFILE_STEPS "), problems(ACME + ".profile.json",
                m -> m.put("packaging", Map.of("preset", "FILE_PER_CALL", "path", "/x"))), "steps and a preset");
        assertEquals(List.of("INT_PROFILE_STEPS /packaging/jsonTemplate"), problems(DMS + ".profile.json",
                m -> obj(m.get("packaging")).remove("jsonTemplate")));
        assertEquals(List.of("INT_PROFILE_TEMPLATE /steps/1/path"), problems(ACME + ".profile.json",
                m -> step(m, 1).put("path", "/v1/batches/{step.create.batchId}/files?k={value.batchLabel}")));
        assertEquals(List.of("INT_PROFILE_TEMPLATE /steps/0/bodyTemplate"), problems(ACME + ".profile.json",
                m -> step(m, 0).put("bodyTemplate", "{\"a\":\"{secret.key}\",\"attributes\":{}}")));
        assertEquals(List.of("INT_PROFILE_HEADER /steps/1/headers/Authorization"), problems(ACME + ".profile.json",
                m -> obj(step(m, 1).get("headers")).put("Authorization", "Bearer x")));
        assertEquals(List.of("INT_PROFILE_RULE /rules/1"), problems(ACME + ".profile.json",
                m -> obj(arr(m.get("rules")).get(1)).put("pointer", "/x")), "a pointer without its value");
        assertEquals(List.of("INT_PROFILE_VALUE_UNDECLARED /steps/0"), problems(ACME + ".profile.json",
                m -> m.remove("values")));
        assertEquals(List.of("INT_PROFILE_ATTRIBUTE /documentAttributes/0/default"), problems(ACME + ".profile.json",
                m -> obj(arr(m.get("documentAttributes")).get(0)).put("default", "SECRET")));
        assertEquals(List.of("INT_PROFILE_ATTRIBUTE /steps/1"), problems(ACME + ".profile.json",
                m -> m.remove("documentAttributes")));
        assertEquals(List.of("INT_PROFILE_FIELDS /fields/object"), problems(ACME + ".profile.json",
                m -> obj(m.get("fields")).put("object", "metadata")));
        assertEquals(List.of("INT_PROFILE_FIELDS /fields/step"), problems(ACME + ".profile.json",
                m -> obj(m.get("fields")).put("step", "nope")));
        assertEquals(List.of("INT_PROFILE_SETTING /settings/senderIdentity/default"), problems(ACME + ".profile.json",
                m -> obj(obj(m.get("settings")).get("senderIdentity")).put("default", "MAYBE")));
        assertEquals(List.of("INT_PROFILE_SETTING /settings/senderIdentity/values/LOOKUP/engine.unknown"),
                problems(ACME + ".profile.json", m -> obj(obj(obj(obj(m.get("settings")).get("senderIdentity"))
                        .get("values")).get("LOOKUP")).put("engine.unknown", "x")));
        assertEquals(List.of("INT_PROFILE_SIGN_IN /signIn/apiKeyHeader"), problems(DMS + ".profile.json",
                m -> obj(m.get("signIn")).remove("apiKeyHeader")));
        assertEquals(List.of("INT_PROFILE_INSTALLATION_DATA /notes/0"), problems(ACME + ".profile.json",
                m -> arr(m.get("notes")).set(0, "Send to https://archive.example.com")));
        assertEquals(List.of("INT_PROFILE_INSTALLATION_DATA /steps/1/headers/X-Acme-Item-Ref"),
                problems(ACME + ".profile.json", m -> obj(step(m, 1).get("headers")).put("X-Acme-Item-Ref",
                        "password=hunter2")));
        assertEquals(List.of("INT_PROFILE_INSTALLATION_DATA /steps/0"), problems(ACME + ".profile.json",
                m -> step(m, 0).put("bodyTemplate", "{\"n\":\"{field:contract_number}\",\"label\":\"{value.batchLabel}\","
                        + "\"attributes\":{}}")), "a case field key belongs to the installation");
    }

    @Test
    void noProblemRepeatsAValue() {
        ProfileValidator.Result r = ProfileValidator.validate(changed(ACME + ".profile.json",
                m -> arr(m.get("notes")).set(0, "password=hunter2 and https://secret-host.example")));
        for (ProfileProblem p : r.problems()) {
            assertFalse(p.toString().contains("hunter2"));
            assertFalse(p.toString().contains("secret-host"));
        }
    }

    private static String IntegrationCode_INT_PROFILE_UNREADABLE() {
        return IntegrationCode.INT_PROFILE_UNREADABLE.name();
    }

    private static Map<String, Object> step(Map<String, Object> m, int i) {
        return obj(arr(m.get("steps")).get(i));
    }
}
