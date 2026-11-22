package com.documentplatform.eraselog;

/** Applies one entry through its handler and moves the position; turns handler failures into erase log codes. */
final class Applying {

    private Applying() {
    }

    static EraseHandler.Applied apply(EraseHandlers handlers, ErasePosition.Locked locked, EraseEntry entry) {
        EraseHandler handler = handlers.require(entry);
        EraseHandler.Context ctx = locked::connection;
        try {
            return locked.applyAndAdvance(entry, () -> {
                try {
                    EraseHandler.Applied a = handler.apply(entry, ctx);
                    if (a == null) {
                        throw new EraseHandlerException("ERASE_HANDLER_ERROR", "the " + entry.kind()
                                + " handler gave no outcome");
                    }
                    return a;
                } catch (EraseHandlerException | EraseLogException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw EraseHandlerException.unexpected(entry.kind(), e);
                }
            });
        } catch (EraseHandlerException e) {
            throw new EraseLogException(EraseLogError.ERASE_HANDLER_FAILED, e.code(), "entry " + entry.seq() + " ("
                    + entry.kind() + " of " + entry.request().recordType() + " " + entry.request().recordId()
                    + ") was not applied: " + e.getMessage());
        }
    }
}
