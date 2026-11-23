package com.documentplatform.crypto.vault;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One Vault server in dev mode for the test run (image {@code hashicorp/vault}), set up as
 * {@code docs/design/crypto.md} describes: Transit enabled, the {@code platform-crypto-app} and
 * {@code platform-crypto-setup} and {@code platform-crypto-offboard} policies, the AppRole method with a role for the app. Dev mode has KV version 2 at
 * {@code secret/}. Set-up calls use the root token; the code under test never does.
 */
final class VaultDev {

    static final String ROOT_TOKEN = "root-test-token-c8e41a";
    static final String ROLE = "platform-app";
    /** A role whose tokens live {@link #SHORT_TTL_SECONDS} seconds: the app must log in again. */
    static final String SHORT_ROLE = "platform-app-short";
    static final int SHORT_TTL_SECONDS = 3;

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
        root("PUT", "/v1/sys/policies/acl/platform-crypto-app", VaultJson.write(Map.of("policy",
                resource("/vault/crypto-app.hcl"))));
        root("PUT", "/v1/sys/policies/acl/platform-crypto-setup", VaultJson.write(Map.of("policy",
                resource("/vault/crypto-setup.hcl"))));
        root("PUT", "/v1/sys/policies/acl/platform-crypto-offboard", VaultJson.write(Map.of("policy",
                resource("/vault/crypto-offboard.hcl"))));
        root("POST", "/v1/sys/auth/approle", "{\"type\":\"approle\"}");
        root("POST", "/v1/auth/approle/role/" + ROLE, "{\"token_policies\":\"platform-crypto-app\","
                + "\"token_ttl\":\"15m\",\"secret_id_ttl\":\"1h\"}");
        root("POST", "/v1/auth/approle/role/" + SHORT_ROLE, "{\"token_policies\":\"platform-crypto-app\","
                + "\"token_ttl\":\"" + SHORT_TTL_SECONDS + "s\",\"token_max_ttl\":\"" + SHORT_TTL_SECONDS
                + "s\",\"secret_id_ttl\":\"1h\"}");
    }

    private VaultDev() {
    }

    static URI address() {
        return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8200));
    }

    /** A new token with only the given policies (plus Vault's default policy). */
    static String token(String... policies) {
        return tokenWithTtl("1h", policies);
    }

    /** A new token with the given TTL (a Vault duration such as {@code 2s}) and policies. */
    static String tokenWithTtl(String ttl, String... policies) {
        StringBuilder list = new StringBuilder();
        for (String p : policies) {
            list.append(list.isEmpty() ? "" : ",").append('"').append(p).append('"');
        }
        Map<String, Object> r = root("POST", "/v1/auth/token/create", "{\"policies\":[" + list + "],\"ttl\":\""
                + ttl + "\"}");
        return VaultJson.object(r, "auth").flatMap(a -> VaultJson.string(a, "client_token")).orElseThrow();
    }

    static String roleId() {
        return roleId(ROLE);
    }

    static String roleId(String role) {
        return VaultJson.object(root("GET", "/v1/auth/approle/role/" + role + "/role-id", null), "data")
                .flatMap(d -> VaultJson.string(d, "role_id")).orElseThrow();
    }

    static String newSecretId() {
        return newSecretId(ROLE);
    }

    static String newSecretId(String role) {
        return VaultJson.object(root("POST", "/v1/auth/approle/role/" + role + "/secret-id", "{}"), "data")
                .flatMap(d -> VaultJson.string(d, "secret_id")).orElseThrow();
    }

    /** The HTTP status Vault answers to one call with the given token (nothing else sent). */
    static int statusWithToken(String method, String path, String token) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(address() + path)).header("X-Vault-Token", token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try {
            return HTTP.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> root(String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address() + path)).header("X-Vault-Token", ROOT_TOKEN)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> json = r.body() == null || r.body().isBlank() ? Map.of()
                    : VaultJson.parseObject(r.body());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("Vault set-up " + method + " " + path + " failed: HTTP "
                        + r.statusCode() + " " + json.get("errors"));
            }
            return json;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = VaultDev.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
