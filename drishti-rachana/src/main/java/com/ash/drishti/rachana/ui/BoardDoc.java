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
package com.ash.drishti.rachana.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One board document (UI_BOARD.md section 3). Read by {@link UiDocuments#readBoard}; YAML and JSON give equal values. It
 * holds no data and no code.
 *
 * @param rachana the grammar version (1)
 * @param board the name
 * @param version the board's revision (set by the store; 0 when absent)
 * @param title the title
 * @param description the description
 * @param params the board's own control inputs, by name (role {@code CONTROL})
 * @param chips the chip instances, by instance name, in document order
 * @param localChips board-local chips, full chip documents
 * @param traces the wires
 * @param layout the layout (never null; empty when absent)
 * @param owner set by the store, never trusted from the body (null when absent)
 * @param workspace set by the store (null when absent)
 */
public record BoardDoc(int rachana, String board, int version, String title, String description, Map<String, PinSpec> params,
        Map<String, Instance> chips, List<ChipDoc> localChips, List<Trace> traces, Layout layout, String owner, String workspace) {

    /** The prefix of a board parameter in a trace's {@code from}: {@code board.params.<name>}. */
    public static final String PARAMS = "board.params.";

    public BoardDoc {
        params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
        chips = chips == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(chips));
        localChips = localChips == null ? List.of() : List.copyOf(localChips);
        traces = traces == null ? List.of() : List.copyOf(traces);
        layout = layout == null ? Layout.EMPTY : layout;
    }

    /**
     * One chip instance on the board.
     *
     * @param name the instance name
     * @param use the chip and version ({@code null} when {@code use} is missing or unversioned; {@link #useText} says what was written)
     * @param useText {@code use} as written
     * @param title the frame title, or null
     * @param supply the supplier, or null (a chip fed only by traces)
     * @param map supplier attributes onto pins and shape columns, by key ({@code rows.id}), in document order
     * @param controls constant values of control pins
     * @param follow {@code compatible} when the store may upgrade it automatically (9.3), else null
     */
    public record Instance(String name, ChipRef use, String useText, String title, Supply supply, Map<String, Mapping> map,
            Map<String, JsonNode> controls, String follow) {

        public Instance {
            map = map == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
            controls = controls == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(controls));
        }
    }

    /**
     * One supplier: {@code connector}, {@code model}, {@code dataset} or {@code live}, with an optional {@code query}.
     *
     * @param kind which kind of supplier
     * @param name {@code <pack>/<kind or search>}, the model, the dataset, or {@code <pack>/<topic>}
     * @param query the query, as written (null when absent)
     */
    public record Supply(SupplyKind kind, String name, JsonNode query) {

        /** {@code connector:trading/trade-search}: how a {@link SupplierCatalog} names it. */
        public String key() {
            return kind.yamlName() + ":" + name;
        }
    }

    /** The kinds of supplier. */
    public enum SupplyKind {
        CONNECTOR, MODEL, DATASET, LIVE;

        public String yamlName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One {@code map} entry.
     *
     * @param key the pin or {@code <pin>.<column>} (or {@code <pin>.rows}: a document path read as rows)
     * @param attributes the supplier attribute(s)
     * @param list true when written as a list (only a {@code many} column takes one)
     */
    public record Mapping(String key, List<String> attributes, boolean list) {

        public Mapping {
            attributes = List.copyOf(attributes);
        }
    }

    /**
     * One wire: from an out pin (or a board parameter) to an in pin.
     *
     * @param from {@code <instance>.<out pin>} or {@code board.params.<name>}
     * @param to {@code <instance>.<in pin>}
     * @param when the condition, as written (null when absent)
     * @param transform the transform (null when none)
     */
    public record Trace(String from, String to, JsonNode when, Transform transform) {}

    /**
     * A trace's transform: {@code first} (a selection to one id), {@code date} (an instant to its date) or
     * {@code { filter-on: <pin>.<column> }} (an id, selection or period to a filter on that column).
     *
     * @param kind which transform
     * @param column the {@code filter-on} column, else null
     */
    public record Transform(TransformKind kind, String column) {}

    /** The transforms. */
    public enum TransformKind { FIRST, DATE, FILTER_ON }

    /**
     * The layout: named grid areas per device and the theme.
     *
     * @param theme the theme, or null
     * @param desktop the desktop grid (null when absent)
     * @param tablet the tablet grid (null: falls back to desktop)
     * @param phone the phone column (null when absent)
     */
    public record Layout(String theme, Grid desktop, Grid tablet, Phone phone) {

        public static final Layout EMPTY = new Layout(null, null, null, null);
    }

    /**
     * A grid of areas.
     *
     * @param columns the grid's columns (12 by default)
     * @param areas the areas
     */
    public record Grid(int columns, List<Area> areas) {

        public Grid {
            areas = areas == null ? List.of() : List.copyOf(areas);
        }
    }

    /**
     * One placed chip: {@code at: [column, row]}, {@code span: [columns wide, rows high]}, 1-based.
     *
     * @param chip the instance
     * @param column its first column
     * @param row its first row
     * @param width columns wide
     * @param height rows high
     */
    public record Area(String chip, int column, int row, int width, int height) {

        /** True when the two areas share a cell. */
        public boolean overlaps(Area o) {
            return column < o.column + o.width && o.column < column + width && row < o.row + o.height && o.row < row + height;
        }
    }

    /**
     * The phone layout: one column.
     *
     * @param order the instances, in order
     * @param collapsed the instances shown as a heading the person opens
     */
    public record Phone(List<String> order, List<String> collapsed) {

        public Phone {
            order = order == null ? List.of() : List.copyOf(order);
            collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
        }
    }
}
