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
package com.ash.drishti.engine.design;

import com.ash.drishti.engine.view.ViewModel;
import java.util.List;
import java.util.Map;

/**
 * An auto-designed screen.
 *
 * @param yaml the drafted Sutra
 * @param reasons why each decision was made: {@code title}, {@code strip}, {@code strip.<label>} and one entry per panel id
 * @param alternatives per panel id, the runner-up kinds with options filled, best first
 * @param pruned panels (and strip figures) dropped or demoted because the samples could not fill them
 * @param preview the draft rendered against the first sample, or null when no previewer was given
 * @param samples how many samples the draft was checked against
 */
public record Design(String yaml, Map<String, String> reasons, Map<String, List<PanelChoice>> alternatives, List<Pruned> pruned,
        ViewModel preview, int samples) {

    /**
     * A panel the samples could not fill.
     *
     * @param panel the panel id (a strip figure: its label)
     * @param kind the panel kind ({@code strip} for a figure)
     * @param action {@code dropped} or {@code demoted} (to the kind in {@code to})
     * @param to the kind it was demoted to, or null
     * @param bad in how many samples it came out empty or with an error
     * @param of how many samples were checked
     * @param reason a sentence saying why
     */
    public record Pruned(String panel, String kind, String action, String to, int bad, int of, String reason) {}
}
