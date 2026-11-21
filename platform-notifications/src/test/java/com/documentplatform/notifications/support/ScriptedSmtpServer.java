package com.documentplatform.notifications.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A tiny SMTP server whose replies the test chooses, to produce each failure a real mail server can give: refused
 * login, refused recipient (permanent or temporary), rejected message, a "try later" greeting, no STARTTLS, or no
 * greeting at all (timeout).
 */
public final class ScriptedSmtpServer implements AutoCloseable {

    public String greeting = "220 scripted ESMTP";
    public boolean silent;
    public List<String> extensions = List.of("AUTH PLAIN LOGIN");
    public String authReply = "235 2.7.0 Authentication successful";
    /** The reply to {@code AUTH XOAUTH2}; null for {@link #authReply}. */
    public String xoauth2Reply;
    public String mailFromReply = "250 2.1.0 OK";
    public Function<String, String> rcptReply = address -> "250 2.1.5 OK";
    public String endOfDataReply = "250 2.0.0 queued";

    private final ServerSocket server;
    private final List<String> received = new ArrayList<>();
    private final Thread acceptor;

    public ScriptedSmtpServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "scripted-smtp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Every line the client sent (including the base64 login lines). */
    public synchronized List<String> received() {
        return List.copyOf(received);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> session(s), "scripted-smtp-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void session(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = socket.getOutputStream()) {
            if (silent) {
                while (in.read() != -1) {
                    // say nothing until the client gives up
                }
                return;
            }
            reply(out, greeting);
            if (!greeting.startsWith("220")) {
                return;
            }
            String line;
            while ((line = in.readLine()) != null) {
                record(line);
                String cmd = line.toUpperCase();
                if (cmd.startsWith("EHLO")) {
                    StringBuilder b = new StringBuilder("250-scripted");
                    for (String ext : extensions) {
                        b.append("\r\n250-").append(ext);
                    }
                    b.append("\r\n250 8BITMIME");
                    reply(out, b.toString());
                } else if (cmd.startsWith("HELO")) {
                    reply(out, "250 scripted");
                } else if (cmd.startsWith("AUTH LOGIN")) {
                    reply(out, "334 VXNlcm5hbWU6");
                    record(in.readLine());
                    reply(out, "334 UGFzc3dvcmQ6");
                    record(in.readLine());
                    reply(out, authReply);
                } else if (cmd.startsWith("AUTH XOAUTH2")) {
                    if (cmd.trim().equals("AUTH XOAUTH2")) {
                        reply(out, "334 ");
                        record(in.readLine());
                    }
                    reply(out, xoauth2Reply != null ? xoauth2Reply : authReply);
                } else if (cmd.startsWith("AUTH PLAIN")) {
                    if (cmd.trim().equals("AUTH PLAIN")) {
                        reply(out, "334 ");
                        record(in.readLine());
                    }
                    reply(out, authReply);
                } else if (cmd.startsWith("MAIL FROM")) {
                    reply(out, mailFromReply);
                } else if (cmd.startsWith("RCPT TO")) {
                    String address = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                    reply(out, rcptReply.apply(address));
                } else if (cmd.startsWith("DATA")) {
                    reply(out, "354 go ahead");
                    String data;
                    while ((data = in.readLine()) != null && !data.equals(".")) {
                        record(data);
                    }
                    reply(out, endOfDataReply);
                } else if (cmd.startsWith("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else if (cmd.startsWith("RSET") || cmd.startsWith("NOOP")) {
                    reply(out, "250 OK");
                } else if (cmd.startsWith("STARTTLS")) {
                    reply(out, "454 TLS not available");
                } else {
                    reply(out, "500 unknown command");
                }
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private synchronized void record(String line) {
        if (line != null) {
            received.add(line);
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
