package com.documentplatform.integration.result;

import com.documentplatform.integration.IntegrationCode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The result of one attempt: the whole step sequence tried once (or resumed from where an earlier attempt stopped).
 * Holds codes, statuses and the values read from the answers (for example a submission ID); never a secret, a
 * document's content or the destination's message text. The engine never retries: the app decides what to do next.
 *
 * @param integrationId    which integration
 * @param configVersion    the configuration version this attempt used (keep it with the attempt, for the audit)
 * @param outcome          the result class
 * @param code             the platform's code
 * @param destinationCode  the destination's own code when it sent one (safe characters, at most 40), else null
 * @param httpStatus       the last answer's status, 0 when there was none
 * @param retryAfter       the destination's Retry-After when it sent one, else null
 * @param attemptUsed      false when the app should not count this attempt (busy, unless a rule said otherwise)
 * @param correlationId    sent as {@code X-Correlation-Id} when the configuration sends it
 * @param stepReached      the step that ended the attempt (the failing one, or the last one when sent), or null
 * @param documentReached  the document of {@code stepReached} when it runs per document, else null
 * @param values           values read from steps run once, by {@code <step>.<name>}
 * @param documentValues   values read from per-document steps: source document ID → {@code <step>.<name>} → value
 * @param stepsDone        steps run once that succeeded (to resume)
 * @param documentsDone    documents whose per-document steps all succeeded (to resume)
 * @param steps            every call made, in order
 * @param test             true for a Test action with fake data
 * @param pending          for {@link Outcome#ACCEPTED}: what to keep to ask for the status later; else null
 */
public record AttemptResult(UUID integrationId, int configVersion, Outcome outcome, IntegrationCode code,
                            String destinationCode, int httpStatus, Duration retryAfter, boolean attemptUsed,
                            String correlationId, String stepReached, String documentReached,
                            Map<String, String> values, Map<String, Map<String, String>> documentValues,
                            Set<String> stepsDone, Set<String> documentsDone, List<StepResult> steps, boolean test,
                            PendingStatus pending) {

    public AttemptResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(code, "code");
        values = Map.copyOf(values);
        documentValues = Map.copyOf(documentValues);
        stepsDone = Set.copyOf(stepsDone);
        documentsDone = Set.copyOf(documentsDone);
        steps = List.copyOf(steps);
        if ((outcome == Outcome.ACCEPTED) != (pending != null)) {
            throw new IllegalArgumentException("An ACCEPTED result, and only it, has a pending status");
        }
    }

    /** Without a pending status (every outcome but ACCEPTED). */
    public AttemptResult(UUID integrationId, int configVersion, Outcome outcome, IntegrationCode code,
                         String destinationCode, int httpStatus, Duration retryAfter, boolean attemptUsed,
                         String correlationId, String stepReached, String documentReached,
                         Map<String, String> values, Map<String, Map<String, String>> documentValues,
                         Set<String> stepsDone, Set<String> documentsDone, List<StepResult> steps, boolean test) {
        this(integrationId, configVersion, outcome, code, destinationCode, httpStatus, retryAfter, attemptUsed,
                correlationId, stepReached, documentReached, values, documentValues, stepsDone, documentsDone, steps,
                test, null);
    }

    /** SENT or ALREADY_RECEIVED. */
    public boolean delivered() {
        return outcome == Outcome.SENT || outcome == Outcome.ALREADY_RECEIVED;
    }

    @Override
    public String toString() {
        return "AttemptResult[integration " + integrationId + " v" + configVersion + ": " + outcome + " " + code
                + (destinationCode == null ? "" : " (" + destinationCode + ")") + ", status " + httpStatus
                + (stepReached == null ? "" : ", step " + stepReached) + (test ? ", test" : "") + "]";
    }
}
