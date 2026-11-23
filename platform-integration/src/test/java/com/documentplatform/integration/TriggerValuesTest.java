package com.documentplatform.integration;

import com.documentplatform.integration.watch.internal.TriggerValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Trigger files: the fixed mapping for JSON and XML, and the hardened parsers (attack files refused). */
class TriggerValuesTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> mapping(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void jsonPointersWithEveryListElement() throws Exception {
        Object t = TriggerValues.json(b("﻿{\"loan\":{\"number\":\"L-17\",\"amount\":1250.5,\"level\":2},"
                + "\"files\":[{\"name\":\"a.pdf\"},{\"name\":\"b.pdf\"},{\"other\":1}],\"tags\":[\"x\",\"y\"],"
                + "\"a~b\":{\"c/d\":true}}"));
        Map<String, List<String>> v = TriggerValues.extract(t, mapping("number", "/loan/number", "amount",
                "/loan/amount", "level", "/loan/level", "files", "/files/*/name", "second", "/files/1/name", "tags",
                "/tags", "escaped", "/a~0b/c~1d", "missing", "/nothing/here", "object", "/loan"));
        assertEquals(List.of("L-17"), v.get("number"));
        assertEquals(List.of("1250.5"), v.get("amount"));
        assertEquals(List.of("2"), v.get("level"));
        assertEquals(List.of("a.pdf", "b.pdf"), v.get("files"));
        assertEquals(List.of("b.pdf"), v.get("second"));
        assertEquals(List.of("x", "y"), v.get("tags"));
        assertEquals(List.of("true"), v.get("escaped"));
        assertEquals(List.of(), v.get("missing"));
        assertEquals(List.of(), v.get("object"), "an object is not a value");
        assertTrue(TriggerValues.validJsonPath("/files/*/name"));
        assertFalse(TriggerValues.validJsonPath("files"));
        assertFalse(TriggerValues.validJsonPath("/a~2"));
    }

    @Test
    void xmlPathsWithIndexesAndAttributes() throws Exception {
        Object t = TriggerValues.xml(b("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<p:drop xmlns:p=\"urn:example\">"
                + "<p:loan number=\"L-17\" level=\"2\"><amount> 1250.50 </amount></p:loan>"
                + "<files><file name=\"a.pdf\"><type>contract</type></file><file name=\"b.pdf\"/></files></p:drop>"));
        Map<String, List<String>> v = TriggerValues.extract(t, mapping("number", "/drop/loan/@number", "amount",
                "/drop/loan/amount", "files", "/drop/files/file/@name", "second", "/drop/files/file[2]/@name",
                "type", "/drop/files/file[1]/type", "none", "/drop/files/file[3]/@name", "wrongRoot", "/other/x"));
        assertEquals(List.of("L-17"), v.get("number"));
        assertEquals(List.of("1250.50"), v.get("amount"));
        assertEquals(List.of("a.pdf", "b.pdf"), v.get("files"));
        assertEquals(List.of("b.pdf"), v.get("second"));
        assertEquals(List.of("contract"), v.get("type"));
        assertEquals(List.of(), v.get("none"));
        assertEquals(List.of(), v.get("wrongRoot"));
        assertTrue(TriggerValues.validXmlPath("/drop/files/file[2]/@name"));
        for (String bad : List.of("drop/x", "/drop/", "/@x", "/drop/@x/y", "/drop/x[0]", "/drop/x[a]", "/drop//x",
                "/drop/*", "/drop/x|y")) {
            assertFalse(TriggerValues.validXmlPath(bad), bad);
        }
    }

    @Test
    void billionLaughsIsRefused() {
        String laughs = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">"
                + "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">"
                + "<!ENTITY lol4 \"&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;\">]>"
                + "<lolz>&lol4;</lolz>";
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(b(laughs)));
    }

    @Test
    void externalEntitiesAndXIncludeAreRefused(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "SECRET-planted-xxe");
        String uri = secret.toUri().toString();
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"" + uri + "\">]><d>&x;</d>";
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(b(xxe)));
        String parameter = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY % p SYSTEM \"" + uri + "\"> %p;]><d/>";
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(b(parameter)));
        String external = "<?xml version=\"1.0\"?><!DOCTYPE d SYSTEM \"" + uri + "\"><d/>";
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(b(external)));
        // XInclude is not processed: the include element stays an element, nothing is read from the file.
        Object inc = TriggerValues.xml(b("<d xmlns:xi=\"http://www.w3.org/2001/XInclude\"><v>ok</v>"
                + "<xi:include href=\"" + uri + "\" parse=\"text\"/></d>"));
        Map<String, List<String>> v = TriggerValues.extract(inc, mapping("v", "/d/v", "inc", "/d/include"));
        assertEquals(List.of("ok"), v.get("v"));
        assertEquals(List.of(""), v.get("inc"));
    }

    @Test
    void depthSizeAndEncodingLimits() {
        String deepXml = "<a>".repeat(TriggerValues.MAX_XML_DEPTH + 1) + "</a>".repeat(TriggerValues.MAX_XML_DEPTH + 1);
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(b(deepXml)));
        String deepJson = "[".repeat(40) + "]".repeat(40);
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.json(b(deepJson)));
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.json(b("{\"a\":")));
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(b("<a><b></a>")));
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.json(new byte[]{'"', (byte) 0xc3, '"'}));
        // An encoding declaration cannot switch away from UTF-8.
        assertThrows(TriggerValues.Unreadable.class, () -> TriggerValues.xml(new byte[]{'<', 'a', '>', (byte) 0xe9,
                '<', '/', 'a', '>'}));
        TriggerValues.Unreadable u = assertThrows(TriggerValues.Unreadable.class,
                () -> TriggerValues.json(b("{\"secret\":\"VALUE-planted-77\"")));
        assertFalse(String.valueOf(u.getMessage()).contains("VALUE-planted-77"));
    }
}
