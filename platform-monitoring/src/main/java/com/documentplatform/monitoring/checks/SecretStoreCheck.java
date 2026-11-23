package com.documentplatform.monitoring.checks;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Each configured secret reference resolves in the secret store. Only the reference's name is ever reported; the
 * secret is read and dropped at once, never kept or compared.
 */
public final class SecretStoreCheck implements DependencyCheck {

    private final SecretResolver secrets;
    private final List<SecretRef> refs;
    private final boolean required;

    public SecretStoreCheck(SecretResolver secrets, List<SecretRef> refs, boolean required) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.refs = List.copyOf(refs);
        this.required = required;
    }

    @Override
    public String name() {
        return "secret-store";
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Verdict check() {
        for (SecretRef ref : refs) {
            Optional<Secret> s;
            try {
                s = secrets.resolve(ref);
            } catch (RuntimeException e) {
                return Verdict.down(MonitoringCode.MON_SECRET_UNAVAILABLE, Map.of("secretRef", ref.toString()));
            }
            if (s.isEmpty()) return Verdict.down(MonitoringCode.MON_SECRET_MISSING, Map.of("secretRef", ref.toString()));
        }
        return Verdict.working();
    }
}
