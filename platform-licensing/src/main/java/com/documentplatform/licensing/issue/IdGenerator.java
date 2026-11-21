package com.documentplatform.licensing.issue;

import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Random IDs for tenants ({@code TEN-}), entity slots ({@code ENT-}) and licences ({@code LIC-}): 16 Crockford base32
 * characters (80 random bits) in four groups, for example {@code TEN-7K3M-9QX2-H4BD-0RWE}. Each new ID is checked
 * against every ID already issued (all prefixes); a collision draws again.
 */
public final class IdGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int MAX_ATTEMPTS = 20;

    private final RandomGenerator random;

    public IdGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @param taken every ID in use; the new ID is added to it
     * @throws IssueException {@code ISSUE_ID_COLLISION} after {@value #MAX_ATTEMPTS} draws that were all taken
     */
    public String next(String prefix, Set<String> taken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            StringBuilder b = new StringBuilder(prefix);
            for (int i = 0; i < 16; i++) {
                if (i % 4 == 0) {
                    b.append('-');
                }
                b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String id = b.toString();
            if (taken.add(id)) {
                return id;
            }
        }
        throw new IssueException(IssueError.ISSUE_ID_COLLISION,
                MAX_ATTEMPTS + " random " + prefix + " IDs in a row were already issued; check the random source.");
    }
}
