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

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
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
 * @param tls the shared TLS material ({@code tls.*}: a private CA, a client certificate) for an {@code https://} endpoint, or null
 */
public record S3Settings(String endpoint, String accessKey, String secretKey, String region, boolean pathStyle, int readBlockBytes,
        TlsMaterial tls) {

    /** Without TLS material: the JVM's trust, as before. */
    public S3Settings(String endpoint, String accessKey, String secretKey, String region, boolean pathStyle, int readBlockBytes) {
        this(endpoint, accessKey, secretKey, region, pathStyle, readBlockBytes, null);
    }

    /** The default smallest ranged GET: 1 MiB. */
    public static final int DEFAULT_READ_BLOCK = 1 << 20;

    /** From connector settings ({@code s3.*}); {@code s3.read-block-kb} sets the smallest ranged GET. */
    public static S3Settings from(Map<String, String> settings) {
        String endpoint = settings.getOrDefault("s3.endpoint", "").trim();
        boolean pathStyle = Boolean.parseBoolean(settings.getOrDefault("s3.path-style", String.valueOf(!endpoint.isEmpty())));
        int block = Integer.parseInt(settings.getOrDefault("s3.read-block-kb", String.valueOf(DEFAULT_READ_BLOCK / 1024))) * 1024;
        return new S3Settings(endpoint, settings.getOrDefault("s3.access-key", "").trim(), settings.getOrDefault("s3.secret-key", ""),
                settings.getOrDefault("s3.region", "").trim(), pathStyle, Math.max(4096, block), tls(settings, endpoint));
    }

    /**
     * The shared {@code tls.*} settings for this endpoint: an {@code https://} endpoint builds the module's context (a private CA,
     * a client certificate); {@code tls.*} against a plain or absent endpoint is a start-up error naming the setting.
     */
    static TlsMaterial tls(Map<String, String> settings, String endpoint) {
        TlsSettings ts = TlsSettings.from(settings);
        boolean https = endpoint.regionMatches(true, 0, "https://", 0, 8);
        if (!https) {
            if (ts.enabled()) {
                throw new TlsException("tls.enabled is true but s3.endpoint is not https:// (" + (endpoint.isEmpty() ? "not set" : endpoint) + ")");
            }
            if (TlsSettings.anyGiven(settings, "tls.")) {
                throw new TlsException("tls.* is set but s3.endpoint is not https://: set s3.endpoint to the store's https:// address");
            }
            return null;
        }
        return TlsContexts.build(ts);
    }

    @Override
    public String toString() {                                   // never the secret
        return "S3Settings[endpoint=" + endpoint + ", region=" + region + ", pathStyle=" + pathStyle + ", keys="
                + (accessKey.isEmpty() ? "chain" : "static") + "]";
    }
}
