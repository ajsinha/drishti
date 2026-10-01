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
package com.ash.drishti.plugin.redis;

import com.ash.drishti.api.ColumnSet;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads one business day of a kind from its column hash ({@link RedisLayout#columns}): the {@code meta} field, then one
 * pipelined {@code HMGET} per column (the ids and each promoted path) with every chunk's field, then each column decoded
 * on its own virtual thread. A million trades of nineteen fields is twenty commands and about two thousand fields; no
 * document is read.
 */
final class ColumnReader {

    private ColumnReader() {
    }

    /** The day's description, or empty when Redis does not hold the day (never loaded, or expired). */
    static Optional<ColumnCodec.Meta> meta(RedisClusterAsyncCommands<byte[], byte[]> redis, String domain, String kind, LocalDate day, Duration timeout)
            throws Exception {
        byte[] m = await(redis.hget(RedisLayout.bytes(RedisLayout.columns(domain, kind, day)), RedisLayout.bytes(RedisLayout.META)), timeout);
        return m == null ? Optional.empty() : Optional.of(ColumnCodec.Meta.decode(m));
    }

    /**
     * The day's ids and the promoted {@code paths} the meta lists (all of them when null), in id order; empty when the
     * hash changed or went while it was read (the caller reads the meta again).
     */
    static Optional<ColumnSet> read(RedisClusterAsyncCommands<byte[], byte[]> redis, String domain, String kind, LocalDate day, ColumnCodec.Meta meta,
            List<String> paths, Duration timeout) throws Exception {
        byte[] key = RedisLayout.bytes(RedisLayout.columns(domain, kind, day));
        List<String> wanted = new ArrayList<>();
        wanted.add(RedisLayout.IDS);
        (paths == null ? meta.columns().keySet() : paths).stream().filter(meta.columns()::containsKey).forEach(wanted::add);
        Map<String, RedisFuture<List<KeyValue<byte[], byte[]>>>> sent = new LinkedHashMap<>();
        for (String column : wanted) {
            byte[][] fields = new byte[meta.chunks()][];
            for (int k = 0; k < meta.chunks(); k++) {
                fields[k] = RedisLayout.bytes(RedisLayout.field(column, k));
            }
            sent.put(column, redis.hmget(key, fields));               // all sent at once: one round trip for the day
        }
        Map<String, Future<Object>> decoded = new LinkedHashMap<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<String, RedisFuture<List<KeyValue<byte[], byte[]>>>> e : sent.entrySet()) {
                String column = e.getKey();
                boolean numeric = !column.equals(RedisLayout.IDS) && meta.columns().get(column);
                decoded.put(column, pool.submit(() -> decode(await(e.getValue(), timeout), meta.rows(), numeric)));
            }
            Map<String, double[]> numbers = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            String[] ids = null;
            for (Map.Entry<String, Future<Object>> e : decoded.entrySet()) {
                Object values = e.getValue().get();
                if (values == null) {
                    return Optional.empty();                           // a chunk is missing: rewritten or expired meanwhile
                }
                if (e.getKey().equals(RedisLayout.IDS)) {
                    ids = (String[]) values;
                } else if (values instanceof double[] d) {
                    numbers.put(e.getKey(), d);
                } else {
                    texts.put(e.getKey(), (String[]) values);
                }
            }
            return Optional.of(new ColumnSet(ids, numbers, texts, day));
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception x ? x : new IllegalStateException(e.getCause());
        }
    }

    /** One column's chunks, in order, as one array; null when a chunk is missing. */
    private static Object decode(List<KeyValue<byte[], byte[]>> chunks, int rows, boolean numeric) {
        double[] numbers = numeric ? new double[rows] : null;
        String[] texts = numeric ? null : new String[rows];
        Map<String, String> pool = ColumnCodec.pool();
        int at = 0;
        for (KeyValue<byte[], byte[]> kv : chunks) {
            if (!kv.hasValue()) {
                return null;
            }
            byte[] chunk = kv.getValue();
            if (numeric) {
                at += ColumnCodec.readNumbers(chunk, numbers, at);
            } else if (ColumnCodec.numeric(chunk)) {
                at += ColumnCodec.readNumbersAsTexts(chunk, texts, at);
            } else {
                at += ColumnCodec.readTexts(chunk, texts, at, pool);
            }
        }
        return at == rows ? numeric ? numbers : texts : null;
    }

    static <T> T await(Future<T> f, Duration timeout) throws Exception {
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception x ? x : new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            f.cancel(false);
            throw e;
        }
    }
}
