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

import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a panel of one kind with the options given (a kind's required options, such as {@code rows} for a table, must be
 * among them: a panel that the parser would refuse is not added). Without {@code at} it goes at the end of the main
 * column.
 *
 * @param id the new panel's id; null makes one from the kind ({@code table}, {@code table2}, ...)
 * @param kind one of the twenty panel kinds
 * @param at where it goes; null: the end of the main column
 * @param options option name to value, in the order they are written; may also hold {@code title}, {@code key}, {@code code}
 */
public record AddPanel(String id, String kind, At at, Map<String, Object> options) implements Op {

    /**
     * Where a new panel goes.
     *
     * @param area {@code main} or {@code right}; null: the area of the panel it is placed next to, else main
     * @param before place it before this panel (or null)
     * @param after place it after this panel (or null)
     * @param span width in columns of the 12-column grid; null or 12: the whole column
     * @param height height in grid rows; null: as tall as its content
     */
    public record At(String area, String before, String after, Integer span, Integer height) {}

    @Override
    public String name() {
        return "addPanel";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (kind == null) {
            throw new OpException(OpException.MALFORMED, "addPanel needs a 'kind'");
        }
        PanelKind k = OpChecks.kind(kind);
        List<Panel> panels = doc.sutra().panels();
        String newId = id != null ? id : fresh(panels, k);
        if (!OpChecks.ID.matcher(newId).matches()) {
            throw new OpException(OpException.BAD_VALUE, "a panel id starts with a letter and has letters, digits, - and _ (up to 64), not '" + newId + "'");
        }
        if (panels.stream().anyMatch(p -> p.id().equals(newId))) {
            throw new OpException(OpException.BAD_VALUE, "there is already a panel '" + newId + "'");
        }
        At where = at == null ? new At(null, null, null, null, null) : at;
        if (where.before() != null && where.after() != null) {
            throw new OpException(OpException.MALFORMED, "'at' takes before or after, not both");
        }
        int target = -1;
        String next = where.before() != null ? where.before() : where.after();
        if (next != null) {
            target = indexOf(panels, next);
        }
        Area area = where.area() != null ? OpChecks.area(where.area()) : target >= 0 ? panels.get(target).area() : Area.MAIN;
        int position = target >= 0 ? (where.before() != null ? target : target + 1) : afterColumn(panels, area);

        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("id", newId);
        keys.put("kind", k.id());
        if (options != null) {
            options.forEach((name, value) -> {
                Object v = OpChecks.plain(value);
                OpChecks.option(k, name, v);
                if (v != null && !"area".equals(name) && !Panel.SPAN.equals(name) && !Panel.HEIGHT.equals(name)) {
                    keys.put(name, v);
                }
            });
        }
        if (area == Area.RIGHT) {
            keys.put("area", "right");
        }
        Integer span = where.span() != null ? where.span() : options != null && options.get(Panel.SPAN) instanceof Number n ? n.intValue() : null;
        if (span != null && span != Panel.MAX_SPAN) {
            OpChecks.range(Panel.SPAN, (long) span, Panel.MAX_SPAN);
            keys.put(Panel.SPAN, (long) span);
        }
        Integer height = where.height() != null ? where.height() : options != null && options.get(Panel.HEIGHT) instanceof Number n ? n.intValue() : null;
        if (height != null) {
            OpChecks.range(Panel.HEIGHT, (long) height, Panel.MAX_HEIGHT);
            keys.put(Panel.HEIGHT, (long) height);
        }
        doc.insertPanel(position, keys);
    }

    private static int indexOf(List<Panel> panels, String id) {
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new OpException(OpException.NO_PANEL, "no panel '" + id + "' in this Sutra");
    }

    /** One past the last panel of {@code area}; the end of the list when the area has none. */
    static int afterColumn(List<Panel> panels, Area area) {
        int last = -1;
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).area() == area) {
                last = i;
            }
        }
        return last < 0 ? panels.size() : last + 1;
    }

    private static String fresh(List<Panel> panels, PanelKind k) {
        String id = k.id();
        for (int n = 2; ; n++) {
            String candidate = id;
            if (panels.stream().noneMatch(p -> p.id().equals(candidate))) {
                return candidate;
            }
            id = k.id() + n;
        }
    }
}
