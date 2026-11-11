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
package com.ash.drishti.testkit.s3;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * One S3 API server a test talks to: which simulator it is, its endpoint and its keys (path-style, {@code us-east-1}).
 *
 * @param simulator the simulator's name, for report lines ("S3Mock 4.12.4 (in-process)", "MinIO", ...)
 * @param endpoint  {@code http://127.0.0.1:<port>}
 * @param accessKey the access key
 * @param secretKey the secret key
 */
public record S3Endpoint(String simulator, String endpoint, String accessKey, String secretKey) {

    /** The ports the S3 simulators may take (the test port range 18750-18759). */
    public static final int FIRST_PORT = 18750;
    public static final int LAST_PORT = 18759;
    private static final AtomicInteger NEXT = new AtomicInteger(FIRST_PORT);

    /** A path-style client for this endpoint; the caller closes it. */
    public S3Client client() {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).forcePathStyle(true).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))).build();
    }

    /** Creates the bucket when it is not there. */
    public void bucket(String name) {
        try (S3Client c = client()) {
            if (c.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(name))) {
                c.createBucket(b -> b.bucket(name));
            }
        }
    }

    /** A free port in 18750-18759 not handed out before in this JVM; fails when the range is used up. */
    public static int freePort() {
        for (int p = NEXT.getAndIncrement(); p <= LAST_PORT; p = NEXT.getAndIncrement()) {
            try (ServerSocket s = new ServerSocket(p)) {
                s.setReuseAddress(true);
                return p;
            } catch (IOException busy) {
                // taken: try the next
            }
        }
        throw new IllegalStateException("no free port left in " + FIRST_PORT + "-" + LAST_PORT);
    }
}
