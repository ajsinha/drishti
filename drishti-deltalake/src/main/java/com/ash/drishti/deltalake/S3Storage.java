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

import io.delta.kernel.utils.FileStatus;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3 and S3-compatible stores through the AWS SDK v2 (no Hadoop): {@code s3://}, {@code s3a://} and {@code s3n://}
 * URIs, listed with {@code ListObjectsV2} and read with ranged GETs. One client, shared and thread-safe.
 */
public final class S3Storage implements Storage, AutoCloseable {

    private final S3Client client;
    private final int blockBytes;

    public S3Storage(S3Settings settings) {
        this(client(settings), settings.readBlockBytes());
    }

    S3Storage(S3Client client, int blockBytes) {
        this.client = client;
        this.blockBytes = blockBytes;
    }

    private static S3Client client(S3Settings s) {
        Apache5HttpClient.Builder http = Apache5HttpClient.builder().maxConnections(64).connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(60));
        if (s.tls() != null) {
            // the shared module's trust, client certificate, protocols and cipher suites; the name check stays unless turned off
            var p = s.tls().sslParameters();
            http.tlsSocketStrategy(new org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy(s.tls().sslContext(), p.getProtocols(),
                    p.getCipherSuites(), org.apache.hc.core5.reactor.ssl.SSLBufferMode.STATIC,
                    s.tls().settings().verifyHostname() ? null : org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE));
        }
        S3ClientBuilder b = S3Client.builder()
                .httpClientBuilder(http)
                // S3-compatible stores do not all speak the SDK's newer default checksums
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .region(region(s.region()));
        if (!s.endpoint().isEmpty()) {
            b.endpointOverride(URI.create(s.endpoint()));
        }
        b.forcePathStyle(s.pathStyle());
        b.credentialsProvider(s.accessKey().isEmpty() ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKey(), s.secretKey())));
        return b.build();
    }

    private static Region region(String configured) {
        if (!configured.isEmpty()) {
            return Region.of(configured);
        }
        try {
            return DefaultAwsRegionProviderChain.builder().build().getRegion();
        } catch (RuntimeException e) {
            return Region.US_EAST_1;                             // an endpoint without a region (MinIO): any will do
        }
    }

    /** {@code s3a://bucket/a/b}: scheme {@code s3a}, bucket, key {@code a/b} (no leading or trailing slash). */
    record Location(String scheme, String bucket, String key) {
        static Location parse(String path) {
            int sep = path.indexOf("://");
            if (sep < 0) {
                throw new IllegalArgumentException("not an S3 URI: " + path);
            }
            String scheme = path.substring(0, sep).toLowerCase(Locale.ROOT);
            String rest = path.substring(sep + 3);
            int slash = rest.indexOf('/');
            String bucket = slash < 0 ? rest : rest.substring(0, slash);
            String key = slash < 0 ? "" : rest.substring(slash + 1);
            while (key.endsWith("/")) {
                key = key.substring(0, key.length() - 1);
            }
            if (bucket.isEmpty()) {
                throw new IllegalArgumentException("no bucket in " + path);
            }
            return new Location(scheme, bucket, key);
        }

        String uri(String k) {
            return scheme + "://" + bucket + (k.isEmpty() ? "" : "/" + k);
        }

        String prefix() {
            return key.isEmpty() ? "" : key + "/";
        }
    }

    @Override
    public String qualify(String path) {
        Location l = Location.parse(path);
        return l.uri(l.key());
    }

    @Override
    public List<FileStatus> listFrom(String path) throws IOException {
        Location l = Location.parse(path);
        int slash = l.key().lastIndexOf('/');
        String dirPrefix = slash < 0 ? "" : l.key().substring(0, slash + 1);
        String startKey = l.key();
        List<FileStatus> out = new ArrayList<>();
        try {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(l.bucket()).prefix(dirPrefix).delimiter("/");
            String after = before(startKey);
            if (after.compareTo(dirPrefix) > 0) {
                req.startAfter(after);                           // S3's start is exclusive: begin just before the key
            }
            for (ListObjectsV2Response page : client.listObjectsV2Paginator(req.build())) {
                for (S3Object o : page.contents()) {
                    if (o.key().compareTo(startKey) >= 0 && !o.key().equals(dirPrefix)) {
                        out.add(FileStatus.of(l.uri(o.key()), o.size(), o.lastModified().toEpochMilli()));
                    }
                }
            }
            if (out.isEmpty() && !exists(l.bucket(), dirPrefix)) {
                throw new FileNotFoundException("no such directory: " + l.uri(dirPrefix));
            }
        } catch (S3Exception e) {
            throw io(path, e);
        }
        return out;                                              // S3 lists keys in order
    }

    /** The largest string before {@code key} that S3 can take as a start-after (shortened, so it is always valid). */
    private static String before(String key) {
        if (key.isEmpty()) {
            return "";
        }
        char last = key.charAt(key.length() - 1);
        return last == 0 ? key.substring(0, key.length() - 1) : key.substring(0, key.length() - 1) + (char) (last - 1);
    }

    private boolean exists(String bucket, String prefix) {
        return client.listObjectsV2(b -> b.bucket(bucket).prefix(prefix).maxKeys(1)).keyCount() > 0;
    }

    @Override
    public FileStatus status(String path) throws IOException {
        Location l = Location.parse(path);
        try {
            HeadObjectResponse h = client.headObject(b -> b.bucket(l.bucket()).key(l.key()));
            return FileStatus.of(path, h.contentLength(), h.lastModified().toEpochMilli());
        } catch (NoSuchKeyException e) {
            throw missing(path, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw missing(path, e);
            }
            throw io(path, e);
        }
    }

    @Override
    public RangeReader open(String path, long knownLength) throws IOException {
        Location l = Location.parse(path);
        long length = knownLength >= 0 ? knownLength : status(path).getSize();
        return new ObjectReader(l, length);
    }

    @Override
    public List<String> directories(String path) throws IOException {
        Location l = Location.parse(path);
        List<String> out = new ArrayList<>();
        try {
            for (ListObjectsV2Response page : client.listObjectsV2Paginator(b -> b.bucket(l.bucket()).prefix(l.prefix()).delimiter("/"))) {
                for (CommonPrefix p : page.commonPrefixes()) {
                    String name = p.prefix().substring(l.prefix().length());
                    out.add(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                }
            }
        } catch (NoSuchKeyException e) {
            return List.of();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return List.of();                                // no such bucket
            }
            throw io(path, e);
        }
        out.sort(null);
        return out;
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        Location l = Location.parse(path);
        try {
            return exists(l.bucket(), l.prefix());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw io(path, e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static FileNotFoundException missing(String path, Exception cause) {
        FileNotFoundException e = new FileNotFoundException("no such object: " + path);
        e.initCause(cause);
        return e;
    }

    private static IOException io(String path, S3Exception e) {
        return new IOException("S3 " + e.statusCode() + " for " + path + ": " + e.awsErrorDetails().errorMessage(), e);
    }

    /**
     * Ranged GETs of one object. A read smaller than a block fetches a whole block and serves the next small reads
     * from it (Parquet reads a footer's length, then the footer); a large read (a column chunk) is one GET of exactly
     * its range. Used by one thread at a time, like the stream it backs.
     */
    private final class ObjectReader implements RangeReader {
        private final Location location;
        private final long length;
        private byte[] block = new byte[0];
        private long blockStart = -1;

        ObjectReader(Location location, long length) {
            this.location = location;
            this.length = length;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public int read(long position, ByteBuffer dst) throws IOException {
            if (position >= length) {
                return -1;
            }
            int want = (int) Math.min(dst.remaining(), length - position);
            if (want == 0) {
                return 0;
            }
            if (blockStart >= 0 && position >= blockStart && position < blockStart + block.length) {
                int n = (int) Math.min(want, blockStart + block.length - position);
                dst.put(block, (int) (position - blockStart), n);
                return n;
            }
            if (want >= blockBytes) {
                byte[] direct = get(position, want);
                dst.put(direct);
                return direct.length == 0 ? -1 : direct.length;
            }
            // a footer is read from its end backwards: a block that ends at the file's end serves both reads
            long start = position + blockBytes > length ? Math.max(0, length - blockBytes) : position;
            block = get(start, (int) Math.min(blockBytes, length - start));
            blockStart = start;
            if (blockStart + block.length <= position) {
                return -1;                                       // the object is shorter than the log said
            }
            int n =(int) Math.min(want, blockStart + block.length - position);
            dst.put(block, (int) (position - blockStart), n);
            return n;
        }

        private byte[] get(long start, int count) throws IOException {
            String range = "bytes=" + start + "-" + (start + count - 1);
            try (ResponseInputStream<GetObjectResponse> in = client.getObject(b -> b.bucket(location.bucket()).key(location.key()).range(range))) {
                return readAll(in, count);
            } catch (NoSuchKeyException e) {
                throw missing(location.uri(location.key()), e);
            } catch (S3Exception e) {
                throw io(location.uri(location.key()), e);
            }
        }

        @Override
        public void close() {
            block = new byte[0];
            blockStart = -1;
        }
    }

    private static byte[] readAll(InputStream in, int count) throws IOException {
        byte[] out = new byte[count];
        int at = 0;
        while (at < count) {
            int n = in.read(out, at, count - at);
            if (n < 0) {
                break;
            }
            at += n;
        }
        if (at < count) {
            byte[] shorter = new byte[at];
            System.arraycopy(out, 0, shorter, 0, at);
            return shorter;
        }
        return out;
    }
}
