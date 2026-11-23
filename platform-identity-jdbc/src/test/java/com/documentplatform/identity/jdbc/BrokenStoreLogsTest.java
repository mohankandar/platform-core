package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.admission.ConnectionKey;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database cannot be reached and the driver's text carries a password, a JDBC URL and a user name. The store
 * errors name the operation and the SQL state only, and the log lines of the lookups carry nothing of that text.
 */
class BrokenStoreLogsTest {

    static final String PLANTED_PASSWORD = "PLANTED-DB-PW-7Qx";
    static final String PLANTED_URL = "jdbc:postgresql://db.corp.example:5432/identity?password=" + PLANTED_PASSWORD;
    static final String PLANTED_USER = "planted_db_user";
    static final List<String> PLANTED = List.of(PLANTED_PASSWORD, "db.corp.example", PLANTED_USER, "password=");

    /** Every connection attempt fails like a wrong password, with the planted text in the driver's message. */
    static DataSource broken() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("FATAL: password authentication failed for user \"" + PLANTED_USER
                                + "\" at " + PLANTED_URL, "28P01");
                    }
                    if (method.getName().equals("toString")) return "broken data source";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void storeErrorsAndLogLinesNameTheOperationOnly() {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            private final SimpleFormatter format = new SimpleFormatter();

            @Override
            public void publish(LogRecord r) {
                lines.add(format.format(r)); // message, parameters and any exception with its stack trace
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Logger logger = Logger.getLogger("com.documentplatform.identity.jdbc");
        Level before = logger.getLevel();
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
        List<String> texts = new ArrayList<>();
        try {
            DataSource ds = broken();
            ConnectionKey key = new ConnectionKey("customer-oidc", URI.create("https://login.example.test/realms/a"),
                    "api://product-a");
            PostgresIdentityLookup lookup = new PostgresIdentityLookup(ds, "PRODUCT_A");
            assertNull(lookup.find(key));
            assertNull(lookup.activeGrants(key));
            assertNull(lookup.activePlatformGrants(key));
            assertNull(new PostgresMachineClients(ds, "PRODUCT_A").entitiesWithEnabledClient("client-1"));

            IdentityStoreException thrown = assertThrows(IdentityStoreException.class,
                    () -> lookup.findActive("customer-oidc", URI.create("https://login.example.test/realms/a"),
                            "api://product-a"));
            assertEquals("IDN_STORE_FAILED: Identity store: read login connection failed (SQL state 28P01)", thrown.getMessage());
            texts.add(thrown.getMessage());
            texts.add(thrown.toString());
            IdentityStoreException binding = assertThrows(IdentityStoreException.class,
                    () -> new PostgresMachineBindingStore(ds).activeBinding("PRODUCT_A", "client-1"));
            assertSame(SQLException.class, binding.getCause().getClass(), "the driver's exception stays the cause");
            texts.add(binding.getMessage());
            texts.add(binding.toString());
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(before);
        }
        assertEquals(4, lines.size(), String.join("\n", lines));
        assertTrue(lines.stream().allMatch(l -> l.contains("SQL state 28P01")), String.join("\n", lines));
        List<String> all = new ArrayList<>(lines);
        all.addAll(texts);
        for (String text : all) {
            for (String planted : PLANTED) {
                assertFalse(text.contains(planted), planted + " in: " + text);
            }
        }
    }
}
