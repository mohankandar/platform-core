package com.documentplatform.integration;

import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.outbound.Installation;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** B4: a JSON member marked "omit when missing" ({@code {name?}}) is left out, comma included, when it has no value. */
class OmitWhenMissingTest {

    /** Text values from the map; JSON values for {field:...} from the second map; documents from the third. */
    private record Vals(Map<String, String> text, Map<String, String> json, List<Template.Values> docs)
            implements Template.Values {
        Vals(Map<String, String> text) {
            this(text, Map.of(), List.of());
        }

        @Override
        public Optional<String> text(String name) {
            return Optional.ofNullable(text.get(name));
        }

        @Override
        public Optional<String> json(String name) {
            return Optional.ofNullable(json.get(name));
        }

        @Override
        public List<Template.Values> documents() {
            return docs;
        }
    }

    private static String fill(String template, Template.Values v) throws Exception {
        String out = Template.parse(template, Template.Place.JSON).fill(v);
        Json.parse(out); // always JSON
        return out;
    }

    private static IntegrationCode problem(String text, Template.Place place) {
        return assertThrows(Template.Problem.class, () -> Template.parse(text, place)).code();
    }

    @Test
    void aMissingMemberIsLeftOutWithItsComma() throws Exception {
        String t = "{\"id\":\"{package.id}\",\"loan\":\"{value.loan?}\",\"ref\":\"{value.ref?}\"}";
        assertEquals("{\"id\":\"p1\",\"loan\":\"L-1\",\"ref\":\"R\"}",
                fill(t, new Vals(Map.of("package.id", "p1", "value.loan", "L-1", "value.ref", "R"))));
        assertEquals("{\"id\":\"p1\",\"ref\":\"R\"}", fill(t, new Vals(Map.of("package.id", "p1", "value.ref", "R"))));
        assertEquals("{\"id\":\"p1\",\"loan\":\"L-1\"}", fill(t, new Vals(Map.of("package.id", "p1", "value.loan", "L-1"))));
        assertEquals("{\"id\":\"p1\"}", fill(t, new Vals(Map.of("package.id", "p1"))));
        // A present value is written as usual (escaped); an empty string is a value, not "missing".
        assertEquals("{\"id\":\"p1\",\"loan\":\"a\\\"b\",\"ref\":\"\"}",
                fill(t, new Vals(Map.of("package.id", "p1", "value.loan", "a\"b", "value.ref", ""))));
    }

    @Test
    void theFirstOrOnlyMemberLeavesTheNextComma() throws Exception {
        String t = "{ \"a\" : \"{value.a?}\" , \"b\":\"{value.b?}\", \"c\":1 }";
        assertEquals("{\"b\":\"B\",\"c\":1}", fill(t, new Vals(Map.of("value.b", "B"))).replaceAll("\\s", ""));
        assertEquals("{\"c\":1}", fill(t, new Vals(Map.of())).replaceAll("\\s", ""));
        assertEquals("{\"a\":\"A\",\"c\":1}", fill(t, new Vals(Map.of("value.a", "A"))).replaceAll("\\s", ""));
        assertEquals("{}", fill("{\"a\":\"{value.a?}\"}", new Vals(Map.of())));
        assertEquals("{\"x\":{},\"y\":2}", fill("{\"x\":{\"a\":\"{value.a?}\"},\"y\":2}", new Vals(Map.of())));
    }

    @Test
    void typedFieldValuesAndNumbersOutsideAString() throws Exception {
        String t = "{\"amount\":{field:loan_amount?},\"count\":{package.documentCount?},\"n\":\"{field:name?}\"}";
        Vals all = new Vals(Map.of("package.documentCount", "2", "field:name", "Ann"),
                Map.of("field:loan_amount", "250000.00"), List.of());
        assertEquals("{\"amount\":250000.00,\"count\":2,\"n\":\"Ann\"}", fill(t, all));
        assertEquals("{\"count\":2}", fill(t, new Vals(Map.of("package.documentCount", "2"))));
        // Present but not usable where it goes: refused, never silently left out.
        Template.Values notANumber = name -> Optional.ofNullable(Map.of("package.documentCount", "x").get(name));
        assertEquals(IntegrationCode.INT_VALUE_NOT_ALLOWED, assertThrows(Template.Problem.class,
                () -> Template.parse("{\"count\":{package.documentCount?}}", Template.Place.JSON).fill(notANumber))
                .code());
    }

    @Test
    void insideTheEachBlockPerDocument() throws Exception {
        String t = "{\"docs\":[{#each doc}{\"id\":\"{doc.sourceDocumentId}\",\"level\":\"{doc.attr.level?}\"}{/each}]}";
        Template.Values d1 = new Vals(Map.of("doc.sourceDocumentId", "d1", "doc.attr.level", "HIGH"));
        Template.Values d2 = new Vals(Map.of("doc.sourceDocumentId", "d2"));
        assertEquals("{\"docs\":[{\"id\":\"d1\",\"level\":\"HIGH\"},{\"id\":\"d2\"}]}",
                fill(t, new Vals(Map.of(), Map.of(), List.of(d1, d2))));
        assertEquals(Set.of("doc.attr.level"), Template.parse(t, Template.Place.JSON).optionalPlaceholders());
    }

    @Test
    void refusedAnywhereButAWholeMember() {
        for (String bad : List.of(
                "{\"a\":\"x-{value.a?}\"}",          // part of a longer string
                "{\"a\":\"{value.a?}-x\"}",
                "{\"a\":[\"{value.a?}\"]}",          // in a list
                "[{package.documentCount?}]",
                "{\"{value.a?}\":1}",                // as a key
                "{\"a\":\"{value.a?}{value.b}\"}",
                "{\"a\":{package.documentCount?}0}")) {
            assertEquals(IntegrationCode.INT_CONFIG_OMIT_PLACE, problem(bad, Template.Place.JSON), bad);
        }
        assertEquals(IntegrationCode.INT_CONFIG_OMIT_PLACE, problem("/a/{value.a?}", Template.Place.PATH));
        assertEquals(IntegrationCode.INT_CONFIG_OMIT_PLACE, problem("x {value.a?}", Template.Place.HEADER));
        assertEquals(IntegrationCode.INT_CONFIG_OMIT_PLACE, problem("{value.a?}.pdf", Template.Place.NAME));
        // Still no expressions: "?" means only this.
        assertEquals(IntegrationCode.INT_CONFIG_TEMPLATE_INVALID, problem("/a/{value.a??}", Template.Place.PATH));
    }

    @Test
    void theSaveCheckRefusesItOutsideAMemberAndAcceptsIt() {
        IntegrationConfig ok = config("{\"id\":\"{package.id}\",\"loan\":\"{value.loan?}\"}");
        assertTrue(ConfigCheck.check(ok, Installation.onPremises()).ok());
        IntegrationConfig bad = config("{\"id\":\"{package.id}\",\"loan\":\"L-{value.loan?}\"}");
        List<ConfigProblem> errors = ConfigCheck.check(bad, Installation.onPremises()).errors();
        assertEquals(1, errors.size(), errors.toString());
        assertEquals(IntegrationCode.INT_CONFIG_OMIT_PLACE, errors.get(0).code());
        assertEquals("steps[0].bodyTemplate", errors.get(0).where());
        IntegrationConfig header = config("{}").withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/x")
                .withJsonBody("{}").withHeader("X-Loan", "{value.loan?}")));
        assertEquals(IntegrationCode.INT_CONFIG_OMIT_PLACE,
                ConfigCheck.check(header, Installation.onPremises()).errors().get(0).code());
    }

    private static IntegrationConfig config(String body) {
        return IntegrationConfig.apiPush(UUID.randomUUID(), "PRODUCT_A", "Omit test",
                        Endpoint.of("https://api.example.test"), new SignIn.BearerFixed(SecretRef.of("int/t")))
                .withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/x").withJsonBody(body)));
    }

    @Test
    void placeholdersStillListThemForTheFieldChecks() throws Exception {
        Template t = Template.parse("{\"a\":{field:loan_amount?},\"b\":\"{value.b}\"}", Template.Place.JSON);
        assertEquals(Set.of("field:loan_amount", "value.b"), t.placeholders());
        assertEquals(Set.of("field:loan_amount"), t.optionalPlaceholders());
        Map<String, String> none = new HashMap<>();
        none.put("value.b", "B");
        assertEquals("{\"b\":\"B\"}", t.fill(new Vals(none)));
    }
}
