package com.documentplatform.identity.signin;

/**
 * What the IT Admin gives the test sign-in: optionally a token (pasted, or got through a test sign-in with the
 * draft's values), and the name of the claim that carries the person's groups. The token is checked and forgotten:
 * never stored, logged, or shown back ({@link #toString()} hides it).
 *
 * @param token       a signed JWT (ID or access token), or null to check only the discovery document and keys
 * @param groupsClaim the groups claim's name; {@code groups} when null or blank
 */
public record TestSignIn(String token, String groupsClaim) {

    public static final String DEFAULT_GROUPS_CLAIM = "groups";

    public TestSignIn {
        if (token != null) {
            token = token.strip();
            if (token.isEmpty()) token = null;
        }
        groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? DEFAULT_GROUPS_CLAIM : groupsClaim.strip();
    }

    /** Checks the issuer's discovery document and keys only. */
    public static TestSignIn issuerOnly() {
        return new TestSignIn(null, null);
    }

    /** Also checks this token, with the groups claim {@code groups}. */
    public static TestSignIn withToken(String token) {
        return new TestSignIn(token, null);
    }

    public boolean hasToken() {
        return token != null;
    }

    @Override
    public String toString() {
        return "TestSignIn[token=" + (token == null ? "none" : "(hidden)") + ", groupsClaim=" + groupsClaim + "]";
    }
}
