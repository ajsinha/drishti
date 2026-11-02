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
package com.ash.drishti.server.cube;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The measure provider SPI of the shared cube core (MR_CUBE_LENS.md section 4; CCR_CUBE_LENS.md reuses it): one source per cube. The
 * core ({@link CubeEngine}) owns the pivot (partitions, cells, row and column totals, the grand total, diversification), the cell budget,
 * the busy gate, the cell cache, provenance ids, saved views and exports; a source owns what its members are and how a set of them is
 * aggregated and measured. The core never computes a figure: every number comes from {@link Universe#figures} on an aggregate the source
 * made for exactly that set of members, so a non-additive total is the union's own figure, never a sum of cells.
 *
 * <p>The market-risk cube's source is the VaR engine ({@code VarCubeSource}: members are the trades of a business date, an aggregate is the
 * summed scenario P&amp;L vector, sharded dates batched per owner). The counterparty cube's will be the exposure engine (members the trades,
 * an aggregate the netting sets' path matrices, netted and collateralised per set before the sum).
 *
 * @param <A> the aggregate of a set of members (the MR cube: a {@code double[]} of scenario P&amp;L)
 */
public interface CubeSource<A> {

    /** The cube's id in URLs and views ({@code mr}). */
    String id();

    /** Its title ({@code MR cube}). */
    String title();

    /** The measures it offers, in the order a chip bar shows them; a measure whose engine is off is left out. */
    List<CubeMeasure> measures();

    /** The measures a view asks for when it names none. */
    List<String> defaultMeasures();

    /** The dimension a drill opens below {@code dimension} (desk to book to position to trade); null: the last level. */
    String drillBelow(String dimension);

    /** The earlier business dates a sparkline of {@code view} may read (newest last), without opening a date for it. */
    List<LocalDate> historyDates(Principal p, CubeView view, int n);

    /**
     * The view's members as the person sees them, on its date and through its lens: refused in words (a scope the person may not see, a
     * date that is not there) exactly as the engine refuses the same scope.
     */
    Universe<A> open(Principal p, CubeView view);

    /** One opened view: its members, how they split by a dimension, and how a set of them is aggregated and measured. */
    interface Universe<A> {

        LocalDate date();

        /** The view's scope as the engine writes it (canonical). */
        String canonical();

        /** Every member of the view (the scope's, as the person sees them). */
        OrdinalSet members();

        /** The dimensions this view may use (its book's columns, hierarchies and the derived ones). */
        List<CubeDimension> dimensions();

        /**
         * The members split by a dimension's value, every part a subset of {@link #members()} and the parts disjoint; in the dimension's
         * own order (a hierarchy's, a configured order, else by value). Refused with {@code DRS-5601} for an unknown dimension.
         */
        Map<String, OrdinalSet> partition(String dimension);

        /** What a value of a dimension is called on screen (a node's name; the value itself by default). */
        String label(String dimension, String value);

        /**
         * The scope of a cell in the engine's grammar (the view's scope intersected with each coordinate), so the cell's figures can be asked
         * of the engine itself ({@code /api/v1/var/scope}) and equal it; null when no scope text names the cell (a coordinate the grammar
         * cannot write), said so in the cell.
         */
        String scopeOf(Map<String, String> coords);

        /** The part of a cache key that changes when any input does (date, data version, batches, the viewer's rights, the lens). */
        String cacheKey();

        /**
         * The aggregates of many sets at once, in order: one batch (on a sharded date one call per owner for all of them, section 4.11), each
         * the aggregate the engine makes for that set alone.
         */
        List<A> aggregates(List<OrdinalSet> sets);

        /** The bytes an aggregate holds (the cell cache's weigher). */
        long bytes(A aggregate);

        /**
         * A cell's figures, measure id to value (null: not available, with the reason in {@link Figures#notes()}); {@code total} is the
         * view's grand total's aggregate (a component is a cell's share of it). Extra keys a source adds ({@code limit}, {@code limitUse})
         * are passed through to the answer.
         */
        Figures figures(List<String> measures, A aggregate, Cell cell, A total);

        /** The detail of one cell for its right rail (its histogram, VaR scenario, ES tail): source-specific, never on the grid. */
        Map<String, Object> detail(A aggregate, Cell cell);

        /** What every cell of the view shares in its provenance (business date, data versions, batches, scenario set, method, shards). */
        Map<String, Object> provenance();

        /** The view's headline beside the pivot (the scope's own figures and limit), computed from the total's aggregate. */
        Map<String, Object> headline(A total);
    }

    /**
     * A cell, a row or column total, or the grand total.
     *
     * @param kind {@code cell}, {@code row}, {@code column} or {@code total}
     * @param coords dimension to value (none for the grand total)
     * @param set its members
     * @param scope its scope text (null when the grammar cannot write it)
     */
    record Cell(String kind, Map<String, String> coords, OrdinalSet set, String scope) {}

    /** A cell's figures and, for each figure not given, why. */
    record Figures(Map<String, Double> values, Map<String, String> notes) {}
}
