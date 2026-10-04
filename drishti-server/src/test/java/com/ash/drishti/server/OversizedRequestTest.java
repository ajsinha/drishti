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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * S2-08 on a real embedded Tomcat: an oversized builder request (declared or chunked, the 26 MB PATCH and 28 MB upload of
 * the QA repro) is answered 413 application/problem+json DRS-5005 naming the limit and the size, and the client reads it
 * instead of a broken pipe or a reset.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"drishti.rachana.hot-reload=false",
        "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.identity.database-url=jdbc:sqlite:target/oversized-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/oversized-files-${random.uuid}"})
class OversizedRequestTest {

    @Value("${local.server.port}")
    int port;

    private static final int MB = 1024 * 1024;

    @Test
    void aDeclaredOversizePatchIsRefusedBeforeItsBodyIsRead() throws Exception {
        String r = send("PATCH", "/api/v1/builder/designs/none", 26 * MB, false);
        assertProblem(r);
        assertThat(r).contains("26.0 MiB").contains("25.0 MiB").contains("\"limitBytes\":26214400");
    }

    @Test
    void aDeclaredOversizeUploadIsRefused() throws Exception {
        String r = send("POST", "/api/v1/builder/designs/import", 28 * MB, false);
        assertProblem(r);
        assertThat(r).contains("28.0 MiB");
    }

    @Test
    void aChunkedOversizeBodyIsRefusedToo() throws Exception {
        String r = send("POST", "/api/v1/builder/shape", 27 * MB, true);
        assertProblem(r);
        assertThat(r).contains("over the limit of 25.0 MiB");
    }

    @Test
    void aBodyWithinTheLimitIsNotTouched() throws Exception {
        String r = send("POST", "/api/v1/builder/shape", 100, false);
        assertThat(r).doesNotContain("HTTP/1.1 413");
    }

    private static void assertProblem(String r) {
        assertThat(r).startsWith("HTTP/1.1 413").contains("application/problem+json").contains("DRS-5005");
    }

    /** Sends {@code size} body bytes (all of them, as a client would) and returns the raw response text. */
    private String send(String method, String path, int size, boolean chunked) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(60_000);
            OutputStream out = s.getOutputStream();
            String head = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + size + "\r\n") + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            byte[] block = new byte[64 * 1024];
            java.util.Arrays.fill(block, (byte) 'x');
            try {
                for (int sent = 0; sent < size; sent += block.length) {
                    int n = Math.min(block.length, size - sent);
                    if (chunked) {
                        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write(block, 0, n);
                    if (chunked) {
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                }
                if (chunked) {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
            } catch (java.io.IOException e) {
                throw new AssertionError("the connection broke while sending: " + e, e);
            }
            InputStream in = s.getInputStream();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    got.write(buf, 0, n);
                    if (new String(got.toByteArray(), StandardCharsets.UTF_8).contains("}")) {
                        break;
                    }
                }
            } catch (java.io.IOException e) {
                throw new AssertionError("the connection was reset before the answer was read: " + e, e);
            }
            return got.toString(StandardCharsets.UTF_8);
        }
    }
}
