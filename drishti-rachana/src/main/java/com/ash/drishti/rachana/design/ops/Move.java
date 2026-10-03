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
package com.ash.drishti.rachana.design.ops;

import com.ash.drishti.rachana.SutraLayoutEditor;
import com.ash.drishti.rachana.SutraLayoutEditor.Placement;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves a panel to another place and/or column and sizes it, through {@link SutraLayoutEditor} (so the panels' own text
 * and the comments above them move with them). Without {@code before} and {@code after} it goes to the end of its column.
 *
 * @param panel the panel's id
 * @param area {@code main} or {@code right}; null keeps the panel's column
 * @param before place it before this panel (or null)
 * @param after place it after this panel (or null)
 * @param span width in columns of the 12-column grid (12 means the whole column); null keeps the current width
 * @param height height in grid rows (0 means as tall as its content); null keeps the current height
 */
public record Move(String panel, String area, String before, String after, Integer span, Integer height) implements Op {

    @Override
    public String name() {
        return "move";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        List<Panel> panels = doc.sutra().panels();
        Panel moved = doc.panel(panel);
        if (before != null && after != null) {
            throw new OpException(OpException.MALFORMED, "'move' takes before or after, not both");
        }
        String near = before != null ? before : after;
        if (panel.equals(near)) {
            throw new OpException(OpException.BAD_VALUE, "a panel cannot be placed next to itself");
        }
        Area to = area != null ? OpChecks.area(area) : near != null ? doc.panel(near).area() : moved.area();
        if (span != null) {
            OpChecks.range(Panel.SPAN, (long) span, Panel.MAX_SPAN);
        }
        if (height != null && height != 0) {
            OpChecks.range(Panel.HEIGHT, (long) height, Panel.MAX_HEIGHT);
        }
        List<Panel> rest = new ArrayList<>(panels);
        rest.remove(moved);
        int at;
        if (near != null) {
            int target = -1;
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i).id().equals(near)) {
                    target = i;
                }
            }
            at = before != null ? target : target + 1;
        } else {
            at = AddPanel.afterColumn(rest, to);
        }
        List<Placement> placements = new ArrayList<>();
        for (Panel p : rest) {
            placements.add(new Placement(p.id(), p.area(), p.span().orElse(null), p.height().orElse(null), false));
        }
        Placement now = new Placement(panel, to, span != null ? span : moved.span().orElse(null),
                height != null ? (height == 0 ? null : height) : moved.height().orElse(null), false);
        placements.add(Math.min(at, placements.size()), now);
        try {
            doc.replaceAll(new SutraLayoutEditor().apply(doc.text(), placements, false, false).text());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new OpException(OpException.UNEDITABLE, e.getMessage());
        }
    }
}
