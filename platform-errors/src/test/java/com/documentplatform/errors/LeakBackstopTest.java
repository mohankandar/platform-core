package com.documentplatform.errors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** P045: the forms the review got past SafeText (SEC-19), forged log lines (SEC-20), guessable references (SEC-22). */
class LeakBackstopTest {

    @Test
    void theFormsThatGotPastSafeTextAreRemoved() {
        for (String s : new String[]{"{\"password\": \"hunter2\"}", "{\"client_secret\":\"hunter2\"}",
                "\"apiKey\" : \"hunter2\"", "passphrase=hunter2", "credential=hunter2", "private_key=hunter2",
                "secret_id=hunter2", "pass=hunter2", "password%3Dhunter2", "password = correct horse hunter2 staple",
                "Pwd=x hunter2", "Authorization: Token hunter2", "Authorization: Digest username=\"hunter2\"",
                "redis://default:pa/hunter2@cache", "jdbc:oracle:thin:scott/hunter2@//db", "https://u:hunter2@host/x"}) {
            assertFalse(SafeText.scrub(s).contains("hunter2"), s + " -> " + SafeText.scrub(s));
        }
        for (String n : new String[]{"signingKey", "encryptionKey", "masterKey", "hmacKey", "accessKey", "sharedKey",
                "db.pass", "connectionString", "client-assertion", "pfx", "API_KEY", "x-api-key"}) {
            assertTrue(SafeText.secretName(n), n);
        }
    }

    @Test
    void ordinaryTextStays() {
        for (String s : new String[]{"https://host:8080/path?mail=a@b.example", "the folder /var/app is full",
                "sha256:" + "0a".repeat(32)}) {
            assertEquals(s, SafeText.scrub(s));
        }
        assertFalse(SafeText.secretName("key-alias"));
        assertFalse(SafeText.secretName("monkey"));
    }

    @Test
    void aDetailNeverStartsALogLineOfItsOwn() {
        PlatformError e = new PlatformError(PlatformErrorSample.CODE, java.util.Map.of("file",
                "a.pdf\" \ncode=SEC_FAKE ref=R-0000000000AA cause=\"forged"), ReferenceId.next(), java.time.Instant.now());
        assertFalse(e.logLine().contains("\n"), e.logLine());
        assertFalse(e.details().get("file").contains("\n"));
        assertEquals(501, SafeText.oneLine("x".repeat(2000)).length());
    }

    @Test
    void theNextReferenceIsNotGuessableFromTheLast() {
        long before = -1;
        boolean farApart = false;
        for (int i = 0; i < 50; i++) {
            long r = 0;
            for (char c : ReferenceId.next().substring(9).toCharArray()) r = r * 32 + ReferenceId.ALPHABET.indexOf(c);
            if (before >= 0 && Math.abs(r - before) > 1024) farApart = true;
            before = r;
        }
        assertTrue(farApart, "the random part is drawn fresh, not stepped by at most 1024");
    }

    /** A code whose cause holds the detail. */
    enum PlatformErrorSample implements PlatformCode {
        CODE;

        @Override
        public String code() {
            return name();
        }

        @Override
        public Kind kind() {
            return Kind.FAILED;
        }

        @Override
        public String cause() {
            return "The file {file} could not be read.";
        }
    }
}
