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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Loads rows into MongoDB in {@link MongoLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.mongodb.MongoLoader FILE|- [uri] [database] [--keep-days N] [--ttl-days N]
 * [--doc-format string|bson] [--batch N] [--in-flight N]}. Each line is {@code {"domain", "kind", "id", "date", "doc",
 * "columns": {path: value}}} ({@code columns}, the fields the pack promotes, is optional), as
 * {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; {@code -} reads a stream, so a book of
 * millions loads without a file. Rows go to the domain's collection and their promoted fields to its {@code _columns}
 * collection (both created with zstd compression and the connector's index) in unordered bulk writes of {@code --batch} (1,000) replace-or-insert operations by {@code _id}, at most
 * {@code --in-flight} (16) batches at once on virtual threads: loading the same rows again changes nothing.
 * {@code --keep-days N} then deletes, in every domain collection of the database, each kind's business days older than its N newest; {@code --ttl-days N}
 * stamps each document with {@code expireAt} (its business date plus N days) and adds the TTL index, so MongoDB deletes
 * old days by itself. {@code tools/load-mongodb.sh} runs it.
 */
public final class MongoLoader {

    private record Row(String domain, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

    private final MongoDatabase db;
    private final MongoLayout.DocFormat format;
    private final int ttlDays;
    private final int batchSize;
    private final Semaphore inFlight;
    private final int maxInFlight;
    private final Map<String, MongoCollection<BsonDocument>> collections = new ConcurrentHashMap<>();
    private final Map<String, MongoCollection<BsonDocument>> columnCollections = new ConcurrentHashMap<>();
    private final AtomicLong written = new AtomicLong();
    private final AtomicReference<Exception> failed = new AtomicReference<>();
    private final long t0 = System.nanoTime();

    MongoLoader(MongoDatabase db, MongoLayout.DocFormat format, int ttlDays, int batchSize, int maxInFlight) {
        this.db = db;
        this.format = format;
        this.ttlDays = ttlDays;
        this.batchSize = batchSize;
        this.maxInFlight = maxInFlight;
        this.inFlight = new Semaphore(maxInFlight);
    }

    public static void main(String[] args) throws Exception {
        String file = args[0];
        String uri = args.length > 1 && !args[1].startsWith("--") ? args[1] : "mongodb://localhost:27017";
        String database = args.length > 2 && !args[2].startsWith("--") ? args[2] : "drishti";
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        int inFlight = Integer.parseInt(options.getOrDefault("in-flight", "16"));
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .applyToConnectionPoolSettings(p -> p.maxSize(Math.max(100, inFlight + 8))).build();
        try (MongoClient client = MongoClients.create(settings);
             BufferedReader in = file.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                     : Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            MongoLoader loader = new MongoLoader(client.getDatabase(database),
                    MongoLayout.DocFormat.valueOf(options.getOrDefault("doc-format", "string").toUpperCase(java.util.Locale.ROOT)),
                    Integer.parseInt(options.getOrDefault("ttl-days", "0")), Integer.parseInt(options.getOrDefault("batch", "1000")), inFlight);
            long n = loader.load(in);
            System.out.printf("mongodb: loaded %,d rows into database %s in %,.0f s%n", n, database, loader.seconds());
            if (options.containsKey("keep-days")) {
                long t1 = System.nanoTime();
                List<String> domains = client.getDatabase(database).listCollectionNames().into(new ArrayList<>()).stream()
                        .filter(c -> !c.endsWith(MongoLayout.COLUMNS_SUFFIX) && !c.startsWith("system.")).toList();
                long gone = loader.keepDays(Integer.parseInt(options.get("keep-days")), domains);
                System.out.printf("mongodb: deleted %,d documents older than each kind's %s newest business days in %,d ms%n", gone,
                        options.get("keep-days"), (System.nanoTime() - t1) / 1_000_000);
            }
        }
    }

    private double seconds() {
        return (System.nanoTime() - t0) / 1e9;
    }

    /** Loads every line of {@code in}; returns the rows written. */
    long load(BufferedReader in) throws Exception {
        JsonFactory json = new JsonFactory();
        Map<String, List<Row>> batches = new HashMap<>();             // domain -> the batch being filled
        try (ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            String line;
            while ((line = in.readLine()) != null && failed.get() == null) {
                if (line.isBlank()) {
                    continue;
                }
                Row row = parse(json, line);
                List<Row> batch = batches.computeIfAbsent(row.domain(), d -> new ArrayList<>(batchSize));
                batch.add(row);
                if (batch.size() >= batchSize) {
                    submit(writers, row.domain(), batch);
                    batches.remove(row.domain());
                }
            }
            for (Map.Entry<String, List<Row>> e : batches.entrySet()) {
                submit(writers, e.getKey(), e.getValue());
            }
            inFlight.acquire(maxInFlight);                             // every batch written
            inFlight.release(maxInFlight);
        }
        if (failed.get() != null) {
            throw failed.get();
        }
        return written.get();
    }

    private void submit(ExecutorService writers, String domain, List<Row> rows) throws InterruptedException {
        MongoCollection<BsonDocument> c = collection(domain);
        MongoCollection<BsonDocument> narrow = columnCollections.get(domain);
        inFlight.acquire();
        writers.execute(() -> {
            try {
                List<WriteModel<BsonDocument>> models = new ArrayList<>(rows.size());
                List<WriteModel<BsonDocument>> columns = new ArrayList<>(rows.size());
                ReplaceOptions upsert = new ReplaceOptions().upsert(true);
                for (Row r : rows) {
                    BsonDocument d = MongoLayout.record(r.kind(), r.id(), r.date(), r.doc(), r.columns(), format,
                            ttlDays > 0 ? MongoLayout.expireAt(r.date(), ttlDays) : null);
                    models.add(new ReplaceOneModel<>(Filters.eq(MongoLayout.KEY, d.get(MongoLayout.KEY)), d, upsert));
                    if (!r.columns().isEmpty()) {
                        columns.add(new ReplaceOneModel<>(Filters.eq(MongoLayout.KEY, d.get(MongoLayout.KEY)), MongoLayout.columnsRecord(d), upsert));
                    }
                }
                c.bulkWrite(models, new BulkWriteOptions().ordered(false));
                if (!columns.isEmpty()) {
                    narrow.bulkWrite(columns, new BulkWriteOptions().ordered(false));
                }
                long before = written.getAndAdd(rows.size());
                if (before / 100_000 != (before + rows.size()) / 100_000) {
                    System.err.printf("mongodb: %,d rows (%,.0f s)%n", before + rows.size(), seconds());
                }
            } catch (Exception e) {
                failed.compareAndSet(null, e);
            } finally {
                inFlight.release();
            }
        });
    }

    /** The domain's collection (and its {@code _columns} collection), created and indexed on first use. */
    private MongoCollection<BsonDocument> collection(String domain) {
        return collections.computeIfAbsent(domain, d -> {
            MongoLayout.prepare(db, d, ttlDays > 0);
            columnCollections.put(d, db.getCollection(MongoLayout.columnsCollection(d), BsonDocument.class));
            return db.getCollection(d, BsonDocument.class);
        });
    }

    /**
     * Retention by deletion: for each kind of each domain's collection, every business day older than its {@code keep}
     * newest is deleted (a range of the {@code day_ids} index), in both collections. Returns the documents deleted.
     */
    long keepDays(int keep, java.util.Collection<String> domains) {
        long deleted = 0;
        for (String domain : domains) {
            MongoCollection<Document> c = db.getCollection(domain);
            for (String kind : c.distinct(MongoLayout.KIND, String.class)) {
                List<Integer> dates = c.distinct(MongoLayout.DATE, Filters.eq(MongoLayout.KIND, kind), Integer.class).into(new ArrayList<>());
                dates.sort(null);
                if (dates.size() > keep) {
                    Bson older = Filters.and(Filters.eq(MongoLayout.KIND, kind), Filters.lt(MongoLayout.DATE, dates.get(dates.size() - keep)));
                    deleted += c.deleteMany(older).getDeletedCount();
                    var unused = db.getCollection(MongoLayout.columnsCollection(domain)).deleteMany(older);
                }
            }
        }
        return deleted;
    }

    private static Row parse(JsonFactory json, String line) throws java.io.IOException {
        String domain = null;
        String kind = null;
        String id = null;
        String date = null;
        String doc = null;
        Map<String, Object> columns = new LinkedHashMap<>();
        try (JsonParser p = json.createParser(line)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                switch (name) {
                    case "domain" -> domain = p.getText();
                    case "kind" -> kind = p.getText();
                    case "id" -> id = p.getText();
                    case "date" -> date = p.getText();
                    case "doc" -> doc = p.getText();
                    case "columns" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (p.nextToken() == JsonToken.FIELD_NAME) {
                                String path = p.currentName();
                                JsonToken v = p.nextToken();
                                columns.put(path, v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT ? (Object) p.getDoubleValue()
                                        : v == JsonToken.VALUE_NULL ? null : p.getText());
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        }
        return new Row(domain, kind, id, LocalDate.parse(date), doc, columns);
    }
}
