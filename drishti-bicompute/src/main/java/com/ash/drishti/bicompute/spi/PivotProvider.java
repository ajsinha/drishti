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
package com.ash.drishti.bicompute.spi;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.Partials;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A measure provider (PIVOT_ENGINE.md section 6.1): turns cell keys and functions into partial states over the data it owns; one interface
 * for query-based providers (the tabular provider compiles cell sets to {@code GROUP BY GROUPING SETS}) and set-based ones (the VaR provider
 * maps each key to its members and computes each cell from its own members' vectors). The core never computes a figure itself.
 *
 * <p>Every method gets the {@link Viewer}: a provider applies the person's entitlements, row rules and masks in what it reads, before any
 * aggregation; a masked field is not in its catalogue and is refused as a dimension, an input or a filter.
 */
public interface PivotProvider {

    /** The provider's id ({@code tabular}, {@code var}). */
    String id();

    /** True when this provider answers {@code source}. */
    boolean serves(String source);

    /** The sources it offers this person (id to title), for the screen's source picker. */
    Map<String, String> sources(Viewer v);

    /** The source's fields (typed, ordered), its native measures, its dates, as this person sees them. */
    Catalogue catalogue(Viewer v, String source, String date);

    /** What it can do. */
    Capabilities capabilities();

    /**
     * One of the provider's native measures as a function ({@code var()}), or empty when the name is not one of them; the core then looks
     * among the built-ins (when {@link Capabilities#builtIns()}).
     */
    /**
     * The view as this provider answers it, before anything is planned (PIVOT_ENGINE.md section 6.7): a document source turns Rachana
     * paths ({@code $.LEGS[*].NOTIONAL}) into its field ids, takes the view's grain and counts a coarser figure once per parent. Refuses in
     * words ({@code DRS-5701}) what cannot be answered honestly (two separate lists crossed). The default: the view as it is.
     */
    default com.ash.drishti.bicompute.core.PivotView prepare(Viewer v, com.ash.drishti.bicompute.core.PivotView view) {
        return view;
    }

    default Optional<AggFunction<Object>> nativeFunction(String name, List<String> args) {
        return Optional.empty();
    }

    /** The data version the plan reads (part of every cache key): the lake versions, the viewer's mask signature, the date. */
    String version(Viewer v, String source, String date);

    /** The scan estimate before anything runs (section 9.7): rows, bytes, columns, seconds; nothing is read. */
    Estimate estimate(Viewer v, AggregationPlan plan);

    /** Partial states per cell key for every set of the plan, for this node's part ({@link AggregationPlan#shard()}). */
    Partials evaluate(Viewer v, AggregationPlan plan);

    /** The member rows of a cell (drill-through), paged, as the person may see them. */
    Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit);

    /** The cell as the provider's own scope text (VaR's grammar; the tabular provider's WHERE in words), when it has one. */
    default Optional<String> scopeOf(Viewer v, AggregationPlan plan, CellKey key) {
        return Optional.empty();
    }

    /**
     * A source's catalogue.
     *
     * @param source the source's id
     * @param title its title
     * @param fields the fields this person may use (masked ones left out)
     * @param measures the provider's native measures (VaR, ES): functions only it evaluates
     * @param date the business date read (null: undated)
     * @param dates the dates it holds, oldest first
     * @param version the data version read
     * @param masked how many fields are hidden from this person
     * @param notes what every cell of the source shares (layer, route, row rules in words)
     */
    record Catalogue(String source, String title, List<Field> fields, List<Native> measures, String date, List<String> dates, String version,
            int masked, Map<String, Object> notes) {}

    /**
     * A field.
     *
     * @param name its name
     * @param type {@code string}, {@code date}, {@code number} or {@code hierarchy}
     * @param label what a chip says
     * @param note where its values come from
     * @param unit what its values are (PIVOT_ENGINE.md section 5.4): {@code money} (an amount in the currency of the row's {@code currency}
     *     field), {@code rate} or {@code pct} (never summed), {@code count}, {@code currency} (the field holding a row's currency), or blank
     */
    record Field(String name, String type, String label, String note, String unit) {
        public Field {
            unit = unit == null ? "" : unit;
        }

        public Field(String name, String type, String label, String note) {
            this(name, type, label, note, "");
        }
    }

    /**
     * A provider's own measure ({@code var}, {@code es}).
     *
     * @param name the function name a view writes ({@code var()})
     * @param label the column header
     * @param kind its kind
     * @param unit {@code money}, {@code count}, {@code pct} or blank
     * @param description a sentence for the field list and the provenance
     */
    record Native(String name, String label, Kind kind, String unit, String description) {}

    /**
     * @param builtIns the built-in functions over any numeric field
     * @param shards it evaluates one shard of the rows
     * @param drill it gives a cell's member rows
     * @param scopeText it writes a cell's scope in its own grammar
     */
    record Capabilities(boolean builtIns, boolean shards, boolean drill, boolean scopeText) {}

    /**
     * A scan estimate.
     *
     * @param rows the rows read (-1: unknown)
     * @param bytes the bytes read (-1: unknown)
     * @param columns the columns the plan reads
     * @param ofColumns the source's columns
     * @param seconds the time it should take
     * @param how how it was made, in words
     */
    record Estimate(long rows, long bytes, int columns, int ofColumns, double seconds, String how) {}

    /**
     * A page of member rows.
     *
     * @param columns the column names
     * @param rows the rows
     * @param total every member row of the cell (-1: unknown)
     * @param note a sentence (masked columns left out, aggregate only)
     */
    record Rows(List<String> columns, List<List<Object>> rows, long total, String note) {}
}
