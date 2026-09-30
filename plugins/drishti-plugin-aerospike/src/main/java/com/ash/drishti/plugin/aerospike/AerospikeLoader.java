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
import com.aerospike.client.Key;
import com.aerospike.client.policy.ClientPolicy;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Loads rows written by {@code make_data.py --jsonl FILE} into Aerospike, a set per data domain:
 * {@code java -cp <plugin classpath> com.ash.drishti.plugin.aerospike.AerospikeLoader FILE [hosts] [namespace]}.
 * Each line is {@code {"domain", "kind", "id", "date", "doc"}}; {@code tools/load-aerospike.sh} runs it.
 */
public final class AerospikeLoader {

    private AerospikeLoader() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String hosts = args.length > 1 ? args[1] : "localhost:3000";
        String namespace = args.length > 2 ? args[2] : "test";
        JsonFactory json = new JsonFactory();
        long n = 0;
        try (AerospikeClient c = new AerospikeClient(new ClientPolicy(), Host.parseHosts(hosts, 3000));
             BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String domain = null, kind = null, id = null, date = null, doc = null;
                try (JsonParser p = json.createParser(line)) {
                    p.nextToken();
                    while (p.nextToken() != null && p.currentName() != null) {
                        String name = p.currentName();
                        p.nextToken();
                        switch (name) {
                            case "domain" -> domain = p.getText();
                            case "kind" -> kind = p.getText();
                            case "id" -> id = p.getText();
                            case "date" -> date = p.getText();
                            case "doc" -> doc = p.getText();
                            default -> p.skipChildren();
                        }
                    }
                }
                c.put(null, new Key(namespace, domain, DatedRecords.key(kind, id)), DatedRecords.bins(kind, id, LocalDate.parse(date), doc));
                n++;
            }
        }
        System.out.println("aerospike: loaded " + n + " rows into namespace " + namespace);
    }
}
