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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.write.Operations;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Path-based tables in S3 on the native engine: the loader writes them through the AWS SDK (no Hadoop, no S3A), the
 * connector reads them, and commits are conditional PUTs: on MinIO, which honours {@code If-None-Match}, writers
 * committing at once lose nothing; on a store that does not (the probe at the first commit finds it), the table is
 * single-writer and commits still work. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class NativeOnS3Test {

    static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z";

    @Container
    static final GenericContainer<?> S3MOCK = new GenericContainer<>(
            "adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92")
            .withEnv("initialBuckets", "lake").withCreateContainerCmdModifier(IcebergRestCatalogTest::oneGb)
            .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));

    static Map<String, String> settings(GenericContainer<?> c, int port, String user, String secret) {
        Map<String, String> s = new HashMap<>();
        s.put("root", "s3://lake/warehouse");
        s.put("domain", "desk");
        s.put("engine", "native");
        s.put("s3.endpoint", "http://" + c.getHost() + ":" + c.getMappedPort(port));
        s.put("s3.access-key", user);
        s.put("s3.secret-key", secret);
        s.put("s3.region", "us-east-1");
        return s;
    }

    static void bucket(Map<String, String> s) {
        try (S3Client c = S3Client.builder().endpointOverride(URI.create(s.get("s3.endpoint"))).forcePathStyle(true).region(Region.US_EAST_1)
                .httpClient(ApacheHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s.get("s3.access-key"), s.get("s3.secret-key")))).build()) {
            if (c.listBuckets().buckets().stream().noneMatch(b -> b.name().equals("lake"))) {
                c.createBucket(b -> b.bucket("lake"));
            }
        }
    }

    /** MinIO's image when Docker already has it (it is not pulled: the test machine may have no registry access). */
    static boolean minioPresent() {
        try {
            org.testcontainers.DockerClientFactory.instance().client().inspectImageCmd(MINIO_IMAGE).exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void theLoaderWritesAndTheConnectorReadsATableInS3() throws Exception {
        // S3Mock refuses a second conditional create of a key (the probe passes) but not two racing ones, so writers at once
        // are tested on MinIO only (as on AWS S3, its conditional writes are atomic)
        assertThat(loadReadAndCommitAtOnce(settings(S3MOCK, 9090, "k", "s"), false)).isTrue();
    }

    @Test
    void onMinioWritersCommittingAtOnceLoseNothing() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(minioPresent(), MINIO_IMAGE + " is not in Docker here");
        try (GenericContainer<?> minio = new GenericContainer<>(MINIO_IMAGE).withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "drishti").withEnv("MINIO_ROOT_PASSWORD", "drishti-secret").withExposedPorts(9000)
                .withCreateContainerCmdModifier(IcebergRestCatalogTest::oneGb).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))) {
            minio.start();
            assertThat(loadReadAndCommitAtOnce(settings(minio, 9000, "drishti", "drishti-secret"), true)).as("MinIO honours If-None-Match").isTrue();
        }
    }

    /** The loader, the connector, then (when the store honours conditional writes) three writers at once; returns whether it does. */
    static boolean loadReadAndCommitAtOnce(Map<String, String> s, boolean atOnce) throws Exception {
        bucket(s);
        Path lines = Files.createTempDirectory("drishti-iceberg-s3").resolve("rows.jsonl");
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add("{\"domain\":\"desk\",\"kind\":\"trade\",\"id\":\"T-" + i + "\",\"date\":\"2026-09-30\",\"doc\":\"{\\\"mtm\\\":" + i
                    + "}\",\"columns\":{\"mtm\":" + i + "}}");
        }
        Files.write(lines, rows);
        List<String> args = new ArrayList<>(List.of(lines.toString(), "s3://lake/warehouse", "--engine", "native", "--file-rows", "8",
                "--spill-dir", lines.getParent().resolve("spill").toString()));
        for (String k : List.of("s3.endpoint", "s3.access-key", "s3.secret-key", "s3.region")) {
            args.addAll(List.of("--set", k + "=" + s.get(k)));
        }
        IcebergLoader.main(args.toArray(new String[0]));
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        Map<String, String> read = new HashMap<>(s);
        read.put("layout.trade.columns", "mtm");
        p.start(DatedSourceContract.context(read));
        try {
            assertThat(p.fetch(EntityRef.of("trade", "T-7")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(7);
            assertThat(p.columns("trade", List.of("mtm"), AsOf.LATEST).orElseThrow().size()).isEqualTo(20);
            assertThat(p.health()).isEqualTo("UP");
        } finally {
            p.close();
        }
        boolean exclusive;
        try (Catalog c = Catalogs.open(s)) {
            Table t = c.load("trade").orElseThrow();
            Operations.setProperties(t, Map.of("drishti.probe", "1"));          // a commit, so the probe has run
            exclusive = t.ops().exclusiveCommits();
        }
        if (!exclusive || !atOnce) {
            System.out.println(exclusive ? "writers at once are not tried on this store" : "the store does not honour conditional writes: the table is single-writer");
            return exclusive;
        }
        int writers = 3;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                done.add(pool.submit(() -> {
                    try (Catalog c = Catalogs.open(s)) {
                        Table t = c.load("trade").orElseThrow();
                        start.await();
                        for (int i = 0; i < 3; i++) {
                            LocalDate day = LocalDate.of(2026, 8, 1 + me * 3 + i);
                            Operations.append(t, NativeLayout.writeDay(t, day, NativeCommitTest.rows("W" + me, 5).iterator(), 100), Map.of());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        }
        try (Catalog c = Catalogs.open(s); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Table t = c.load("trade").orElseThrow();
            assertThat(NativeCommitTest.liveRows(t, pool)).isEqualTo(20 + writers * 3 * 5L);
            assertThat(t.metadata().snapshots()).hasSize(1 + writers * 3);
        }
        return true;
    }

    @Test
    void theCommitProbeDecidesWhetherATableIsSingleWriter() throws Exception {
        Map<String, String> s = settings(S3MOCK, 9090, "k", "s");
        s.put("domain", "probe");
        bucket(s);
        try (Catalog c = Catalogs.open(s); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Table t = NativeLayout.open(c, "trade", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 100);
            LocalDate day = LocalDate.of(2026, 9, 30);
            NativeLayout.commitDays(t, Map.of(day, NativeLayout.writeDay(t, day, NativeCommitTest.rows("A", 4).iterator(), 100)));
            assertThat(NativeCommitTest.liveRows(t, pool)).isEqualTo(4);
            boolean exclusive = t.ops().exclusiveCommits();
            System.out.println("S3Mock honours conditional writes: " + exclusive);
            assertThat(t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool)).hasSize(1);
        }
    }
}
