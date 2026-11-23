package com.documentplatform.eraselog.spring;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasePosition;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import com.documentplatform.eraselog.testkit.InMemoryErasePosition;
import com.documentplatform.eraselog.testkit.RecordingEraseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** P044, SEC-3 in the Spring wiring: keyed with the app's key service, the setting, and the seal command. */
class KeyedEraseLogWiringTest {

    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");

    static ApplicationContextRunner app(Path location, String... more) {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(CUSTOMER);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EraseLogAutoConfiguration.class))
                .withPropertyValues("platform.erase-log.location=" + location.toAbsolutePath(),
                        "platform.erase-log.exit-after-replay=false")
                .withPropertyValues(more)
                .withBean(KeyService.class, () -> keys)
                .withBean(CustomerId.class, () -> CUSTOMER)
                .withBean(ErasePosition.class, InMemoryErasePosition::new);
    }

    static EraseRequest req(String id) {
        return new EraseRequest(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A", "case", id,
                "document", Instant.parse("2026-11-20T10:00:00Z"), ErasedBy.rule("RET-7Y"), "AUD-" + id);
    }

    @Test
    void withTheKeyServiceTheLogIsKeyedAndANewLogIsSealedAtItsFirstEntry(@TempDir Path dir) {
        RecordingEraseHandler docs = new RecordingEraseHandler("document").store("d1");
        app(dir.resolve("log")).withBean(RecordingEraseHandler.class, () -> docs).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            EraseLog log = ctx.getBean(EraseLog.class);
            assertTrue(log.keyed());
            ctx.getBean(Eraser.class).erase(req("d1"));
            assertTrue(log.sealRecord().isPresent());
            assertTrue(log.after(0).get(0).keyed());
            assertTrue(log.verify().intact());
        });
    }

    @Test
    void keyedTrueWithoutAKeyRefusesToStart(@TempDir Path dir) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EraseLogAutoConfiguration.class))
                .withPropertyValues("platform.erase-log.location=" + dir.resolve("log").toAbsolutePath(),
                        "platform.erase-log.keyed=true")
                .withBean(ErasePosition.class, InMemoryErasePosition::new)
                .run(ctx -> assertTrue(String.valueOf(ctx.getStartupFailure()).contains("keyed is true"),
                        String.valueOf(ctx.getStartupFailure())));
    }

    @Test
    void anExistingLogIsBlockedUntilTheSealCommandRan(@TempDir Path dir) {
        Path location = dir.resolve("log");
        // Written by an earlier release: unkeyed entries.
        new EraseLog(new FileEraseLogStore(location), Clock.systemUTC()).record(req("old-1"));
        RecordingEraseHandler docs = new RecordingEraseHandler("document");
        app(location).withBean(RecordingEraseHandler.class, () -> docs).run(ctx -> {
            EraseGuard guard = ctx.getBean(EraseGuard.class);
            EraseLog log = ctx.getBean(EraseLog.class);
            assertFalse(guard.mayTakeWork());
            assertEquals(EraseLogError.ERASE_LOG_NOT_SEALED, guard.state().code());

            AtomicInteger exited = new AtomicInteger(-1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new EraseLogSealRunner(log, guard, ctx, true,
                    new PrintStream(out, true, StandardCharsets.UTF_8), exited::set)
                    .run(new DefaultApplicationArguments("--erase-log-seal"));
            assertEquals(0, exited.get(), out.toString(StandardCharsets.UTF_8));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("from 2 on carries a MAC"));
            assertTrue(guard.mayTakeWork(), "sealed: the guard's full check lifts the block");
            assertTrue(log.verify().intact());
        });
    }
}
