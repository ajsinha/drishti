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
package com.ash.drishti.server.flight;

import java.util.List;
import java.util.Optional;

/**
 * Where Flight finds a lake's tables: the lake configuration (docs/architecture/LAKE_CONFIG.md, {@code config/lakes/<lake>.yaml}), never a
 * connector's settings. A lake and a kind name a table: its format, root and path, storage, promoted columns and vector columns; a
 * {@code mem://<store>} lake is the in-memory lake, answered by the node that owns the store.
 *
 * <p>This is the shape Flight needs from the lake configuration's model (LAKE_CONFIG.md section 3, built on its own branch); the
 * server binds it to that model ({@link #NONE} until it is there: the lake form then refuses with the reason).
 */
public interface FlightLakes {

    /** One table of a lake as the lake file describes it. */
    record LakeTable(String lake, String kind, String format, String root, String path, List<String> partitionBy, List<String> promote,
            List<String> vectors) {

        public LakeTable {
            partitionBy = partitionBy == null ? List.of() : List.copyOf(partitionBy);
            promote = promote == null ? List.of() : List.copyOf(promote);
            vectors = vectors == null ? List.of() : List.copyOf(vectors);
        }

        /** True for a table of the in-memory lake. */
        public boolean inMemory() {
            return root != null && root.startsWith("mem://");
        }

        /** The memory store of an in-memory table ({@code mem://feeds/...} is {@code feeds}). */
        public String store() {
            if (!inMemory()) {
                return null;
            }
            String rest = root.substring("mem://".length());
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }
    }

    /** The lakes this server knows, by name. */
    List<String> lakes();

    /** A lake's table for a kind ({@code <domain>.<kind>} or the kind alone). */
    Optional<LakeTable> table(String lake, String kind);

    /** The tables of a lake. */
    List<LakeTable> tables(String lake);

    /** No lake configuration on this server yet. */
    FlightLakes NONE = new FlightLakes() {
        @Override
        public List<String> lakes() {
            return List.of();
        }

        @Override
        public Optional<LakeTable> table(String lake, String kind) {
            return Optional.empty();
        }

        @Override
        public List<LakeTable> tables(String lake) {
            return List.of();
        }
    };
}
