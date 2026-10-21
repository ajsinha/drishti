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
package com.ash.drishti.identity.content;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** The S3 backend against real MinIO (Testcontainers), opt-in: {@code DRISHTI_TEST_MINIO=1} and the {@code minio/minio} image pullable. */
@EnabledIfEnvironmentVariable(named = "DRISHTI_TEST_MINIO", matches = "1|true")
class MinioContentStoreTest extends ContentStoreContract {

    private static GenericContainer<?> minio;
    private String prefix;

    @BeforeAll
    @SuppressWarnings("resource")
    static void up() {
        minio = new GenericContainer<>(System.getenv().getOrDefault("DRISHTI_TEST_MINIO_IMAGE", "minio/minio:latest"))
                .withEnv("MINIO_ROOT_USER", "drishti").withEnv("MINIO_ROOT_PASSWORD", "drishti-secret").withCommand("server", "/data")
                .withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();
        try (S3Client c = S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("drishti", "drishti-secret"))).build()) {
            c.createBucket(b -> b.bucket("content"));
        }
    }

    @AfterAll
    static void down() {
        minio.stop();
    }

    private static String endpoint() {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    @Override
    protected ContentStore open() {
        prefix = "t-" + UUID.randomUUID();
        return reopen();
    }

    @Override
    protected ContentStore reopen() {
        return new S3ContentStore(new S3ContentStore.Settings("content", prefix, "us-east-1", endpoint(), true, "drishti", "drishti-secret"));
    }
}
