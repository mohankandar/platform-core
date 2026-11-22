package com.documentplatform.integration;

import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.FieldLists;
import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.config.HttpMethod;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** B3: the folder export's configuration: path rules (local, share, Unix), name templates, save checks, stored form. */
class FolderConfigTest {

    static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private static List<IntegrationCode> codes(IntegrationConfig c, Installation i, FieldLists f) {
        return ConfigCheck.check(c, i, f).errors().stream().map(ConfigProblem::code).toList();
    }

    private static IntegrationConfig folder(FolderTarget t) {
        return IntegrationConfig.folderExport(ENTITY, "PRODUCT_A", "Share export", t);
    }

    @Test
    void localSharedAndUnixFoldersMustBeAbsolute() {
        for (String ok : List.of("D:\\exports\\in", "d:/exports", "\\\\fileserver\\exports", "\\\\fs01\\exp$\\in\\2026",
                "/srv/exports", "/mnt/share/in box")) {
            assertTrue(FolderTarget.validFolder(ok), ok);
        }
        for (String bad : List.of("", "exports", ".\\exports", "C:exports", "\\\\server", "\\\\server\\", "//server/share",
                "D:\\exports\\..\\other", "/srv/./x", "/srv/a*b", "D:\\a?b", "D:\\a\"b", "D:\\x:stream", "/srv/a:b",
                "/srv/a\nb", "\\\\server\\share\\..")) {
            assertFalse(FolderTarget.validFolder(bad), bad);
        }
        assertTrue(FolderTarget.of("\\\\fs\\exp").share());
        assertFalse(FolderTarget.of("D:\\exp").share());
    }

    @Test
    void namesAreOneSafeSegmentAndOwnFileNamesAreMadeSafe() {
        for (String ok : List.of("PKG-1", "statement 2026.pdf", "a%2Fb", "x.y.z")) {
            assertTrue(FolderTarget.validName(ok), ok);
        }
        for (String bad : List.of("", ".", "..", "a/b", "a\\b", "a:b", "x.", "x ", " x", "CON", "nul.txt", "com1.pdf",
                "pkg.partial", "PKG.PARTIAL", "a<b", "a|b", "x".repeat(201))) {
            assertFalse(FolderTarget.validName(bad), bad);
        }
        assertEquals("a_b_c.pdf", FolderTarget.safeName("a/b\\c.pdf"));
        assertEquals("Q3 _final_.pdf", FolderTarget.safeName("Q3 \"final\".pdf"));
        assertEquals("_CON.pdf", FolderTarget.safeName("CON.pdf"));
        assertEquals("document", FolderTarget.safeName("..."));
        assertEquals("hidden", FolderTarget.safeName(".hidden"));
        assertEquals("x.partial_", FolderTarget.safeName("x.partial"));
        String longName = FolderTarget.safeName("y".repeat(300) + ".pdf");
        assertEquals(200, longName.length());
        assertTrue(longName.endsWith(".pdf"));
        for (String n : List.of("a/b\\c.pdf", "CON.pdf", "...", "x.partial", "z".repeat(400))) {
            assertTrue(FolderTarget.validName(FolderTarget.safeName(n)), n);
        }
    }

    @Test
    void theSaveCheckAcceptsAFolderExport() {
        IntegrationConfig c = folder(FolderTarget.of("\\\\fileserver\\exports").withFolderName("{package.id}-{value.batch}")
                .withFileName("{doc.sourceDocumentId}-{doc.fileName}"));
        assertEquals(List.of(), codes(c, Installation.onPremises(), null));
        assertEquals(Kind.FOLDER_EXPORT, c.kind());
        assertInstanceOf(SignIn.None.class, c.signIn());
    }

    @Test
    void theSaveCheckRefusesWhatAFolderExportCannotHave() {
        Installation onPrem = Installation.onPremises();
        assertEquals(List.of(IntegrationCode.INT_CONFIG_FOLDER_HOSTED),
                codes(folder(FolderTarget.of("D:\\exports")), Installation.hostedService(), null));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_FOLDER_INVALID),
                codes(folder(FolderTarget.of("exports")), onPrem, null));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_FOLDER_INVALID),
                codes(folder(FolderTarget.of("D:\\exports")).withFolder(null), onPrem, null));
        // Names: separators, literal braces, reserved results, the manifest's name, unknown placeholders.
        for (String bad : List.of("a/{package.id}", "{package.id}\\x", "x:{package.id}", "{package.id}.partial", "CON",
                "{package.id", "", ".")) {
            assertEquals(List.of(IntegrationCode.INT_CONFIG_FOLDER_INVALID),
                    codes(folder(FolderTarget.of("D:\\e").withFolderName(bad)), onPrem, null), bad);
        }
        assertEquals(List.of(IntegrationCode.INT_CONFIG_FOLDER_INVALID),
                codes(folder(FolderTarget.of("D:\\e").withFileName("manifest.json")), onPrem, null));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE),
                codes(folder(FolderTarget.of("D:\\e").withFolderName("{doc.fileName}")), onPrem, null));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_PLACEHOLDER_PLACE),
                codes(folder(FolderTarget.of("D:\\e").withFolderName("{correlationId}")), onPrem, null));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_PLACEHOLDER_FORBIDDEN),
                codes(folder(FolderTarget.of("D:\\e").withFileName("{doc.content}")), onPrem, null));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_OMIT_PLACE),
                codes(folder(FolderTarget.of("D:\\e").withFileName("{doc.fileName?}")), onPrem, null));
        // No steps, endpoint, health call or sign-in bundle.
        IntegrationConfig steps = folder(FolderTarget.of("D:\\e"))
                .withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/x")));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_STEPS_INVALID), codes(steps, onPrem, null));
        IntegrationConfig signIn = folder(FolderTarget.of("D:\\e")).withSignIn(new SignIn.BearerFixed(SecretRef.of("x")));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_INVALID), codes(signIn, onPrem, null));
        // A folder target on another kind.
        IntegrationConfig api = IntegrationConfig.apiPush(ENTITY, "PRODUCT_A", "x",
                        com.documentplatform.integration.config.Endpoint.of("https://api.example.test"),
                        new SignIn.BearerFixed(SecretRef.of("x")))
                .withSteps(List.of(CallStep.of("s", HttpMethod.POST, "/x"))).withFolder(FolderTarget.of("D:\\e"));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_INVALID), codes(api, onPrem, null));
    }

    @Test
    void caseFieldsInNamesMustExistAndNeverBeSensitive() {
        FieldSet fields = FieldSet.of(List.of(
                FieldSpec.builder("reference_number", "Reference", FieldType.TEXT).build(),
                FieldSpec.builder("holder_name", "Holder", FieldType.TEXT).sensitive(true).build()));
        FieldLists lists = (entity, product) -> Optional.of(fields);
        Installation onPrem = Installation.onPremises();
        assertEquals(List.of(), codes(folder(FolderTarget.of("D:\\e").withFolderName("{field:reference_number}")),
                onPrem, lists));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_FIELD_UNKNOWN),
                codes(folder(FolderTarget.of("D:\\e").withFolderName("{field:nope}")), onPrem, lists));
        // Even when allowed for the manifest, a sensitive field never goes into a name.
        IntegrationConfig sensitive = folder(FolderTarget.of("D:\\e").withFileName("{field:holder_name}.pdf"))
                .withAllowedSensitiveFields(java.util.Set.of("holder_name"));
        assertEquals(List.of(IntegrationCode.INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED), codes(sensitive, onPrem, lists));
    }

    @Test
    void theStoredFormKeepsTheFolderTarget() {
        IntegrationConfig c = folder(new FolderTarget("\\\\fs\\exports\\in", "{package.id}", "{doc.sourceDocumentId}.pdf"))
                .withAllowedSensitiveFields(java.util.Set.of("holder_name"));
        IntegrationConfig back = ConfigJson.read(ConfigJson.write(c));
        assertEquals(c, back);
        IntegrationConfig noFile = folder(FolderTarget.of("/srv/exports"));
        assertEquals(noFile, ConfigJson.read(ConfigJson.write(noFile)));
        assertNull(noFile.folder().fileNameTemplate());
        assertEquals(List.of("folder.fileName", "folder.folder"), ConfigJson.changedFields(c,
                c.withFolder(new FolderTarget("D:\\other", "{package.id}", null))));
    }
}
