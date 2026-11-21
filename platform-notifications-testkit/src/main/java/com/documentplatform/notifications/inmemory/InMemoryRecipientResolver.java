package com.documentplatform.notifications.inmemory;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Role holders kept in memory. A null entity means a tenant-level role. People are known by user id
 * ({@link #person}) once granted a role or added with {@link #know}; a revoked role leaves them known.
 */
public final class InMemoryRecipientResolver implements RecipientResolver {

    private record Key(TenantId tenant, EntityId entity, RoleId role) {
    }

    private record Person(TenantId tenant, String userId) {
    }

    private final Map<Key, Set<Recipient>> holders = new HashMap<>();
    private final Map<Person, Recipient> people = new HashMap<>();

    public synchronized InMemoryRecipientResolver grant(TenantId tenant, EntityId entity, RoleId role,
                                                        Recipient... people) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(role, "role");
        holders.computeIfAbsent(new Key(tenant, entity, role), k -> new LinkedHashSet<>()).addAll(List.of(people));
        return know(tenant, people);
    }

    /** Takes the role away from these people. */
    public synchronized InMemoryRecipientResolver revoke(TenantId tenant, EntityId entity, RoleId role,
                                                         Recipient... people) {
        Set<Recipient> held = holders.get(new Key(tenant, entity, role));
        if (held != null) {
            List.of(people).forEach(held::remove);
        }
        return this;
    }

    /** People known by user id without a role, for messages to one person. */
    public synchronized InMemoryRecipientResolver know(TenantId tenant, Recipient... people) {
        for (Recipient r : people) {
            this.people.put(new Person(tenant, r.userId()), r);
        }
        return this;
    }

    @Override
    public synchronized Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role) {
        return Set.copyOf(holders.getOrDefault(new Key(tenant, entity, role), Set.of()));
    }

    @Override
    public synchronized Optional<Recipient> person(TenantId tenant, String userId) {
        return Optional.ofNullable(people.get(new Person(tenant, userId)));
    }
}
