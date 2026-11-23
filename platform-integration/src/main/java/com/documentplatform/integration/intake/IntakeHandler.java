package com.documentplatform.integration.intake;

/**
 * The app's side of intake: it gets each incoming item (API polling) or drop (watched folder) after platform-core
 * found, read, waited for, claimed and scanned it, and decides what it means. platform-core only acts on the answer.
 *
 * <p>The files' streams are valid only while {@link #handle} runs: the app copies what it keeps. An exception (not an
 * answer) is a failure of the app: nothing is acknowledged or archived, and the item or drop is tried again at a
 * later pass. The app's own idempotency (by the source's ID) answers {@link IntakeAnswer#ALREADY_TAKEN} for what it
 * already has, so an item or drop handed over twice is never taken twice.
 *
 * @param <T> {@code PolledItem} or {@code Drop}
 */
@FunctionalInterface
public interface IntakeHandler<T> {

    IntakeAnswer handle(T incoming);
}
