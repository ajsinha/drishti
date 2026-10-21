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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * QA3B-12: {@code load-iceberg.sh} (and its PowerShell twin, which run the same loader) ends a failed load with one clear line, never a Java
 * stack trace: a REST catalog whose bucket is missing names the bucket, the endpoint and what to check.
 */
class IcebergLoaderFailureTest {

    @Test
    void aMissingBucketNamesTheBucketTheEndpointAndWhatToCheck() {
        Throwable qa = new UncheckedIOException(new IOException("createTable book: HTTP 500 NoSuchBucketException: The specified bucket does not exist"));
        String line = IcebergLoader.oneLine(qa, Map.of("catalog", "rest", "uri", "http://localhost:8181", "warehouse", "s3://warehouse/",
                "s3.endpoint", "http://localhost:9090"));
        assertThat(line).startsWith("the bucket 'warehouse' does not exist at http://localhost:9090 (said by the REST catalog at http://localhost:8181)")
                .contains("create it, or check --warehouse, --set s3.endpoint").contains("NoSuchBucketException").doesNotContain("\n");
        assertThat(IcebergLoader.oneLine(qa, Map.of("root", "s3a://risk-lake/iceberg"))).startsWith("the bucket 'risk-lake' does not exist at AWS S3");
        assertThat(IcebergLoader.oneLine(new IllegalStateException("3 rows not loaded: their business date is after 2026-10-21"), Map.of()))
                .startsWith("the load failed: 3 rows not loaded");
    }

    @Test
    void theLoaderEndsAFailureWithOneLineAndStatusOne() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();                                          // nothing listens once it is closed
        }
        Path dir = Files.createTempDirectory("drishti-iceberg-loader-failure");
        Path lines = dir.resolve("rows.jsonl");
        Files.writeString(lines, "{\"domain\":\"credit\",\"kind\":\"book\",\"id\":\"B-1\",\"date\":\"2026-09-30\",\"doc\":\"{}\",\"columns\":{}}\n");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"), IcebergLoader.class.getName(), lines.toString(),
                "--catalog", "rest", "--uri", "http://127.0.0.1:" + port, "--set", "s3.endpoint=http://127.0.0.1:" + port, "--spill-dir",
                dir.resolve("spill").toString())).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(1);
        List<String> said = err.lines().filter(l -> l.startsWith("iceberg: ") && !l.contains("rows read")).toList();
        assertThat(said).as(err).hasSize(1);
        assertThat(said.get(0)).startsWith("iceberg: ").contains("127.0.0.1:" + port);
        assertThat(said.get(0)).as("the reason in words, never ': null' (QA4-07)").doesNotContain(": null").doesNotContain("(null");
        assertThat(err).as("no stack trace").doesNotContain("Exception in thread").doesNotContain("\tat ");
    }

    /** QA4-07: a connection failure without a message (a ConnectException over a ClosedChannelException) reads as words, never ": null". */
    @Test
    void aFailureWithoutAMessageIsSaidInWords() {
        var refused = new java.net.ConnectException();
        refused.initCause(new java.nio.channels.ClosedChannelException());
        Throwable qa = new UncheckedIOException("cannot reach the REST catalog at http://127.0.0.1:1: " + com.ash.drishti.iceberg.io.Reasons.of(new IOException(refused)),
                new IOException(refused));
        String line = IcebergLoader.oneLine(qa, Map.of("catalog", "rest", "uri", "http://127.0.0.1:1"));
        assertThat(line).startsWith("cannot reach the REST catalog at http://127.0.0.1:1 (the connection was refused or closed before an answer "
                + "(ClosedChannelException)): check --uri").doesNotContain("null");
        assertThat(com.ash.drishti.iceberg.io.Reasons.of(new IOException(refused))).isEqualTo("the connection was refused or closed before an answer (ClosedChannelException)");
        assertThat(com.ash.drishti.iceberg.io.Reasons.of(new RuntimeException("null", new IllegalStateException("the real reason")))).isEqualTo("the real reason");
        assertThat(IcebergLoader.oneLine(new java.nio.channels.ClosedChannelException(), Map.of())).startsWith("the load failed: the connection was closed")
                .doesNotContain("null");
    }
}
