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
package com.ash.drishti.plugin.mongodb;

import com.ash.drishti.api.ColumnSet;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.conversions.Bson;

/**
 * Reads one day of a kind as columns: only {@code id} and the promoted fields ({@code c}) travel, and they are decoded
 * from the wire straight into column arrays (no document object, no boxed number per value). The day is split into id
 * ranges at {@code boundaries} (ids taken from an earlier read of the kind), each range read at once on a virtual thread
 * over the {@code day_ids} index in id order, so the ranges concatenated are the day's rows sorted by id. Boundaries
 * that no longer fit the data only unbalance the ranges; every row is still read exactly once.
 */
final class ColumnReader {

    private static final BsonDocument DAY_HINT = new BsonDocument(MongoLayout.KIND, new BsonInt32(1)).append(MongoLayout.DATE, new BsonInt32(1))
            .append(MongoLayout.ID, new BsonInt32(1));

    /**
     * One range's columns as they are decoded. A column stays numeric ({@code double[]}, NaN for null) while every value
     * is a number, and becomes text at its first text value. Repeated texts (books, desks, currencies) share one string;
     * a column whose values are mostly distinct (trade ids) stops pooling.
     */
    private static final class Range {
        private final int width;
        private String[] ids = new String[4096];
        private final double[][] nums;
        private final String[][] texts;
        private final boolean[] seen;
        private final List<Map<String, String>> pools = new ArrayList<>();
        private int n;

        Range(int width) {
            this.width = width;
            this.nums = new double[width][];
            this.texts = new String[width][];
            this.seen = new boolean[width];
            for (int c = 0; c < width; c++) {
                nums[c] = new double[ids.length];
                Arrays.fill(nums[c], Double.NaN);
                pools.add(new HashMap<>());
            }
        }

        /** Room for one more row, which becomes row {@code n}. */
        void next() {
            if (n == ids.length) {
                int size = ids.length * 2;
                ids = Arrays.copyOf(ids, size);
                for (int c = 0; c < width; c++) {
                    if (texts[c] != null) {
                        texts[c] = Arrays.copyOf(texts[c], size);
                    } else {
                        double[] grown = Arrays.copyOf(nums[c], size);
                        Arrays.fill(grown, n, size, Double.NaN);
                        nums[c] = grown;
                    }
                }
            }
        }

        void id(String id) {
            ids[n] = id;
        }

        /** Ends row {@code n}: kept when it had an id, else cleared for the next. */
        void end() {
            if (ids[n] != null) {
                n++;
                return;
            }
            for (int c = 0; c < width; c++) {
                if (texts[c] != null) {
                    texts[c][n] = null;
                } else {
                    nums[c][n] = Double.NaN;
                }
            }
        }

        void number(int c, double v) {
            seen[c] = true;
            if (texts[c] == null) {
                nums[c][n] = v;
            } else {
                texts[c][n] = format(v);
            }
        }

        void text(int c, String v) {
            seen[c] = true;
            if (texts[c] == null) {                                    // the first text: the column so far, as texts
                texts[c] = new String[ids.length];
                for (int i = 0; i < n; i++) {
                    texts[c][i] = Double.isNaN(nums[c][i]) ? null : format(nums[c][i]);
                }
                nums[c] = null;
            }
            Map<String, String> pool = pools.get(c);
            if (pool != null && pool.size() > 10_000 && pool.size() * 2 > n) {
                pools.set(c, null);                                    // mostly distinct: pooling would only cost memory
                pool = null;
            }
            texts[c][n] = pool == null ? v : pool.computeIfAbsent(v, k -> k);
        }
    }

    private static String format(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Decodes {@code {id, c: {...}}} into a {@link Range}, skipping the fields of {@code c} it was not asked for. */
    private static final class RangeCodec implements Codec<Range> {
        private final Map<String, Integer> slots;
        private final Range range;

        RangeCodec(Map<String, Integer> slots, Range range) {
            this.slots = slots;
            this.range = range;
        }

        @Override
        public Range decode(BsonReader r, DecoderContext ctx) {
            r.readStartDocument();
            range.next();
            while (r.readBsonType() != BsonType.END_OF_DOCUMENT) {
                String name = r.readName();
                if (name.equals(MongoLayout.ID) && r.getCurrentBsonType() == BsonType.STRING) {
                    range.id(r.readString());
                } else if (name.equals(MongoLayout.COLUMNS) && r.getCurrentBsonType() == BsonType.DOCUMENT) {
                    r.readStartDocument();
                    while (r.readBsonType() != BsonType.END_OF_DOCUMENT) {
                        Integer slot = slots.get(r.readName());
                        if (slot == null) {
                            r.skipValue();
                        } else {
                            value(r, slot);
                        }
                    }
                    r.readEndDocument();
                } else {
                    r.skipValue();
                }
            }
            r.readEndDocument();
            range.end();
            return range;
        }

        private void value(BsonReader r, int c) {
            switch (r.getCurrentBsonType()) {
                case DOUBLE -> range.number(c, r.readDouble());
                case INT32 -> range.number(c, r.readInt32());
                case INT64 -> range.number(c, r.readInt64());
                case DECIMAL128 -> range.number(c, r.readDecimal128().bigDecimalValue().doubleValue());
                case STRING -> range.text(c, r.readString());
                case BOOLEAN -> range.text(c, String.valueOf(r.readBoolean()));
                default -> r.skipValue();                              // null, or a type a column cannot hold
            }
        }

        @Override
        public void encode(BsonWriter w, Range value, EncoderContext ctx) {
            throw new UnsupportedOperationException("read only");
        }

        @Override
        public Class<Range> getEncoderClass() {
            return Range.class;
        }
    }

    private final MongoCollection<BsonDocument> collection;
    private final int batchSize;

    ColumnReader(MongoCollection<BsonDocument> collection, int batchSize) {
        this.collection = collection;
        this.batchSize = batchSize;
    }

    /** Every entity of {@code kind} on {@code day} with {@code paths}, read in {@code boundaries.size() + 1} ranges at once. */
    ColumnSet read(String kind, LocalDate day, List<String> paths, List<String> boundaries) {
        Map<String, Integer> slots = new HashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            slots.put(MongoLayout.field(paths.get(i)), i);
        }
        Bson day0 = Filters.and(Filters.eq(MongoLayout.KIND, kind), Filters.eq(MongoLayout.DATE, MongoLayout.day(day)));
        List<String> cuts = boundaries.stream().distinct().sorted().toList();
        List<Range> ranges = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Range>> running = new ArrayList<>();
            for (int i = 0; i <= cuts.size(); i++) {
                List<Bson> range = new ArrayList<>(List.of(day0));
                if (i > 0) {
                    range.add(Filters.gte(MongoLayout.ID, cuts.get(i - 1)));
                }
                if (i < cuts.size()) {
                    range.add(Filters.lt(MongoLayout.ID, cuts.get(i)));
                }
                Bson filter = Filters.and(range);
                running.add(pool.submit(() -> readRange(filter, slots, paths.size())));
            }
            for (Future<Range> f : running) {
                ranges.add(f.get());
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return merge(ranges, paths, day);
    }

    private Range readRange(Bson filter, Map<String, Integer> slots, int width) {
        Range range = new Range(width);
        MongoCollection<Range> rows = collection.withDocumentClass(Range.class)
                .withCodecRegistry(CodecRegistries.fromRegistries(CodecRegistries.fromCodecs(new RangeCodec(slots, range)), collection.getCodecRegistry()));
        try (MongoCursor<Range> it = rows.find(filter).projection(Projections.fields(Projections.excludeId(),
                Projections.include(MongoLayout.ID, MongoLayout.COLUMNS))).sort(Sorts.ascending(MongoLayout.ID)).hint(DAY_HINT)
                .batchSize(batchSize).iterator()) {
            while (it.hasNext()) {
                it.next();                                             // each document lands in the range's arrays
            }
        }
        return range;
    }

    /** The ranges, in id order, as one column set: a path is numeric when every range holds it as numbers and one has a value. */
    private static ColumnSet merge(List<Range> ranges, List<String> paths, LocalDate day) {
        int n = ranges.stream().mapToInt(r -> r.n).sum();
        String[] ids = new String[n];
        int at = 0;
        for (Range r : ranges) {
            System.arraycopy(r.ids, 0, ids, at, r.n);
            at += r.n;
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            int col = c;
            boolean numeric = ranges.stream().noneMatch(r -> r.texts[col] != null) && ranges.stream().anyMatch(r -> r.seen[col]);
            at = 0;
            if (numeric) {
                double[] v = new double[n];
                for (Range r : ranges) {
                    System.arraycopy(r.nums[c], 0, v, at, r.n);
                    at += r.n;
                }
                nums.put(paths.get(c), v);
            } else {
                String[] v = new String[n];
                for (Range r : ranges) {
                    if (r.texts[c] != null) {
                        System.arraycopy(r.texts[c], 0, v, at, r.n);
                    } else {
                        for (int i = 0; i < r.n; i++) {
                            v[at + i] = Double.isNaN(r.nums[c][i]) ? null : format(r.nums[c][i]);
                        }
                    }
                    at += r.n;
                }
                texts.put(paths.get(c), v);
            }
        }
        return new ColumnSet(ids, nums, texts, day);
    }
}
