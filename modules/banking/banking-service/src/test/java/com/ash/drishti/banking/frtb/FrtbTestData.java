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
package com.ash.drishti.banking.frtb;

import com.ash.drishti.api.DataNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/** The committed demo sensitivities and factor dictionary (data/testdata/market-risk/) read as a connector would serve them. */
final class FrtbTestData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<DataNode>> CACHE = new ConcurrentHashMap<>();

    private FrtbTestData() {}

    static Path root() {
        Path p = Path.of("../../..");
        return Files.isDirectory(p.resolve("data/testdata")) ? p : Path.of(".");
    }

    static List<DataNode> read(String kind) {
        return CACHE.computeIfAbsent(kind, k -> {
            Path dir = root().resolve("data/testdata/market-risk").resolve(k);
            List<DataNode> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) {
                return null;
            }
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".jsonl.gz")).sorted().toList()) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
                        for (String line = r.readLine(); line != null; line = r.readLine()) {
                            out.add(DataNode.of(JSON.readValue(line, Object.class)));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out;
        });
    }

    /** The service over the demo files; {@code scopes} null resolves scopes on the records' own attributes. */
    static FrtbService service(FrtbService.Scopes scopes) {
        FrtbProperties props = new FrtbProperties(null, root().resolve("modules/banking/dmod/packs/market-risk/capital").toString(), null, null, null, null, null, null,
                Path.of(System.getProperty("java.io.tmpdir"), "frtb-recipes-test-" + ProcessHandle.current().pid()).toString(), null, null);
        FrtbData data = new FrtbData((kind, asOf, sink) -> {
            if (!kind.equals("position-sensitivity") && !kind.equals("risk-factor")) {
                return false;
            }
            List<DataNode> rows = read(kind);
            if (rows == null) {
                return false;
            }
            rows.forEach(sink);
            return true;
        }, props);
        return new FrtbService(data, scopes, props);
    }
}
