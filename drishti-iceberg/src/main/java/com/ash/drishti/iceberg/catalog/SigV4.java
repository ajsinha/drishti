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
package com.ash.drishti.iceberg.catalog;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

/**
 * SigV4 request signing for REST catalogs behind AWS authentication (AWS Glue's Iceberg endpoint, an API Gateway),
 * with the AWS SDK's own signer: {@code catalog.rest.sigv4-enabled: true}, {@code catalog.rest.signing-region} and
 * {@code catalog.rest.signing-name} ({@code glue} for Glue; default {@code execute-api}), Iceberg's property names.
 * Credentials: {@code catalog.rest.access-key-id}, {@code catalog.rest.secret-access-key} (and
 * {@code catalog.rest.session-token}), else the AWS chain (environment, profile, instance role). Thread-safe.
 */
final class SigV4 {

    /** Headers the JDK client sets itself and refuses from callers. */
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");

    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();
    private final AwsCredentialsProvider credentials;
    private final String region;
    private final String service;

    private SigV4(AwsCredentialsProvider credentials, String region, String service) {
        this.credentials = credentials;
        this.region = region;
        this.service = service;
    }

    static SigV4 from(Map<String, String> props) {
        String key = props.getOrDefault("rest.access-key-id", "");
        String secret = props.getOrDefault("rest.secret-access-key", "");
        String token = props.getOrDefault("rest.session-token", "");
        AwsCredentialsProvider p = key.isBlank() ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(token.isBlank() ? AwsBasicCredentials.create(key, secret) : AwsSessionCredentials.create(key, secret, token));
        String region = props.getOrDefault("rest.signing-region", props.getOrDefault("client.region", "us-east-1"));
        return new SigV4(p, region, props.getOrDefault("rest.signing-name", "execute-api"));
    }

    /** Adds the signature headers ({@code Authorization}, {@code X-Amz-Date}, {@code X-Amz-Content-Sha256}, …) to the request. */
    void sign(HttpRequest.Builder b, String method, URI uri, byte[] body, Map<String, String> headers) {
        SdkHttpRequest.Builder req = SdkHttpRequest.builder().method(SdkHttpMethod.fromValue(method)).uri(uri);
        headers.forEach(req::putHeader);
        req.putHeader("Accept", "application/json");
        if (body != null) {
            req.putHeader("Content-Type", "application/json");
        }
        SignedRequest signed = signer.sign(r -> r.identity(credentials.resolveCredentials()).request(req.build())
                .payload(body == null ? null : ContentStreamProvider.fromByteArray(body))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service).putProperty(AwsV4HttpSigner.REGION_NAME, region));
        for (Map.Entry<String, List<String>> h : signed.request().headers().entrySet()) {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            if (RESTRICTED.contains(name) || name.equals("accept") || name.equals("content-type") || headers.containsKey(h.getKey())) {
                continue;
            }
            for (String v : h.getValue()) {
                b.setHeader(h.getKey(), v);
            }
        }
    }
}
