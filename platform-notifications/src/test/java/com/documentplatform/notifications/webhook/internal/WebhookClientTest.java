package com.documentplatform.notifications.webhook.internal;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.webhook.WebhookDirectory;
import com.documentplatform.notifications.webhook.WebhookOptions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The webhook client's own helpers (moved from {@code webhook.WebhookChannelsTest} with the client, P041). */
class WebhookClientTest {

    @Test
    void retryAfterAsAnHttpDateIsPassedOn() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        WebhookClient client = new WebhookClient(ChannelKind.SLACK, WebhookDirectory.of(Map.of()),
                ref -> Optional.empty(), WebhookOptions.DEFAULT, Clock.fixed(now, ZoneOffset.UTC));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now.plusSeconds(90), ZoneOffset.UTC));
        assertEquals(Duration.ofSeconds(90), client.result(503, date).retryAfter());
        assertNull(client.result(429, "soon").retryAfter(), "unreadable: the normal backoff applies");
        assertNull(client.result(429, null).retryAfter());
    }

    @Test
    void jsonEscapesEverythingThatCouldBreakOut() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003cscript\\u003e \\u0026 \\u2028\"", Json.write("a\"b\\c\n<script> & \u2028"));
        assertEquals("{\"k\":[1,true,null]}", Json.write(Map.of("k", java.util.Arrays.asList(1, true, null))));
    }
}
