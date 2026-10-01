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
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The same contract with the tables in an Iceberg REST catalog (the Iceberg project's REST fixture, as Polaris,
 * Snowflake Open Catalog or Glue's REST endpoint would serve them) and their files in S3 (Adobe S3Mock), read through
 * S3FileIO. The writer is the loader's code path ({@link IcebergLayout} through {@link IcebergLake}). Skipped where
 * Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class IcebergRestCatalogTest extends DatedSourceContract {

    static final Network NET = Network.newNetwork();

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>(
            "adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92")
            .withNetwork(NET).withNetworkAliases("s3").withEnv("initialBuckets", "warehouse").withCreateContainerCmdModifier(IcebergRestCatalogTest::oneGb)
            .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));

    @Container
    static final GenericContainer<?> CATALOG = new GenericContainer<>("apache/iceberg-rest-fixture:1.10.1")
            .withNetwork(NET).dependsOn(S3).withExposedPorts(8181).withCreateContainerCmdModifier(IcebergRestCatalogTest::oneGb)
            .withEnv("CATALOG_WAREHOUSE", "s3://warehouse/")
            .withEnv("CATALOG_IO__IMPL", "org.apache.iceberg.aws.s3.S3FileIO")
            .withEnv("CATALOG_S3_ENDPOINT", "http://s3:9090")
            .withEnv("CATALOG_S3_PATH__STYLE__ACCESS", "true")
            .withEnv("AWS_REGION", "us-east-1").withEnv("AWS_ACCESS_KEY_ID", "k").withEnv("AWS_SECRET_ACCESS_KEY", "s")
            .waitingFor(Wait.forHttp("/v1/config").forPort(8181).forStatusCode(200));

    private static IcebergSourcePlugin plugin;

    /** Each container is capped at 1 GB: the test machine is shared. */
    static void oneGb(com.github.dockerjava.api.command.CreateContainerCmd cmd) {
        cmd.getHostConfig().withMemory(1024L * 1024 * 1024);
    }

    static Map<String, String> settings() {
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
        Map<String, String> s = new HashMap<>();
        s.put("catalog", "rest");
        s.put("uri", "http://" + CATALOG.getHost() + ":" + CATALOG.getMappedPort(8181));
        s.put("domain", "desk");
        s.put("s3.endpoint", endpoint);
        s.put("s3.access-key", "k");
        s.put("s3.secret-key", "s");
        s.put("s3.region", "us-east-1");
        return s;
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        try (S3Client c = S3Client.builder().endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090))).forcePathStyle(true)
                .region(Region.US_EAST_1).httpClient(ApacheHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s"))).build()) {
            if (c.listBuckets().buckets().stream().noneMatch(b -> b.name().equals("warehouse"))) {
                c.createBucket(b -> b.bucket("warehouse"));
            }
        }
        Map<String, Boolean> trade = new LinkedHashMap<>();
        trade.put("mtm", true);
        trade.put("nettingSet", false);
        try (IcebergLake lake = IcebergLake.of(settings())) {
            IcebergSourcePluginTest.write(lake, ROWS, Map.of("trade", trade));
        }
        Map<String, String> s = settings();
        s.putAll(Map.of("mode.counterparty", "effective", "source-name", "rest-iceberg", "layout.trade.columns", "mtm,nettingSet"));
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(context(s));
        plugin = p;
        return p;
    }

    @Test
    void columnsAndOneEntityComeThroughTheCatalogFromObjectStorage() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D2)).orElseThrow().size()).isEqualTo(2);
        assertThat(p.fetch(EntityRef.of("trade", "T-2")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(-120);
        assertThat(p.health()).isEqualTo("UP");
    }
}
