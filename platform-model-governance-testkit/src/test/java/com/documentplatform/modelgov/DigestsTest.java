package com.documentplatform.modelgov;

import com.documentplatform.modelgov.testkit.FakeModelServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.documentplatform.modelgov.testkit.ModelGovFixtures.digest;
import static org.junit.jupiter.api.Assertions.*;

/** The file digest, the model server's manifest digest (fake server) and the configuration fingerprint. */
class DigestsTest {

    @Test
    void fileDigestIsTheSha256OfTheBytes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("model.bin");
        Files.writeString(f, "abc", StandardCharsets.US_ASCII);
        assertEquals(new ModelDigest("sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                ModelDigest.sha256Of(f));
        Files.writeString(f, "abd", StandardCharsets.US_ASCII);
        assertNotEquals(digest("abc"), ModelDigest.sha256Of(f));
    }

    @Test
    void digestTextForm() {
        ModelDigest d = ModelDigest.parse("SHA256:" + "AB".repeat(32));
        assertEquals("sha256:" + "ab".repeat(32), d.toString());
        assertEquals(d, ModelDigest.parse(d.toString()));
        for (String bad : new String[]{null, "", "sha256", ":ab", "sha256:xyz", "sha256:abc"}) {
            ModelGovException e = assertThrows(ModelGovException.class, () -> ModelDigest.parse(bad), bad);
            assertEquals(ModelGovCode.MOD_VALUE_INVALID, e.code());
        }
    }

    @Test
    void manifestDigestFromTheModelServer() throws IOException {
        ModelDigest d = digest("served");
        try (FakeModelServer server = FakeModelServer.start().put("library/example-detector", "1.0", d)) {
            ManifestDigests digests = new ManifestDigests(server.address(), Duration.ofSeconds(5));
            assertEquals(Optional.of(d), digests.digest("library/example-detector", "1.0"));
            assertEquals(Optional.empty(), digests.digest("library/example-detector", "2.0"));
            assertEquals(List.of("HEAD /v2/library/example-detector/manifests/1.0",
                    "HEAD /v2/library/example-detector/manifests/2.0"), server.requests());

            server.failWith(500);
            IOException e = assertThrows(IOException.class, () -> digests.digest("library/example-detector", "1.0"));
            assertEquals("manifest answer 500", e.getMessage());

            server.failWith(0).redirectTo("http://169.254.169.254/v2/x/manifests/1");
            assertThrows(IOException.class, () -> digests.digest("library/example-detector", "1.0"),
                    "a redirect is never followed");
            assertEquals(3 + 1, server.requests().size());
        }
    }

    @Test
    void fingerprintCanonicalForm() {
        assertEquals("{\"a\":[1,2.5,\"x\\\"y\",null,true],\"b\":{\"c\":0}}",
                ConfigFingerprint.canonical(" { \"b\" : { \"c\" : 0.0 }, \"a\" : [ 1.0, 2.50, \"x\\\"y\", null, true ] } "));
        assertTrue(ConfigFingerprint.isFingerprint(ConfigFingerprint.of("{}")));
        assertFalse(ConfigFingerprint.isFingerprint("sha256:ABC"));
        assertThrows(RuntimeException.class, () -> ConfigFingerprint.of("{\"a\":1,}"));
        assertThrows(RuntimeException.class, () -> ConfigFingerprint.of("{\"a\":1} trailing"));
    }
}
