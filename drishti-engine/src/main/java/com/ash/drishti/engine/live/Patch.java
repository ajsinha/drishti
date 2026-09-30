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
package com.ash.drishti.engine.live;

import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One change to a rendered view.
 *
 * @param op {@code strip} (one header figure), {@code panel} (a whole panel) or {@code provenance}
 * @param index strip position for {@code strip}
 * @param cell the new strip cell
 * @param panel the new panel for {@code panel}
 * @param provenance the new provenance
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Patch(String op, Integer index, ViewModel.Cell cell, ViewModel.PanelView panel, ViewModel.Provenance provenance) {

    static Patch strip(int i, ViewModel.Cell c) {
        return new Patch("strip", i, c, null, null);
    }

    static Patch panel(ViewModel.PanelView p) {
        return new Patch("panel", null, null, p, null);
    }

    static Patch provenance(ViewModel.Provenance p) {
        return new Patch("provenance", null, null, null, p);
    }
}
