package com.documentplatform.licensing.json;

/** A document is not valid JSON of the strict subset {@link Json} reads. The message names a position, not content. */
public class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }
}
