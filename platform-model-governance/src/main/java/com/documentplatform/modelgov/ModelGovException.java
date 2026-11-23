package com.documentplatform.modelgov;

import com.documentplatform.errors.HasPlatformCode;
import com.documentplatform.errors.PlatformCode;
import com.documentplatform.errors.PlatformError;

import java.util.Map;
import java.util.Objects;

/**
 * A model-governance refusal: its {@link ModelGovCode} and safe details (IDs, names, digests; never content). The
 * message is the code's cause with the details filled in.
 */
public final class ModelGovException extends RuntimeException implements HasPlatformCode {

    private final ModelGovCode code;
    private final Map<String, String> details;

    public ModelGovException(ModelGovCode code, Map<String, ?> details) {
        super(code.code() + ": " + PlatformError.fill(code, details), null, false, false);
        this.code = Objects.requireNonNull(code, "code");
        java.util.Map<String, String> d = new java.util.LinkedHashMap<>();
        if (details != null) details.forEach((k, v) -> d.put(k, String.valueOf(v)));
        this.details = Map.copyOf(d);
    }

    public ModelGovCode code() {
        return code;
    }

    /** The safe details (for {@link PlatformError} and the app's notice). */
    public Map<String, String> details() {
        return details;
    }

    @Override
    public PlatformCode platformCode() {
        return code;
    }
}
