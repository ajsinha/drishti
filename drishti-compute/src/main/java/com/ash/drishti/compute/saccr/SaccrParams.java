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
package com.ash.drishti.compute.saccr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One regulation's SA-CCR parameter file (docs/guides/SACCR_GUIDE.md section 4; the shipped file is the Basel text's,
 * {@code saccr/saccr-bcbs.yaml} in this jar). Every number is read into one flat table by a stable key ({@code alpha},
 * {@code IR.sf}, {@code CREDIT.single.sf.BBB}, {@code mpor-floors-days.bilateral}) with the CRE52 paragraph that sets it, so the engine
 * reads its numbers by key and the playground's Config tab holds exactly those cells. A key the method needs that the file leaves out,
 * or a value out of its range, refuses the file ({@code DRS-5413}, every problem listed).
 */
public final class SaccrParams {

    /** The keys the method reads, with their allowed range (inclusive). */
    private static final Map<String, double[]> REQUIRED = required();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public final String regulation;
    public final String source;
    public final String version;
    public final String hash;
    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Map<String, String> refs = new LinkedHashMap<>();

    private SaccrParams(JsonNode root, String hash) {
        this.hash = hash;
        regulation = root.path("regulation").asText("");
        source = root.path("source").asText("");
        version = root.path("version").asText("");
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isObject()) {
                walk(e.getKey().equals("supervisory-table") ? "" : e.getKey(), e.getValue(), e.getValue().path("ref").asText(""));
            }
        }
        List<String> problems = new ArrayList<>();
        if (regulation.isBlank()) {
            problems.add("regulation is missing");
        }
        if (version.isBlank()) {
            problems.add("version is missing");
        }
        REQUIRED.forEach((k, range) -> {
            Double v = values.get(k);
            if (v == null) {
                problems.add(k + " is missing");
            } else if (!Double.isFinite(v) || v < range[0] || v > range[1]) {
                problems.add(k + " = " + v + " is outside [" + range[0] + ", " + range[1] + "]");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("DRS-5413 the SA-CCR parameter file " + (version.isBlank() ? "" : regulation + " " + version + " ")
                    + "is refused: " + String.join("; ", problems));
        }
    }

    private void walk(String path, JsonNode node, String ref) {
        String r = node.path("ref").asText(ref);
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            if (name.equals("ref")) {
                continue;
            }
            JsonNode v = e.getValue();
            String key = name.equals("value") ? path : path.isEmpty() ? name : path + "." + name;
            if (v.isObject()) {
                walk(key, v, r);
            } else if (v.isNumber()) {
                values.put(key, v.asDouble());
                refs.put(key, r);
            } else if (v.isBoolean()) {
                values.put(key, v.asBoolean() ? 1.0 : 0.0);
                refs.put(key, r);
            }
        }
    }

    /** The parameters of a YAML file's bytes; {@link IllegalArgumentException} (DRS-5413) when refused. */
    public static SaccrParams parse(byte[] yaml) {
        try {
            JsonNode root = YAML.readTree(yaml);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("DRS-5413 the SA-CCR parameter file is not a YAML mapping");
            }
            return new SaccrParams(root, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml)));
        } catch (IOException e) {
            throw new IllegalArgumentException("DRS-5413 the SA-CCR parameter file is not YAML: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shipped Basel parameters ({@code saccr/saccr-bcbs.yaml}). */
    public static SaccrParams bcbs() {
        return parse(builtInBytes());
    }

    /** The bytes of the shipped Basel file. */
    public static byte[] builtInBytes() {
        try (InputStream in = SaccrParams.class.getClassLoader().getResourceAsStream("saccr/saccr-bcbs.yaml")) {
            if (in == null) {
                throw new IllegalStateException("the built-in saccr/saccr-bcbs.yaml is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A value by key; {@link IllegalArgumentException} when the file has none. */
    public double get(String key) {
        Double v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("DRS-5413 the SA-CCR parameter " + key + " is not in " + regulation + " " + version);
        }
        return v;
    }

    /** True when the file has the key. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** The CRE52 paragraph of a key ("" when none). */
    public String ref(String key) {
        return refs.getOrDefault(key, "");
    }

    /** Every key and value, in the file's order (the playground's Config tab). */
    public Map<String, Double> values() {
        return Collections.unmodifiableMap(values);
    }

    /** "BCBS 1.0 (Basel Framework, CRE52 ...)". */
    public String describe() {
        return regulation + " " + version + (source.isBlank() ? "" : " (" + source + ")");
    }

    private static Map<String, double[]> required() {
        Map<String, double[]> m = new LinkedHashMap<>();
        double[] pos = {1e-9, 1e9};
        double[] unit = {0, 1};
        m.put("alpha", new double[] {0.1, 10});
        m.put("cap-at-unmargined", unit);
        m.put("multiplier-floor", unit);
        m.put("supervisory-duration-rate", pos);
        m.put("ir-buckets.lower-years", pos);
        m.put("ir-buckets.upper-years", pos);
        for (String w : List.of("b1b2", "b2b3", "b1b3")) {
            m.put("ir-bucket-weights." + w, new double[] {0, 2});
        }
        m.put("basis-factor", pos);
        m.put("volatility-factor", pos);
        m.put("cdo-delta.numerator", pos);
        m.put("cdo-delta.slope", pos);
        m.put("time.mf-days-per-year", pos);
        m.put("time.sd-days-per-year", pos);
        m.put("time.min-maturity-days", pos);
        for (String f : List.of("bilateral", "cleared", "large-or-illiquid", "large-netting-set-trades")) {
            m.put("mpor-floors-days." + f, pos);
        }
        m.put("dispute-doubling.disputes-over", new double[] {0, 1e6});
        m.put("dispute-doubling.factor", pos);
        m.put("margined-mf-scale", pos);
        m.put("IR.sf", pos);
        m.put("IR.sigma", pos);
        m.put("FX.sf", pos);
        m.put("FX.sigma", pos);
        for (String q : List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC")) {
            m.put("CREDIT.single.sf." + q, pos);
        }
        for (String q : List.of("IG", "SG")) {
            m.put("CREDIT.index.sf." + q, pos);
        }
        for (String p : List.of("CREDIT.single", "CREDIT.index")) {
            m.put(p + ".rho", unit);
            m.put(p + ".sigma", pos);
        }
        for (String p : List.of("EQUITY.single", "EQUITY.index", "COMMODITY.ELECTRICITY", "COMMODITY.other")) {
            m.put(p + ".sf", pos);
            m.put(p + ".rho", unit);
            m.put(p + ".sigma", pos);
        }
        return Collections.unmodifiableMap(m);
    }
}
