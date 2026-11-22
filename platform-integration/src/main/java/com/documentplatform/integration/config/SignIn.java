package com.documentplatform.integration.config;

import com.documentplatform.notifications.secret.SecretRef;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How the integration signs in to the destination. Secrets are always references into the secret store, read for each
 * attempt; sign-in always goes in headers, never in the address.
 */
public sealed interface SignIn {

    Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}");

    /** The type, as stored. */
    String type();

    /**
     * No sign-in: internal network on premises only. Off unless the installation allows it, refused when hosted, warned
     * when saved, and on the app's compliance report.
     */
    record None() implements SignIn {
        public String type() {
            return "NONE";
        }
    }

    /** HTTP Basic: user name and a password from the secret store. */
    record Basic(String username, SecretRef password) implements SignIn {
        public Basic {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            if (username.isEmpty() || username.length() > 200 || username.contains(":")
                    || username.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new IllegalArgumentException("User name is not valid (1-200 characters, no ':')");
            }
        }

        public String type() {
            return "BASIC";
        }
    }

    /** An API key in a request header (never in the address). */
    record ApiKey(String header, SecretRef key) implements SignIn {
        public ApiKey {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(key, "key");
            if (!HEADER_NAME.matcher(header).matches()) {
                throw new IllegalArgumentException("API key header name is not valid");
            }
        }

        public String type() {
            return "API_KEY";
        }
    }

    /** A fixed bearer token from the secret store. */
    record BearerFixed(SecretRef token) implements SignIn {
        public BearerFixed {
            Objects.requireNonNull(token, "token");
        }

        public String type() {
            return "BEARER_FIXED";
        }
    }

    /**
     * OAuth 2.0 client credentials: a token from the login system's token address, kept until shortly before it
     * expires (generic: any token address, scope or audience).
     *
     * @param tokenAddress   the token endpoint (https; plain http only where internal addresses are allowed)
     * @param clientId       the client ID
     * @param credentialType a client secret, or a certificate with its private key (RFC 7523 client assertion)
     * @param credential     where the client secret, or the certificate and key (PEM), is kept in the secret store
     * @param scope          optional scope ({@code scope} form field)
     * @param audience       optional audience ({@code audience} form field, for login systems that use it)
     * @param clientAuth     for a client secret: in the form ({@code client_secret_post}) or as HTTP Basic
     *                       ({@code client_secret_basic})
     */
    record OAuth2ClientCredentials(URI tokenAddress, String clientId, CredentialType credentialType,
                                   SecretRef credential, String scope, String audience, ClientAuth clientAuth)
            implements SignIn {

        public enum CredentialType { SECRET, CERTIFICATE }

        public enum ClientAuth { POST, BASIC }

        private static final Pattern CLIENT_ID = Pattern.compile("[\\x21-\\x7e]{1,255}");
        private static final Pattern SCOPE_TOKEN = Pattern.compile("[\\x21\\x23-\\x5b\\x5d-\\x7e]+");

        public OAuth2ClientCredentials {
            Objects.requireNonNull(tokenAddress, "tokenAddress");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(credentialType, "credentialType");
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(clientAuth, "clientAuth");
            String scheme = tokenAddress.getScheme();
            if (!tokenAddress.isAbsolute() || tokenAddress.getHost() == null || tokenAddress.getRawQuery() != null
                    || tokenAddress.getRawFragment() != null || tokenAddress.getRawUserInfo() != null
                    || scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                throw new IllegalArgumentException("Token address is not valid");
            }
            if (!CLIENT_ID.matcher(clientId).matches() || clientId.contains(":")) {
                throw new IllegalArgumentException("Client ID is not valid");
            }
            if (scope != null) {
                boolean ok = !scope.isEmpty() && scope.length() <= 1000;
                for (String part : scope.split(" ", -1)) {
                    ok &= SCOPE_TOKEN.matcher(part).matches();
                }
                if (!ok) throw new IllegalArgumentException("Scope is not valid");
            }
            if (audience != null && (audience.isEmpty() || audience.length() > 500
                    || audience.chars().anyMatch(c -> c <= 0x20 || c == 0x7f))) {
                throw new IllegalArgumentException("Audience is not valid");
            }
        }

        public static OAuth2ClientCredentials secret(URI tokenAddress, String clientId, SecretRef secret,
                                                     String scope) {
            return new OAuth2ClientCredentials(tokenAddress, clientId, CredentialType.SECRET, secret, scope, null,
                    ClientAuth.POST);
        }

        public static OAuth2ClientCredentials certificate(URI tokenAddress, String clientId, SecretRef pem,
                                                          String scope) {
            return new OAuth2ClientCredentials(tokenAddress, clientId, CredentialType.CERTIFICATE, pem, scope, null,
                    ClientAuth.POST);
        }

        /**
         * The Microsoft Entra preset: token address
         * {@code https://login.microsoftonline.com/<directory>/oauth2/v2.0/token} and a {@code .default} scope, for
         * example {@code api://<app-id>/.default}.
         */
        public static OAuth2ClientCredentials microsoft(String directoryId, String clientId, CredentialType type,
                                                        SecretRef credential, String scope) {
            if (directoryId == null || !directoryId.matches("[A-Za-z0-9.-]{1,253}")) {
                throw new IllegalArgumentException("Directory (tenant) ID is not valid");
            }
            return new OAuth2ClientCredentials(
                    URI.create("https://login.microsoftonline.com/" + directoryId + "/oauth2/v2.0/token"), clientId,
                    type, credential, scope, null, ClientAuth.POST);
        }

        public OAuth2ClientCredentials withAudience(String audience) {
            return new OAuth2ClientCredentials(tokenAddress, clientId, credentialType, credential, scope, audience,
                    clientAuth);
        }

        public OAuth2ClientCredentials withClientAuth(ClientAuth auth) {
            return new OAuth2ClientCredentials(tokenAddress, clientId, credentialType, credential, scope, audience,
                    auth);
        }

        public String type() {
            return "OAUTH2_CLIENT_CREDENTIALS";
        }
    }
}
