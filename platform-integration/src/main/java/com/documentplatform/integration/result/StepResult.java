package com.documentplatform.integration.result;

import com.documentplatform.integration.IntegrationCode;

/**
 * One call made during an attempt.
 *
 * @param step            the step's name
 * @param sourceDocumentId the document, for a step run once per document; null otherwise
 * @param httpStatus      0 when there was no answer
 * @param destinationCode the destination's own code (safe characters, at most 40), or null
 */
public record StepResult(String step, String sourceDocumentId, Outcome outcome, IntegrationCode code, int httpStatus,
                         String destinationCode) {
}
