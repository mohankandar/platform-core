package com.documentplatform.integration.credential;

import com.documentplatform.notifications.secret.SecretRef;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A client certificate in a keystore the customer manages (PKCS#12 or JKS). Nothing is copied: the file is read when a
 * call needs the certificate, so the customer may also renew the certificate in the file itself.
 *
 * @param path     the keystore file's absolute path
 * @param password the keystore's password (also the key's), by reference into the secret store
 * @param alias    the entry holding the private key and its certificate chain
 */
public record KeystoreRef(String path, SecretRef password, String alias) {

    public KeystoreRef {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(alias, "alias");
        if (path.isBlank() || path.length() > 1000 || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)
                || !Path.of(path).isAbsolute()) {
            throw new IllegalArgumentException("A keystore path is an absolute path of at most 1000 characters");
        }
        if (alias.isBlank() || alias.length() > 200 || alias.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("A keystore alias is 1-200 characters without control characters");
        }
    }
}
