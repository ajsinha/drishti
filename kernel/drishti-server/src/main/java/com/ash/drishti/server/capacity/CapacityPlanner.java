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
package com.ash.drishti.server.capacity;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The single-node capacity planner's one file, {@code capacity/planner.yaml} on the classpath (CLUSTER_GUIDE.md 9.7): the inputs,
 * coefficients, results, settings, JVM arguments, warnings and calibration lines, each result an Excel-subset expression over names. The
 * server never evaluates them: Admin → Capacity's {@code drishti_capacity.js} does as you type, {@code tools/sizing_planner.py} writes the same
 * expressions into the workbook and the shared test vectors keep both honest. Here the file is read once, the server jar's version put in
 * its {@code {VERSION}} placeholder, and inputs given by name are checked and typed.
 */
public final class CapacityPlanner {

    static final String RESOURCE = "capacity/planner.yaml";

    private final Map<String, Object> plan;
    private final Map<String, Map<String, Object>> inputs = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    CapacityPlanner(Map<String, Object> plan, String version) {
        this.plan = plan;
        for (Map<String, Object> in : (List<Map<String, Object>>) plan.get("inputs")) {
            Object d = in.get("default");
            if (d instanceof String s && s.contains("{VERSION}")) {
                in.put("default", s.replace("{VERSION}", version));
            }
            inputs.put((String) in.get("name"), in);
        }
    }

    /** The built-in planner, with {@code version} (the server's) in the server jar's name. */
    @SuppressWarnings("unchecked")
    public static CapacityPlanner builtIn(String version) {
        try (InputStream in = CapacityPlanner.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the server has no " + RESOURCE);
            }
            return new CapacityPlanner(new ObjectMapper(new YAMLFactory()).readValue(in, LinkedHashMap.class), version);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The whole file, as the page reads it. */
    public Map<String, Object> plan() {
        return plan;
    }

    /** Every input's name, in the file's order. */
    public List<String> inputNames() {
        return new ArrayList<>(inputs.keySet());
    }

    /** {@code int}, {@code dec} or {@code text}. */
    public String kind(String name) {
        return (String) inputs.get(name).get("kind");
    }

    /** Every input at its default. */
    public Map<String, Object> defaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        inputs.forEach((n, in) -> m.put(n, in.get("default")));
        return m;
    }

    /**
     * The defaults with the given inputs in place, each typed by its kind; an unknown name or a number that is not one is refused in words,
     * with the list of the valid names.
     */
    public Map<String, Object> inputs(Map<String, String> given) {
        Map<String, Object> m = defaults();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (!inputs.containsKey(e.getKey())) {
                unknown.add(e.getKey());
                continue;
            }
            m.put(e.getKey(), typed(e.getKey(), e.getValue()));
        }
        if (!unknown.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no planner input " + unknown + "; the inputs are " + new TreeSet<>(inputs.keySet()));
        }
        return m;
    }

    /** A value as its input's kind: a number for {@code int} and {@code dec} (refused when it is not one), text as given. */
    Object typed(String name, Object value) {
        if (value == null) {
            return inputs.get(name).get("default");
        }
        if ("text".equals(kind(name))) {
            String s = value.toString().strip();
            if (s.length() > 300) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is at most 300 characters");
            }
            return s;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            double d = Double.parseDouble(value.toString().strip().replace(",", ""));
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is a number (" + inputs.get(name).get("unit") + "), not " + value);
        }
    }
}
