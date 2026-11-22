package com.documentplatform.integration.config;

import java.util.List;
import java.util.Map;

/**
 * Packaging presets: shortcuts that expand to ordinary {@link CallStep}s (the engine has no special path for any of
 * them). Change the steps they return like any other steps.
 */
public final class Packaging {

    /** The packagings offered. */
    public enum Kind {
        /** Create the package (JSON), then upload each document on its own (raw file). */
        TWO_STEP,
        /** One call: a JSON part and every document as a file part (multipart/form-data). */
        MULTIPART,
        /** One call per document: the raw file, its details in headers. */
        FILE_PER_CALL
    }

    private Packaging() {
    }

    /**
     * Create, then upload each document.
     *
     * @param createPath       the create call's path
     * @param createBody       the create call's JSON template
     * @param idValue          the value read from the create answer that names the package (for example
     *                         {@code submissionId})
     * @param idPointer        where that value is in the answer ({@code /submissionId})
     * @param uploadPath       the upload path, which uses {@code {step.create.<idValue>}}
     * @param uploadHeaders    the upload's header templates ({@code {doc.*}} placeholders)
     */
    public static List<CallStep> twoStep(String createPath, String createBody, String idValue, String idPointer,
                                         String uploadPath, Map<String, String> uploadHeaders) {
        CallStep create = CallStep.of("create", HttpMethod.POST, createPath).withJsonBody(createBody)
                .reading(idValue, idPointer, true);
        CallStep upload = CallStep.of("upload", HttpMethod.POST, uploadPath).eachDocument().withFileBody();
        for (Map.Entry<String, String> h : uploadHeaders.entrySet()) upload = upload.withHeader(h.getKey(), h.getValue());
        return List.of(create, upload);
    }

    /** One multipart call with a JSON part and a file part per document (file name: the document's). */
    public static List<CallStep> multipart(String path, String jsonPartTemplate, String jsonPart, String filePart) {
        return List.of(CallStep.of("send", HttpMethod.POST, path).withMultipartBody(jsonPartTemplate,
                new CallStep.Multipart(jsonPart, filePart, "{doc.fileName}")));
    }

    /** One call per document, the raw file as the body, its details in headers. */
    public static List<CallStep> filePerCall(String path, Map<String, String> headers) {
        CallStep s = CallStep.of("upload", HttpMethod.POST, path).eachDocument().withFileBody();
        for (Map.Entry<String, String> h : headers.entrySet()) s = s.withHeader(h.getKey(), h.getValue());
        return List.of(s);
    }
}
