package com.documentplatform.licensing.spring;

import java.time.Clock;
import java.util.Objects;

/** The server clock licensing reads. The system UTC clock unless an app (or a test) defines its own bean. */
public record LicensingClock(Clock clock) {

    public LicensingClock {
        Objects.requireNonNull(clock, "clock");
    }
}
