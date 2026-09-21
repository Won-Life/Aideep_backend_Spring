package com.aideep.domain.auth.support;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Local-only Google/SMTP substitutes: no real mail or external identity accounts.
 */
public class FakeIdentityServers implements AutoCloseable {
    private final HttpServer googleHttpServer;
    private final ServerSocket smtpServerSocket;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    public volatile String profile;
    public volatile String tokenForm;
    public volatile String userInfoAuthorization;
    public volatile int googleStatus = 200;
    public volatile boolean rejectMail;
    public final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    public FakeIdentityServers() {
        try {
            googleHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            googleHttpServer.createContext("/token", exchange -> {
                tokenForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body = "{\"access_token\":\"fake-google-access\",\"token_type\":\"Bearer\"}".getBytes(
                        StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(googleStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            googleHttpServer.createContext("/userinfo", exchange -> {
                userInfoAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = profile.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            googleHttpServer.setExecutor(executorService);
            googleHttpServer.start();
            smtpServerSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            executorService.submit(() -> {
                while (!smtpServerSocket.isClosed()) {
                    try {
                        Socket socket = smtpServerSocket.accept();
                        executorService.submit(() -> serveMail(socket));
                    } catch (IOException e) {
                        if (!smtpServerSocket.isClosed()) throw new UncheckedIOException(e);
                    }
                }
            });
            reset();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void reset() {
        profile = "{\"sub\":\"google-subject\",\"email\":\"google@example.com\",\"email_verified\":true,\"name\":\"Google User\"}";
        tokenForm = null;
        userInfoAuthorization = null;
        googleStatus = 200;
        rejectMail = false;
        messages.clear();
    }

    public String googleUrl() {
        return "http://127.0.0.1:" + googleHttpServer.getAddress().getPort();
    }

    public int smtpPort() {
        return smtpServerSocket.getLocalPort();
    }

    private void serveMail(Socket socket) {
        try (socket; var in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); var out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(10000);
            out.print("220 localhost ESMTP\r\n");
            out.flush();
            String line;
            boolean data = false;
            StringBuilder message = new StringBuilder();
            while ((line = in.readLine()) != null) {
                if (data) {
                    if (line.equals(".")) {
                        messages.add(message.toString());
                        data = false;
                        out.print("250 accepted\r\n");
                    } else message.append(line).append('\n');
                } else if (line.startsWith("EHLO") || line.startsWith("HELO")) out.print("250 localhost\r\n");
                else if (line.startsWith("MAIL") && rejectMail) out.print("550 rejected\r\n");
                else if (line.equals("DATA")) {
                    data = true;
                    out.print("354 send message\r\n");
                } else if (line.equals("QUIT")) {
                    out.print("221 bye\r\n");
                    out.flush();
                    break;
                } else out.print("250 OK\r\n");
                out.flush();
            }
        } catch (IOException e) {
            if (!smtpServerSocket.isClosed()) throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        googleHttpServer.stop(0);
        smtpServerSocket.close();
        executorService.shutdownNow();
    }
}
