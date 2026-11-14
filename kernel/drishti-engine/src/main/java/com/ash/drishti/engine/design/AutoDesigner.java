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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.design.Design.Pruned;
import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.inference.Candidate;
import com.ash.drishti.inference.LayoutPacker;
import com.ash.drishti.inference.Role;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.SutraWriter;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Match;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.model.Title;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Screen Builder step 3: drafts a complete Sutra from a shape and its samples, and suggests panels for one field. The
 * choices come from {@link PanelChooser} (one rule set for runtime inference's recipes and for shapes); the layout
 * from inference's {@link LayoutPacker}. Every decision carries a reason, every panel keeps its runner-up kinds as
 * alternatives, and the draft is previewed against every sample so panels the samples cannot fill are dropped or demoted
 * (share set by {@code drishti.builder.prune-share}). Writes nothing; thread-safe (no state between calls).
 */
public final class AutoDesigner {

    private static final SourceLocation DRAFT = new SourceLocation("auto-design", 0, 0);
    private static final Set<String> HALF = Set.of("line", "area", "hbar", "histogram", "scatter", "waterfall");
    private static final Map<String, Integer> HEIGHT = Map.of("candlestick", 10, "surface", 12, "graph", 12);
    private static final int CHART_HEIGHT = 9;
    private static final int MAX_DEPTH = 3;
    private static final int FIRST_LINK_KEY = 7;
    private static final int MAX_LINK_KEYS = 2;

    /** A link to one of these kinds is the party the document is with: the title's 'with' and the first link key. */
    private static final java.util.regex.Pattern PARTY = java.util.regex.Pattern.compile("(?i)counterparty|customer|client|party|issuer|obligor|borrower");
    /** A dimension named like this says what the document is: it goes beside the kind in the title. */
    private static final java.util.regex.Pattern WHAT = java.util.regex.Pattern.compile("(?i)product|instrument|type|class|category|asset");

    private final BuilderProperties props;
    private final Semantics semantics;
    private final java.util.regex.Pattern idNames;
    private final List<java.util.regex.Pattern> keyFigures;
    private final SutraWriter writer = new SutraWriter();

    public AutoDesigner(BuilderProperties props, Semantics semantics) {
        this.props = props;
        this.semantics = semantics;
        this.idNames = java.util.regex.Pattern.compile(props.idNames(), java.util.regex.Pattern.CASE_INSENSITIVE);
        this.keyFigures = props.keyFigures().stream().map(p -> java.util.regex.Pattern.compile(p, java.util.regex.Pattern.CASE_INSENSITIVE)).toList();
    }

    // ---------------------------------------------------------------------------------------------------- suggest

    /**
     * Ranked panel kinds for one field of the shape, options filled. With {@code at}, for the pair of fields (a dimension
     * and a measure of the same rows, in either order, or two measures).
     *
     * @throws DrishtiException {@code DRS-5001} when a path is not in the shape
     */
    public List<PanelChoice> suggest(Shape shape, List<Sample> samples, String path, String at) {
        SampleStats stats = new SampleStats(samples);
        ShapeModel model = new ShapeModel(shape, stats, idNames);
        PanelChooser chooser = new PanelChooser(semantics, props, stats, model);
        FieldNode f = require(model, path);
        if (at != null && !at.isBlank()) {
            return chooser.pair(f, require(model, at));
        }
        return chooser.choose(f);
    }

    private static FieldNode require(ShapeModel model, String path) {
        FieldNode f = path == null ? null : model.find(path.trim());
        if (f == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no such path in the shape: " + path + " (paths look like $.profile or $.rows[].book)");
        }
        return f;
    }

    /** What a kind may look like (the same pattern the designs API uses): it becomes a Sutra name and a file name. */
    public static final java.util.regex.Pattern KIND = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    // ---------------------------------------------------------------------------------------------------- design

    /**
     * Drafts the screen. With a {@code previewer} the draft is rendered against every sample and the panels they cannot fill
     * are pruned; without one the draft is returned as designed.
     *
     * @param kind the entity kind the Sutra will match (its name and title come from it)
     */
    public Design design(Shape shape, List<Sample> samples, String kind, DesignPreviewer previewer) {
        String k = kind == null || kind.isBlank() ? "sample" : kind.trim();
        if (!KIND.matcher(k).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'kind' is letters, digits, . _ - (up to 64), starting with a letter or digit");
        }
        SampleStats stats = new SampleStats(samples);
        ShapeModel model = new ShapeModel(shape, stats, idNames);
        PanelChooser chooser = new PanelChooser(semantics, props, stats, model);
        Plan plan = new Plan(k, model, stats, chooser);
        plan.collect();
        List<Pruned> pruned = new ArrayList<>();
        plan.fit(pruned);
        int total = samples == null ? 0 : samples.size();
        if (previewer != null && total > 0) {
            plan.prune(samples, previewer, pruned);
        }
        Sutra sutra = plan.assemble();
        String yaml = writer.write(sutra, plan.name(), 1, "Drafted by auto-design from " + total + " sample" + (total == 1 ? "" : "s") + ". Edit freely.");
        ViewModel first = previewer != null && total > 0 ? previewer.preview(yaml, k, samples.get(0).document()) : null;
        return new Design(yaml, plan.reasons, plan.alternatives(), List.copyOf(pruned), first, total);
    }

    // ------------------------------------------------------------------------------------------------------ plan

    /** One panel being designed: where it comes from and its ranked choices; {@code pick} is the one in use. */
    private static final class Draft {
        final String id;
        final String source;
        final int order;
        final List<PanelChoice> choices;
        int pick;
        String note = "";

        Draft(String id, String source, int order, List<PanelChoice> choices) {
            this.id = id;
            this.source = source;
            this.order = order;
            this.choices = choices;
        }

        PanelChoice chosen() {
            return choices.get(pick);
        }
    }

    /** The state of one design run; never shared between calls. */
    private final class Plan {
        final String kind;
        final ShapeModel model;
        final SampleStats stats;
        final PanelChooser chooser;
        final List<Draft> drafts = new ArrayList<>();
        final List<Draft> dropped = new ArrayList<>();
        final Map<String, String> reasons = new LinkedHashMap<>();
        final List<FieldNode> used = new ArrayList<>();
        List<StripItem> strip = List.of();
        Title title;
        Map<String, String> keys = new LinkedHashMap<>();

        final StructurePanels structure;

        Plan(String kind, ShapeModel model, SampleStats stats, PanelChooser chooser) {
            this.kind = kind;
            this.model = model;
            this.stats = stats;
            this.chooser = chooser;
            this.structure = new StructurePanels(props, stats, chooser);
        }

        String name() {
            String n = kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            return (n.isEmpty() ? "sample" : n) + "-auto";
        }

        // ------------------------------------------------------------------------------------------- collect

        void collect() {
            FieldNode root = model.root();
            List<FieldNode> statuses = new ArrayList<>();
            PanelChoice details = structure.details(root, kind);
            if (details != null) {
                add("$.details", List.of(details), "details");
            }
            visit(root, 1, statuses, true);
            if (statuses.size() >= 2) {
                PanelChoice st = chooser.statusOf(statuses, "Status", 0.8, statuses.size() + " fields are states");
                add("$.status", List.of(st), "status");
            }
            title();
            strip();
            keys();
        }

        private void visit(FieldNode node, int depth, List<FieldNode> statuses, boolean collect) {
            for (FieldNode p : node.props().values()) {
                if (p.array() && p.tree() && p.props().isEmpty() && node.object()) {
                    add(p.path(), chooser.treeUnder(node, p), null);
                } else if (p.array()) {
                    List<StructurePanels.Planned> planned = structure.forList(p);
                    planned.forEach(x -> add(x.source(), x.choices(), null));
                    if (planned.isEmpty() && !p.roleName().isEmpty() && !p.is("link")) {
                        add(p.path(), chooser.choose(p), null);
                    }
                } else if (p.object()) {
                    if (p.is("link")) {
                        continue;
                    }
                    if (p.is("graph")) {
                        add(p.path(), chooser.choose(p), null);
                        continue;
                    }
                    long scalars = p.props().values().stream().filter(FieldNode::scalar).count();
                    if (scalars >= 2) {
                        add(p.path(), chooser.choose(p), null);
                    }
                    if (depth < MAX_DEPTH) {
                        visit(p, depth + 1, statuses, scalars < 2);
                    }
                } else if (p.scalar()) {
                    scalar(p, depth, statuses, collect);
                }
            }
        }

        private void scalar(FieldNode p, int depth, List<FieldNode> statuses, boolean collect) {
            if (p.is("status")) {
                if (collect && depth <= 2) {
                    statuses.add(p);
                }
            } else if (p.is("text")) {
                add(p.path(), chooser.choose(p), null);
            } else if (p.is("measure") && (chooser.limitOf(p) != null && !isLimit(p) || chooser.isFraction(p))) {
                add(p.path(), chooser.choose(p), null);
            } else if (p.is("measure") && depth == 1 && !isLimit(p) && onlyMeasure(p)) {
                add(p.path(), chooser.choose(p), null);     // the document's one headline measure: a big number
            }
        }

        /** Whether {@code p} is the only top-level measure of the document (a headline figure rather than one of several). */
        private boolean onlyMeasure(FieldNode p) {
            return model.root().props().values().stream().filter(q -> q.scalar() && q.is("measure")).count() == 1;
        }

        private boolean isLimit(FieldNode p) {
            return props.limitNames().stream().anyMatch(n -> p.name().toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT)));
        }

        private void add(String source, List<PanelChoice> choices, String idHint) {
            if (choices.isEmpty()) {
                return;
            }
            String id = unique(idHint != null ? idHint : slug(source));
            drafts.add(new Draft(id, source, drafts.size() * 10, choices));
        }

        private String unique(String base) {
            String id = base.isEmpty() ? "panel" : base;
            String candidate = id;
            int n = 1;
            while (taken(candidate) || "built".equals(candidate) || "refs".equals(candidate)) {
                candidate = id + "-" + (++n);
            }
            return candidate;
        }

        private boolean taken(String id) {
            return drafts.stream().anyMatch(d -> d.id.equals(id));
        }

        private String slug(String path) {
            return path.replace("$.", "").replaceAll("\\[\\]", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase(Locale.ROOT);
        }

        // --------------------------------------------------------------------------------------------- title

        /**
         * The link a field makes: to its value, or for an {id, name} record to its id with its name shown, by the record's own field names
         * ({@code ID} and {@code NAME} in an UPPER_SNAKE document).
         */
        private static String linkTo(FieldNode p) {
            if (!p.object()) {
                return "link(" + p.path() + ", '" + p.role().kind() + "')";
            }
            return "link(" + child(p, "id") + ", '" + p.role().kind() + "', " + child(p, "name") + ")";
        }

        private static String child(FieldNode p, String name) {
            return p.props().values().stream().filter(c -> c.name().equalsIgnoreCase(name)).map(FieldNode::path).findFirst().orElse(p.path() + "." + name);
        }

        /** Whether {@code p} links to the document's own kind (its id, read as a link to itself). */
        private boolean self(FieldNode p) {
            return p.is("link") && kind.equalsIgnoreCase(p.role().kind());
        }

        /** The links of the document to other entities, the party it is with first (a counterparty, a client, an issuer). */
        private List<FieldNode> links() {
            List<FieldNode> links = new ArrayList<>(model.root().props().values().stream()
                    .filter(p -> p.is("link") && !p.array() && p.role().kind() != null && !self(p)).toList());
            links.sort(Comparator.comparingInt(p -> PARTY.matcher(p.role().kind()).find() ? 0 : p.object() ? 1 : 2));
            return links;
        }

        private void title() {
            FieldNode root = model.root();
            FieldNode id = root.props().values().stream().filter(p -> p.scalar() && self(p)).findFirst()      // a link to its own kind is its id
                    .orElseGet(() -> root.props().values().stream().filter(p -> p.scalar() && p.is("id")).findFirst().orElse(null));
            List<FieldNode> dims = root.props().values().stream().filter(p -> p.scalar() && p.is("dimension")).toList();
            FieldNode dim = dims.stream().filter(p -> WHAT.matcher(p.name()).find()).findFirst().orElse(dims.isEmpty() ? null : dims.get(0));
            List<FieldNode> links = links();
            FieldNode link = links.isEmpty() ? null : links.get(0);
            String pill = Semantics.humanize(kind.replace('-', ' ')) + (dim == null ? "" : " · ${" + dim.path() + "}");
            String with = link == null ? null : linkTo(link);
            title = new Title(pill, id == null ? "'" + kind + "'" : id.path(), with);
            if (id != null) {
                used.add(id);
            }
            if (dim != null) {
                used.add(dim);
            }
            reasons.put("title", (id == null ? "no field looks like an id, so the title shows the kind" : "'" + id.name() + "' is the id (" + id.role().reason() + ")")
                    + (dim == null ? "" : "; '" + dim.name() + "' (" + dim.role().reason() + ") shows beside the kind")
                    + (link == null ? "" : "; '" + link.name() + "' is a link, shown as the title's 'with'"));
        }

        // --------------------------------------------------------------------------------------------- strip

        /**
         * The key figures first ({@code drishti.builder.key-figures}, in their order: currency, side, notional, value, quantity,
         * trade date, maturity, status, book), each the first top-level field its pattern names, else that field of the first
         * record of a top-level list ({@code first($.LEGS).NOTIONAL}); then the strip is filled up to {@code strip-max} with the
         * figures ranked by role, presence and how much they vary. The value (MTM, PV) is emphasised, else the heaviest amount.
         */
        private void strip() {
            List<StripItem> items = new ArrayList<>();
            List<FieldNode> shown = new ArrayList<>(used);
            Map<StripItem, FieldNode> of = new LinkedHashMap<>();
            for (java.util.regex.Pattern kf : keyFigures) {
                if (items.size() >= props.stripMax()) {
                    break;
                }
                FieldNode f = model.root().props().values().stream().filter(p -> p.scalar() && !shown.contains(p) && !self(p) && kf.matcher(p.name()).find()
                        && filled(p)).findFirst().orElse(null);
                String bind = f == null ? null : figure(f, f.path());
                if (f == null) {
                    for (FieldNode list : model.root().props().values()) {
                        FieldNode g = !list.array() || list.props().isEmpty() ? null : list.props().values().stream()
                                .filter(p -> p.scalar() && kf.matcher(p.name()).find() && filled(p)).findFirst().orElse(null);
                        if (g != null && shown.stream().noneMatch(s -> s.name().equals(g.name()))) {
                            f = g;
                            bind = figure(g, "first(" + list.path() + ")." + g.name());
                            break;
                        }
                    }
                }
                if (f == null) {
                    continue;
                }
                shown.add(f);
                boolean status = f.is("status");
                String label = Semantics.humanize(f.name());
                Role r = chooser.roleOf(f);
                String fmt = status || f.is("link") || !"number".equals(f.type()) && !f.date() ? null : f.date() ? "date" : r.fmt();
                StripItem item = new StripItem(label, bind, fmt, status ? "status" : "number".equals(f.type()) ? r.tone() : null, false, DRAFT);
                items.add(item);
                of.put(item, f);
                reasons.put("strip." + label, "a key figure (drishti.builder.key-figures: " + kf.pattern() + ")"
                        + (bind.startsWith("first(") ? ", from the first record of its list" : ""));
            }
            List<StripItem> ranked = ranked(shown, props.stripMax() - items.size(), of);
            items.addAll(ranked);
            List<FieldNode> amounts = of.values().stream().filter(f -> "number".equals(f.type()) && !f.is("dimension")).toList();
            FieldNode emph = amounts.stream().filter(f -> "signed-money".equals(chooser.roleOf(f).name())).findFirst()       // the value (MTM, PV)
                    .orElseGet(() -> stats.count() >= 2 && amounts.stream().anyMatch(f -> stats.variation(f.path()) > 0)
                            ? amounts.stream().max(Comparator.comparingDouble((FieldNode f) -> stats.variation(f.path()))).orElse(null)
                            : amounts.stream().max(Comparator.comparingInt((FieldNode f) -> chooser.roleOf(f).weight())).orElse(null));
            StripItem pick = items.stream().filter(i -> of.get(i) == emph).findFirst().orElse(null);
            if (pick == null) {
                pick = items.stream().filter(StripItem::emphasis).findFirst().orElse(null);
            }
            List<StripItem> out = new ArrayList<>();
            for (StripItem i : items) {
                boolean e = i == pick;
                out.add(new StripItem(i.label(), i.bind(), i.fmt(), i.tone(), e, DRAFT));
                if (e) {
                    reasons.merge("strip." + i.label(), "; emphasised", String::concat);
                }
            }
            strip = out;
            reasons.put("strip", out.isEmpty() ? "no key figure, top-level measure, date or state to show"
                    : out.size() + " figure" + (out.size() == 1 ? "" : "s") + " of at most " + props.stripMax()
                            + ": the key figures, then the rest ranked by role, presence and how much they vary across the samples");
        }

        /** Whether the samples give the field a value (without samples: its presence in the shape). */
        private boolean filled(FieldNode p) {
            return stats.count() > 0 ? stats.present(p.path()) > 0 : p.presence() >= props.rareBelow();
        }

        /** A key figure's expression: a link opens its entity, a buy/sell code reads as Buy or Sell. */
        private String figure(FieldNode f, String path) {
            if (f.is("link") && f.role().kind() != null && !f.array()) {
                return "link(" + path + ", '" + f.role().kind() + "')";
            }
            if ("string".equals(f.type()) && stats.count() > 0) {
                java.util.Set<String> seen = stats.texts(f.path(), 4);
                if (!seen.isEmpty() && java.util.Set.of("B", "S").containsAll(seen)) {
                    return path + " == 'B' ? 'Buy' : (" + path + " == 'S' ? 'Sell' : " + path + ")";
                }
            }
            return path;
        }

        /** Up to {@code slots} more figures, ranked by role, presence and how much they vary across the samples. */
        private List<StripItem> ranked(List<FieldNode> shown, int slots, Map<StripItem, FieldNode> of) {
            record Scored(FieldNode f, int order, int weight, double spread, String why) {}
            List<Scored> scored = new ArrayList<>();
            int[] order = {0};
            collectStrip(model.root(), 1, f -> {
                order[0]++;
                if (shown.contains(f) || self(f)) {
                    return;
                }
                int base = switch (f.roleName()) {
                    case "measure" -> 60;
                    case "date" -> 45;
                    case "status" -> 40;
                    case "dimension" -> 30;
                    default -> -1;
                };
                if (base < 0) {
                    return;
                }
                Role r = chooser.roleOf(f);
                int w = base + Math.min(r.weight(), 90) / 5 - (f.path().indexOf('.', 2) > 0 ? 10 : 0);
                int n = stats.count();
                double share = n > 0 ? stats.present(f.path()) / (double) n : f.presence();
                String why = f.role().reason();
                if (share < props.rareBelow()) {
                    reasons.put("strip." + Semantics.humanize(f.name()), "left out: present in only " + Math.round(share * n) + " of " + n + " samples");
                    return;
                }
                double spread = 0;
                if (n >= 2) {
                    spread = stats.variation(f.path());
                    if (stats.distinct(f.path(), 2) == 1) {
                        w -= 35;
                        why += "; the same in every sample, so ranked lower";
                    } else if (spread > 0) {
                        w += (int) Math.min(15, Math.round(spread * 30));
                        why += "; varies across the samples";
                    }
                }
                scored.add(new Scored(f, order[0], w, spread, why));
            });
            List<Scored> top = new ArrayList<>(scored);
            top.sort(Comparator.comparingInt((Scored s) -> -s.weight()).thenComparingInt(Scored::order));
            top = new ArrayList<>(top.subList(0, Math.max(0, Math.min(slots, top.size()))));
            Scored emph = null;
            if (stats.count() >= 2) {
                emph = top.stream().filter(s -> s.f().is("measure") && s.spread() > 0).max(Comparator.comparingDouble(Scored::spread)).orElse(null);
            }
            if (emph == null) {
                emph = top.stream().filter(s -> "signed-money".equals(chooser.roleOf(s.f()).name()))
                        .min(Comparator.comparingInt((Scored s) -> s.f().name().toLowerCase(Locale.ROOT).contains("mtm") ? 0 : 1)
                                .thenComparingInt(Scored::order)).orElse(null);
            }
            top.sort(Comparator.comparingInt(Scored::order));
            List<StripItem> items = new ArrayList<>();
            Map<String, Integer> names = new LinkedHashMap<>();
            top.forEach(s -> names.merge(s.f().name(), 1, Integer::sum));
            for (Scored s : top) {
                Role r = chooser.roleOf(s.f());
                boolean status = s.f().is("status");
                String label = Semantics.humanize(s.f().name());
                int dot = s.f().path().lastIndexOf('.');
                if (names.get(s.f().name()) > 1 && dot > 2) {          // a top-level field ($.name) has no parent to tell it apart: it keeps its own label
                    String parent = s.f().path().substring(2, dot);
                    label = Semantics.humanize(parent.substring(parent.lastIndexOf('.') + 1)) + " " + label.toLowerCase(Locale.ROOT);
                }
                boolean e = s == emph;
                StripItem item = new StripItem(label, s.f().path(), status ? null : r.fmt(), status ? "status" : r.tone(), e, DRAFT);
                items.add(item);
                of.put(item, s.f());
                reasons.put("strip." + label, s.why() + (e ? "; it varies most across the samples" : ""));
            }
            return items;
        }

        private void collectStrip(FieldNode node, int depth, java.util.function.Consumer<FieldNode> out) {
            for (FieldNode p : node.props().values()) {
                if (p.scalar()) {
                    out.accept(p);
                } else if (p.object() && depth < 2 && !p.is("link") && !p.is("graph")) {
                    collectStrip(p, depth + 1, out);
                }
            }
        }

        // ---------------------------------------------------------------------------------------------- keys

        /** F7 and F8 open the entities the document links to, the party it is with first; never the document itself. */
        private void keys() {
            int n = FIRST_LINK_KEY;
            for (FieldNode p : links()) {
                if (n >= FIRST_LINK_KEY + MAX_LINK_KEYS) {
                    break;
                }
                keys.put("F" + n++, linkTo(p));
            }
        }

        // -------------------------------------------------------------------------------------------- assemble

        /** Packs the drafts under the panel limits; what does not fit is dropped, with the reason. */
        void fit(List<Pruned> pruned) {
            LayoutPacker.Packed packed = pack();
            List<Draft> overflow = new ArrayList<>(drafts);
            packed.panels().forEach(p -> overflow.removeIf(d -> d.id.equals(p.id())));
            for (Draft d : overflow) {
                dropped.add(d);
                drafts.remove(d);
                pruned.add(new Pruned(d.id, d.chosen().kind(), "dropped", null, 0, 0, "over the limit of " + props.maxPanels() + " main and "
                        + props.maxSidePanels() + " side panels (drishti.builder.max-panels, max-side-panels)"));
            }
        }

        private LayoutPacker.Packed pack() {
            List<Candidate> cands = new ArrayList<>();
            for (Draft d : drafts) {
                PanelChoice c = d.chosen();
                cands.add(new Candidate(c.toPanel(d.id), c.score(), "auto-design", c.reason(), d.source, d.order));
            }
            LayoutPacker.Packed packed = LayoutPacker.pack(cands, props.maxPanels(), props.maxSidePanels());
            if (model.all().stream().anyMatch(n -> n.is("link"))) {
                return packed;
            }
            // nothing in the data refers to another entity: the links panel would stay empty on every sample
            return new LayoutPacker.Packed(packed.panels().stream().filter(p -> !"refs".equals(p.id())).toList(), packed.why());
        }

        Sutra assemble() {
            LayoutPacker.Packed packed = pack();
            for (Draft d : drafts) {
                reasons.put(d.id, d.chosen().reason() + d.note);
            }
            return new Sutra(name(), 1, null, new Match(kind, null, 1), title, strip, sized(packed.panels()), keys, DRAFT);
        }

        /** Charts go side by side in pairs (span 6), alone they take the column; everything else keeps the full width. */
        private List<Panel> sized(List<Panel> in) {
            List<Panel> out = new ArrayList<>(in);
            for (int i = 0; i < out.size(); i++) {
                Panel p = out.get(i);
                if (p.area() != Area.MAIN || p.kind() == PanelKind.PROVENANCE) {
                    continue;
                }
                boolean half = HALF.contains(p.kind().id());
                Integer h = HEIGHT.get(p.kind().id());
                if (half && i + 1 < out.size() && out.get(i + 1).area() == Area.MAIN && HALF.contains(out.get(i + 1).kind().id())) {
                    out.set(i, sizedPanel(p, 6, CHART_HEIGHT));
                    out.set(i + 1, sizedPanel(out.get(i + 1), 6, CHART_HEIGHT));
                    i++;
                } else if (half) {
                    out.set(i, sizedPanel(p, 0, CHART_HEIGHT));
                } else if (h != null) {
                    out.set(i, sizedPanel(p, 0, h));
                }
            }
            return out;
        }

        private Panel sizedPanel(Panel p, int span, int height) {
            Map<String, Object> o = new LinkedHashMap<>(p.options());
            if (span > 0) {
                o.put(Panel.SPAN, span);
            }
            o.put(Panel.HEIGHT, height);
            return new Panel(p.id(), p.kind(), p.title(), p.key(), p.code(), p.area(), p.infer(), p.columns(), p.body(), o, p.location(), p.parts(), p.wiring());
        }

        Map<String, List<PanelChoice>> alternatives() {
            Map<String, List<PanelChoice>> out = new LinkedHashMap<>();
            for (Draft d : drafts) {
                List<PanelChoice> alts = new ArrayList<>();
                for (int i = 0; i < d.choices.size() && alts.size() < props.maxAlternatives(); i++) {
                    if (i != d.pick) {
                        alts.add(d.choices.get(i));
                    }
                }
                out.put(d.id, alts);
            }
            return out;
        }

        // ------------------------------------------------------------------------------------------- pruning

        void prune(List<Sample> samples, DesignPreviewer previewer, List<Pruned> pruned) {
            int n = samples.size();
            Map<String, Pruned> demoted = new LinkedHashMap<>();
            Counts c = count(samples, previewer);
            for (int round = 0; round < 2; round++) {
                boolean changed = false;
                for (Draft d : new ArrayList<>(drafts)) {
                    int bad = c.empty.getOrDefault(d.id, 0) + c.error.getOrDefault(d.id, 0);
                    if (bad == 0 || bad <= props.pruneShare() * n) {
                        continue;
                    }
                    String why = why(c, d.id, n);
                    PanelChoice was = d.chosen();
                    if (round == 0 && d.pick == 0 && d.choices.size() > 1) {
                        d.pick = 1;
                        d.note = " Demoted from " + was.kind() + ": " + why + ".";
                        demoted.put(d.id, new Pruned(d.id, was.kind(), "demoted", d.chosen().kind(), bad, n, why));
                    } else {
                        Pruned earlier = demoted.remove(d.id);
                        drafts.remove(d);
                        dropped.add(d);
                        reasons.remove(d.id);
                        pruned.add(new Pruned(d.id, earlier == null ? was.kind() : earlier.kind(), "dropped", null, bad, n,
                                why + (earlier == null ? "" : " (the alternative " + was.kind() + " was no better)")));
                    }
                    changed = true;
                }
                if (!changed) {
                    break;
                }
                c = count(samples, previewer);
            }
            pruned.addAll(demoted.values());
            for (Draft d : drafts) {
                int bad = c.empty.getOrDefault(d.id, 0) + c.error.getOrDefault(d.id, 0);
                if (bad > 0) {
                    d.note += " " + why(c, d.id, n) + ", under the prune share of " + props.pruneShare() + ", so it stays.";
                }
            }
            pruneStrip(c, n, pruned);
        }

        private void pruneStrip(Counts c, int n, List<Pruned> pruned) {
            List<StripItem> keep = new ArrayList<>();
            for (StripItem s : strip) {
                int blank = c.stripBlank.getOrDefault(s.label(), 0);
                if (blank > props.pruneShare() * n) {
                    pruned.add(new Pruned(s.label(), "strip", "dropped", null, blank, n, "blank in " + blank + " of " + n + " samples"));
                    reasons.remove("strip." + s.label());
                } else {
                    keep.add(s);
                }
            }
            strip = keep;
        }

        private String why(Counts c, String id, int n) {
            int e = c.empty.getOrDefault(id, 0);
            int x = c.error.getOrDefault(id, 0);
            List<String> parts = new ArrayList<>();
            if (e > 0) {
                parts.add("empty in " + e + " of " + n + " samples");
            }
            if (x > 0) {
                parts.add("an error in " + x + " of " + n + " samples (" + c.firstError.get(id) + ")");
            }
            return String.join(", ", parts);
        }

        /** Renders the current draft against every sample (through {@link SampleChecker}, the one checker) and tallies, per panel and strip figure, where it came out empty or broken. */
        private Counts count(List<Sample> samples, DesignPreviewer previewer) {
            Counts c = new Counts();
            String yaml = writer.write(assemble(), name(), 1, "draft");
            RuntimeException[] failure = new RuntimeException[1];
            SampleChecker.Matrix m = CHECKER.check(List.of(), samples.stream().map(x -> new SampleChecker.Input(x.name(), x.document(), null)).toList(), in -> {
                try {
                    return previewer.preview(yaml, kind, in.document());
                } catch (RuntimeException e) {
                    failure[0] = e;                          // a draft that does not render is a fault, not a pruning signal
                    throw e;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            m.panels().forEach(p -> {
                if (p.counts().error() > 0) {
                    c.error.put(p.id(), p.counts().error());
                    c.firstError.put(p.id(), p.firstError());
                }
                if (p.counts().empty() > 0) {
                    c.empty.put(p.id(), p.counts().empty());
                }
            });
            m.strip().stream().filter(r -> r.blank() > 0).forEach(r -> c.stripBlank.put(r.label(), r.blank()));
            return c;
        }
    }

    private static final SampleChecker CHECKER = new SampleChecker();

    private static final class Counts {
        final Map<String, Integer> empty = new LinkedHashMap<>();
        final Map<String, Integer> error = new LinkedHashMap<>();
        final Map<String, String> firstError = new LinkedHashMap<>();
        final Map<String, Integer> stripBlank = new LinkedHashMap<>();
    }
}
