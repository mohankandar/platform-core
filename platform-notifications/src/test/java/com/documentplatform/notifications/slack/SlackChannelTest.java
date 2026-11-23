package com.documentplatform.notifications.slack;

import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Slack sender's package-private payload builder (moved from {@code webhook.WebhookChannelsTest}, P041). */
class SlackChannelTest {

    @Test
    void longSlackMessagesAreSplitWithinSlackLimits() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add("  - item-" + i + ", 2026-11-20 10:05 Z https://app.customer.example/items/item-" + i);
        }
        String json = SlackChannel.payload(new Message(MessageKind.SUMMARY, "Summary: " + "x".repeat(200), lines, null));
        assertTrue(json.contains("\"text\":\"Summary: " + "x".repeat(138) + "...\""), "header cut to 150");
        int sections = json.split("\"type\":\"section\"", -1).length - 1;
        assertTrue(sections > 1 && sections <= 45, "sections: " + sections);
    }
}
