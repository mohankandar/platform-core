package com.documentplatform.setup;

import com.documentplatform.crypto.testkit.InMemoryKeyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.documentplatform.setup.SetupCommandTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Integration profiles named in the sign-in file: checked in preflight, listed as "checked", stored nowhere. */
class ProfileSetupTest {

    @TempDir
    Path dir;

    record Result(int exit, String out, String err) {
    }

    private void demo(String profilesJson) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of("demo"))) {
            for (Path f : files) Files.copy(f, dir.resolve(f.getFileName()));
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("profiles/acme-archive-v1.profile.json")) {
            Files.write(dir.resolve("acme-archive-v1.profile.json"), in.readAllBytes());
        }
        if (profilesJson != null) {
            String signin = Files.readString(dir.resolve("signin-demo.json"));
            Files.writeString(dir.resolve("signin-demo.json"), signin.replaceFirst(
                    "\"roleMappingFile\": \"role-mapping-product-a.csv\",",
                    "\"roleMappingFile\": \"role-mapping-product-a.csv\",\n      \"integrationProfiles\": " + profilesJson
                            + ","));
        }
    }

    private Result run(Pg db, String action) {
        List<String> args = new ArrayList<>(List.of(action,
                "--licence", dir.resolve("licence-demo.json").toString(),
                "--signin", dir.resolve("signin-demo.json").toString(),
                "--product", A,
                "--keys", dir.resolve("development-trusted-keys.json").toString(),
                "--product-roles", dir.resolve("product-roles-product-a.json").toString(),
                "--config-out", dir.resolve("platform-tenant.properties").toString(),
                "--run-log", dir.resolve("setup-runs.log").toString()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InMemoryKeyService keys = new InMemoryKeyService();
        int exit = new SetupCommand(db.appEnv(PASSWORD), Clock.fixed(NOW, ZoneOffset.UTC),
                env -> new SetupKeys.Connection(keys, () -> { })).run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static final String GOOD = "[{\"name\": \"acme-archive-v1\", \"file\": \"acme-archive-v1.profile.json\", "
            + "\"tag\": \"acme-archive-profile-v1.2.0\"}]";

    @Test
    void aValidProfileIsCheckedAndStoredNowhere() throws Exception {
        demo(GOOD);
        Pg db = Pg.migrated();
        Result r = run(db, "apply");
        assertEquals(0, r.exit(), r.out() + r.err());
        assertTrue(r.out().contains("checked     integration profile acme-archive-v1 1.2.0 (tag acme-archive-profile-v1.2.0, "
                + "file acme-archive-v1.profile.json; stored nowhere, the app reads the file)"), r.out());
        assertTrue(Files.readString(dir.resolve("setup-runs.log")).contains("checked=1"));
        List<String> tables = db.tables();
        assertTrue(tables.stream().noneMatch(t -> t.contains("profile")), "nothing stored: " + tables);
    }

    @Test
    void refusalsNameTheFileAndThePlace() throws Exception {
        demo("[{\"name\": \"acme-archive-v1\", \"file\": \"acme-archive-v1.profile.json\"}]");
        Pg db = Pg.migrated();
        Result noTag = run(db, "plan");
        assertEquals(SetupCommand.EXIT_REFUSED, noTag.exit());
        assertTrue(noTag.err().contains("SETUP_PROFILE_TAG_MISSING") || noTag.err().contains("without its release tag"),
                noTag.err());
        assertTrue(Files.readString(dir.resolve("setup-runs.log")).contains("code=SETUP_PROFILE_TAG_MISSING"));

        Files.writeString(dir.resolve("acme-archive-v1.profile.json"), Files.readString(dir.resolve(
                "acme-archive-v1.profile.json")).replace("\"direction\": \"OUTBOUND\",", "\"direction\": \"SIDEWAYS\","));
        Path signin = dir.resolve("signin-demo.json");
        Files.writeString(signin, Files.readString(signin).replace("\"file\": \"acme-archive-v1.profile.json\"}",
                "\"file\": \"acme-archive-v1.profile.json\", \"tag\": \"v1.2.0\"}"));
        Result invalid = run(db, "plan");
        assertEquals(SetupCommand.EXIT_REFUSED, invalid.exit());
        assertTrue(invalid.err().contains("acme-archive-v1.profile.json INT_PROFILE_SCHEMA at /direction"), invalid.err());
        assertTrue(invalid.err().contains("What to do: Use the integration profile file"), invalid.err());
        assertEquals(0, db.count("select count(*) from idn_customer"), "refused in preflight");
    }

    @Test
    void theNameAndTheVersionMustMatchTheEntry() throws Exception {
        demo("[{\"name\": \"other-archive-v1\", \"file\": \"acme-archive-v1.profile.json\", \"tag\": \"v1.2.0\"}]");
        Pg db = Pg.migrated();
        Result name = run(db, "plan");
        assertTrue(name.err().contains("is profile acme-archive-v1, not other-archive-v1"), name.err());
        assertTrue(name.err().contains("acme-archive-v1.profile.json /name"), name.err());
        Path signin = dir.resolve("signin-demo.json");
        Files.writeString(signin, Files.readString(signin).replace("\"other-archive-v1\"", "\"acme-archive-v1\"")
                .replace("\"v1.2.0\"", "\"acme-archive-profile-v1.1.0\""));
        Result version = run(db, "plan");
        assertTrue(version.err().contains("is version 1.2.0, which the tag acme-archive-profile-v1.1.0 does not name"),
                version.err());
    }
}
