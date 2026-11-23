package com.documentplatform.notifications.teams;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Teams sender's package-private address check (moved from {@code webhook.WebhookChannelsTest}, P041). */
class TeamsChannelTest {

    @Test
    void workflowsAddressesAreAccepted() {
        // Workflows addresses (Logic Apps / Power Platform hosts) are accepted.
        for (String workflows : List.of(
                "https://prod-12.westeurope.logic.azure.com:443/workflows/x/triggers/manual/paths/invoke?sig=s",
                "https://default1.environment.api.powerplatform.com/powerautomate/automations/direct/workflows/x")) {
            assertEquals(Optional.empty(), TeamsChannel.retiredConnector(URI.create(workflows)));
        }
    }
}
