package com.documentplatform.integration.intake;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.result.Outcome;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The result of one polling pass. The app stores {@link #nextState} and starts the next pass with it; on a failure it
 * is the position after the last page that was fully handled, so nothing is skipped. Codes and IDs only, never a
 * value or content.
 *
 * @param integrationId the polling integration
 * @param configVersion the configuration version used
 * @param nextState     where the next pass starts
 * @param more          whether the pass stopped at its page limit with more pages left
 * @param pages         pages fully handled
 * @param taken         items the app took now
 * @param alreadyTaken  items the app already had
 * @param refused       items the app refused (with its codes)
 * @param acknowledged  items acknowledged to the source
 * @param problems      items left alone (not handed over, not acknowledged) and why: no ID, a refused download, too
 *                      large
 * @param quarantined   items whose file was infected: never handed over, not acknowledged
 * @param failure       why the pass stopped early, or null when it ran to its end or page limit
 * @param correlationId the pass's correlation ID, when sent
 */
public record PollResult(UUID integrationId, int configVersion, CursorState nextState, boolean more, int pages,
                         int taken, int alreadyTaken, List<Refusal> refused, int acknowledged,
                         List<ItemProblem> problems, List<Quarantined> quarantined, Failure failure,
                         String correlationId) {

    /** An item the app refused. */
    public record Refusal(String sourceItemId, String appCode) {
    }

    /**
     * An item left alone.
     *
     * @param sourceItemId    the item's ID, or null when it had none
     * @param code            the platform code
     * @param destinationCode the source's own code, or null
     * @param httpStatus      the source's status, or 0
     */
    public record ItemProblem(String sourceItemId, IntegrationCode code, String destinationCode, int httpStatus) {
    }

    /** An item whose file the scanner found infected. */
    public record Quarantined(String sourceItemId, String threat) {
    }

    /**
     * Why the pass stopped: the usual classified outcome, the step ({@code list}, {@code download},
     * {@code acknowledge}, {@code scan}, {@code handler}) and the item, when one.
     */
    public record Failure(Outcome outcome, IntegrationCode code, String destinationCode, int httpStatus,
                          Duration retryAfter, String step, String sourceItemId) {
        public Failure {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(code, "code");
        }
    }

    public PollResult {
        Objects.requireNonNull(nextState, "nextState");
        refused = List.copyOf(refused);
        problems = List.copyOf(problems);
        quarantined = List.copyOf(quarantined);
    }

    /** Whether the pass ran without stopping early. */
    public boolean ok() {
        return failure == null;
    }

    @Override
    public String toString() {
        return "PollResult[" + integrationId + " v" + configVersion + ", pages " + pages + ", taken " + taken
                + ", already " + alreadyTaken + ", refused " + refused.size() + ", acknowledged " + acknowledged
                + ", problems " + problems.size() + ", quarantined " + quarantined.size()
                + (failure == null ? "" : ", stopped " + failure.outcome() + " " + failure.code()
                + (failure.step() == null ? "" : " at " + failure.step())) + (more ? ", more" : "") + "]";
    }
}
