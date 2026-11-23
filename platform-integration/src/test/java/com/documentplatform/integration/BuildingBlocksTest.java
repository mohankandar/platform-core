package com.documentplatform.integration;

import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.internal.Classification;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.FieldLists;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.Packaging;
import com.documentplatform.integration.config.Rule;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.Template;
import com.documentplatform.integration.engine.CaseFields;
import com.documentplatform.integration.json.internal.Json;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.Installation;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The P024 building blocks without the network: each-block, field values, presets, rules, settings, storage. */
class BuildingBlocksTest {

    static final FieldSet FIELDS = FieldSet.of(List.of(
            FieldSpec.builder("account_no", "Account", FieldType.NUMBER).build(),
            FieldSpec.builder("amount", "Amount", FieldType.MONEY).currency("USD").build(),
            FieldSpec.builder("closing", "Closing", FieldType.DATE).build(),
            FieldSpec.builder("names", "Names", FieldType.TEXT).multiValue(true).build(),
            FieldSpec.builder("address", "Address", FieldType.ADDRESS).build(),
            FieldSpec.builder("ssn", "SSN", FieldType.TEXT).sensitive(true).build()));

    static CaseFields fields() {
        Map<String, List<FieldValue>> in = new LinkedHashMap<>();
        in.put("account_no", List.of(new FieldValue.Text("00123")));
        in.put("amount", List.of(new FieldValue.Text("$1,250")));
        in.put("closing", List.of(new FieldValue.Text("11/21/2026")));
        in.put("names", List.of(new FieldValue.Text("Ann Lee"), new FieldValue.Text("Bo Lee")));
        in.put("address", List.of(FieldValue.address("1 Main St", "Springfield", "il", "62701")));
        in.put("ssn", List.of(new FieldValue.Text("123-45-6789")));
        ValidatedFields v = FIELDS.validate(in);
        assertTrue(v.accepted(), v.problems().toString());
        return CaseFields.of(FIELDS, v);
    }

    @Test
    void theEachBlockWritesOneItemPerDocument() throws Exception {
        Template t = Template.parse("{\"docs\":[{#each doc}{\"id\":\"{doc.sourceDocumentId}\",\"n\":{doc.size}}{/each}],"
                + "\"count\":{package.documentCount}}", Template.Place.JSON);
        assertTrue(t.hasEach());
        assertEquals(Set.of("package.documentCount"), t.placeholdersOutsideEach());
        Template.Values doc1 = name -> Optional.of(name.equals("doc.size") ? "10" : "a");
        Template.Values doc2 = name -> Optional.of(name.equals("doc.size") ? "20" : "b\"");
        String out = t.fill(new Template.Values() {
            public Optional<String> text(String name) {
                return Optional.of("2");
            }

            public List<Template.Values> documents() {
                return List.of(doc1, doc2);
            }
        });
        assertEquals(Map.of("docs", List.of(Map.of("id", "a", "n", 10L), Map.of("id", "b\"", "n", 20L)), "count", 2L),
                Json.parse(out));
        for (String bad : new String[]{"[{#each doc}{#each doc}1{/each}{/each}]", "[{#each doc}1]",
                "\"{#each doc}x{/each}\"", "[{#each doc}{/each}]", "{#each doc}1{/each}{#each doc}2{/each}"}) {
            assertThrows(Template.Problem.class, () -> Template.parse(bad, Template.Place.JSON), bad);
        }
        assertThrows(Template.Problem.class, () -> Template.parse("x {#each doc}y{/each}", Template.Place.HEADER));
    }

    @Test
    void fieldValuesAreWrittenByTheirType() throws Exception {
        CaseFields f = fields();
        Template t = Template.parse("{\"amount\":{field:amount},\"closing\":{field:closing},\"names\":{field:names},"
                + "\"address\":{field:address},\"account\":\"{field:account_no}\"}", Template.Place.JSON);
        Template.Values v = new Template.Values() {
            public Optional<String> text(String name) {
                return f.field(name.substring(6)).flatMap(CaseFields::text);
            }

            public Optional<String> json(String name) {
                return f.field(name.substring(6)).flatMap(CaseFields::json);
            }
        };
        Map<String, Object> out = Json.object(t.fill(v));
        assertEquals(1250.0, ((Number) out.get("amount")).doubleValue(), "MONEY normalised, as a number");
        assertEquals("2026-11-21", out.get("closing"), "DATE normalised, as text");
        assertEquals(List.of("Ann Lee", "Bo Lee"), out.get("names"), "multi-value as a list");
        assertEquals("Springfield", ((Map<?, ?>) out.get("address")).get("city"), "ADDRESS as an object");
        assertEquals("00123", out.get("account"), "in quotes: the text, leading zeros kept");
        assertThrows(Template.Problem.class, () -> Template.parse("{\"a\":{field:account_no}}", Template.Place.JSON)
                .fill(v), "a NUMBER with leading zeros is not a JSON number");
        assertTrue(f.field("ssn").orElseThrow().sensitive());
        assertFalse(f.toString().contains("123-45"));
        assertThrows(CaseFields.Invalid.class, () -> CaseFields.fromDocument("{\"format\":\"x\"}", FIELDS));
        assertEquals(f.field("amount"), CaseFields.fromDocument(f.document(), FIELDS).field("amount"));
    }

    private static IntegrationConfig with(List<CallStep> steps) {
        return IntegrationConfig.apiPush(UUID.randomUUID(), "DEMO", "x", new Endpoint(URI.create("https://api.example"),
                true), SignIn.OAuth2ClientCredentials.secret(URI.create("https://login.example/token"), "c",
                SecretRef.of("int/c"), null)).withSteps(steps);
    }

    private static List<IntegrationCode> problems(IntegrationConfig c, FieldLists lists) {
        return ConfigCheck.check(c, Installation.onPremises(), lists).errors().stream().map(ConfigProblem::code).toList();
    }

    @Test
    void presetsExpandToOrdinaryValidSteps() {
        FieldLists lists = (e, p) -> Optional.of(FIELDS);
        List<CallStep> two = Packaging.twoStep("/subs", "{\"id\":\"{package.id}\",\"docs\":[{#each doc}"
                + "\"{doc.sourceDocumentId}\"{/each}]}", "subId", "/id", "/subs/{step.create.subId}/files",
                Map.of("X-Name", "{doc.fileName}"));
        assertEquals(List.of(), problems(with(two), lists));
        assertEquals(CallStep.Repeat.EACH_DOCUMENT, two.get(1).repeat());
        assertEquals(List.of(), problems(with(Packaging.multipart("/send", "{\"amount\":{field:amount}}", "metadata",
                "files")), lists));
        assertEquals(List.of(), problems(with(Packaging.filePerCall("/file", Map.of("X-Sha", "{doc.sha256}"))), lists));
    }

    @Test
    void theSaveCheckCoversTheNewBlocks() {
        FieldLists lists = (e, p) -> Optional.of(FIELDS);
        CallStep json = CallStep.of("s", HttpMethod.POST, "/x").withJsonBody("{\"ssn\":\"{field:ssn}\"}");
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED), problems(with(List.of(json)), lists));
        assertEquals(List.of(), problems(with(List.of(json)).withAllowedSensitiveFields(Set.of("ssn")), lists));
        CallStep header = CallStep.of("s", HttpMethod.GET, "/x").withHeader("X-Ssn", "{field:ssn}");
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED),
                problems(with(List.of(header)).withAllowedSensitiveFields(Set.of("ssn")), lists),
                "a sensitive field goes in a JSON body only, never a header or path");
        assertEquals(List.of(IntegrationCode.INT_CONFIG_FIELD_UNKNOWN), problems(with(List.of(json)), FieldLists.NONE));
        assertEquals(List.of(), problems(with(List.of(json)), null), "the run-time re-check has no field list");
        CallStep integrityOnce = CallStep.of("s", HttpMethod.POST, "/x").withJsonBody("{}").reading("sha", "/sha", false)
                .withIntegrity("sha", null);
        assertEquals(List.of(IntegrationCode.INT_CONFIG_BODY_INVALID), problems(with(List.of(integrityOnce)), lists));
        CallStep multiPerDoc = CallStep.of("s", HttpMethod.POST, "/x").eachDocument()
                .withMultipartBody("{}", new CallStep.Multipart("j", "f", "{doc.fileName}"));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_BODY_INVALID), problems(with(List.of(multiPerDoc)), lists));
        CallStep docOutsideEach = CallStep.of("s", HttpMethod.POST, "/x").withJsonBody("{\"n\":\"{doc.fileName}\"}");
        assertEquals(List.of(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE), problems(with(List.of(docOutsideEach)), lists));
        IntegrationConfig claimsWithoutOAuth = with(List.of(CallStep.of("s", HttpMethod.GET, "/x")))
                .withSignIn(new SignIn.BearerFixed(SecretRef.of("int/t")))
                .withSetting(IntegrationConfig.REQUIRED_TOKEN_CLAIMS, "a");
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SETTING_INVALID), problems(claimsWithoutOAuth, lists));
    }

    @Test
    void stepRulesComeFirstAndRulesCanAskForRetryAfter() {
        Classification c = Classification.of(List.of(Rule.of("503", null, Outcome.BUSY)),
                List.of(Rule.of("503", null, Outcome.OUTAGE)));
        assertEquals(Outcome.BUSY, c.decide(503, null, null).outcome());
        Classification ra = Classification.of(List.of(Rule.of("503", null, Outcome.BUSY).whenRetryAfter()
                .withPlatformCode(IntegrationCode.INT_DESTINATION_BUSY)));
        assertEquals(Outcome.BUSY, ra.decide(503, null, null, true).outcome());
        assertEquals(IntegrationCode.INT_DESTINATION_BUSY, ra.decide(503, null, null, true).code());
        assertEquals(Outcome.OUTAGE, ra.decide(503, null, null, false).outcome());
    }

    @Test
    void theNewPartsAreStoredAndOldStoredConfigurationsStillRead() {
        IntegrationConfig c = with(List.of(
                CallStep.of("send", HttpMethod.POST, "/x").withMultipartBody("{\"a\":1}",
                        new CallStep.Multipart("meta", "files", "{doc.fileName}")).withRules(List.of(
                        Rule.of("503", "X", Outcome.BUSY).whenRetryAfter())),
                CallStep.of("up", HttpMethod.PUT, "/y").eachDocument().withFileBody().reading("sha", "/sha", true)
                        .withIntegrity("sha", null)))
                .withAllowedSensitiveFields(Set.of("ssn")).withSetting("senderIdentity", "LOOKUP")
                .withLimits(Limits.DEFAULT.withMaxPackageBytes(5000));
        assertEquals(c, ConfigJson.read(ConfigJson.write(c)));
        // A P023 configuration (no package limit, settings, sensitive list, step rules) reads with the defaults.
        String old = ConfigJson.write(with(List.of(CallStep.of("s", HttpMethod.GET, "/x"))))
                .replace(",\"maxPackageBytes\":" + Limits.DEFAULT_PACKAGE_BYTES, "");
        IntegrationConfig read = ConfigJson.read(old);
        assertEquals(Limits.DEFAULT_PACKAGE_BYTES, read.limits().maxPackageBytes());
        assertTrue(read.settings().isEmpty());
        assertTrue(read.allowedSensitiveFields().isEmpty());
    }
}
