package com.documentplatform.integration.watch;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.result.Outcome;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The result of one watched-folder pass. IDs, names, codes and counts only; never content or extracted values.
 *
 * @param integrationId the watched-folder integration
 * @param configVersion the configuration version used
 * @param taken         drops the app took now (archived)
 * @param alreadyTaken  drops the app already had (archived)
 * @param refused       drops the app refused (moved to {@code _problem} with its code)
 * @param problems      drops platform-core could not hand over (moved to {@code _problem}, or left where they were
 *                      when moving failed) and why
 * @param quarantined   drops with an infected file (moved to {@code _quarantine}, never handed over)
 * @param incomplete    drops with files but no trigger after the waiting time; each reported once, nothing moved
 * @param held          drops put back to be tried again at the next pass (a scan that failed), and why
 * @param claimsLost    drops another instance claimed first
 * @param released      stuck claims (their instance died) put back
 * @param more          whether drops were left for the next pass (the pass's limit)
 * @param failure       why the pass stopped early (root missing, no permission, share unreachable, scanner, the
 *                      app's handler), or null
 */
public record WatchResult(UUID integrationId, int configVersion, int taken, int alreadyTaken, List<Refusal> refused,
                          List<Problem> problems, List<Quarantined> quarantined, List<Incomplete> incomplete,
                          List<Problem> held, int claimsLost, int released, boolean more, Failure failure) {

    public record Refusal(String dropId, String appCode) {
    }

    public record Problem(String dropId, IntegrationCode code) {
    }

    /** A drop with an infected file: the file's name and the scanner's name for what it found. */
    public record Quarantined(String dropId, String fileName, String threat) {
    }

    /** A drop without its trigger: how long its oldest file has waited, and its file names. */
    public record Incomplete(String dropId, Duration age, List<String> files) {
        public Incomplete {
            files = List.copyOf(files);
        }
    }

    /** Why the pass stopped; {@code dropId} when it stopped at one. */
    public record Failure(Outcome outcome, IntegrationCode code, String dropId) {
        public Failure {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(code, "code");
        }
    }

    public WatchResult {
        refused = List.copyOf(refused);
        problems = List.copyOf(problems);
        quarantined = List.copyOf(quarantined);
        incomplete = List.copyOf(incomplete);
        held = List.copyOf(held);
    }

    public boolean ok() {
        return failure == null;
    }

    @Override
    public String toString() {
        return "WatchResult[" + integrationId + " v" + configVersion + ", taken " + taken + ", already "
                + alreadyTaken + ", refused " + refused.size() + ", problems " + problems.size() + ", quarantined "
                + quarantined.size() + ", incomplete " + incomplete.size() + ", held " + held.size() + ", lost "
                + claimsLost + ", released " + released + (more ? ", more" : "")
                + (failure == null ? "" : ", stopped " + failure.outcome() + " " + failure.code()) + "]";
    }
}
