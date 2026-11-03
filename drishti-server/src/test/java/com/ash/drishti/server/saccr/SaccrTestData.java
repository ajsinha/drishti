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
package com.ash.drishti.server.saccr;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.server.frtb.FrtbData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * The committed demo data read as connectors would serve the business date 2026-09-30: each kind's documents replayed (the snapshot, then the
 * day files in date order, the newest record of a key wins), the classification rows of the date.
 */
final class SaccrTestData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<DataNode>> CACHE = new ConcurrentHashMap<>();
    /** kind → its folder and key. */
    static final Map<String, String[]> KINDS = Map.of("saccr-trade", new String[] {"counterparty-risk", "SACCR_ROW_ID"}, "netting-set",
            new String[] {"counterparty-risk", "NETTING_SET_ID"}, "csa", new String[] {"counterparty-risk", "CSA_ID"}, "collateral-balance",
            new String[] {"counterparty-risk", "BALANCE_ID"}, "trade-valuation", new String[] {"market-risk", "TRADE_ID"});

    private SaccrTestData() {}

    static Path root() {
        Path p = Path.of("..");
        return Files.isDirectory(p.resolve("data/testdata")) ? p : Path.of(".");
    }

    static List<DataNode> read(String kind) {
        String[] k = KINDS.get(kind);
        if (k == null) {
            return null;
        }
        return CACHE.computeIfAbsent(kind, x -> {
            Path dir = root().resolve("data/testdata").resolve(k[0]).resolve(kind);
            Map<String, DataNode> latest = new LinkedHashMap<>();
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jsonl.gz"))
                        .sorted(Comparator.comparing((Path p) -> !p.getFileName().toString().startsWith("snapshot")).thenComparing(Path::toString)).toList()) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
                        for (String line = r.readLine(); line != null; line = r.readLine()) {
                            if (!line.isBlank()) {
                                DataNode n = DataNode.of(JSON.readValue(line, Object.class));
                                DataNode id = n.get(k[1]);
                                latest.put(id == null || id.isMissing() ? String.valueOf(latest.size()) : id.asText(), n);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new ArrayList<>(latest.values());
        });
    }

    static FrtbData.Reader reader() {
        return (kind, asOf, sink) -> {
            List<DataNode> rows = read(kind);
            if (rows == null) {
                return false;
            }
            rows.forEach(sink);
            return true;
        };
    }

    static SaccrService service() {
        SaccrProperties props = SaccrProperties.defaults();
        return new SaccrService(new SaccrData(reader(), props), null, props);
    }
}
