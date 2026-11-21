package com.documentplatform.licensing.issue;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One Vault server in dev mode for the test run (image {@code hashicorp/vault}), set up the way
 * {@code docs/install/licence-signing.md} describes: Transit enabled, ed25519 keys, the {@code licensing-signer}
 * policy, the userpass method with a named user. Set-up calls use the root token; the code under test never does.
 */
final class VaultDev {

    static final String ROOT_TOKEN = "root-test-token-6f1d2c";
    static final String USER = "owner-test";
    static final String PASSWORD = "pw-for-tests-9a7c3e";
    static final String POLICY = policy();

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>("hashicorp/vault:1.17.6")
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
            .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
            .withEnv("SKIP_SETCAP", "true")
            .withCommand("server", "-dev")
            .withExposedPorts(8200)
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
        root("POST", "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        for (String key : List.of("lic-2026", "lic-rot", "lic-audit")) {
            root("POST", "/v1/transit/keys/" + key, "{\"type\":\"ed25519\"}");
        }
        root("POST", "/v1/transit/keys/lic-exp", "{\"type\":\"ed25519\",\"exportable\":true}");
        root("POST", "/v1/transit/keys/lic-ecdsa", "{\"type\":\"ecdsa-p256\"}");
        root("PUT", "/v1/sys/policies/acl/licensing-signer", VaultJson.write(Map.of("policy", POLICY)));
        root("POST", "/v1/sys/auth/userpass", "{\"type\":\"userpass\"}");
        root("POST", "/v1/auth/userpass/users/" + USER, VaultJson.write(Map.of("password", PASSWORD,
                "token_policies", "licensing-signer", "token_ttl", "15m")));
    }

    private VaultDev() {
    }

    static String address() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8200);
    }

    /** A new token with only the given policies (plus Vault's default policy). */
    static String token(String... policies) {
        StringBuilder list = new StringBuilder();
        for (String p : policies) {
            list.append(list.isEmpty() ? "" : ",").append('"').append(p).append('"');
        }
        Map<String, Object> r = root("POST", "/v1/auth/token/create", "{\"policies\":[" + list + "],\"ttl\":\"1h\"}");
        return VaultJson.object(r, "auth").flatMap(a -> VaultJson.string(a, "client_token")).orElseThrow();
    }

    static Map<String, Object> root(String method, String path, String body) {
        return call(ROOT_TOKEN, method, path, body).body();
    }

    record Reply(int status, Map<String, Object> body) {
    }

    static Reply call(String token, String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address() + path)).header("X-Vault-Token", token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> json = r.body() == null || r.body().isBlank() ? Map.of()
                    : VaultJson.parseObject(r.body());
            if (token.equals(ROOT_TOKEN) && r.statusCode() >= 300) {
                throw new IllegalStateException("Vault set-up " + method + " " + path + " failed: HTTP "
                        + r.statusCode() + " " + json.get("errors"));
            }
            return new Reply(r.statusCode(), json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String policy() {
        try (InputStream in = VaultDev.class.getResourceAsStream("/vault/licensing-signer.hcl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
