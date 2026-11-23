package com.documentplatform.integration;

import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.json.internal.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TemplateTest {

    private static Optional<String> v(String name) {
        return Optional.ofNullable(Map.of("package.id", "p/1", "doc.size", "42", "value.loan", "a\"b\\c\n",
                "doc.fileName", "x y.pdf").get(name));
    }

    private static IntegrationCode problem(String text, Template.Place place) {
        return assertThrows(Template.Problem.class, () -> Template.parse(text, place)).code();
    }

    @Test
    void pathValuesStayOneSegment() throws Exception {
        Template t = Template.parse("/api/{package.id}/files/{doc.fileName}?mode=full", Template.Place.PATH);
        assertEquals(Set.of("package.id", "doc.fileName"), t.placeholders());
        assertEquals("/api/p%2F1/files/x%20y.pdf?mode=full", t.render(TemplateTest::v));
        assertEquals(IntegrationCode.INT_VALUE_NOT_ALLOWED, assertThrows(Template.Problem.class,
                () -> Template.parse("/a/{value.x}", Template.Place.PATH).render(n -> Optional.of(""))).code());
        assertEquals("/a/%2E%2E", Template.parse("/a/{value.x}", Template.Place.PATH)
                .render(n -> Optional.of("..")));
    }

    @Test
    void pathsAreChecked() {
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_IN_QUERY, problem("/a?id={package.id}", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("a/b", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("//host/b", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("/a/./b", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("/a b", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("/a/{ x}", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("/a#frag", Template.Place.PATH));
    }

    @Test
    void forbiddenAndUnknownPlaceholders() {
        for (String p : new String[]{"secret.x", "token", "password", "signin.basic", "content", "doc.content",
                "doc.bytes", "credential.ref", "doc.text"}) {
            assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN, problem("x {" + p + "}",
                    Template.Place.HEADER), p);
        }
        for (String p : new String[]{"doc.owner", "package.name", "value", "step.a", "env.HOME", "x"}) {
            assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_UNKNOWN, problem("x {" + p + "}",
                    Template.Place.HEADER), p);
        }
    }

    @Test
    void jsonValuesAreEscapedInStringsAndOnlyNumbersOutside() throws Exception {
        Template t = Template.parse("{\"id\":\"{package.id}\",\"n\":{doc.size},\"loan\":\"{value.loan}\"}",
                Template.Place.JSON);
        String out = t.render(TemplateTest::v);
        Map<String, Object> m = Json.object(out);
        assertEquals("p/1", m.get("id"));
        assertEquals(42L, m.get("n"));
        assertEquals("a\"b\\c\n", m.get("loan"));
        assertEquals(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE, problem("{\"a\":{value.loan}}", Template.Place.JSON));
        assertEquals(IntegrationCode.INT_VALUE_NOT_ALLOWED, assertThrows(Template.Problem.class,
                () -> Template.parse("{\"n\":{doc.size}}", Template.Place.JSON)
                        .render(n -> Optional.of("1,\"x\":2"))).code());
        // A placeholder after an escaped quote is still inside the string.
        assertEquals("{\"a\":\"\\\"p/1\"}", Template.parse("{\"a\":\"\\\"{package.id}\"}", Template.Place.JSON)
                .render(TemplateTest::v));
    }

    @Test
    void headersRefuseLineBreaks() throws Exception {
        Template t = Template.parse("Key {value.loan}", Template.Place.HEADER);
        assertEquals(IntegrationCode.INT_VALUE_NOT_ALLOWED, assertThrows(Template.Problem.class,
                () -> t.render(TemplateTest::v)).code());
        assertEquals("Key ok", t.render(n -> Optional.of("ok")));
        assertEquals(IntegrationCode.INT_VALUE_MISSING, assertThrows(Template.Problem.class,
                () -> t.render(n -> Optional.empty())).code());
    }
}
