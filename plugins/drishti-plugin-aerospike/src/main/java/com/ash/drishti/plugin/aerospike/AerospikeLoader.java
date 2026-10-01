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
package com.ash.drishti.plugin.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads rows into Aerospike in {@link AerospikeLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.aerospike.AerospikeLoader FILE|- [hosts] [namespace] [--ttl-days N]}. Each line is
 * {@code {"domain", "kind", "id", "date", "doc", "columns": {path: value}}} ({@code columns}, the fields the pack
 * promotes, is optional), as {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; {@code -} reads
 * a stream, so a book of millions loads without a file. Up to 128 writes are in flight; each kind's business dates are
 * recorded once at the end. {@code --ttl-days} lets Aerospike expire each day's documents after that long (history
 * retention without a maintenance job). {@code tools/load-aerospike.sh} runs it.
 */
public final class AerospikeLoader {

    private AerospikeLoader() {
    }

    public static void main(String[] args) throws Exception {
        String file = args[0];
        String hosts = args.length > 1 && !args[1].startsWith("--") ? args[1] : "localhost:3000";
        String namespace = args.length > 2 && !args[2].startsWith("--") ? args[2] : "test";
        int ttlDays = 0;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--ttl-days")) {
                ttlDays = Integer.parseInt(args[i + 1]);
            }
        }
        WritePolicy policy = new WritePolicy();
        policy.expiration = ttlDays > 0 ? ttlDays * 86_400 : -1;          // -1: never expires
        policy.sendKey = true;
        JsonFactory json = new JsonFactory();
        Map<String, Set<LocalDate>> dates = new ConcurrentHashMap<>();     // "domain\u001fkind" -> its business dates
        AtomicLong n = new AtomicLong();
        AtomicReference<Exception> failed = new AtomicReference<>();
        Semaphore inFlight = new Semaphore(128);
        long t0 = System.nanoTime();
        ClientPolicy client = new ClientPolicy();
        client.maxConnsPerNode = 256;                                       // above the writes in flight (the default is 100)
        try (AerospikeClient c = new AerospikeClient(client, Host.parseHosts(hosts, 3000));
             BufferedReader in = file.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                     : Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8);
             ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            String line;
            while ((line = in.readLine()) != null && failed.get() == null) {
                if (line.isBlank()) {
                    continue;
                }
                Row row = parse(json, line);
                dates.computeIfAbsent(row.domain() + "\u001f" + row.kind(), k -> ConcurrentHashMap.newKeySet()).add(row.date());
                inFlight.acquire();
                writers.execute(() -> {
                    try {
                        AerospikeLayout.write(c, policy, namespace, row.domain(), row.kind(), row.id(), row.date(), row.doc(), row.columns());
                        long done = n.incrementAndGet();
                        if (done % 100_000 == 0) {
                            System.err.printf("aerospike: %,d rows (%,.0f s)%n", done, (System.nanoTime() - t0) / 1e9);
                        }
                    } catch (Exception e) {
                        failed.compareAndSet(null, e);
                    } finally {
                        inFlight.release();
                    }
                });
            }
            inFlight.acquire(128);                                        // every write finished
            if (failed.get() != null) {
                throw failed.get();
            }
            dates.forEach((k, ds) -> {
                String[] dk = k.split("\u001f", 2);
                AerospikeLayout.addDates(c, policy, namespace, dk[0], dk[1], ds);
            });
        }
        System.out.printf("aerospike: loaded %,d rows into namespace %s in %,.0f s%n", n.get(), namespace, (System.nanoTime() - t0) / 1e9);
    }

    private record Row(String domain, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

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
