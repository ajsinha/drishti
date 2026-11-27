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

package org.helixlab.genomics;

import static org.helixlab.genomics.ShowcaseData.num;
import static org.helixlab.genomics.ShowcaseData.str;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which samples a call is about (modules/genomics/docs/guide.md section 4, the channel {@code genomics.samples}): a cohort, then
 * optionally clusters ({@code cluster=C3,C1}), a rectangle on the map of samples ({@code box=x0,x1,y0,y1} in the embedding's values) or
 * named samples ({@code samples=S1,S2}, at most 3,000). The page sends what was clicked or drawn; the server finds the members.
 *
 * @param cohort the cohort ({@code ALL} or empty: every sample)
 * @param clusters the clusters (empty: any)
 * @param box x0, x1, y0, y1 or null
 * @param samples the samples named (empty: any)
 */
public record Selection(String cohort, Set<String> clusters, double[] box, Set<String> samples) {

    static final int MOST_NAMED = 3000;

    /** Reads the request's parameters (blank ones are none); a box that does not read is refused in words. */
    public static Selection of(String cohort, String cluster, String box, String samples) {
        double[] b = null;
        if (box != null && !box.isBlank()) {
            String[] p = box.split(",");
            if (p.length != 4) {
                throw new IllegalArgumentException("box is x0,x1,y0,y1 (four numbers), not " + box);
            }
            b = new double[4];
            for (int i = 0; i < 4; i++) {
                b[i] = num(p[i]);
                if (Double.isNaN(b[i])) {
                    throw new IllegalArgumentException("box is x0,x1,y0,y1 (four numbers), not " + box);
                }
            }
            b = new double[] {Math.min(b[0], b[1]), Math.max(b[0], b[1]), Math.min(b[2], b[3]), Math.max(b[2], b[3])};
        }
        Set<String> named = list(samples);
        if (named.size() > MOST_NAMED) {
            throw new IllegalArgumentException("at most " + MOST_NAMED + " samples are named in one call (" + named.size() + " were): send a cluster or a box");
        }
        return new Selection(cohort == null ? "" : cohort.trim(), list(cluster), b, named);
    }

    /** Whether anything narrows the cohort. */
    public boolean narrowed() {
        return !clusters.isEmpty() || box != null || !samples.isEmpty();
    }

    /** Whether a sample's row is a member. */
    public boolean has(Map<String, Object> s) {
        if (!cohort.isEmpty() && !cohort.equalsIgnoreCase("ALL") && !cohort.equalsIgnoreCase(str(s.get("COHORT")))) {
            return false;
        }
        if (!clusters.isEmpty() && !clusters.contains(str(s.get("CLUSTER")))) {
            return false;
        }
        if (!samples.isEmpty() && !samples.contains(str(s.get("SAMPLE_ID")))) {
            return false;
        }
        if (box != null) {
            double x = num(s.get("EMB_X")), y = num(s.get("EMB_Y"));
            return x >= box[0] && x <= box[1] && y >= box[2] && y <= box[3];
        }
        return true;
    }

    /** The members among the sample rows, in their order. */
    public Set<String> members(List<Map<String, Object>> sampleRows) {
        Set<String> out = new LinkedHashSet<>();
        sampleRows.stream().filter(this::has).forEach(s -> out.add(str(s.get("SAMPLE_ID"))));
        return out;
    }

    /** The selection in words ("cohort ONC-1, cluster C3 immune-hot, a rectangle on the map"). */
    public String words() {
        List<String> w = new ArrayList<>();
        w.add(cohort.isEmpty() || cohort.equalsIgnoreCase("ALL") ? "every cohort" : "cohort " + cohort);
        if (!clusters.isEmpty()) {
            w.add((clusters.size() == 1 ? "cluster " : "clusters ") + String.join(", ", new TreeSet<>(clusters)));
        }
        if (box != null) {
            w.add("a rectangle on the map of samples");
        }
        if (!samples.isEmpty()) {
            w.add(samples.size() + " named samples");
        }
        return String.join(", ", w);
    }

    /** The request's parameters again, for a link ("cluster=..&box=.."). */
    public Map<String, String> params() {
        Map<String, String> p = new HashMap<>();
        if (!clusters.isEmpty()) {
            p.put("cluster", String.join(",", clusters));
        }
        if (box != null) {
            p.put("box", Arrays.stream(box).mapToObj(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        }
        return p;
    }

    private static Set<String> list(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) {
            for (String p : s.split(",")) {
                if (!p.isBlank()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    /** A sample responded: RESPONSE is a yes word (responder, response, CR, PR, yes, true, 1). */
    public static boolean responded(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = str(v).trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return s.equals("responder") || s.equals("response") || s.equals("yes") || s.equals("y") || s.equals("true") || s.equals("1") || s.equals("cr") || s.equals("pr")
                || s.equals("complete response") || s.equals("partial response") || s.equals("r");
    }
}
