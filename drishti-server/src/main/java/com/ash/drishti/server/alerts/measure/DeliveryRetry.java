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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.common.RetryPolicy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Tries a failed alert delivery (email, webhook, text) again with the shared retry policy ({@code drishti.retry.alerts.*}), up to {@code max-attempts} in all. The first
 * attempt is made at once by the caller's thread, so a firing still tells the truth about the first try; the later ones wait on virtual threads and write their outcome
 * into the alert's history through a {@link Sink}. A delivery answers one of: {@code sent} (or anything not starting with {@code failed}), {@code failed: reason} (worth
 * trying again) or {@code failed (permanent): reason} (not: a refused address, a refused host, a 4xx). Pending retries are kept in memory only: a restart drops them and the
 * history keeps the last text it was given.
 */
final class DeliveryRetry implements AutoCloseable {

    /** What the first attempt left: the text for the history and how many attempts have been made. */
    record First(String text, int attempts) {}

    /** Where the outcome of a later attempt goes. */
    @FunctionalInterface
    interface Sink {
        void update(String channel, String text, int attempts);
    }

    private final RetryPolicy policy;
    private final int max;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    DeliveryRetry(RetryPolicy policy, int maxAttempts) {
        this.policy = policy;
        this.max = Math.max(1, maxAttempts);
    }

    static boolean failed(String r) {
        return r != null && r.startsWith("failed");
    }

    static boolean permanent(String r) {
        return r != null && r.startsWith("failed (permanent)");
    }

    static String reason(String r) {
        int i = r.indexOf(':');
        return i < 0 ? r : r.substring(i + 1).strip();
    }

    First run(String channel, Supplier<String> attempt, Sink sink) {
        String r = attempt.get();
        if (!failed(r)) {
            return new First(r, 1);
        }
        if (permanent(r) || !policy.enabled() || max <= 1) {
            return new First(permanent(r) ? r + " (not retried)" : r, 1);
        }
        Duration wait = delay(1, r);
        pool.execute(() -> later(channel, attempt, sink, wait));
        return new First("retrying: attempt 1 of " + max + " failed (" + reason(r) + "); next try in " + seconds(wait), 1);
    }

    private void later(String channel, Supplier<String> attempt, Sink sink, Duration firstWait) {
        Duration wait = firstWait;
        for (int n = 2; n <= max; n++) {
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String r = attempt.get();
            if (!failed(r)) {
                sink.update(channel, r + " (attempt " + n + " of " + max + ")", n);
                return;
            }
            if (permanent(r)) {
                sink.update(channel, r + " (not retried further)", n);
                return;
            }
            if (n == max) {
                sink.update(channel, "failed after " + max + " attempts: " + reason(r), n);
                return;
            }
            wait = delay(n, r);
            sink.update(channel, "retrying: attempt " + n + " of " + max + " failed (" + reason(r) + "); next try in " + seconds(wait), n);
        }
    }

    private Duration delay(int attempt, String r) {
        return policy.delay(attempt, RetryPolicy.looksPermanent(reason(r)), RandomGenerator.getDefault().nextDouble());
    }

    private static String seconds(Duration d) {
        return d.toMillis() < 1000 ? d.toMillis() + " ms" : Math.round(d.toMillis() / 1000.0) + " s";
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
