package com.documentplatform.modelgov.spring;

import com.documentplatform.modelgov.ModelGovCode;
import com.documentplatform.modelgov.ModelMeasures;
import com.documentplatform.modelgov.ModelReviews;
import com.documentplatform.modelgov.jdbc.ModelGovSchema;
import com.documentplatform.modelgov.jdbc.ModelGovSchemaException;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.Verdict;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The monitoring check (platform-monitoring) of model governance: DOWN with the schema check's {@code MG_*} code when
 * the {@code mg_} tables are wrong; HAVING_PROBLEMS with {@code MOD_DRIFT_UNHANDLED} when a drift has no recorded
 * action, or {@code MOD_REVIEW_OVERDUE} when a use's review is overdue; else WORKING. Counts, IDs and dates only.
 * Never required at startup (the schema check at startup already refuses a wrong schema).
 */
public final class ModelGovCheck implements DependencyCheck {

    private final DataSource dataSource;
    private final ModelReviews reviews;
    private final ModelMeasures measures;
    private final Clock clock;

    /** @param dataSource the database of the Postgres store, or null when the app supplies its own store */
    public ModelGovCheck(DataSource dataSource, ModelReviews reviews, ModelMeasures measures, Clock clock) {
        this.dataSource = dataSource;
        this.reviews = reviews;
        this.measures = measures;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "model-governance";
    }

    @Override
    public Duration every() {
        return Duration.ofMinutes(5);
    }

    @Override
    public Verdict check() {
        if (dataSource != null) {
            try {
                ModelGovSchema.verify(dataSource);
            } catch (ModelGovSchemaException e) {
                return Verdict.down(e.problem());
            }
        }
        List<ModelMeasures.DriftStatus> open = measures.openDrifts();
        List<ModelReviews.ReviewDue> overdue = reviews.overdue(clock.instant());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("openDrifts", open.size());
        details.put("overdueReviews", overdue.size());
        if (!open.isEmpty()) {
            details.put("count", open.size());
            return Verdict.problems(ModelGovCode.MOD_DRIFT_UNHANDLED, details);
        }
        if (!overdue.isEmpty()) {
            details.put("use", overdue.get(0).use().useId());
            details.put("due", overdue.get(0).dueDate());
            return Verdict.problems(ModelGovCode.MOD_REVIEW_OVERDUE, details);
        }
        return Verdict.working(com.documentplatform.monitoring.MonitoringCode.MON_WORKING, details);
    }
}
