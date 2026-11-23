package com.documentplatform.integration;

import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileValidator;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.testkit.ProfileContract;
import com.documentplatform.integration.testkit.ProfileMock;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.documentplatform.integration.ProfileFiles.*;
import static org.junit.jupiter.api.Assertions.*;

/** The contract-test kit: the made-up profiles fit their specs, a planted spec change is caught, the live check. */
class ProfileContractTest {

    private static List<String> check(String name, String specText) {
        return ProfileContract.checkAgainstSpec(profile(name), OpenApiSpec.parse(specText)).stream()
                .map(ProfileContract.Problem::toString).toList();
    }

    @Test
    void bothProfilesFitTheirSpecs() {
        assertEquals(List.of(), check(ACME, text(ACME + ".openapi.json")));
        assertEquals(List.of(), check(DMS, text(DMS + ".openapi.json")));
    }

    @Test
    void plantedSpecChangesAreCaught() {
        // A new error code without a rule.
        List<String> code = check(ACME, changed(ACME + ".openapi.json", s -> arr(obj(obj(obj(s.get("components"))
                .get("responses")).get("Unavailable")).get("x-error-codes")).add("ACME-MAINTENANCE")));
        assertTrue(code.stream().anyMatch(p -> p.contains("503 ACME-MAINTENANCE has no rule")), code.toString());
        // A new required header.
        List<String> header = check(ACME, changed(ACME + ".openapi.json", s -> arr(obj(obj(obj(s.get("paths"))
                .get("/v1/batches/{batchId}/files")).get("post")).get("parameters")).add(Map.of("in", "header",
                "name", "X-Acme-Tenant", "required", true, "schema", Map.of("type", "string")))));
        assertTrue(header.stream().anyMatch(p -> p.contains("requires header X-Acme-Tenant")), header.toString());
        // A new required body field.
        List<String> field = check(ACME, changed(ACME + ".openapi.json", s -> arr(obj(obj(obj(s.get("components"))
                .get("schemas")).get("CreateBatch")).get("required")).add("owner")));
        assertTrue(field.stream().anyMatch(p -> p.contains("request body /owner: required")), field.toString());
        // A moved path.
        List<String> path = check(ACME, changed(ACME + ".openapi.json", s -> {
            Map<String, Object> paths = obj(s.get("paths"));
            paths.put("/v2/batches", paths.remove("/v1/batches"));
        }));
        assertTrue(path.stream().anyMatch(p -> p.contains("POST /v1/batches is not an operation")), path.toString());
        // The multipart part renamed.
        List<String> part = check(DMS, changed(DMS + ".openapi.json", s -> {
            Map<String, Object> props = obj(obj(obj(obj(obj(obj(obj(obj(s.get("paths")).get("/api/v2/packages"))
                    .get("post")).get("requestBody")).get("content")).get("multipart/form-data")).get("schema"))
                    .get("properties"));
            props.put("meta", props.remove("metadata"));
        }));
        assertTrue(part.stream().anyMatch(p -> p.contains("no part metadata")), part.toString());
        // A value the profile reads disappears from the answer.
        List<String> read = check(ACME, changed(ACME + ".openapi.json", s -> obj(obj(obj(obj(s.get("components"))
                .get("schemas")).get("File")).get("properties")).remove("sha256")));
        assertTrue(read.stream().anyMatch(p -> p.contains("checksum (/sha256) is not in the answer")), read.toString());
    }

    @Test
    void theLiveCheckAgainstAServer() {
        for (String name : List.of(ACME, DMS)) {
            IntegrationProfile p = profile(name);
            try (ProfileMock mock = ProfileMock.start(p, spec(name))) {
                InMemorySecretResolver secrets = new InMemorySecretResolver().put(ProfileMock.SECRET_REF, ProfileMock.SECRET);
                Installation inst = Installation.onPremises().withInternalAddresses(true);
                assertEquals(List.of(), ProfileContract.checkAgainstServer(p, mock.address(), mock.signIn(), secrets, inst),
                        name);
                mock.healthDown();
                List<ProfileContract.Problem> down = ProfileContract.checkAgainstServer(p, mock.address(), mock.signIn(),
                        secrets, inst);
                assertTrue(down.stream().anyMatch(x -> x.where().equals("health")), down.toString());
                mock.working();
                List<ProfileContract.Problem> badKey = ProfileContract.checkAgainstServer(p, mock.address(),
                        mock.signIn(), new InMemorySecretResolver().put(ProfileMock.SECRET_REF, "wrong"), inst);
                assertFalse(badKey.isEmpty(), name + ": a wrong credential is reported");
            }
        }
    }

    @Test
    void theKitAndTheValidatorShareTheFormat() {
        assertTrue(ProfileValidator.validate(text(ACME + ".profile.json")).valid());
        assertTrue(ProfileValidator.validate(text(DMS + ".profile.json")).valid());
    }
}
