package com.documentplatform.notifications;

import java.util.Objects;

/** Who one set of {@link ActionLink}s is made for. */
public sealed interface ActionAudience {

    /** One person, by e-mail: the links work only for them. */
    record Person(String userId) implements ActionAudience {
        public Person {
            Text.require(userId, Text.ID, "User id");
        }
    }

    /**
     * A Teams or Slack channel: the webhook posts to everyone in it, so the links name the destination and the app
     * checks the signed-in person when one is opened.
     */
    record Channel(DestinationId destination) implements ActionAudience {
        public Channel {
            Objects.requireNonNull(destination, "destination");
        }
    }
}
