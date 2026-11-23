package com.documentplatform.notifications.webhook.internal;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.webhook.WebhookDirectory;
import com.documentplatform.notifications.webhook.WebhookOptions;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Posts a JSON payload to a destination's incoming webhook (Teams Workflows or Slack) and turns the outcome into a
 * specific {@link FailureCode}. The address is read from the secret store for each attempt and used only to build the
 * request: it never reaches a result, an exception text or a log. Redirects are not followed.
 *
 * <p>Uses the Java runtime's proxy settings ({@code https.proxyHost} / {@code https.proxyPort}) when set.
 *
 * <p>Internal (P041): shared by the Teams and Slack senders; may change in any release, not for apps.
 */
public final class WebhookClient implements AutoCloseable {

    private final ChannelKind kind;
    private final WebhookDirectory directory;
    private final SecretResolver secrets;
    private final WebhookOptions options;
    private final HttpClient http;
    private final Clock clock;

    public WebhookClient(ChannelKind kind, WebhookDirectory directory, SecretResolver secrets, WebhookOptions options) {
        this(kind, directory, secrets, options, Clock.systemUTC());
    }

    WebhookClient(ChannelKind kind, WebhookDirectory directory, SecretResolver secrets, WebhookOptions options,
                  Clock clock) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * @param addressCheck a channel-specific check of the address (for example: not a retired Teams connector);
     *                     returns the failure, or empty if the address is acceptable
     */
    public SendResult post(OutgoingMessage out, String json, Function<URI, Optional<FailureCode>> addressCheck) {
        Optional<SecretRef> ref = directory.find(out.tenant(), out.destination());
        if (ref.isEmpty()) {
            return SendResult.failed(FailureCode.DESTINATION_NOT_CONFIGURED);
        }
        Optional<Secret> secret;
        try {
            secret = secrets.resolve(ref.get());
        } catch (RuntimeException storeDown) {
            return SendResult.failed(FailureCode.SECRET_UNAVAILABLE);
        }
        if (secret.isEmpty()) {
            return SendResult.failed(FailureCode.SECRET_NOT_FOUND);
        }
        URI uri;
        try {
            uri = new URI(secret.get().reveal().trim());
        } catch (URISyntaxException notAnAddress) {
            return SendResult.failed(FailureCode.WEBHOOK_ADDRESS_INVALID);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return SendResult.failed(FailureCode.WEBHOOK_ADDRESS_INVALID);
        }
        String scheme = uri.getScheme().toLowerCase();
        // Plain http only with the test switch AND to this machine (P037, xc-public-api-3): the address carries a token.
        boolean localHttp = scheme.equals("http") && options.allowPlainHttp() && loopback(uri.getHost());
        if (!scheme.equals("https") && !localHttp) {
            return SendResult.failed(scheme.equals("http") ? FailureCode.WEBHOOK_ADDRESS_NOT_SECURE
                    : FailureCode.WEBHOOK_ADDRESS_INVALID);
        }
        Optional<FailureCode> refused = addressCheck.apply(uri);
        if (refused.isPresent()) {
            return SendResult.failed(refused.get());
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException badAddress) {
            return SendResult.failed(FailureCode.WEBHOOK_ADDRESS_INVALID);
        }
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpConnectTimeoutException e) {
            return SendResult.failed(FailureCode.WEBHOOK_UNREACHABLE);
        } catch (HttpTimeoutException e) {
            return SendResult.failed(FailureCode.WEBHOOK_TIMEOUT);
        } catch (IOException e) {
            return SendResult.failed(FailureCode.WEBHOOK_UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failed(FailureCode.WEBHOOK_TIMEOUT);
        } catch (IllegalArgumentException | SecurityException e) {
            return SendResult.failed(FailureCode.WEBHOOK_ADDRESS_INVALID);
        }
        return result(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null));
    }

    /** The outcome for an HTTP status (and Retry-After header, if any). */
    SendResult result(int status, String retryAfterHeader) {
        if (status >= 200 && status < 300) {
            return SendResult.ok();
        }
        return switch (status) {
            case 400, 413, 415, 422 -> SendResult.failed(FailureCode.WEBHOOK_PAYLOAD_REJECTED);
            case 401, 403 -> SendResult.failed(FailureCode.WEBHOOK_REJECTED);
            case 404, 410 -> SendResult.failed(FailureCode.WEBHOOK_ADDRESS_INVALID);
            case 408 -> SendResult.failed(FailureCode.WEBHOOK_TIMEOUT, retryAfter(retryAfterHeader));
            case 429 -> SendResult.failed(FailureCode.WEBHOOK_RATE_LIMITED, retryAfter(retryAfterHeader));
            default -> status >= 500 && status < 600
                    ? SendResult.failed(status == 504 ? FailureCode.WEBHOOK_TIMEOUT : FailureCode.WEBHOOK_SERVER_ERROR,
                    retryAfter(retryAfterHeader))
                    : SendResult.failed(FailureCode.WEBHOOK_UNEXPECTED_REPLY);
        };
    }

    /** Retry-After as seconds or an HTTP date; null if absent or unreadable. */
    Duration retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.trim();
        if (v.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(v));
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }
        try {
            Duration d = Duration.between(clock.instant(),
                    ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return d.isNegative() ? Duration.ZERO : d;
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    public ChannelKind kind() {
        return kind;
    }

    @Override
    public void close() {
        http.close();
    }

    /** localhost, 127.0.0.1 or [::1]: the only hosts the test switch allows plain http to. */
    static boolean loopback(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }
}
