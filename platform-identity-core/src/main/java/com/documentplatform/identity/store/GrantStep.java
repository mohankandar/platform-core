package com.documentplatform.identity.store;

import com.documentplatform.identity.mapping.Grant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One grant history row to write, worked out from what one change really added and removed (the same rule for the
 * Postgres and in-memory stores): a group that lost exactly one role and gained exactly one role is one CHANGED row;
 * everything else is one ADDED or REMOVED row per grant.
 */
public record GrantStep(String group, String roleBefore, String roleAfter, GrantChange.Kind kind) {

    /** @param added the grants really added; @param removed the grants really removed */
    public static List<GrantStep> of(Collection<Grant> added, Collection<Grant> removed) {
        Map<String, List<String>> plus = byGroup(added);
        Map<String, List<String>> minus = byGroup(removed);
        List<GrantStep> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : minus.entrySet()) {
            List<String> gained = plus.get(e.getKey());
            if (e.getValue().size() == 1 && gained != null && gained.size() == 1) {
                out.add(new GrantStep(e.getKey(), e.getValue().get(0), gained.get(0), GrantChange.Kind.CHANGED));
                plus.remove(e.getKey());
                continue;
            }
            for (String role : e.getValue()) out.add(new GrantStep(e.getKey(), role, null, GrantChange.Kind.REMOVED));
        }
        plus.forEach((group, roles) -> roles.forEach(r -> out.add(new GrantStep(group, null, r, GrantChange.Kind.ADDED))));
        out.sort(Comparator.comparing(GrantStep::group).thenComparing(s -> s.kind().ordinal()));
        return List.copyOf(out);
    }

    private static Map<String, List<String>> byGroup(Collection<Grant> grants) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Grant g : grants) out.computeIfAbsent(g.group(), k -> new ArrayList<>()).add(g.role());
        out.values().forEach(l -> l.sort(Comparator.naturalOrder()));
        return out;
    }
}
