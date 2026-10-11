/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.testkit;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small forward proxy for tests, on a local port (no real network): it forwards plain {@code http://} requests and tunnels
 * {@code CONNECT} (the https way), counts both, and can demand Basic {@code Proxy-Authorization} (answering 407 otherwise).
 *
 * <pre>{@code
 * try (FakeProxy proxy = FakeProxy.start().requireAuth("user", "secret")) {
 *     // point the connector at proxy.url(); then
 *     assertThat(proxy.forwarded() + proxy.connects()).isEqualTo(1);
 * }
 * }</pre>
 *
 * <p>Only requests that carried the right credentials (or when none are demanded) count in {@link #forwarded()} and
 * {@link #connects()}; those answered 407 count in {@link #rejected()}.
 */
public final class FakeProxy implements Closeable {

    private final ServerSocket server;
    private final AtomicInteger forwarded = new AtomicInteger();
    private final AtomicInteger connects = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private volatile String expectedAuth;
    private volatile boolean refuseConnect;

    private FakeProxy() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("fake-proxy-accept").start(this::acceptLoop);
    }

    public static FakeProxy start() {
        try {
            return new FakeProxy();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Demands {@code Proxy-Authorization: Basic base64(user:password)}. */
    public FakeProxy requireAuth(String user, String password) {
        this.expectedAuth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Answers every CONNECT with 403, as a proxy that forbids the target does. */
    public FakeProxy refuseConnect() {
        this.refuseConnect = true;
        return this;
    }

    public int port() {
        return server.getLocalPort();
    }

    /** {@code http://127.0.0.1:port}. */
    public String url() {
        return "http://127.0.0.1:" + port();
    }

    /** Plain http requests it forwarded. */
    public int forwarded() {
        return forwarded.get();
    }

    /** CONNECT tunnels it opened. */
    public int connects() {
        return connects.get();
    }

    /** Requests it answered 407. */
    public int rejected() {
        return rejected.get();
    }

    /** Everything it served, forwarded or tunnelled. */
    public int served() {
        return forwarded.get() + connects.get();
    }

    /** The request lines it accepted ({@code GET http://127.0.0.1:1234/x HTTP/1.1}, {@code CONNECT host:443 HTTP/1.1}). */
    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** The {@code Proxy-Authorization} values it saw, in order (including those it rejected). */
    public List<String> authorizations() {
        synchronized (authorizations) {
            return List.copyOf(authorizations);
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing a test fixture
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().name("fake-proxy-conn").start(() -> serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (!client.isClosed()) {
                String requestLine = line(in);
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                Map<String, String> headers = new LinkedHashMap<>();
                for (String h = line(in); h != null && !h.isEmpty(); h = line(in)) {
                    int c = h.indexOf(':');
                    if (c > 0) {
                        headers.put(h.substring(0, c).strip().toLowerCase(java.util.Locale.ROOT), h.substring(c + 1).strip());
                    }
                }
                String auth = headers.get("proxy-authorization");
                if (auth != null) {
                    authorizations.add(auth);
                }
                if (expectedAuth != null && !expectedAuth.equals(auth)) {
                    rejected.incrementAndGet();
                    drain(in, headers);
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"fake-proxy\"\r\n"
                            + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    continue;
                }
                String[] parts = requestLine.split(" ");
                if (parts[0].equals("CONNECT")) {
                    requests.add(requestLine);
                    if (refuseConnect) {
                        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return;
                    }
                    tunnel(client, parts[1], out);
                    return;
                }
                requests.add(requestLine);
                forward(parts, headers, in, out);
                return;
            }
        } catch (IOException ignored) {
            // a client that went away
        }
    }

    private void tunnel(Socket client, String hostPort, OutputStream out) throws IOException {
        int colon = hostPort.lastIndexOf(':');
        try (Socket upstream = new Socket(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)))) {
            connects.incrementAndGet();
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread up = Thread.ofVirtual().start(() -> pipe(client, upstream));
            pipe(upstream, client);
            up.join();
        } catch (IOException e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.getOutputStream().flush();
            to.shutdownOutput();
        } catch (IOException ignored) {
            // one side closed
        }
    }

    private void forward(String[] request, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        URI target = URI.create(request[1]);
        byte[] body = drain(in, headers);
        int port = target.getPort() > 0 ? target.getPort() : 80;
        try (Socket upstream = new Socket(target.getHost(), port)) {
            forwarded.incrementAndGet();
            StringBuilder head = new StringBuilder(request[0]).append(' ')
                    .append(target.getRawPath().isEmpty() ? "/" : target.getRawPath())
                    .append(target.getRawQuery() == null ? "" : "?" + target.getRawQuery()).append(" HTTP/1.1\r\n");
            headers.forEach((k, v) -> {
                if (!k.startsWith("proxy-") && !k.equals("connection")) {
                    head.append(k).append(": ").append(v).append("\r\n");
                }
            });
            head.append("Connection: close\r\n\r\n");
            OutputStream uo = upstream.getOutputStream();
            uo.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            uo.write(body);
            uo.flush();
            upstream.getInputStream().transferTo(out);
            out.flush();
        } catch (IOException e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    /** The request body exactly as it came (a chunked one with its framing, so it can be sent on unchanged). */
    private static byte[] drain(InputStream in, Map<String, String> headers) throws IOException {
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            while (true) {
                String size = line(in);
                raw.write((size + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                int n = Integer.parseInt(size.split(";")[0].strip(), 16);
                if (n == 0) {
                    for (String t = line(in); t != null && !t.isEmpty(); t = line(in)) {
                        raw.write((t + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                    raw.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    return raw.toByteArray();
                }
                raw.write(in.readNBytes(n));
                line(in);
                raw.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        String len = headers.get("content-length");
        return len == null ? new byte[0] : in.readNBytes(Integer.parseInt(len));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
        }
        return c < 0 && b.size() == 0 ? null : b.toString(StandardCharsets.ISO_8859_1);
    }
}
