package com.documentplatform.crypto.spring;

import java.util.List;

/**
 * The crypto install config is wrong or incomplete. Lists every problem with its property key and what to do; never
 * a token or secret ID.
 */
public final class CryptoConfigException extends IllegalStateException {

    private final List<String> problems;

    public CryptoConfigException(List<String> problems) {
        super("Crypto configuration is not valid:\n - " + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
