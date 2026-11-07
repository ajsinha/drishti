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
package com.ash.drishti.banking.simm;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.banking.frtb.FrtbData;
import com.ash.drishti.banking.frtb.FrtbProperties;
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

/**
 * The committed demo data read as connectors would serve them: the sensitivities and the dictionary (data/testdata/market-risk/), the
 * netting sets, CSAs and stored SIMM figures (data/testdata/counterparty-risk/, the snapshot then the day files), and the TOY parameter
 * file of drishti-compute's tests (round illustrative numbers, not ISDA's).
 */
final class SimmTestData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<DataNode>> CACHE = new ConcurrentHashMap<>();
    static final Map<String, String> DOMAIN = Map.of("position-sensitivity", "market-risk", "risk-factor", "market-risk", "netting-set", "counterparty-risk",
            "csa", "counterparty-risk", "simm", "counterparty-risk");

    private SimmTestData() {}

    static Path root() {
        Path p = Path.of("..");
        return Files.isDirectory(p.resolve("data/testdata")) ? p : Path.of(".");
    }

    /** The toy parameter folder (drishti-compute/src/test/resources/simm: simm-toy.yaml only). */
    static Path toyDir() {
        return root().resolve("drishti-compute/src/test/resources/simm");
    }

    static List<DataNode> read(String kind) {
        String domain = DOMAIN.get(kind);
        if (domain == null) {
            return null;
        }
        return CACHE.computeIfAbsent(kind, k -> {
            Path dir = root().resolve("data/testdata").resolve(domain).resolve(k);
            List<DataNode> out = new ArrayList<>();
            try (var files = Files.list(dir)) {
                // <kind>.<YYYY-MM-DD>.jsonl.gz in date order: the first day's snapshot, then each day's changes (the newest record of a key last)
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

    static SimmProperties props(boolean enabled, Path parameters) {
        return new SimmProperties(enabled, parameters.toString(), null, null, null, null, null, null, null, null, null,
                Path.of(System.getProperty("java.io.tmpdir"), "simm-recipes-test-" + ProcessHandle.current().pid()).toString(), null, null);
    }

    /** The service over the demo files with the toy parameters; scopes resolved on the records' own attributes. */
    static SimmService service() {
        return service(props(true, toyDir()));
    }

    static SimmService service(SimmProperties props) {
        FrtbData sens = new FrtbData(reader(), FrtbProperties.defaults());
        return new SimmService(new SimmData(reader(), sens, props), null, props, Path.of(props.parametersDir()));
    }
}
