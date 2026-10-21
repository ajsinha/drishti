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

import java.util.function.BiPredicate;

/**
 * What the board check needs besides the board (UI_BOARD.md section 5).
 *
 * @param chips where {@code use: name@version} is found (board-local chips are looked up in the board first)
 * @param suppliers the suppliers as the author sees them; null skips the supplier rules (attribute existence of BC-05, BC-08, BC-W5)
 * @param limits the size limits (BC-09)
 * @param shared true for a board shared in a workspace: only released chip versions (BC-11)
 * @param certifiedOnly true when the workspace demands certified chips (BC-11)
 * @param subtype {@code subtype.test(k, j)}: the pack declares kind K a subtype of kind J (4.2)
 * @param fragment true for a chip manual's example: {@code rachana}, {@code board} and the layout may be left out, BC-11 is not applied
 */
public record BoardCheckContext(ChipCatalog chips, SupplierCatalog suppliers, Limits limits, boolean shared, boolean certifiedOnly,
        BiPredicate<String, String> subtype, boolean fragment) {

    public BoardCheckContext {
        chips = chips == null ? ChipCatalog.empty() : chips;
        limits = limits == null ? Limits.DEFAULTS : limits;
        subtype = subtype == null ? PinCompatibility.NO_SUBTYPES : subtype;
    }

    /** A personal board checked against {@code chips}, no supplier catalogue, the default limits. */
    public static BoardCheckContext personal(ChipCatalog chips) {
        return new BoardCheckContext(chips, null, Limits.DEFAULTS, false, false, null, false);
    }

    public BoardCheckContext withSuppliers(SupplierCatalog s) {
        return new BoardCheckContext(chips, s, limits, shared, certifiedOnly, subtype, fragment);
    }

    public BoardCheckContext asShared(boolean certified) {
        return new BoardCheckContext(chips, suppliers, limits, true, certified, subtype, fragment);
    }

    public BoardCheckContext asFragment() {
        return new BoardCheckContext(chips, suppliers, limits, shared, certifiedOnly, subtype, true);
    }

    /**
     * The size limits of UI_BOARD.md 7.3 that the static check enforces ({@code config/ui/ui.yaml} in P3).
     *
     * @param bytes the board document's size
     * @param chips chip instances per board
     * @param traces traces per board
     * @param moduleDepth module nesting depth
     */
    public record Limits(int bytes, int chips, int traces, int moduleDepth) {

        public static final Limits DEFAULTS = new Limits(64 * 1024, 40, 120, 3);
    }
}
