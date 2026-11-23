package com.documentplatform.crypto.spring;

import java.util.List;

/**
 * The crypto install config is wrong or incomplete. Lists every problem with its property key and what to do; never
 * a token or secret ID.
 */
final class CryptoConfigException extends IllegalStateException implements com.documentplatform.errors.HasPlatformCode {

    private final List<String> problems;

    public CryptoConfigException(List<String> problems) {
        super(com.documentplatform.crypto.CryptoError.CRYPTO_SETTINGS_INVALID.name() + ": Crypto configuration is not valid:\n - "
                + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** The code, for monitoring, the failure analyzer and the app's error handler. */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return com.documentplatform.crypto.CryptoError.CRYPTO_SETTINGS_INVALID;
    }
}
