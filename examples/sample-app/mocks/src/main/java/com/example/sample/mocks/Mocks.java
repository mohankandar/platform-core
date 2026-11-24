package com.example.sample.mocks;

import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.integration.testkit.MockDestination;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Starts the mock systems on 127.0.0.1 and writes their addresses to a file (first argument). Test values only. */
public final class Mocks {

    private Mocks() {
    }

    public static void main(String[] args) throws Exception {
        MockIssuer issuer = MockIssuer.start();
        MockDestination destination = MockDestination.start().expectApiKey("X-Api-Key", "sample-api-key");
        GreenMail mail = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP).dynamicPort())
                .withConfiguration(GreenMailConfiguration.aConfig().withUser("notify@sample.example", "notify", "sample-mail-password"));
        mail.start();
        HttpServer admin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        admin.createContext("/", exchange -> {
            String answer = switch (exchange.getRequestURI().getPath()) {
                case "/token" -> issuer.token("api://demo-product-a", List.of("Demo Finance Readers"), Duration.ofMinutes(10));
                case "/mails" -> String.valueOf(mail.getReceivedMessages().length);
                case "/pushes" -> String.valueOf(destination.received().size());
                default -> "?";
            };
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        admin.start();
        Files.writeString(Path.of(args[0]), "ISSUER=" + issuer.issuer() + "\nDESTINATION=" + destination.address()
                + "\nMAIL_PORT=" + mail.getSmtp().getPort() + "\nADMIN=http://127.0.0.1:" + admin.getAddress().getPort() + "\n");
        Thread.currentThread().join();
    }
}
