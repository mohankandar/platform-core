package com.documentplatform.monitoring.spring;

import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.watch.WatchedFolder;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.checks.IntegrationChecks;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** One check per enabled integration, from platform-integration-spring's beans (loaded only with that module). */
final class IntegrationSource {

    private IntegrationSource() {
    }

    /** The source, or null when the app has no integration store. */
    static Supplier<List<DependencyCheck>> of(ListableBeanFactory beans) {
        IntegrationStore store = beans.getBeanProvider(IntegrationStore.class).getIfUnique();
        if (store == null) return null;
        CallEngine engine = beans.getBeanProvider(CallEngine.class).getIfUnique();
        FolderExport folders = beans.getBeanProvider(FolderExport.class).getIfUnique();
        WatchedFolder watched = beans.getBeanProvider(WatchedFolder.class).getIfUnique();
        Function<StoredIntegration, HealthResult> health = s -> {
            Kind k = s.config().kind();
            if (k == Kind.FOLDER_EXPORT) return folders == null ? null : folders.health(s);
            if (k == Kind.WATCHED_FOLDER) return watched == null ? null : watched.health(s);
            if (k == Kind.CALLBACK) return null;
            return engine == null ? null : engine.health(s);
        };
        return new IntegrationChecks(store, health);
    }
}
