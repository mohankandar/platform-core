package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlers;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogStore;
import com.documentplatform.eraselog.ErasePosition;
import com.documentplatform.eraselog.EraseReplay;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import com.documentplatform.eraselog.jdbc.EraseLogSchema;
import com.documentplatform.eraselog.jdbc.PostgresErasePosition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.lang.System.Logger.Level;

/**
 * Wires the erase log from the install config ({@code platform.erase-log.*}) and the app's beans.
 *
 * <p>The app supplies: its {@link EraseHandler} beans (one per kind; for case fields
 * {@code new CaseFieldsEraseHandler(fieldValues, valueStore, legalHolds)}), and the {@link DataSource} holding the
 * {@code erl_position} table. It calls {@link Eraser#erase} for every erasure, and checks {@link EraseGuard#mayTakeWork()}
 * before taking work.
 *
 * <p>At startup: the settings are checked ({@link EraseLogConfig}); the {@code erl_} table is checked
 * ({@link EraseLogSchema#verify}; never created); the guard's full check runs ({@link EraseGuard#checkAtStartup()}).
 * A restored database does not stop the app: it starts, reports OUT_OF_SERVICE and takes no work until the replay
 * ({@code --erase-replay}, {@link EraseReplayRunner}) has run and verified.
 *
 * <p>Every bean backs off when the app defines its own (for example the in-memory store and position in tests).
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnProperty(prefix = EraseLogProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EraseLogProperties.class)
public class EraseLogAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(EraseLogAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnMissingBean
    EraseLogConfig eraseLogConfig(EraseLogProperties properties) {
        return EraseLogConfig.from(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    EraseLogStore eraseLogStore(EraseLogConfig config) {
        return new FileEraseLogStore(config.location());
    }

    @Bean
    @ConditionalOnMissingBean
    EraseLog eraseLog(EraseLogStore store, ObjectProvider<Clock> clock) {
        return new EraseLog(store, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ErasePosition erasePosition(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfUnique();
        if (ds == null) {
            throw new IllegalStateException("The erase log needs the app's DataSource (the database it protects, with "
                    + "table erl_position), or an ErasePosition bean");
        }
        EraseLogSchema.verify(ds);
        return new PostgresErasePosition(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    EraseHandlers eraseHandlers(ObjectProvider<EraseHandler> handlers) {
        return EraseHandlers.of(handlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    Eraser eraser(EraseLog log, ErasePosition position, EraseHandlers handlers) {
        return new Eraser(log, position, handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    EraseReplay eraseReplay(EraseLog log, ErasePosition position, EraseHandlers handlers, ObjectProvider<Clock> clock) {
        return new EraseReplay(log, position, handlers, clock.getIfAvailable(Clock::systemUTC));
    }

    /** The guard, with the startup check already run. */
    @Bean
    @ConditionalOnMissingBean
    EraseGuard eraseGuard(EraseLog log, ErasePosition position, ObjectProvider<Clock> clock) {
        EraseGuard guard = new EraseGuard(log, position, clock.getIfAvailable(Clock::systemUTC));
        EraseGuard.State s = guard.checkAtStartup();
        if (s.mayTakeWork()) {
            LOG.log(Level.INFO, "Erase log {0} at {1}: in step (entry {2})", s.logId(), log.location(), s.applied());
        } else {
            LOG.log(Level.WARNING, "Erase log at {0}: {1}: {2}. The app takes no work until this is resolved.",
                    log.location(), s.code(), s.message());
        }
        return guard;
    }

    @Bean
    @ConditionalOnMissingBean
    EraseLogWorker eraseLogWorker(EraseGuard guard, EraseLog log, ErasePosition position, EraseLogConfig config,
                                  ObjectProvider<Clock> clock) {
        return new EraseLogWorker(guard, log, position, config.retention(), config.recheck(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    EraseReplayRunner eraseReplayRunner(EraseReplay replay, EraseGuard guard, ApplicationContext context,
                                        EraseLogConfig config) {
        return new EraseReplayRunner(replay, guard, context, config.exitAfterReplay());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class Health {

        @Bean(name = "eraseLogHealthIndicator")
        @ConditionalOnMissingBean(name = "eraseLogHealthIndicator")
        EraseLogHealthIndicator eraseLogHealthIndicator(EraseGuard guard, EraseLogConfig config, EraseLog log) {
            return new EraseLogHealthIndicator(guard, config.retention(), log.location());
        }
    }
}
