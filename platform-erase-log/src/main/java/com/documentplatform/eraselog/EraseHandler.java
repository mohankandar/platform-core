package com.documentplatform.eraselog;

import java.sql.Connection;
import java.util.Optional;

/**
 * Erases one kind of thing ({@link #kind()}: {@code case-fields}, {@code document}, {@code redacted-copy}, …) for an
 * erase log entry, and checks afterwards that it is gone. The same handler serves the everyday erase
 * ({@link Eraser}) and the replay after a restore ({@link EraseReplay}), so it must be <b>safe to run twice</b>:
 * something already erased is {@link Applied#ALREADY_GONE}, not an error.
 *
 * <p>Built in: {@code fields.CaseFieldsEraseHandler} ({@code case-fields}, platform-fields' record erase). Documents and
 * copies are the apps' handlers. A refusal or failure is an {@link EraseHandlerException} with the handler's code.
 */
public interface EraseHandler {

    /** The kind this handler erases, as in {@link EraseRequest#kind()}. */
    String kind();

    /**
     * Checks, before anything is logged, that the erasure may go ahead (for example: no legal hold). Throw an
     * {@link EraseHandlerException} to refuse; nothing is logged then. The default allows everything.
     */
    default void check(EraseRequest request) {
    }

    /**
     * Erases what {@code entry} names.
     *
     * @param ctx the position's transaction, for a handler erasing in the app's own database
     */
    Applied apply(EraseEntry entry, Context ctx);

    /** Whether what {@code entry} names can still be read anywhere this handler looks. */
    Verified verify(EraseEntry entry);

    enum Applied {
        /** Erased now. */
        ERASED,
        /** Nothing was there any more (erased before, or never stored): done. */
        ALREADY_GONE
    }

    enum Verified {
        /** Nothing of the entry can be read. */
        GONE,
        /** Something of the entry can still be read: the replay is not complete. */
        READABLE
    }

    /** What the handler may use while applying. */
    interface Context {
        /** The transaction that also moves the applied position, when the position is in a database. */
        Optional<Connection> connection();
    }
}
