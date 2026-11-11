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
 * @param op {@code strip} (one header figure), {@code panel} (a whole panel), {@code provenance}, {@code deleted} (the
 *     source deleted the entity: the client keeps what it shows but says so) or {@code restored} (a deleted entity came
 *     back; the patches after it bring the view up to date)
 * @param index strip position for {@code strip}
 * @param cell the new strip cell
 * @param panel the new panel for {@code panel}
 * @param provenance the new provenance
 * @param at when the source deleted the entity, for {@code deleted}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Patch(String op, Integer index, ViewModel.Cell cell, ViewModel.PanelView panel, ViewModel.Provenance provenance,
        java.time.Instant at) {

    public Patch(String op, Integer index, ViewModel.Cell cell, ViewModel.PanelView panel, ViewModel.Provenance provenance) {
        this(op, index, cell, panel, provenance, null);
    }

    static Patch strip(int i, ViewModel.Cell c) {
        return new Patch("strip", i, c, null, null);
    }

    static Patch panel(ViewModel.PanelView p) {
        return new Patch("panel", null, null, p, null);
    }

    static Patch provenance(ViewModel.Provenance p) {
        return new Patch("provenance", null, null, null, p);
    }

    static Patch deleted(java.time.Instant at) {
        return new Patch("deleted", null, null, null, null, at);
    }

    static Patch restored() {
        return new Patch("restored", null, null, null, null);
    }
}
