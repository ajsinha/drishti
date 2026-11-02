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

/**
 * A measure of a cube (MR_CUBE_LENS.md section 4.1). Its class decides how a total is made, and the core never mixes them up:
 *
 * <ul>
 *   <li>{@link Kind#ADDITIVE}: the sum of the members (PV, a sensitivity by bucket, a trade count, a component); a total equals the sum of
 *       its cells, so an export writes it as a {@code SUM} formula.</li>
 *   <li>{@link Kind#VECTOR}: re-aggregated from the members' vectors (VaR, ES, stressed VaR; PFE on the counterparty cube): every cell and
 *       every total is its own set's statistic, never a sum of other cells; the gap is shown as diversification.</li>
 *   <li>{@link Kind#FORMULA}: the members' inputs summed to the formula's grain, then the formula (FRTB-SA's SBM; SA-CCR, SIMM on the
 *       counterparty cube): non-additive too, each cell and total its own run.</li>
 * </ul>
 *
 * @param id the word a view and the command line name it by ({@code VAR}, {@code COMP_VAR}, {@code DV01})
 * @param label what a column header says
 * @param kind how a total is made
 * @param unit {@code money}, {@code count} or {@code pct}
 * @param description a sentence for Help and the provenance popover
 * @param needs what must be on for it ("" when nothing beyond the cube's own engine; {@code sensitivities}, {@code frtb-sa}): a measure
 *     whose engine is off is not offered (COMPUTE_ENGINES.md, a "uses" link)
 */
public record CubeMeasure(String id, String label, Kind kind, String unit, String description, String needs) {

    /** How a cell's and a total's figure are made. */
    public enum Kind {
        ADDITIVE, VECTOR, FORMULA;

        /** True when a total is the sum of its parts (an export may write it as a formula). */
        public boolean additive() {
            return this == ADDITIVE;
        }
    }

    public CubeMeasure {
        needs = needs == null ? "" : needs;
    }
}
