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

import static org.helixlab.genomics.ShowcaseData.integer;
import static org.helixlab.genomics.ShowcaseData.num;
import static org.helixlab.genomics.ShowcaseData.round;
import static org.helixlab.genomics.ShowcaseData.str;
import static org.helixlab.genomics.ShowcaseData.yes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The protein workbench's answers (modules/genomics/docs/guide.md sections 4, 5 and 9): the deep mutational scan with its residue
 * tracks, the structure, the design campaign by round over its fitness surface, the rounds' quantiles, the leaderboard, and the Explain
 * of a cell of the scan and of a design. A protein is named by itself or by its gene (HLXK7). The console draws; it computes nothing.
 */
public final class ProteinBench {

    /** The order of the changes in the scan (by kind: hydrophobic, aromatic, special, polar, charged; the stop last). */
    static final List<String> AAS = List.of("A", "V", "I", "L", "M", "F", "W", "Y", "C", "G", "P", "S", "T", "N", "Q", "D", "E", "K", "R", "H", "*");
    /** Each round's designs drawn: its best 50 and an even sample of the rest, to about this many. */
    static final int PER_ROUND = 180, TOP = 50, FIELD = 24;

    private final ShowcaseData data;
    private final GenomicsBoard board;

    public ProteinBench(ShowcaseData data, GenomicsBoard board) {
        this.data = data;
        this.board = board;
    }

    private void needs(String... kinds) {
        List<String> missing = data.now().missing(List.of(kinds));
        if (!missing.isEmpty()) {
            throw new NoSuchElementException("the showcase's data is not loaded here: no rows of " + String.join(", ", missing)
                    + " (load the packs genomics-showcase and protein-engineering: drishti.py lake demo)");
        }
    }

    /** The protein's row (by its name or its gene's), or a refusal in words. */
    Map<String, Object> protein(String id) {
        needs("protein");
        Map<String, Object> p = board.proteinOf(id);
        if (p == null) {
            throw new NoSuchElementException("no protein " + id + " (there are " + data.now().of("protein").stream().map(x -> str(x.get("PROTEIN")) + " (" + str(x.get("GENE")) + ")")
                    .collect(Collectors.joining(", ")) + ")");
        }
        return p;
    }

    /** The proteins: name, gene, length, family. */
    public Map<String, Object> proteins() {
        needs("protein");
        return Map.of("rows", data.now().of("protein").stream().map(p -> Explained.pick(p, List.of("PROTEIN", "GENE", "LENGTH", "FAMILY"))).toList());
    }

    private List<Map<String, Object>> residues(String protein) {
        return data.now().of("residue").stream().filter(r -> str(r.get("PROTEIN")).equals(protein)).sorted(Comparator.comparingDouble(r -> num(r.get("POS")))).toList();
    }

    // ---- the scan ------------------------------------------------------------------------------------------------------------------

    /** The scan of one assay: every measured change (POS, AA, SCORE), the residues' tracks, the assays. */
    public Map<String, Object> dms(String id, String assay) {
        needs("protein", "residue", "dms-score");
        Map<String, Object> p = protein(id);
        String name = str(p.get("PROTEIN"));
        List<String> assays = board.assays(name);
        String as = assay == null || assay.isBlank() ? assays.stream().findFirst().orElse("") : assay;
        if (assays.stream().noneMatch(a -> a.equalsIgnoreCase(as))) {
            throw new NoSuchElementException("no assay " + as + " of " + name + " (there are " + String.join(", ", assays) + ")");
        }
        return data.cached("dms|" + name + "|" + as, () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> present = new HashSet<>();
            data.now().of("dms-score").forEach(d -> {
                if (str(d.get("PROTEIN")).equals(name) && str(d.get("ASSAY")).equalsIgnoreCase(as)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ID", integer(d.get("POS"), 0) + ":" + str(d.get("AA")));
                    r.put("POS", integer(d.get("POS"), 0));
                    r.put("AA", str(d.get("AA")));
                    r.put("SCORE", round(num(d.get("SCORE")), 3));
                    rows.add(r);
                    present.add(str(d.get("AA")));
                }
            });
            List<String> aas = new ArrayList<>(AAS.stream().filter(present::contains).toList());
            present.stream().filter(a -> !AAS.contains(a)).sorted().forEach(aas::add);
            List<Map<String, Object>> res = residues(name).stream().map(r -> Explained.pick(r, List.of("POS", "AA", "SECONDARY", "DOMAIN", "BURIAL", "ACTIVE_SITE"))).toList();
            long helps = rows.stream().filter(r -> num(r.get("SCORE")) > 0.5).count(), hurts = rows.stream().filter(r -> num(r.get("SCORE")) <= GenomicsBoard.DAMAGING).count();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protein", name);
            out.put("gene", p.get("GENE"));
            out.put("length", p.get("LENGTH"));
            out.put("assay", as);
            out.put("assays", assays);
            out.put("aas", aas);
            out.put("rows", rows);
            out.put("residues", res);
            out.put("explain", new Explained("The scan of " + name + " (" + as + ")", rows.size() + " measurements").scope(name)
                    .step("Every position and change measured for " + as, "dms-score", rows.size()).step("The residues' fold, burial and active site", "residue", res.size())
                    .sentence(String.format(Locale.ROOT, "%d of %d changes hurt the protein badly; %d help it. 0 is the natural protein.", hurts, rows.size(), helps)).build());
            return out;
        });
    }

    // ---- the structure -------------------------------------------------------------------------------------------------------------

    /** The structure: each residue with its coordinates, fold, burial, active site and mean effects. */
    public Map<String, Object> structure(String id) {
        needs("protein", "residue");
        Map<String, Object> p = protein(id);
        String name = str(p.get("PROTEIN"));
        List<Map<String, Object>> rows = residues(name).stream()
                .map(r -> Explained.pick(r, List.of("POS", "AA", "X", "Y", "Z", "SECONDARY", "DOMAIN", "BURIAL", "ACTIVE_SITE", "MEAN_ACTIVITY", "MEAN_STABILITY"))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protein", name);
        out.put("gene", p.get("GENE"));
        out.put("rows", rows);
        out.put("activeSite", rows.stream().filter(r -> yes(r.get("ACTIVE_SITE"))).map(r -> r.get("POS")).toList());
        out.put("explain", new Explained("The structure of " + name, rows.size() + " residues").scope(name).step("The residues with their coordinates", "residue", rows.size())
                .sentence("One point per residue, in chain order, coloured by the mean effect of every change there; the coordinates are synthetic.").build());
        return out;
    }

    /** A residue: its twenty changes in each assay and the designs that carry a change there. */
    public Map<String, Object> residue(String id, int pos) {
        needs("protein", "residue", "dms-score");
        String name = str(protein(id).get("PROTEIN"));
        Map<String, Object> res = residues(name).stream().filter(r -> integer(r.get("POS"), -1) == pos).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no position " + pos + " in " + name));
        List<Map<String, Object>> options = data.now().of("dms-score").stream().filter(d -> str(d.get("PROTEIN")).equals(name) && integer(d.get("POS"), -1) == pos)
                .map(d -> Explained.pick(d, List.of("ASSAY", "AA", "SCORE", "SE", "REPLICATES"))).toList();
        Set<String> designs = designsWith(name, pos, null);
        Map<String, Object> out = new LinkedHashMap<>(Explained.pick(res, List.of("POS", "AA", "SECONDARY", "DOMAIN", "BURIAL", "ACTIVE_SITE", "MEAN_ACTIVITY", "MEAN_STABILITY")));
        out.put("protein", name);
        out.put("options", options);
        out.put("designs", designs.size());
        out.put("explain", new Explained("Position " + pos + " of " + name + " (" + str(res.get("AA")) + ")", options.size() + " measurements").scope(name)
                .step("The residue", "residue", 1).step("Every change measured there", "dms-score", options.size()).step("Designs with a change there", "design-mutation", designs.size())
                .sentence(String.format(Locale.ROOT, "Position %d is %s%s; %d designs change it.", pos, str(res.get("SECONDARY")).isEmpty() ? "a residue" : "in a " + str(res.get("SECONDARY")),
                        yes(res.get("ACTIVE_SITE")) ? " in the active site" : "", designs.size()))
                .records(List.of("ASSAY", "AA", "SCORE", "SE", "REPLICATES"), options).build());
        return out;
    }

    /** The designs of a protein with a change at a position (to an amino acid, when given). */
    Set<String> designsWith(String protein, int pos, String aa) {
        Set<String> ids = data.now().of("design").stream().filter(d -> str(d.get("PROTEIN")).equals(protein)).map(d -> str(d.get("DESIGN_ID"))).collect(Collectors.toSet());
        Set<String> out = new HashSet<>();
        data.now().of("design-mutation").forEach(m -> {
            if (integer(m.get("POS"), -1) == pos && (aa == null || aa.isBlank() || str(m.get("AA_TO")).equals(aa)) && ids.contains(str(m.get("DESIGN_ID")))) {
                out.add(str(m.get("DESIGN_ID")));
            }
        });
        return out;
    }

    // ---- the campaign --------------------------------------------------------------------------------------------------------------

    private List<Map<String, Object>> designs(String protein) {
        return data.now().of("design").stream().filter(d -> str(d.get("PROTEIN")).equals(protein)).toList();
    }

    /** The campaign: each round's best 50 and an even sample of the rest; each round's best; the fitness surface; the natural protein. */
    public Map<String, Object> campaign(String id, Integer residue, String aa) {
        needs("protein", "design");
        String name = str(protein(id).get("PROTEIN"));
        Map<String, Object> base = data.cached("campaign|" + name, () -> {
            Map<Integer, List<Map<String, Object>>> byRound = new TreeMap<>();
            designs(name).forEach(d -> byRound.computeIfAbsent(integer(d.get("ROUND"), 0), k -> new ArrayList<>()).add(d));
            List<Map<String, Object>> rows = new ArrayList<>(), best = new ArrayList<>();
            List<String> fields = List.of("DESIGN_ID", "ROUND", "EMB_X", "EMB_Y", "ACTIVITY", "DELTA_TM", "N_CHANGES");
            Map<String, Object> wild = null;
            for (Map.Entry<Integer, List<Map<String, Object>>> e : byRound.entrySet()) {
                List<Map<String, Object>> all = new ArrayList<>(e.getValue());
                all.sort(Comparator.comparingDouble(d -> -num(d.get("ACTIVITY"))));
                if (e.getKey() <= 0 && wild == null) {
                    wild = Explained.pick(all.get(0), fields);
                    continue;
                }
                best.add(Explained.pick(all.get(0), fields));
                List<Map<String, Object>> rest = all.subList(Math.min(TOP, all.size()), all.size());
                int room = Math.max(0, PER_ROUND - TOP), every = rest.isEmpty() || room >= rest.size() ? 1 : (int) Math.ceil(rest.size() / (double) room);
                all.subList(0, Math.min(TOP, all.size())).forEach(d -> rows.add(Explained.pick(d, fields)));
                for (int i = 0; i < rest.size(); i += every) {
                    rows.add(Explained.pick(rest.get(i), fields));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protein", name);
            out.put("rounds", new ArrayList<>(byRound.keySet().stream().filter(r -> r > 0).toList()));
            out.put("total", byRound.values().stream().mapToInt(List::size).sum());
            out.put("rows", rows);
            out.put("best", best);
            out.put("wildType", wild);
            out.put("field", surface(designs(name)));
            return out;
        });
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (residue != null) {
            Set<String> with = designsWith(name, residue, aa);
            out.put("carrying", ((List<?>) base.get("rows")).stream().map(r -> str(((Map<?, ?>) r).get("DESIGN_ID"))).filter(with::contains).toList());
            out.put("carryingTotal", with.size());
        }
        int total = (int) base.get("total"), drawn = ((List<?>) base.get("rows")).size();
        List<?> best = (List<?>) base.get("best");
        Map<?, ?> last = best.isEmpty() ? Map.of() : (Map<?, ?>) best.get(best.size() - 1);
        out.put("explain", new Explained("The design campaign of " + name, total + " designs").scope(name)
                .step("Every design of every round", "design", total).step("Each round's best " + TOP + " and an even sample of the rest, drawn", "design", drawn)
                .step("The fitness surface: the mean activity of the designs in each cell of the map", "design", total)
                .sentence(best.isEmpty() ? "No design rounds yet." : String.format(Locale.ROOT, "%d rounds; the best design, %s, is %s times as active as the natural protein.", best.size(),
                        str(last.get("DESIGN_ID")), round(num(last.get("ACTIVITY")), 1))).build());
        return out;
    }

    /** The surface under the campaign: the mean activity in each cell of a grid over the map (cells with no design left out). */
    static Map<String, Object> surface(List<Map<String, Object>> designs) {
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Map<String, Object> d : designs) {
            double x = num(d.get("EMB_X")), y = num(d.get("EMB_Y"));
            if (!Double.isNaN(x) && !Double.isNaN(y)) {
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
        }
        if (x0 > x1) {
            return Map.of("rows", List.of(), "cell", List.of(1, 1));
        }
        double dx = Math.max(1e-9, (x1 - x0) / FIELD), dy = Math.max(1e-9, (y1 - y0) / FIELD);
        double[][] sum = new double[FIELD + 1][FIELD + 1];
        int[][] n = new int[FIELD + 1][FIELD + 1];
        for (Map<String, Object> d : designs) {
            double x = num(d.get("EMB_X")), y = num(d.get("EMB_Y")), a = num(d.get("ACTIVITY"));
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(a)) {
                continue;
            }
            int i = (int) Math.min(FIELD, Math.floor((x - x0) / dx)), j = (int) Math.min(FIELD, Math.floor((y - y0) / dy));
            sum[i][j] += a;
            n[i][j]++;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= FIELD; i++) {
            for (int j = 0; j <= FIELD; j++) {
                if (n[i][j] > 0) {
                    rows.add(Map.of("x", round(x0 + (i + 0.5) * dx, 4), "y", round(y0 + (j + 0.5) * dy, 4), "value", round(sum[i][j] / n[i][j], 3)));
                }
            }
        }
        return Map.of("rows", rows, "cell", List.of(round(dx, 4), round(dy, 4)));
    }

    /** Activity by round: each round's quantiles (5, 25, 50, 75, 95), its count, its best and its method. */
    public Map<String, Object> rounds(String id) {
        needs("protein", "design");
        String name = str(protein(id).get("PROTEIN"));
        Map<Integer, List<Double>> by = new TreeMap<>();
        designs(name).forEach(d -> {
            double a = num(d.get("ACTIVITY"));
            if (!Double.isNaN(a) && integer(d.get("ROUND"), 0) > 0) {
                by.computeIfAbsent(integer(d.get("ROUND"), 0), k -> new ArrayList<>()).add(a);
            }
        });
        Map<Integer, Map<String, Object>> method = new HashMap<>();
        data.now().of("design-round").stream().filter(r -> str(r.get("PROTEIN")).equals(name)).forEach(r -> method.put(integer(r.get("ROUND"), 0), r));
        List<Map<String, Object>> rows = new ArrayList<>();
        by.forEach((r, xs) -> {
            double[] v = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ROUND", String.valueOf(r));
            row.put("LOW", round(q(v, 0.05), 3));
            row.put("Q1", round(q(v, 0.25), 3));
            row.put("MEDIAN", round(q(v, 0.5), 3));
            row.put("Q3", round(q(v, 0.75), 3));
            row.put("HIGH", round(q(v, 0.95), 3));
            row.put("N", v.length);
            row.put("BEST", round(v[v.length - 1], 3));
            row.put("METHOD", method.containsKey(r) ? str(method.get(r).get("METHOD")) : "");
            rows.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protein", name);
        out.put("rows", rows);
        out.put("explain", new Explained("Activity by round, " + name, rows.size() + " rounds").scope(name)
                .step("The designs by round", "design", by.values().stream().mapToInt(List::size).sum()).step("Each round's 5th, 25th, 50th, 75th and 95th percentiles", "design", rows.size())
                .sentence("Each box is one round: the line its median, the box its middle half, the whiskers the 5th and 95th percentiles; 1 is the natural protein.").build());
        return out;
    }

    /** A quantile of sorted values (linear between the two nearest). */
    static double q(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double at = p * (sorted.length - 1);
        int lo = (int) Math.floor(at), hi = (int) Math.ceil(at);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo);
    }

    /** The leaderboard: the best designs up to a round, each with its changes. */
    public Map<String, Object> leaderboard(String id, Integer round, int n) {
        needs("protein", "design");
        String name = str(protein(id).get("PROTEIN"));
        int upTo = round == null ? Integer.MAX_VALUE : round;
        List<Map<String, Object>> top = designs(name).stream().filter(d -> integer(d.get("ROUND"), 0) > 0 && integer(d.get("ROUND"), 0) <= upTo)
                .sorted(Comparator.comparingDouble(d -> -num(d.get("ACTIVITY")))).limit(Math.max(1, Math.min(200, n)))
                .map(d -> Explained.pick(d, List.of("DESIGN_ID", "ROUND", "ACTIVITY", "DELTA_TM", "N_CHANGES", "MODEL_SCORE", "STATUS", "PARENT"))).toList();
        Set<String> ids = top.stream().map(d -> str(d.get("DESIGN_ID"))).collect(Collectors.toSet());
        Map<String, List<String>> changes = new HashMap<>();
        data.now().of("design-mutation").forEach(m -> {
            if (ids.contains(str(m.get("DESIGN_ID")))) {
                changes.computeIfAbsent(str(m.get("DESIGN_ID")), k -> new ArrayList<>()).add(mutation(m));
            }
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> d : top) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("RANK", rank++);
            r.putAll(d);
            r.put("CHANGES", String.join(", ", changes.getOrDefault(str(d.get("DESIGN_ID")), List.of())));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protein", name);
        out.put("round", round);
        out.put("rows", rows);
        return out;
    }

    private static String mutation(Map<String, Object> m) {
        String t = str(m.get("MUTATION"));
        return t.isEmpty() ? str(m.get("AA_FROM")) + integer(m.get("POS"), 0) + str(m.get("AA_TO")) : t;
    }

    /** A design: its changes (each with the scan's score), its measurements and its parent. */
    public Map<String, Object> design(String id, String designId) {
        needs("protein", "design", "design-mutation");
        String name = str(protein(id).get("PROTEIN"));
        Map<String, Object> d = designs(name).stream().filter(x -> str(x.get("DESIGN_ID")).equals(designId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no design " + designId + " of " + name));
        String assay = board.assays(name).stream().findFirst().orElse("");
        Map<String, Map<String, Object>> scan = board.scanIndex();
        List<Map<String, Object>> muts = data.now().of("design-mutation").stream().filter(m -> str(m.get("DESIGN_ID")).equals(designId))
                .sorted(Comparator.comparingDouble(m -> num(m.get("POS")))).map(m -> {
                    Map<String, Object> r = Explained.pick(m, List.of("POS", "AA_FROM", "AA_TO"));
                    r.put("MUTATION", mutation(m));
                    Map<String, Object> s = scan.get(name + "|" + assay + "|" + integer(m.get("POS"), -1) + "|" + str(m.get("AA_TO")));
                    r.put("SCAN_SCORE", s == null ? null : round(num(s.get("SCORE")), 3));
                    return r;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>(Explained.pick(d, List.of("DESIGN_ID", "PROTEIN", "ROUND", "PARENT", "N_CHANGES", "ACTIVITY", "DELTA_TM", "MODEL_SCORE", "STATUS")));
        out.put("mutations", muts);
        out.put("positions", muts.stream().map(m -> m.get("POS")).toList());
        out.put("cells", muts.stream().map(m -> integer(m.get("POS"), 0) + ":" + str(m.get("AA_TO"))).toList());
        out.put("explain", new Explained("Design " + designId, round(num(d.get("ACTIVITY")), 2) + "× activity").scope(name + ", round " + str(d.get("ROUND")))
                .step("The design", "design", 1).step("Its changes", "design-mutation", muts.size()).step("Each change's score in the scan (" + assay + ")", "dms-score", muts.stream().filter(m -> m.get("SCAN_SCORE") != null).count())
                .sentence(String.format(Locale.ROOT, "%s carries %d changes (%s): %s times the natural protein's activity, %s degrees more stable; its parent is %s.", designId, muts.size(),
                        muts.stream().map(m -> str(m.get("MUTATION"))).collect(Collectors.joining(", ")), round(num(d.get("ACTIVITY")), 1), round(num(d.get("DELTA_TM")), 1), str(d.get("PARENT"))))
                .records(List.of("POS", "AA_FROM", "AA_TO", "MUTATION", "SCAN_SCORE"), muts).build());
        return out;
    }

    /** A cell of the scan: the measurement in every assay, with its error and replicates. */
    public Map<String, Object> explainCell(String id, int pos, String aa) {
        needs("protein", "dms-score");
        String name = str(protein(id).get("PROTEIN"));
        List<Map<String, Object>> rows = data.now().of("dms-score").stream().filter(d -> str(d.get("PROTEIN")).equals(name) && integer(d.get("POS"), -1) == pos && str(d.get("AA")).equals(aa))
                .map(d -> Explained.pick(d, List.of("ASSAY", "SCORE", "SE", "REPLICATES"))).toList();
        Set<String> designs = designsWith(name, pos, aa);
        Map<String, Object> res = residues(name).stream().filter(r -> integer(r.get("POS"), -1) == pos).findFirst().orElse(Map.of());
        String change = str(res.get("AA")) + pos + aa;
        Double first = rows.isEmpty() ? null : num(rows.get(0).get("SCORE"));
        return new Explained(name + " " + change, first == null ? "not measured" : String.valueOf(round(first, 2))).scope(name)
                .step("The change measured in each assay", "dms-score", rows.size()).step("Designs that carry it", "design-mutation", designs.size())
                .sentence(first == null ? change + " was not measured." : String.format(Locale.ROOT, "%s %s the protein (%s in %s, %s); %d designs carry it.", change,
                        first > 0.3 ? "helps" : first <= GenomicsBoard.DAMAGING ? "breaks" : first <= GenomicsBoard.INTERMEDIATE ? "weakens" : "barely changes",
                        round(first, 2), str(rows.get(0).get("ASSAY")), Arrays.toString(new Object[] {rows.get(0).get("REPLICATES")}).replaceAll("[\\[\\]]", "") + " replicates", designs.size()))
                .records(List.of("ASSAY", "SCORE", "SE", "REPLICATES"), rows).build();
    }
}
