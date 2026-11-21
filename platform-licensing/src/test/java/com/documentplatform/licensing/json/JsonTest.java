package com.documentplatform.licensing.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void canonicalSortsKeysAndHasNoWhitespace() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", List.of("x", true));
        m.put("B", Map.of("z", 2L, "y", "w"));
        assertEquals("{\"B\":{\"y\":\"w\",\"z\":2},\"a\":[\"x\",true],\"b\":1}", Json.canonical(m));
    }

    @Test
    void canonicalEscapesOnlyWhatJsonRequires() {
        assertEquals("\"q\\\" b\\\\ n\\n t\\t c\\u0001 é / <\"", Json.canonical("q\" b\\ n\n t\t c\u0001 é / <"));
    }

    @Test
    void prettyAndCanonicalParseToTheSameValue() {
        Map<String, Object> m = Map.of("k", List.of(1L, Map.of("a", "b")), "e", List.of(), "o", Map.of());
        assertEquals(Json.canonical(m), Json.canonical(Json.parse(Json.pretty(m))));
    }

    @Test
    void parsesEscapesAndUnicode() {
        assertEquals("a\"b\\c/d\neé", Json.parse("\"a\\\"b\\\\c\\/d\\ne\\u00e9\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1.5", "1e3", "01", "-0", "{\"a\":1,\"a\":2}", "{\"a\":1} x", "[1,]", "{a:1}",
            "\"unterminated", "\"tab\there\"", "99999999999999999999", "", "tru", "{\"a\" 1}"})
    void refusesWhatIsNotInTheSubset(String text) {
        assertThrows(JsonException.class, () -> Json.parse(text));
    }

    @Test
    void refusesDeepNesting() {
        assertThrows(JsonException.class, () -> Json.parse("[".repeat(40) + "]".repeat(40)));
    }

    @Test
    void failureMessageGivesPositionNotContent() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\"secret-value\":1.5}"));
        assertEquals(false, e.getMessage().contains("secret-value"));
    }
}
