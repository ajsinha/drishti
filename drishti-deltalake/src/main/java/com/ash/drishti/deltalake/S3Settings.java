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
package com.ash.drishti.deltalake;

import java.util.Map;

/**
 * How the engine reaches S3 or an S3-compatible store (MinIO, Ceph, S3Mock): the Delta connector's own settings
 * ({@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key}, {@code s3.region}, {@code s3.path-style}), the
 * same ones its Hadoop engine maps to {@code fs.s3a.*}. Without keys, credentials come from the AWS chain
 * (environment, profile, instance role).
 *
 * @param endpoint an endpoint URL, or blank for AWS
 * @param accessKey the access key, or blank for the AWS credential chain
 * @param secretKey the secret key
 * @param region the region, or blank for the AWS region chain (then {@code us-east-1})
 * @param pathStyle path-style addressing ({@code http://host/bucket/key}), the default with an endpoint
 * @param readBlockBytes the smallest ranged GET: small reads (a Parquet footer, a deletion vector) fetch this much
 */
public record S3Settings(String endpoint, String accessKey, String secretKey, String region, boolean pathStyle, int readBlockBytes) {

    /** The default smallest ranged GET: 1 MiB. */
    public static final int DEFAULT_READ_BLOCK = 1 << 20;

    /** From connector settings ({@code s3.*}); {@code s3.read-block-kb} sets the smallest ranged GET. */
    public static S3Settings from(Map<String, String> settings) {
        String endpoint = settings.getOrDefault("s3.endpoint", "").trim();
        boolean pathStyle = Boolean.parseBoolean(settings.getOrDefault("s3.path-style", String.valueOf(!endpoint.isEmpty())));
        int block = Integer.parseInt(settings.getOrDefault("s3.read-block-kb", String.valueOf(DEFAULT_READ_BLOCK / 1024))) * 1024;
        return new S3Settings(endpoint, settings.getOrDefault("s3.access-key", "").trim(), settings.getOrDefault("s3.secret-key", ""),
                settings.getOrDefault("s3.region", "").trim(), pathStyle, Math.max(4096, block));
    }

    @Override
    public String toString() {                                   // never the secret
        return "S3Settings[endpoint=" + endpoint + ", region=" + region + ", pathStyle=" + pathStyle + ", keys="
                + (accessKey.isEmpty() ? "chain" : "static") + "]";
    }
}
