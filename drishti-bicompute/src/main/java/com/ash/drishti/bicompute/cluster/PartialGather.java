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
package com.ash.drishti.bicompute.cluster;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The coordinator's gather of the pivot's partial states (PIVOT_ENGINE.md section 9.3), in the shape of VaR sharding's {@code ShardGather}
 * ({@code drishti-compute}; this module does not depend on it): every share is asked once, side by side; a share whose node is late past
 * {@code hedgeAfterMs} is asked again of its next node (the first answer wins: both are equal by construction); the answers are combined
 * in <b>share order</b> through a {@link Combine} (zero, add), so the same question gives the same digits whoever answered and in whatever
 * order the answers came. A share with no answer by the deadline fails the whole gather in words ({@code DRS-5720}): never a figure from
 * the other shares alone.
 */
public final class PartialGather {

    /** How the partials combine: the shape of {@code ShardGather.Combine}. */
    public interface Combine<P> {
        /** The combination of no partial. */
        P zero();

        /** {@code acc} with {@code part} added. */
        P add(P acc, P part);
    }

    /**
     * One share of the work: who answers it first, and who next when that one is late.
     *
     * @param id the share's id in messages (a node, a shard)
     * @param nodes the nodes that may answer it, in the order they are asked
     */
    public record Share(String id, List<String> nodes) {}

    /** Asks a node for a share's partial (a remote call, or this node itself). */
    @FunctionalInterface
    public interface Ask<P> {
        P ask(String node, Share share) throws Exception;
    }

    /** The pivot's partial states merged in order: each share's partials appended, then {@link OrderedMerge} once in share order. */
    public static Combine<List<Partials>> partials() {
        return new Combine<>() {
            @Override
            public List<Partials> zero() {
                return new ArrayList<>();
            }

            @Override
            public List<Partials> add(List<Partials> acc, List<Partials> part) {
                acc.addAll(part);
                return acc;
            }
        };
    }

    private PartialGather() {}

    /**
     * Asks every share (in {@code shares} order) on {@code exec}, hedging a late one after {@code hedgeAfterMs} (0: never), waiting at most
     * {@code deadlineMs} for each; combines the answers in share order.
     */
    public static <P> P gather(List<Share> shares, Ask<P> ask, Combine<P> combine, Executor exec, long hedgeAfterMs, long deadlineMs) {
        List<CompletableFuture<P>> asked = new ArrayList<>();
        for (Share s : shares) {
            asked.add(ask(s, 0, ask, exec));
        }
        List<P> got = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < shares.size(); i++) {
            Share s = shares.get(i);
            CompletableFuture<P> f = asked.get(i);
            P answer = null;
            Throwable failure = null;
            try {
                long waited = (System.nanoTime() - start) / 1_000_000;
                if (hedgeAfterMs > 0 && s.nodes().size() > 1 && waited < hedgeAfterMs) {
                    try {
                        answer = f.get(hedgeAfterMs - waited, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException late) {
                        CompletableFuture<P> second = ask(s, 1, ask, exec);
                        f = CompletableFuture.anyOf(f, second).thenApply(x -> {
                            @SuppressWarnings("unchecked")
                            P p = (P) x;
                            return p;
                        });
                    }
                }
                if (answer == null) {
                    long left = Math.max(1, deadlineMs - (System.nanoTime() - start) / 1_000_000);
                    answer = f.get(left, TimeUnit.MILLISECONDS);
                }
            } catch (TimeoutException e) {
                failure = e;
            } catch (ExecutionException | CompletionException e) {
                failure = e.getCause() == null ? e : e.getCause();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
            }
            if (answer == null) {
                if (failure instanceof DrishtiException de) {
                    throw de;
                }
                throw new DrishtiException(ErrorCode.PIVOT_NODE_MISSING, "share " + s.id() + " (node " + String.join(", then ", s.nodes())
                        + ") gave no answer" + (failure instanceof TimeoutException ? " within " + deadlineMs / 1000 + " s" : ": " + failure)
                        + "; its rows are missing, so no figure is shown: try again, or ask an administrator about that node");
            }
            got.add(answer);
        }
        P acc = combine.zero();
        for (P p : got) {                                           // share order: 0, 1, 2 ...
            acc = combine.add(acc, p);
        }
        return acc;
    }

    private static <P> CompletableFuture<P> ask(Share s, int which, Ask<P> ask, Executor exec) {
        String node = s.nodes().get(Math.min(which, s.nodes().size() - 1));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ask.ask(node, s);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, exec);
    }

    /** The shares' partials merged into one answer in share order, then key order. */
    public static Partials merge(AggregationPlan plan, List<Partials> inShareOrder) {
        return OrderedMerge.merge(plan, inShareOrder);
    }
}
