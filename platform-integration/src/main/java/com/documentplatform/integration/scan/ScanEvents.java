package com.documentplatform.integration.scan;

import java.time.Instant;
import java.util.UUID;

/**
 * Tells the app about scan results it must act on: an infected file (for its IT alert). Supplied by the app; the
 * default does nothing. Never holds a file's content.
 */
public interface ScanEvents {

    ScanEvents NONE = new ScanEvents() {
    };

    /**
     * An infected file was quarantined and not handed over.
     *
     * @param integrationId the intake integration
     * @param entity        its entity
     * @param product       its product
     * @param source        {@code poll} or {@code watch}
     * @param sourceId      the polled item's source ID, or the drop's ID
     * @param fileName      the file's name as the source gave it
     * @param threat        the scanner's name for what it found (made safe)
     * @param at            when
     */
    record Infected(UUID integrationId, UUID entity, String product, String source, String sourceId, String fileName,
                    String threat, Instant at) {
    }

    default void infected(Infected event) {
    }
}
