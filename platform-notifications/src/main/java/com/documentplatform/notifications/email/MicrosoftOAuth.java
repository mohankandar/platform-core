package com.documentplatform.notifications.email;

import com.documentplatform.notifications.secret.SecretRef;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sign-in to Microsoft 365 for SMTP with OAuth 2.0 (client-credentials flow, SMTP {@code AUTH XOAUTH2}). The customer
 * registers an app in Microsoft Entra; this holds its IDs and where its credential is kept, never the credential.
 *
 * @param directoryId the Entra directory (tenant) ID: a GUID, or a verified domain such as {@code customer.onmicrosoft.com}
 * @param clientId    the app registration's application (client) ID, a GUID
 * @param credentialType a certificate with its private key (preferred) or a client secret
 * @param credential  where the credential is kept in the secret store. For a certificate: one PEM text holding the
 *                    certificate ({@code BEGIN CERTIFICATE}) and its unencrypted PKCS#8 RSA private key
 *                    ({@code BEGIN PRIVATE KEY}). For a secret: the client secret's value.
 * @param authority   the Microsoft sign-in address; {@link #GLOBAL} unless the customer uses a national cloud
 */
public record MicrosoftOAuth(String directoryId, String clientId, CredentialType credentialType, SecretRef credential,
                             URI authority) {

    public enum CredentialType { CERTIFICATE, SECRET }

    /** Microsoft's global sign-in address. */
    public static final URI GLOBAL = URI.create("https://login.microsoftonline.com");
    /** The scope for SMTP, IMAP and POP with application permissions. */
    public static final String SCOPE = "https://outlook.office365.com/.default";
    /** Microsoft 365's SMTP submission server. */
    public static final String SMTP_HOST = "smtp.office365.com";
    public static final int SMTP_PORT = 587;

    private static final Pattern GUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DOMAIN = Pattern.compile("(?=.{4,253}$)([A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,63}");

    public MicrosoftOAuth {
        Objects.requireNonNull(directoryId, "directoryId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(credentialType, "credentialType");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(authority, "authority");
        if (!isDirectoryId(directoryId)) {
            throw new IllegalArgumentException("Directory (tenant) ID is not valid");
        }
        if (!isClientId(clientId)) {
            throw new IllegalArgumentException("Client ID is not valid");
        }
        if (!authority.isAbsolute() || authority.getHost() == null || authority.getRawQuery() != null
                || authority.getRawFragment() != null || authority.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Sign-in address is not valid");
        }
    }

    /** With Microsoft's global sign-in address. */
    public static MicrosoftOAuth of(String directoryId, String clientId, CredentialType type, SecretRef credential) {
        return new MicrosoftOAuth(directoryId, clientId, type, credential, GLOBAL);
    }

    public static boolean isDirectoryId(String value) {
        return value != null && (GUID.matcher(value).matches() || DOMAIN.matcher(value).matches());
    }

    public static boolean isClientId(String value) {
        return value != null && GUID.matcher(value).matches();
    }

    /** {@code <authority>/<directory ID>/oauth2/v2.0/token}. */
    public URI tokenEndpoint() {
        String base = authority.toString().replaceAll("/+$", "");
        return URI.create(base + "/" + directoryId + "/oauth2/v2.0/token");
    }
}
