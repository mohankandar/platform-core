package com.documentplatform.monitoring.checks;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * One of the app's own dependencies over HTTP (a model server, an OCR service): its health address answers with the
 * expected status. Redirects are not followed; nothing but the status is read. Only the address is shown (no query).
 */
public final class HttpServiceCheck implements DependencyCheck {

    private final String service;
    private final URI address;
    private final int expectedStatus;
    private final Duration timeout;
    private final boolean required;
    private final HttpClient http;

    /**
     * @param address        the health address, for example {@code http://ocr.internal:8080/health}
     * @param expectedStatus usually 200
     */
    public HttpServiceCheck(String service, URI address, int expectedStatus, Duration timeout, boolean required) {
        this.service = Objects.requireNonNull(service, "service");
        this.address = address;
        this.expectedStatus = expectedStatus;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.required = required;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    @Override
    public String name() {
        return "service-" + service;
    }

    @Override
    public String group() {
        return "services";
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Duration timeout() {
        return timeout.plusSeconds(1);
    }

    @Override
    public Verdict check() {
        if (address == null || address.getHost() == null || address.getScheme() == null
                || !(address.getScheme().equalsIgnoreCase("http") || address.getScheme().equalsIgnoreCase("https"))) {
            return Verdict.down(MonitoringCode.MON_HTTP_ADDRESS_INVALID, Map.of("service", service,
                    "address", address == null ? "-" : shown(address)));
        }
        Map<String, String> where = Map.of("service", service, "address", shown(address),
                "timeout", timeout.toSeconds() + " s");
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(address).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == expectedStatus) return Verdict.working();
            return Verdict.down(MonitoringCode.MON_HTTP_UNEXPECTED_ANSWER, Map.of("service", service,
                    "address", shown(address), "status", String.valueOf(r.statusCode()),
                    "expected", String.valueOf(expectedStatus)));
        } catch (HttpTimeoutException e) {
            return Verdict.down(MonitoringCode.MON_HTTP_TIMEOUT, where);
        } catch (IOException e) {
            return Verdict.down(MonitoringCode.MON_HTTP_UNREACHABLE, where);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.down(MonitoringCode.MON_HTTP_TIMEOUT, where);
        }
    }

    private static String shown(URI u) {
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
                + (u.getRawPath() == null ? "" : u.getRawPath());
    }
}
