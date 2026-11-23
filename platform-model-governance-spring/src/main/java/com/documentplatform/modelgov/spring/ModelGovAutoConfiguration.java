package com.documentplatform.modelgov.spring;

import com.documentplatform.modelgov.ModelGate;
import com.documentplatform.modelgov.ModelGovAudit;
import com.documentplatform.modelgov.ModelGovStore;
import com.documentplatform.modelgov.ModelGovernance;
import com.documentplatform.modelgov.ModelEvents;
import com.documentplatform.modelgov.ModelMeasures;
import com.documentplatform.modelgov.ModelPack;
import com.documentplatform.modelgov.ModelReviews;
import com.documentplatform.modelgov.PackTexts;
import com.documentplatform.modelgov.jdbc.ModelGovSchema;
import com.documentplatform.modelgov.jdbc.PostgresModelGovStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.lang.System.Logger.Level;
import java.time.Clock;

/**
 * Wires model governance from {@code platform.model-governance.*} and the app's beans.
 *
 * <p>The app supplies: the roles in its configuration ({@code approver-roles}, {@code forbidden-roles}); its
 * {@link DataSource} holding the {@code mg_} tables (or its own {@link ModelGovStore}); optionally its
 * {@link ModelGovAudit} (else changes are kept in the history tables only, and a line at startup says so), its
 * {@link ModelEvents} beans (drift and review-due events for its notifications), its {@link PackTexts} (else the pack
 * says "Not supplied by the app"), and a {@link Clock}.
 *
 * <p>At startup the settings are checked (every problem listed) and, for the Postgres store, the {@code mg_} tables
 * ({@link ModelGovSchema#verify}; never created). Every bean backs off for the app's own.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = ModelGovProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(ModelGovProperties.class)
public class ModelGovAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(ModelGovAutoConfiguration.class.getName());

    /** The DataSource the Postgres store was built on (absent when the app supplies its own store). */
    public record StoreDatabase(DataSource dataSource) {
    }

    @Bean
    @ConditionalOnMissingBean
    ModelGovConfig modelGovConfig(ModelGovProperties properties) {
        return ModelGovConfig.from(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ModelGovStore.class)
    StoreDatabase modelGovStoreDatabase(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfUnique();
        if (ds == null) {
            throw new IllegalStateException("Model governance needs the app's DataSource (the database with the mg_ "
                    + "tables, created by the schema step from " + ModelGovSchema.LOCATION + "), or a ModelGovStore bean");
        }
        ModelGovSchema.verify(ds);
        return new StoreDatabase(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    ModelGovStore modelGovStore(StoreDatabase db) {
        return new PostgresModelGovStore(db.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    ModelGovernance modelGovernance(ModelGovStore store, ObjectProvider<ModelGovAudit> audit, ModelGovConfig config,
                                    ObjectProvider<Clock> clock) {
        ModelGovAudit a = audit.getIfAvailable(() -> {
            LOG.log(Level.INFO, "Model governance: no ModelGovAudit bean; register changes are kept in the mg_ "
                    + "history tables only");
            return ModelGovAudit.NONE;
        });
        return new ModelGovernance(store, a, config.settings(), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ModelGate modelGate(ModelGovStore store, ObjectProvider<Clock> clock) {
        return new ModelGate(store, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ModelMeasures modelMeasures(ModelGovStore store, ObjectProvider<ModelEvents> events, ModelGovConfig config,
                                ObjectProvider<Clock> clock) {
        return new ModelMeasures(store, events(events), clock.getIfAvailable(Clock::systemUTC),
                config.measureRetention());
    }

    @Bean
    @ConditionalOnMissingBean
    ModelReviews modelReviews(ModelGovStore store, ObjectProvider<ModelEvents> events,
                              ObjectProvider<Clock> clock) {
        return new ModelReviews(store, events(events), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ModelPack modelPack(ModelGovStore store, ModelMeasures measures, ObjectProvider<PackTexts> texts) {
        return new ModelPack(store, measures, texts.getIfAvailable(() -> PackTexts.NOT_SUPPLIED));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = ModelGovProperties.PREFIX, name = "worker.enabled", havingValue = "true",
            matchIfMissing = true)
    ModelGovWorker modelGovWorker(ModelReviews reviews, ModelMeasures measures, ModelGovConfig config,
                                  ObjectProvider<Clock> clock) {
        return new ModelGovWorker(reviews, measures, config.workerEvery(), clock.getIfAvailable(Clock::systemUTC));
    }

    /** The app's listeners, each told in turn. */
    private static ModelEvents events(ObjectProvider<ModelEvents> listeners) {
        return ModelEvents.all(listeners.orderedStream().toList());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.documentplatform.monitoring.DependencyCheck")
    static class Monitoring {

        @Bean
        @ConditionalOnMissingBean(ModelGovCheck.class)
        ModelGovCheck modelGovCheck(ObjectProvider<StoreDatabase> db, ModelReviews reviews, ModelMeasures measures,
                                    ObjectProvider<Clock> clock) {
            StoreDatabase d = db.getIfAvailable();
            return new ModelGovCheck(d == null ? null : d.dataSource(), reviews, measures,
                    clock.getIfAvailable(Clock::systemUTC));
        }
    }
}
