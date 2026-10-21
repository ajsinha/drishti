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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The S3 content store: objects {@code <prefix>/items/<key>}, the version is the ETag. Compare-and-set is the conditional PUT
 * ({@code If-None-Match: *} to create, {@code If-Match: <etag>} to update); {@code 412} or {@code 409} is {@code DRS-5057}. A delete compares
 * the ETag (a HEAD, then {@code If-Match}) and leaves a tombstone {@code <prefix>/tombstones/<key>} for {@link #changes}.
 */
public final class S3ContentStore implements ContentStore {

    /**
     * Where the store is.
     *
     * @param endpoint empty for AWS; else MinIO or another S3-compatible store
     */
    public record Settings(String bucket, String prefix, String region, String endpoint, boolean pathStyle, String accessKey, String secretKey) {}

    private final S3Client s3;
    private final String bucket;
    private final String items;
    private final String tombstones;
    private final String endpoint;

    public S3ContentStore(Settings s) {
        S3ClientBuilder b = S3Client.builder()
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(32).connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .region(Region.of(s.region() == null || s.region().isBlank() ? "us-east-1" : s.region()))
                .forcePathStyle(s.pathStyle());
        if (s.endpoint() != null && !s.endpoint().isBlank()) {
            b.endpointOverride(URI.create(s.endpoint()));
        }
        b.credentialsProvider(s.accessKey() == null || s.accessKey().isBlank() ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKey(), s.secretKey())));
        this.s3 = b.build();
        this.bucket = s.bucket();
        String p = s.prefix() == null ? "" : s.prefix().replaceAll("^/+|/+$", "");
        this.items = (p.isEmpty() ? "" : p + "/") + "items/";
        this.tombstones = (p.isEmpty() ? "" : p + "/") + "tombstones/";
        this.endpoint = s.endpoint() == null ? "" : s.endpoint();
    }

    @Override
    public String root() {
        return "s3 " + (endpoint.isEmpty() ? "" : endpoint + " ") + bucket + "/" + items.substring(0, items.length() - "items/".length());
    }

    @Override
    public Optional<Item> get(String key) {
        ContentStore.check(key);
        try {
            ResponseBytes<GetObjectResponse> r = s3.getObjectAsBytes(g -> g.bucket(bucket).key(items + key));
            return Optional.of(new Item(r.asByteArray(), r.response().eTag()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable(e);
        }
    }

    @Override
    public String put(String key, byte[] bytes, String expectedVersion) {
        ContentStore.check(key);
        try {
            String etag = s3.putObject(p -> {
                p.bucket(bucket).key(items + key);
                if (expectedVersion == null) {
                    p.ifNoneMatch("*");
                } else {
                    p.ifMatch(expectedVersion);
                }
            }, RequestBody.fromBytes(bytes)).eTag();
            deleteQuietly(tombstones + key);
            return etag;
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || e.statusCode() == 409 || (expectedVersion != null && e.statusCode() == 404)) {
                throw ContentStore.conflict(key);
            }
            throw unavailable(e);
        }
    }

    @Override
    public boolean delete(String key, String expectedVersion) {
        ContentStore.check(key);
        String now;
        try {
            now = s3.headObject(h -> h.bucket(bucket).key(items + key)).eTag();
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw unavailable(e);
        }
        if (!now.equals(expectedVersion)) {
            throw ContentStore.conflict(key);
        }
        try {
            s3.putObject(p -> p.bucket(bucket).key(tombstones + key), RequestBody.fromBytes(new byte[0]));
            s3.deleteObject(d -> d.bucket(bucket).key(items + key).ifMatch(expectedVersion));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || e.statusCode() == 409) {
                throw ContentStore.conflict(key);
            }
            throw unavailable(e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        List<String> out = new ArrayList<>();
        for (S3Object o : objects(items + prefix)) {
            out.add(o.key().substring(items.length()));
        }
        out.sort(null);
        return out;
    }

    @Override
    public Changes changes(long since) {
        List<String> out = new ArrayList<>();
        long cursor = since;
        for (String base : List.of(items, tombstones)) {
            for (S3Object o : objects(base)) {
                long m = o.lastModified().toEpochMilli();
                if (m > since) {
                    out.add(o.key().substring(base.length()));
                    cursor = Math.max(cursor, m);
                }
            }
        }
        out.sort(null);
        return new Changes(out.stream().distinct().toList(), cursor);
    }

    @Override
    public void close() {
        s3.close();
    }

    private List<S3Object> objects(String prefix) {
        List<S3Object> out = new ArrayList<>();
        String token = null;
        try {
            do {
                ListObjectsV2Request.Builder r = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (token != null) {
                    r.continuationToken(token);
                }
                ListObjectsV2Response page = s3.listObjectsV2(r.build());
                out.addAll(page.contents());
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (S3Exception e) {
            throw unavailable(e);
        }
        return out;
    }

    private void deleteQuietly(String k) {
        try {
            s3.deleteObject(d -> d.bucket(bucket).key(k));
        } catch (S3Exception e) {
            // a tombstone left behind only repeats a key in changes
        }
    }

    private static DrishtiException unavailable(S3Exception e) {
        return new DrishtiException(ErrorCode.CONTENT_UNAVAILABLE, "content store (s3): " + e.statusCode() + " " + e.getMessage(), e);
    }
}
