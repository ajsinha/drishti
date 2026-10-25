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
package com.ash.drishti.api.compute;

import com.ash.drishti.api.DataNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The inputs of one computation, by the names the connector's {@code reads:} gives them, already read through the normal routing as the
 * requesting viewer (kinds, row entitlements and field masks applied before anything reaches the engine) and pinned to one as-of. An
 * engine never reads data itself. A Python engine receives the same inputs as Arrow tables in its scratch folder.
 */
public final class EngineInputs {

    public static final EngineInputs NONE = new EngineInputs(Map.of());

    private final Map<String, List<DataNode>> inputs;

    public EngineInputs(Map<String, List<DataNode>> inputs) {
        Map<String, List<DataNode>> m = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((k, v) -> m.put(k, v == null ? List.of() : List.copyOf(v)));
        }
        this.inputs = Collections.unmodifiableMap(m);
    }

    /** The names of the inputs. */
    public Set<String> names() {
        return inputs.keySet();
    }

    /** The documents of one input (each as the viewer sees it); refused for a name {@code reads:} does not give. */
    public List<DataNode> documents(String name) {
        List<DataNode> d = inputs.get(name);
        if (d == null) {
            throw new IllegalArgumentException("there is no input '" + name + "' (inputs: " + inputs.keySet() + ")");
        }
        return d;
    }

    /** How many documents all inputs hold. */
    public int size() {
        return inputs.values().stream().mapToInt(List::size).sum();
    }

    /** The inputs as a map, name to documents. */
    public Map<String, List<DataNode>> asMap() {
        return inputs;
    }
}
