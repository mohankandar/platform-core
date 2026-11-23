package com.documentplatform.notifications.spring;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Wakes the background sender when a message was saved in the outbox. Signals pile up into one. */
final class SenderSignal {

    private final Semaphore permits = new Semaphore(0);

    /** Called on the publishing thread: only signals, returns at once. */
    public void raise() {
        if (permits.availablePermits() == 0) {
            permits.release();
        }
    }

    /** Waits for a signal or the timeout, whichever comes first. */
    boolean await(Duration timeout) throws InterruptedException {
        boolean signalled = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        permits.drainPermits();
        return signalled;
    }
}
