package com.documentplatform.notifications.inmemory;

import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.approval.ApprovalRequest;
import com.documentplatform.notifications.approval.ApprovalStore;
import com.documentplatform.notifications.approval.Decision;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Requests and decisions in memory, with the same first-decision-wins rule as the Postgres store. For tests. */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRequest> requests = new LinkedHashMap<>();
    private final Set<String> told = new HashSet<>();

    @Override
    public synchronized void create(ApprovalRequest request) {
        if (requests.putIfAbsent(key(request.tenant(), request.id()), request) != null) {
            throw new IllegalStateException("Request saved twice");
        }
    }

    @Override
    public synchronized Optional<ApprovalRequest> find(TenantId tenant, UUID id) {
        return Optional.ofNullable(requests.get(key(tenant, id)));
    }

    @Override
    public synchronized Stored decide(TenantId tenant, UUID id, Decision decision, Instant now) {
        ApprovalRequest r = requests.get(key(tenant, id));
        if (r == null) {
            return new Stored(Stored.Kind.UNKNOWN, null);
        }
        if (r.decision() != null) {
            return new Stored(Stored.Kind.ALREADY_DECIDED, r);
        }
        if (!r.isOpen(now)) {
            return new Stored(Stored.Kind.EXPIRED, r);
        }
        ApprovalRequest decided = new ApprovalRequest(r.tenant(), r.id(), r.entity(), r.type(), r.requester(),
                r.identifier(), r.link(), r.createdAt(), r.expiresAt(), decision);
        requests.put(key(tenant, id), decided);
        return new Stored(Stored.Kind.DECIDED, decided);
    }

    @Override
    public synchronized int purgeExpiredBefore(Instant before) {
        List<String> gone = requests.entrySet().stream().filter(e -> e.getValue().expiresAt().isBefore(before))
                .map(Map.Entry::getKey).toList();
        gone.forEach(requests::remove);
        told.removeAll(gone);
        return gone.size();
    }

    @Override
    public synchronized List<ApprovalRequest> claimExpiredUndecided(Set<String> types, Instant now, int limit) {
        List<ApprovalRequest> claimed = requests.entrySet().stream()
                .filter(e -> types.contains(e.getValue().type()) && e.getValue().isExpired(now)
                        && !told.contains(e.getKey()))
                .sorted(Comparator.comparing(e -> e.getValue().expiresAt()))
                .limit(limit)
                .map(e -> {
                    told.add(e.getKey());
                    return e.getValue();
                })
                .toList();
        return claimed;
    }

    /** Every request, in the order saved. */
    public synchronized List<ApprovalRequest> all() {
        return List.copyOf(requests.values());
    }

    private static String key(TenantId tenant, UUID id) {
        return tenant.value() + "\u001f" + id;
    }
}
