package com.documentplatform.integration.spring;

import com.documentplatform.integration.callback.CallbackEntry;
import com.documentplatform.integration.callback.CallbackOutbox;
import com.documentplatform.integration.callback.Callbacks;
import com.documentplatform.integration.credential.CredentialChange;
import com.documentplatform.integration.credential.CredentialStore;
import com.documentplatform.integration.credential.IntegrationCredentials;
import com.documentplatform.integration.credential.StoredCredential;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * xc-never-leak-84: the worker logs that a run failed and the exception's class only; never the exception's text
 * (which may hold a database address, a password or a value) nor its stack trace.
 */
class IntegrationWorkerTest {

    static final String PLANTED = "jdbc:postgresql://db.corp.example/app?password=PW-planted-8e8e";

    private final List<String> lines = new ArrayList<>();
    private final Handler capture = new Handler() {
        private final SimpleFormatter f = new SimpleFormatter();

        @Override
        public void publish(LogRecord r) {
            synchronized (lines) {
                lines.add(f.format(r));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger logger = Logger.getLogger(IntegrationWorker.class.getName());

    @BeforeEach
    void listen() {
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void stop() {
        logger.removeHandler(capture);
    }

    static RuntimeException failure() {
        return new IllegalStateException("The store failed at " + PLANTED);
    }

    /** An outbox whose store is down: every call throws with a secret-like text. */
    static final class FailingOutbox implements CallbackOutbox {
        public boolean enqueue(CallbackEntry entry) {
            throw failure();
        }

        public List<CallbackEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
            throw failure();
        }

        public void markSent(UUID id, int attempts, String outcome, String code, int status, Instant at) {
            throw failure();
        }

        public void markRetry(UUID id, int attempts, String outcome, String code, int status, Instant next) {
            throw failure();
        }

        public void markGaveUp(UUID id, int attempts, String outcome, String code, int status, Instant at) {
            throw failure();
        }

        public boolean resend(UUID id, Instant now) {
            throw failure();
        }

        public Optional<CallbackEntry> find(UUID id) {
            throw failure();
        }

        public List<CallbackEntry> gaveUp(UUID entity, String product, int limit) {
            throw failure();
        }

        public int purgeFinishedBefore(Instant before) {
            throw failure();
        }
    }

    /** A certificate store that is down the same way. */
    static final class FailingCredentials implements CredentialStore {
        public List<StoredCredential> list(UUID integrationId) {
            throw failure();
        }

        public int version(UUID integrationId) {
            throw failure();
        }

        public void save(int expectedVersion, CredentialChange change, List<StoredCredential> rows) {
            throw failure();
        }

        public List<CredentialChange> history(UUID integrationId) {
            throw failure();
        }

        public List<StoredCredential> active() {
            throw failure();
        }
    }

    @Test
    void aFailingRunLogsTheExceptionsClassNeverItsText() {
        InMemorySecretResolver secrets = new InMemorySecretResolver();
        try (CallEngine engine = new CallEngine(new Outbound(Installation.onPremises()), secrets,
                Clock.systemUTC())) {
            Callbacks callbacks = new Callbacks(new FailingOutbox(), new InMemoryIntegrationStore(), engine,
                    Clock.systemUTC());
            IntegrationCredentials credentials = new IntegrationCredentials(new FailingCredentials(), null,
                    e -> null, secrets, IntegrationAudit.NONE, Clock.systemUTC());
            new IntegrationWorker(callbacks, credentials, Duration.ofMinutes(1), Duration.ofDays(30),
                    Clock.systemUTC()).runOnce();
        }
        List<String> all;
        synchronized (lines) {
            all = List.copyOf(lines);
        }
        assertEquals(3, all.size(), all.toString());
        assertTrue(all.stream().anyMatch(l -> l.contains("callbacks could not be sent this run")), all.toString());
        assertTrue(all.stream().anyMatch(l -> l.contains("timed certificate switches failed")), all.toString());
        assertTrue(all.stream().anyMatch(l -> l.contains("the purge failed")), all.toString());
        for (String line : all) {
            for (String p : List.of(PLANTED, "PW-planted-8e8e", "db.corp.example", "The store failed")) {
                assertFalse(line.contains(p), "found " + p + " in: " + line);
            }
        }
        assertTrue(all.stream().allMatch(l -> l.contains("IllegalStateException")), "the class is named");
    }
}
