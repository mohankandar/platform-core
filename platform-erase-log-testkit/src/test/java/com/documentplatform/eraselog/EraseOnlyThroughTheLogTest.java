package com.documentplatform.eraselog;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.InMemoryValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.StoredRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P044, SEC-5: no public API outside the erase-log flow can erase a case field value. The build fails when:
 * <ul>
 *   <li>a "may erase" token can be made from outside its package ({@code EraseTicket}, the erase log's handler context);
 *       </li>
 *   <li>a public method that deletes, erases, purges or clears appears in platform-fields or the erase log without
 *       being reviewed here (each listed one says why it cannot erase outside the log);</li>
 *   <li>one of the paths the review found works again (the handler called directly, a replace that takes every value
 *       away).</li>
 * </ul>
 */
class EraseOnlyThroughTheLogTest {

    /** Reviewed public methods with an erasing name, and why each one cannot erase outside the log. */
    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("com.documentplatform.eraselog.Eraser#erase", "the erase log's own flow: logs durably, then erases"),
            Map.entry("com.documentplatform.eraselog.EraseRequest#erasedAt", "a getter (when the erasure was decided)"),
            Map.entry("com.documentplatform.fields.values.FieldValues#erase",
                    "refuses every caller but the erase log's case-fields handler (VALUES_ERASE_OUTSIDE_LOG)"),
            Map.entry("com.documentplatform.fields.values.ValueStore#delete",
                    "needs an EraseTicket, which only FieldValues makes (package-private constructor)"),
            Map.entry("com.documentplatform.fields.jdbc.PostgresValueStore#delete", "the same, for the Postgres store"),
            Map.entry("com.documentplatform.fields.inmemory.InMemoryValueStore#delete", "the same, test scope only"),
            Map.entry("com.documentplatform.eraselog.EraseLog#prune",
                    "removes old log entries the database has applied; never a value"),
            Map.entry("com.documentplatform.eraselog.EraseLogStore#pruneThrough", "the same, the store's part (SPI)"),
            Map.entry("com.documentplatform.eraselog.file.FileEraseLogStore#pruneThrough", "the same"),
            Map.entry("com.documentplatform.eraselog.testkit.InMemoryEraseLogStore#pruneThrough", "the same, tests"),
            Map.entry("com.documentplatform.eraselog.testkit.InMemoryEraseLogStore#remove", "tampering switch, tests"),
            Map.entry("com.documentplatform.eraselog.testkit.InMemoryEraseLogStore#dropSeal", "tampering switch, tests"));

    static final Pattern ERASING = Pattern.compile("(?i)(erase|delete|purge|prune|wipe|destroy|remove|clear|drop)[A-Za-z]*");

    @Test
    void theMayEraseTokensCannotBeMadeFromOutside() throws Exception {
        Class<?> ticket = Class.forName("com.documentplatform.fields.values.EraseTicket");
        assertEquals(0, ticket.getConstructors().length, "EraseTicket has a public constructor");
        for (Method m : ticket.getMethods()) {
            assertFalse(Modifier.isStatic(m.getModifiers()) && m.getReturnType() == ticket,
                    "EraseTicket has a public factory: " + m);
        }
        Class<?> issued = Class.forName("com.documentplatform.eraselog.Applying$Issued");
        assertFalse(Modifier.isPublic(issued.getModifiers()), "the handler context class is public");
        assertEquals(0, issued.getConstructors().length);
        EraseHandler.Context made = java.util.Optional::empty;
        assertFalse(EraseHandler.Context.issuedFor(made, null));
    }

    @Test
    void everyPublicMethodThatSoundsLikeErasingIsReviewed() throws Exception {
        Set<String> found = new TreeSet<>();
        for (Class<?> c : classes("com/documentplatform/fields", "com/documentplatform/eraselog")) {
            if (!Modifier.isPublic(c.getModifiers()) || c.getName().contains(".internal.")) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && ERASING.matcher(m.getName()).matches()) {
                    found.add(c.getName() + "#" + m.getName());
                }
            }
        }
        Set<String> unreviewed = new TreeSet<>(found);
        unreviewed.removeAll(REVIEWED.keySet());
        assertEquals(Set.of(), unreviewed, "public methods that may erase, not reviewed in this test: check each "
                + "cannot erase a value outside the erase log, then list it with the reason");
        Set<String> gone = new TreeSet<>(REVIEWED.keySet());
        gone.removeAll(found);
        assertEquals(Set.of(), gone, "reviewed methods that no longer exist: take them off the list");
    }

    @Test
    void aReplaceThatTakesEveryValueAwayIsRefused() {
        UUID entity = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        FieldScope scope = FieldScope.of(entity, "PRODUCT_A");
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        InMemoryFieldStore fieldStore = new InMemoryFieldStore();
        InMemoryValueStore valueStore = new InMemoryValueStore();
        new FieldDefinitions(fieldStore, valueStore, clock).create(scope, FieldSpec.builder("loan_number", "Loan",
                FieldType.TEXT).primary(true).sensitive(false).build(), "u");
        CustomerId customer = CustomerId.fromTenantId("TEN-TEST-0001");
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(customer);
        FieldValues values = new FieldValues(customer, fieldStore, valueStore, keys, clock);
        RecordRef ref = RecordRef.of(scope, "case", "c1");
        values.claim(ref, Map.of("loan_number", List.of(FieldValue.text("L-1"))));

        StoredRecord r = valueStore.find(ref).orElseThrow();
        FieldValuesException e = assertThrows(FieldValuesException.class,
                () -> valueStore.replace(r.with(null, null, Instant.now(), List.of())));
        assertEquals("VALUES_ERASE_OUTSIDE_LOG", e.code().name());
        assertEquals(1, valueStore.find(ref).orElseThrow().values().size(), "nothing was taken away");
    }

    /** Every class under the given package paths on the test classpath (folders or jars). */
    static List<Class<?>> classes(String... roots) throws Exception {
        Set<String> names = new TreeSet<>();
        for (String root : roots) {
            for (URL u : Collections.list(EraseOnlyThroughTheLogTest.class.getClassLoader().getResources(root))) {
                if (u.getProtocol().equals("jar")) {
                    JarURLConnection c = (JarURLConnection) u.openConnection();
                    try (JarFile jar = c.getJarFile()) {
                        jar.stream().map(e -> e.getName()).filter(n -> n.startsWith(root) && n.endsWith(".class"))
                                .forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
                    }
                } else if (u.getProtocol().equals("file")) {
                    Path dir = Path.of(u.toURI());
                    Path base = dir;
                    for (int i = 0; i < root.split("/").length; i++) base = base.getParent();
                    Path b = base;
                    try (Stream<Path> s = Files.walk(dir)) {
                        s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                            String rel = b.relativize(p).toString().replace('\\', '/');
                            names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
                        });
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        List<Class<?>> out = new java.util.ArrayList<>();
        for (String n : names) {
            if (n.contains("Test") || n.endsWith("Pg") || n.endsWith("Fx")) continue; // test classes on the path
            try {
                out.add(Class.forName(n, false, EraseOnlyThroughTheLogTest.class.getClassLoader()));
            } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
                // not loadable without an optional dependency: not public API reachable here
            }
        }
        return out;
    }
}
