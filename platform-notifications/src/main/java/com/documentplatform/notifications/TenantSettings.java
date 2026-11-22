package com.documentplatform.notifications;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the customer's IT Admin connects once for the tenant. Destinations are references only: the mail server's
 * login and the webhook addresses are secrets held outside this model (secret store, P004), so they cannot reach a
 * message or a log through it.
 *
 * @param appBaseUrl   the app's address for this tenant; message links are built under it
 * @param zone         the tenant's time zone, for daily summaries and the times shown in messages
 * @param destinations the connected channels: the mail server, and each Teams or Slack webhook, by id
 */
public record TenantSettings(URI appBaseUrl, ZoneId zone, Map<DestinationId, ChannelKind> destinations) {

    public TenantSettings {
        Objects.requireNonNull(appBaseUrl, "appBaseUrl");
        Objects.requireNonNull(zone, "zone");
        if (!appBaseUrl.isAbsolute() || !Set.of("https", "http").contains(appBaseUrl.getScheme())
                || appBaseUrl.getHost() == null || appBaseUrl.getRawUserInfo() != null
                || appBaseUrl.getRawQuery() != null || appBaseUrl.getRawFragment() != null) {
            throw new NotificationException(NotificationException.Reason.INVALID_VALUE, "App address is not valid");
        }
        destinations = Map.copyOf(destinations);
    }

    /** The same settings with another time zone (an entity's own, for its messages and summaries). */
    public TenantSettings withZone(ZoneId other) {
        return other.equals(zone) ? this : new TenantSettings(appBaseUrl, other, destinations);
    }

    public boolean isConnected(DestinationId destination) {
        return destinations.containsKey(destination);
    }

    public ChannelKind kindOf(DestinationId destination) {
        return destinations.get(destination);
    }

    /**
     * Where an event goes: the destinations its settings chose that are still connected, or, when its settings chose
     * none, the tenant's e-mail destination(s). Sorted by id, so the order is stable.
     */
    List<DestinationId> targetsFor(Set<DestinationId> chosen) {
        TreeMap<String, DestinationId> sorted = new TreeMap<>();
        destinations.forEach((id, kind) -> {
            if (chosen.isEmpty() ? kind == ChannelKind.EMAIL : chosen.contains(id)) {
                sorted.put(id.value(), id);
            }
        });
        return List.copyOf(sorted.values());
    }
}
