package com.documentplatform.identity.mapping;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static com.documentplatform.identity.mapping.ClientBindingsFile.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientBindingsFileTest {

    private static final Set<String> SOURCES = Set.of("loan-system", "scanner");

    private static ClientBindingsFile.Result read(String text) {
        return ClientBindingsFile.read(text.getBytes(StandardCharsets.UTF_8), SOURCES);
    }

    private static List<String> problems(String text) {
        return read(text).errors().stream().map(p -> p.line() + " " + p.code()).toList();
    }

    @Test
    void readsTheFormatAsSpreadsheetsSaveIt() {
        ClientBindingsFile.Result r = read("﻿" + ClientBindingsFile.HEADER + "\r\n"
                + "0oa1b2c3d4E5f6G7h8i9,ENT-A1,loan-system,Loan system\r\n"
                + "\r\n"
                + "4f1c2e3d-1111-4a2b-9c3d-123456789abc,ENT-A2,scanner,\"Scanner, floor 2\"\r\n"
                + "#not-a-comment,ENT-A1,scanner,\r\n"
                + "Xq3vT9bLm2Np7Rs4Kd8Hf6Jw1Yc5Zg0A,ENT-A1,scanner,an Auth0-style client ID (32 characters)\n");
        assertTrue(r.valid(), r.errors().toString());
        assertEquals(4, r.rows().size());
        assertEquals(new ClientBindingsFile.Row(2, "0oa1b2c3d4E5f6G7h8i9", "ENT-A1", "loan-system", "Loan system"),
                r.rows().get(0));
        assertEquals("Scanner, floor 2", r.rows().get(1).description());
        assertEquals(4, r.rows().get(1).line(), "physical line numbers, empty lines counted");
        assertEquals("#not-a-comment", r.rows().get(2).clientId(), "there are no comment lines");
        assertNull(r.rows().get(2).description());
        assertTrue(read(ClientBindingsFile.HEADER + "\n").valid(), "a file with no bindings is valid");
    }

    @Test
    void theHeaderIsExact() {
        assertEquals(List.of("1 HEADER_WRONG"), problems("client_id,entity_slot,source\nx,ENT-A1,scanner\n"));
        assertEquals(List.of("1 HEADER_WRONG"), problems("Client_ID,entity_slot,source,description\n"));
        assertEquals(List.of("1 HEADER_WRONG"), problems(""));
        assertEquals(List.of("1 HEADER_WRONG"), problems("x,ENT-A1,scanner,\n"));
    }

    @Test
    void everyProblemHasItsLineAndCode() {
        String text = ClientBindingsFile.HEADER + "\n"
                + "ok-1,ENT-A1,loan-system,\n"              // 2
                + "has space,ENT-A1,scanner,\n"             // 3
                + "ok-2,A1,scanner,\n"                      // 4
                + "ok-3,ENT-A1,Loan-System,\n"              // 5
                + "ok-4,ENT-A1,unknown-source,\n"           // 6
                + "ok-5,ENT-A1,scanner\n"                   // 7
                + "ok-1,ENT-A2,scanner,\n"                  // 8
                + "ok-6,ENT-A1,scanner," + "d".repeat(201) + "\n" // 9
                + "\"ok-7,ENT-A1,scanner,\n"                // 10
                + "it's,ENT-A1,scanner,\n";                 // 11
        assertEquals(List.of("3 CLIENT_ID_INVALID", "4 SLOT_INVALID", "5 SOURCE_INVALID", "6 SOURCE_UNKNOWN",
                "7 WRONG_COLUMN_COUNT", "8 DUPLICATE_CLIENT_ID", "9 DESCRIPTION_INVALID", "10 BAD_QUOTES",
                "11 CLIENT_ID_INVALID"), problems(text));
        ClientBindingsFile.Problem dup = read(text).errors().stream().filter(p -> p.code() == DUPLICATE_CLIENT_ID)
                .findFirst().orElseThrow();
        assertEquals("line 8: DUPLICATE_CLIENT_ID: client \"ok-1\" is already on line 2; each client ID binds to "
                + "exactly one entity and one source", dup.display());
        assertEquals(List.of("0 NOT_UTF8"), ClientBindingsFile.read(new byte[]{(byte) 0xff, (byte) 0xfe, 0x41},
                SOURCES).errors().stream().map(p -> p.line() + " " + p.code()).toList());
    }

    @Test
    void secretsAreRefusedAndNeverRepeated() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";
        String secret = "Zk3pQ9vX2mR8tL5wN7bH1cJ4dF6gS0aYqE2uI9oP";
        for (String line : List.of(jwt + ",ENT-A1,scanner,",
                "ok-1,ENT-A1,scanner,password=hunter2",
                "ok-1,ENT-A1,scanner,client_secret: abc",
                "ok-1,ENT-A1,scanner,-----BEGIN PRIVATE KEY-----",
                secret + ",ENT-A1,scanner,",
                "ok-1,ENT-A1,scanner,the secret is " + secret)) {
            ClientBindingsFile.Result r = read(ClientBindingsFile.HEADER + "\n" + line + "\n");
            assertEquals(List.of(SECRET_LIKE), r.errors().stream().map(ClientBindingsFile.Problem::code).toList(), line);
            assertFalse(r.errors().get(0).display().contains("hunter2"));
            assertFalse(r.errors().get(0).display().contains(secret));
            assertTrue(r.rows().isEmpty());
        }
        // Ordinary client IDs and descriptions are not secrets.
        assertTrue(read(ClientBindingsFile.HEADER + "\n"
                + "0oa1b2c3d4E5f6G7h8i9,ENT-A1,scanner,Okta client of the loan system\n"
                + "11111111-2222-3333-4444-555555555555,ENT-A1,loan-system,Entra app (token rotated yearly)\n"
                + "loan-system-prod,ENT-A2,loan-system,\n").valid());
    }
}
