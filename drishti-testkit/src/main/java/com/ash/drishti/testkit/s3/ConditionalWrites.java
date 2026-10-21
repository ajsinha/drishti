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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Which conditional writes an S3 API server honours, found by trying them: create-only ({@code If-None-Match: *} on a key
 * that exists), update-if-match ({@code If-Match} with a stale ETag) and delete-if-match ({@code DeleteObject} with
 * {@code If-Match}). Each is honoured when the server refuses the stale write (412 or 409) and lets the right one through.
 * Racing creates are tried too (eight writers at once, five rounds): atomic when each round has exactly one winner.
 * A test skips what its simulator does not honour and prints {@link #report()}, so each suite says which simulator covered what.
 *
 * @param simulator     the simulator's name
 * @param createOnly    {@code If-None-Match: *} refuses a key that exists
 * @param updateIfMatch {@code If-Match} refuses a stale ETag on a put
 * @param deleteIfMatch {@code If-Match} refuses a stale ETag on a delete
 * @param atomic        racing conditional creates have exactly one winner
 */
public record ConditionalWrites(String simulator, boolean createOnly, boolean updateIfMatch, boolean deleteIfMatch, boolean atomic) {

    /** Tries the three conditional writes on {@code bucket} (under a fresh {@code probe/} key) and says which are honoured. */
    public static ConditionalWrites probe(S3Endpoint e, String bucket) {
        String key = "probe/" + UUID.randomUUID();
        try (S3Client c = e.client()) {
            String v1 = c.putObject(p -> p.bucket(bucket).key(key).ifNoneMatch("*"), RequestBody.fromString("one")).eTag();
            boolean create = refused(() -> c.putObject(p -> p.bucket(bucket).key(key).ifNoneMatch("*"), RequestBody.fromString("again")));
            String v2 = c.putObject(p -> p.bucket(bucket).key(key).ifMatch(v1), RequestBody.fromString("two")).eTag();
            boolean update = refused(() -> c.putObject(p -> p.bucket(bucket).key(key).ifMatch(v1), RequestBody.fromString("stale")));
            boolean delete = refused(() -> c.deleteObject(d -> d.bucket(bucket).key(key).ifMatch(v1)));
            if (delete) {
                delete = !refused(() -> c.deleteObject(d -> d.bucket(bucket).key(key).ifMatch(v2)));
            }
            c.deleteObject(d -> d.bucket(bucket).key(key));
            boolean atomic = create;
            for (int round = 0; atomic && round < 5; round++) {
                atomic = race(c, bucket, key + "-race-" + round) == 1;
            }
            return new ConditionalWrites(e.simulator(), create, update, delete, atomic);
        }
    }

    private static int race(S3Client c, String bucket, String key) {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String body = "writer " + i;
                fs.add(pool.submit(() -> {
                    go.await();
                    return !refused(() -> c.putObject(p -> p.bucket(bucket).key(key).ifNoneMatch("*"), RequestBody.fromString(body)));
                }));
            }
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : fs) {
                wins += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            c.deleteObject(d -> d.bucket(bucket).key(key));
            return wins;
        } catch (Exception ex) {
            throw new IllegalStateException("racing creates on " + key + ": " + ex, ex);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean refused(Runnable r) {
        try {
            r.run();
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 412 || ex.statusCode() == 409) {
                return true;
            }
            throw ex;
        }
    }

    /** Why a case is skipped on this simulator. */
    public String notHonoured(String what) {
        return simulator + " does not honour " + what + "; the in-process stand-in (FakeS3) and MinIO cover it";
    }

    /** The report line: which simulator covered create-only, update-if-match and delete-if-match. */
    public String report() {
        return "[S3 conditional writes] " + simulator + ": create-only " + yes(createOnly) + ", update-if-match " + yes(updateIfMatch)
                + ", delete-if-match " + yes(deleteIfMatch) + ", racing writers " + (atomic ? "atomic (covered)" : "NOT atomic (skipped)");
    }

    private static String yes(boolean b) {
        return b ? "covered" : "NOT honoured (skipped)";
    }
}
