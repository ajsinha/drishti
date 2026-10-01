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
package com.ash.drishti.plugin.delta;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The same contract as the local lake, with the lake in object storage: the fixture lake is uploaded to an S3 API
 * server (Adobe S3Mock) in Docker and read through {@code s3a://}, by the native engine (AWS SDK) here and by Hadoop's
 * S3A in {@link DeltaOnS3HadoopTest}. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class DeltaOnS3Test extends DatedSourceContract {

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>(
            "adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92")
            .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));

    private static final java.util.Map<String, DeltaSourcePlugin> PLUGINS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The Delta engine these tests read with. */
    protected String engine() {
        return "native";
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        DeltaSourcePlugin cached = PLUGINS.get(engine());
        if (cached != null) {
            return cached;
        }
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
        try (S3Client c = S3Client.builder().endpointOverride(URI.create(endpoint)).forcePathStyle(true).region(Region.US_EAST_1)
                .httpClient(ApacheHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s"))).build()) {
            if (c.listBuckets().buckets().stream().noneMatch(b -> b.name().equals("lakes"))) {
                c.createBucket(b -> b.bucket("lakes"));
            }
            Path src = Path.of("src/test/resources/lake");
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String key = "banking/" + src.relativize(f).toString().replace('\\', '/');
                    c.putObject(b -> b.bucket("lakes").key(key), RequestBody.fromFile(f));
                }
            }
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", "s3a://lakes/banking", "domain", "desk", "mode.counterparty", "effective", "source-name", "s3-lake",
                "s3.endpoint", endpoint, "s3.access-key", "k", "s3.secret-key", "s", "s3.region", "us-east-1", "engine", engine())));
        PLUGINS.put(engine(), p);
        return p;
    }
}
