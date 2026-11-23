package com.documentplatform.modelgov;

import java.util.List;

/** Where drift and review-due events go: the app's notifications to the model owner. */
@FunctionalInterface
public interface ModelEvents {

    void raised(ModelEvent event);

    /** No events (tests). */
    ModelEvents NONE = e -> {
    };

    /** Every listener in turn; one that throws does not stop the others. */
    static ModelEvents all(List<? extends ModelEvents> listeners) {
        List<ModelEvents> copy = List.copyOf(listeners);
        return e -> {
            RuntimeException first = null;
            for (ModelEvents l : copy) {
                try {
                    l.raised(e);
                } catch (RuntimeException ex) {
                    if (first == null) first = ex;
                }
            }
            if (first != null) throw first;
        };
    }
}
