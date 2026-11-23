package com.documentplatform.monitoring.spring;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.checks.DatabaseCheck;
import com.documentplatform.monitoring.checks.FileLocationCheck;
import com.documentplatform.monitoring.checks.HttpServiceCheck;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The built-in checks this app gets from its modules and beans. Each optional module's part is in its own nested
 * class, loaded only when the module is on the classpath.
 */
final class BuiltInChecks {

    /** A platform schema: its startup check class, and the bean type whose presence says the schema is in use. */
    record Schema(String name, String schemaClass, String inUseBean) {
    }

    static final List<Schema> SCHEMAS = List.of(
            new Schema("identity", "com.documentplatform.identity.jdbc.IdentitySchema",
                    "com.documentplatform.identity.store.LoginConnectionStore"),
            new Schema("licensing", "com.documentplatform.licensing.jdbc.LicensingSchema",
                    "com.documentplatform.licensing.LicenceService"),
            new Schema("notifications", "com.documentplatform.notifications.jdbc.NotificationSchema",
                    "com.documentplatform.notifications.Notifier"),
            new Schema("fields", "com.documentplatform.fields.jdbc.FieldSchema",
                    "com.documentplatform.fields.FieldStore"),
            new Schema("erase-log", "com.documentplatform.eraselog.jdbc.EraseLogSchema",
                    "com.documentplatform.eraselog.ErasePosition"),
            new Schema("integration", "com.documentplatform.integration.jdbc.IntegrationSchema",
                    "com.documentplatform.integration.store.IntegrationStore"));

    private BuiltInChecks() {
    }

    static List<DependencyCheck> all(ListableBeanFactory beans, MonitoringProperties p, Environment env, Clock clock) {
        ClassLoader cl = BuiltInChecks.class.getClassLoader();
        List<DependencyCheck> out = new ArrayList<>();
        DataSource ds = unique(beans, DataSource.class);
        if (ds != null) out.add(new DatabaseCheck(ds, schemasInUse(beans, cl)));
        if (present("com.documentplatform.crypto.KeyService", cl)) Crypto.add(out, beans, p);
        if (present("com.documentplatform.notifications.secret.SecretResolver", cl)) Secrets.add(out, beans, p);
        if (present("com.documentplatform.licensing.LicenceService", cl)) Licensing.add(out, beans);
        if (present("com.documentplatform.identity.store.LoginConnectionStore", cl)) Login.add(out, beans, p, env, clock);
        String mailHost = env.getProperty("platform.notifications.mail.host");
        if (mailHost != null && !mailHost.isBlank()) {
            int port = env.getProperty("platform.notifications.mail.port", Integer.class, 587);
            out.add(com.documentplatform.monitoring.checks.ChannelCheck.mail("mail", Map.of("host", mailHost,
                    "from-address", env.getProperty("platform.notifications.mail.from-address", "")), mailHost, port));
        }
        p.getFolders().forEach((name, f) -> {
            if (f.getPath() != null && !f.getPath().isBlank()) {
                out.add(new FileLocationCheck(name, Path.of(f.getPath()), f.isWritable(), f.getWarnAt(), f.getDownAt(),
                        f.isRequired()));
            }
        });
        p.getServices().forEach((name, s) -> out.add(new HttpServiceCheck(name, s.getUrl(), s.getExpectedStatus(),
                s.getTimeout(), s.isRequired())));
        return out;
    }

    /** The schemas in use: the module's schema class is there and its main bean is a bean of this app. */
    static List<DatabaseCheck.SchemaVerify> schemasInUse(ListableBeanFactory beans, ClassLoader cl) {
        List<DatabaseCheck.SchemaVerify> out = new ArrayList<>();
        for (Schema s : SCHEMAS) {
            if (!present(s.schemaClass(), cl) || !present(s.inUseBean(), cl)) continue;
            try {
                Class<?> inUse = ClassUtils.forName(s.inUseBean(), cl);
                if (beans.getBeanNamesForType(inUse, true, false).length == 0) continue;
                Method verify = ClassUtils.forName(s.schemaClass(), cl).getMethod("verify", DataSource.class);
                Consumer<DataSource> v = d -> {
                    try {
                        verify.invoke(null, d);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        if (e.getCause() instanceof RuntimeException r) throw r;
                        throw new IllegalStateException(e.getCause());
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                };
                out.add(new DatabaseCheck.SchemaVerify(s.name(), v));
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                // not this module's version: skipped
            }
        }
        return out;
    }

    static boolean present(String name, ClassLoader cl) {
        return ClassUtils.isPresent(name, cl);
    }

    static <T> T unique(ListableBeanFactory beans, Class<T> type) {
        return beans.getBeanProvider(type).getIfUnique();
    }

    static final class Crypto {
        static void add(List<DependencyCheck> out, ListableBeanFactory beans, MonitoringProperties p) {
            com.documentplatform.crypto.KeyService keys = unique(beans, com.documentplatform.crypto.KeyService.class);
            if (keys != null) out.add(new com.documentplatform.monitoring.checks.KeyServiceCheck(keys,
                    p.isCustomerKeysRequired()));
        }
    }

    static final class Secrets {
        static void add(List<DependencyCheck> out, ListableBeanFactory beans, MonitoringProperties p) {
            if (p.getSecrets().isEmpty()) return;
            var resolver = unique(beans, com.documentplatform.notifications.secret.SecretResolver.class);
            if (resolver == null) return;
            out.add(new com.documentplatform.monitoring.checks.SecretStoreCheck(resolver, p.getSecrets().stream()
                    .map(com.documentplatform.notifications.secret.SecretRef::of).toList(), false));
        }
    }

    static final class Licensing {
        static void add(List<DependencyCheck> out, ListableBeanFactory beans) {
            var service = unique(beans, com.documentplatform.licensing.LicenceService.class);
            if (service != null) out.add(new com.documentplatform.monitoring.checks.LicenceCheck(service));
        }
    }

    static final class Login {
        static void add(List<DependencyCheck> out, ListableBeanFactory beans, MonitoringProperties p, Environment env,
                        Clock clock) {
            var store = unique(beans, com.documentplatform.identity.store.LoginConnectionStore.class);
            var fetch = unique(beans, com.documentplatform.identity.signin.IssuerFetch.class);
            String tenant = p.getTenantId() != null ? p.getTenantId() : env.getProperty("platform.licensing.tenant-id");
            String product = p.getProductCode();
            if (store == null || fetch == null || tenant == null || product == null) return;
            var dev = unique(beans, com.documentplatform.identity.signin.DevelopmentLicence.class);
            boolean local = dev != null && dev.signedByDevelopmentKey();
            out.add(new com.documentplatform.monitoring.checks.LoginSystemCheck(product,
                    () -> store.active(tenant, product), fetch, local, clock));
        }
    }
}
