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
package com.ash.drishti.server.bi.gov;

import com.ash.drishti.server.bi.report.ReportYaml;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import java.util.Map;

/**
 * Rewrites the names a report uses of its dataset when the dataset renamed them (docs/guides/BI_GOVERNANCE.md, "Renames"). It changes only the places a
 * report names a dataset field or measure: the wells of a visual, the {@code field} and {@code by} of a filter (report, page, visual and bookmark),
 * a visual's sort, hierarchy and drill-through, and a page's drill-through fields; titles, descriptions and ids are never touched. The report is written back
 * by {@link ReportYaml}, the way the designer writes one (comments in the old text are not kept).
 */
public final class ReportRenamer {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The result: the new text and how many names were replaced. */
    public record Result(String body, int replaced) {}

    private ReportRenamer() {}

    /** @param renames the renames of field ({@code table.column}) and measure names, old to new; a date part after a field ({@code trades.date.year}) is kept */
    public static Result apply(String body, List<Surface.Rename> renames) {
        if (renames.isEmpty()) {
            return new Result(body, 0);
        }
        Map<String, Object> model;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = YAML.readValue(body, Map.class);
            model = m;
        } catch (java.io.IOException e) {
            return new Result(body, 0);
        }
        int[] n = {0};
        renameFilters(model.get("filters"), renames, n);
        if (model.get("bookmarks") instanceof List<?> bms) {
            for (Object b : bms) {
                if (b instanceof Map<?, ?> bm) {
                    renameFilters(bm.get("filters"), renames, n);
                }
            }
        }
        if (model.get("pages") instanceof List<?> pages) {
            for (Object p : pages) {
                if (!(p instanceof Map<?, ?> page)) {
                    continue;
                }
                renameFilters(page.get("filters"), renames, n);
                if (page.get("drillthrough") instanceof Map<?, ?> dt) {
                    renameList(dt.get("fields"), renames, n);
                }
                if (page.get("visuals") instanceof List<?> visuals) {
                    for (Object v : visuals) {
                        if (v instanceof Map<?, ?> visual) {
                            renameVisual(visual, renames, n);
                        }
                    }
                }
            }
        }
        return n[0] == 0 ? new Result(body, 0) : new Result(ReportYaml.write(model), n[0]);
    }

    private static void renameVisual(Map<?, ?> v, List<Surface.Rename> renames, int[] n) {
        if (v.get("wells") instanceof Map<?, ?> wells) {
            wells.values().forEach(l -> renameList(l, renames, n));
        }
        renameFilters(v.get("filters"), renames, n);
        if (v.get("sort") instanceof Map<?, ?> sort) {
            rename(sort, "by", renames, n);
        }
        if (v.get("drillthrough") instanceof Map<?, ?> dt) {
            rename(dt, "field", renames, n);
        }
        rename(v, "hierarchy", renames, n);
    }

    private static void renameFilters(Object filters, List<Surface.Rename> renames, int[] n) {
        if (filters instanceof List<?> l) {
            for (Object f : l) {
                if (f instanceof Map<?, ?> fm) {
                    rename(fm, "field", renames, n);
                    rename(fm, "by", renames, n);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void renameList(Object list, List<Surface.Rename> renames, int[] n) {
        if (list instanceof List<?> l) {
            List<Object> target = (List<Object>) l;
            for (int i = 0; i < target.size(); i++) {
                if (target.get(i) instanceof String s) {
                    String to = swap(s, renames);
                    if (!to.equals(s)) {
                        target.set(i, to);
                        n[0]++;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void rename(Map<?, ?> m, String key, List<Surface.Rename> renames, int[] n) {
        if (m.get(key) instanceof String s) {
            String to = swap(s, renames);
            if (!to.equals(s)) {
                ((Map<String, Object>) m).put(key, to);
                n[0]++;
            }
        }
    }

    private static String swap(String name, List<Surface.Rename> renames) {
        for (Surface.Rename r : renames) {
            if (name.equals(r.from())) {
                return r.to();
            }
            if ("field".equals(r.kind()) && name.startsWith(r.from() + ".")) {          // a date part: trades.tradeDate.year
                return r.to() + name.substring(r.from().length());
            }
        }
        return name;
    }
}
