package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.issue.IssueError;
import com.documentplatform.licensing.issue.IssueException;
import com.documentplatform.licensing.issue.Signer;

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Signing keys in memory, for tests (throwaway keys only). Key IDs may use the Vault form {@code name:version}. */
public final class InMemorySigner implements Signer {

    private final Map<String, KeyPair> keys = new HashMap<>();

    public InMemorySigner put(String keyId, KeyPair key) {
        keys.put(keyId, key);
        return this;
    }

    @Override
    public byte[] sign(String keyId, byte[] message) {
        KeyPair key = keys.get(keyId);
        if (key == null) {
            throw new IssueException(IssueError.ISSUE_KEY_NOT_FOUND, "Key ID " + keyId + ".");
        }
        try {
            return Ed25519.sign(key.getPrivate(), message);
        } catch (InvalidKeyException e) {
            throw new IssueException(IssueError.ISSUE_KEY_INVALID, "Key ID " + keyId + ".");
        }
    }

    /** The public keys of {@code keyName} and of every {@code keyName:version}. */
    @Override
    public Map<String, PublicKey> publicKeys(String keyName) {
        Map<String, PublicKey> out = new TreeMap<>();
        keys.forEach((id, pair) -> {
            if (id.equals(keyName) || id.startsWith(keyName + ":")) {
                out.put(id, pair.getPublic());
            }
        });
        return out;
    }
}
