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
package com.ash.drishti.server.admin;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What would stop working if an artifact went (docs/admin/ARTIFACTS.md, "Impact"). One question, asked of a pack, a connector or a
 * shipped BI file: which packs, connectors, BI datasets, reports and boards, saved searches, monitors, alert rules, measure alerts,
 * subscriptions, shares, workspaces and embed hosts name it. It reads the stores directly and decides nothing about who may see what:
 * the caller is an administrator, who sees everything. The answer is counts plus the first {@link #NAMED} names of each kind.
 *
 * <p>How things are matched, because it is a reading of text and not a dependency graph: a BI file names another by a small set of keys
 * ({@code dataset:}, {@code report:}, {@code connector:}, {@code kind:} ...) and a saved document (monitor, alert, workspace,
 * subscription) is matched on the values of keys that hold a reference ({@code kind}, {@code entity}, {@code path}, {@code dataset},
 * {@code report}, ...), never on free text such as titles and notes. A document that names a kind or an item only in prose is not
 * counted; a document that names it in a reference is, even if it is not currently working.
 */
public final class ArtifactImpact {

    /** How many names of each kind the answer carries; the count is always complete. */
    public static final int NAMED = 25;
    private static final int SHARE_SCAN = 20_000;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> KIND_KEYS = Set.of("kind", "kinds", "entity", "ref", "path", "url", "link");
    private static final Set<String> BI_KEYS = Set.of("dataset", "report", "board", "notebook", "python", "transform", "visual", "target", "item", "id",
            "path", "url", "link", "ref", "source");
    private static final Map<String, String> NAMESPACES = Map.of("monitors", "monitors", "alerts", "alerts", "measure-alerts", "measure-alerts",
            "subscriptions", "subscriptions", "workspaces", "workspaces", "pivots", "saved-views", "layouts", "saved-views", "reports", "saved-views");
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("packs", "Packs that extend it");
        LABELS.put("connectors", "Connectors only it uses");
        LABELS.put("packs-reading", "Packs that read it");
        LABELS.put("datasets", "BI datasets");
        LABELS.put("reports", "BI reports");
        LABELS.put("boards", "BI boards");
        LABELS.put("notebooks", "BI notebooks");
        LABELS.put("python", "BI Python items");
        LABELS.put("saved-searches", "Saved searches");
        LABELS.put("monitors", "Monitors");
        LABELS.put("alerts", "Alert rules");
        LABELS.put("measure-alerts", "Measure alerts");
        LABELS.put("subscriptions", "Subscriptions");
        LABELS.put("shares", "Shares");
        LABELS.put("workspaces", "Workspaces");
        LABELS.put("saved-views", "Saved pivots, layouts and reports");
        LABELS.put("embed-hosts", "Embed hosts");
    }

    /** One named dependant. */
    public record Item(String name, String owner, String detail) {}

    /** All dependants of one category (the API shows the first {@link #NAMED}; actions use all). */
    public record Group(String category, String label, int count, List<Item> items) {}

    /**
     * @param kind pack, connector or bi
     * @param subtype for bi: dataset, report, board, notebook or python
     * @param templated true when a loaded pack's connector template would write the connector back (connectors only)
     * @param templatePack the pack whose template it is
     * @param notes what the administrator should read before choosing
     */
    public record Impact(String kind, String subtype, String name, List<Group> groups, int total, boolean templated, String templatePack, List<String> notes) {

        public boolean blocked() {
            return total > 0;
        }

        /** One line for the audit trail and the archive record. */
        public String summary() {
            if (groups.isEmpty()) {
                return "nothing depends on it";
            }
            List<String> parts = new ArrayList<>();
            groups.forEach(g -> parts.add(g.label() + " (" + g.count() + ")"));
            return String.join(", ", parts);
        }
    }

    private final PackRegistry packs;
    private final ConnectorManager connectors;
    private final BiFolder folder;
    private final BiStore bi;
    private final PreferenceStore prefs;
    private final ObjectProvider<EmbedAppStore> embeds;
    private final ObjectProvider<ShareStore> shares;

    public ArtifactImpact(PackRegistry packs, ConnectorManager connectors, BiFolder folder, BiStore bi, PreferenceStore prefs,
            ObjectProvider<EmbedAppStore> embeds, ObjectProvider<ShareStore> shares) {
        this.packs = packs;
        this.connectors = connectors;
        this.folder = folder;
        this.bi = bi;
        this.prefs = prefs;
        this.embeds = embeds;
        this.shares = shares;
    }

    /** The impact of uninstalling or disabling the artifact; {@code subtype} is the BI file's kind (ignored for packs and connectors). */
    public Impact of(String kind, String subtype, String name) {
        return switch (kind) {
            case "pack" -> pack(name);
            case "connector" -> connector(name);
            case "bi" -> biFile(subtype, name);
            default -> throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "kind is pack, connector or bi, not '" + kind + "'");
        };
    }

    // ---- packs ------------------------------------------------------------------------------------------------------------------

    private Impact pack(String name) {
        Pack pack = packs.packs().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
        Map<String, List<Item>> found = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> kinds = pack == null ? Set.of() : lower(pack.kinds());
        if (pack == null) {
            notes.add("The pack is not loaded, so only what is stored about it can be counted.");
        } else {
            for (Pack p : packs.packs()) {
                if (!p.name().equals(name) && p.parents().contains(name)) {
                    add(found, "packs", p.name(), "", "extends " + name);
                }
            }
            for (String c : pack.connectorRefs()) {
                boolean shared = packs.packs().stream().anyMatch(p -> !p.name().equals(name) && p.connectorRefs().contains(c));
                if (!shared && connectors.defined(c)) {
                    add(found, "connectors", c, "", connectors.origin(c) + " connector; stops serving " + name + " when the pack goes");
                }
            }
        }
        if (!kinds.isEmpty()) {
            Set<String> datasets = new LinkedHashSet<>();
            for (BiRow r : biRows()) {
                if ("dataset".equals(r.subtype()) && r.pairs().stream().anyMatch(p -> "kind".equals(p[0]) && kinds.contains(p[1].toLowerCase(Locale.ROOT)))) {
                    add(found, "datasets", r.name(), r.owner(), "reads a kind of " + name + r.state());
                    datasets.add(r.name());
                }
            }
            addBiDependants(found, datasets, "dataset");
            scanPrefs(found, kindMatcher(kinds), KIND_KEYS);
            scanShares(found, kinds);
            scanEmbeds(found, kinds);
        }
        return impact("pack", "", name, found, false, "", notes);
    }

    /**
     * What turning a command off stops (Admin → Commands): what names its one kind, the BI datasets that read it and what is built on
     * them, saved searches, monitors, alerts, subscriptions, workspaces, shares and embed hosts. Nothing is blocked: a command is turned
     * back on at any time.
     */
    public Impact ofKind(String command, String kind) {
        Map<String, List<Item>> found = new LinkedHashMap<>();
        Set<String> kinds = Set.of(kind.toLowerCase(Locale.ROOT));
        Set<String> datasets = new LinkedHashSet<>();
        for (BiRow r : biRows()) {
            if ("dataset".equals(r.subtype()) && r.pairs().stream().anyMatch(p -> "kind".equals(p[0]) && kinds.contains(p[1].toLowerCase(Locale.ROOT)))) {
                add(found, "datasets", r.name(), r.owner(), "reads " + kind + r.state());
                datasets.add(r.name());
            }
        }
        addBiDependants(found, datasets, "dataset");
        scanPrefs(found, kindMatcher(kinds), KIND_KEYS);
        scanShares(found, kinds);
        scanEmbeds(found, kinds);
        return impact("command", "", command, found, false, "", List.of());
    }

    // ---- connectors -------------------------------------------------------------------------------------------------------------

    private Impact connector(String name) {
        Map<String, List<Item>> found = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> kinds = new TreeSet<>();
        for (Map<String, Object> u : connectors.usedBy(name)) {
            @SuppressWarnings("unchecked")
            List<Object> k = u.get("kinds") instanceof List<?> l ? (List<Object>) l : List.of();
            k.forEach(x -> kinds.add(String.valueOf(x).toLowerCase(Locale.ROOT)));
            add(found, "packs-reading", String.valueOf(u.get("pack")), "", k.isEmpty() ? "names the connector" : "kinds " + k);
        }
        Object own = connectors.summary(name).get("kinds");
        if (own instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x).toLowerCase(Locale.ROOT)));
        }
        Set<String> datasets = new LinkedHashSet<>();
        for (BiRow r : biRows()) {
            if ("dataset".equals(r.subtype()) && r.pairs().stream().anyMatch(p -> "connector".equals(p[0]) && p[1].equals(name))) {
                add(found, "datasets", r.name(), r.owner(), "scans the connector" + r.state());
                datasets.add(r.name());
            }
        }
        addBiDependants(found, datasets, "dataset");
        if (!kinds.isEmpty()) {
            scanPrefs(found, kindMatcher(kinds), KIND_KEYS);
            scanShares(found, kinds);
            scanEmbeds(found, kinds);
        }
        Optional<ConnectorManager.Template> t = connectors.template(name);
        if (t.isPresent()) {
            notes.add("Pack " + t.get().pack() + "'s connector template names it, so it would be written back: it can be disabled or reset, not uninstalled.");
        }
        return impact("connector", "", name, found, t.isPresent(), t.map(ConnectorManager.Template::pack).orElse(""), notes);
    }

    // ---- BI files ---------------------------------------------------------------------------------------------------------------

    private Impact biFile(String subtype, String name) {
        Map<String, List<Item>> found = new LinkedHashMap<>();
        BiRow self = biRows().stream().filter(r -> r.shipped() && r.subtype().equals(subtype) && r.name().equals(name)).findFirst().orElse(null);
        if (self == null) {
            throw new DrishtiException(ErrorCode.ARTIFACT_NOT_FOUND, "no shipped BI " + subtype + " named '" + name + "'");
        }
        addBiDependants(found, Set.of(name), subtype);
        String id = "file:" + subtype + ":" + name;
        scanPrefs(found, s -> s.equals(name) || s.equals(id), BI_KEYS);
        Set<String> kinds = new LinkedHashSet<>();
        self.pairs().stream().filter(p -> "kind".equals(p[0])).forEach(p -> kinds.add(p[1].toLowerCase(Locale.ROOT)));
        if (!kinds.isEmpty()) {
            scanEmbeds(found, kinds);
        }
        return impact("bi", subtype, name, found, false, "", List.of());
    }

    /**
     * Items whose body names one of {@code names} as a {@code targetType}, then the items that name those, and so on (a dataset's
     * report, and the board over that report). A file is never its own dependant.
     */
    private void addBiDependants(Map<String, List<Item>> found, Set<String> names, String targetType) {
        if (names.isEmpty()) {
            return;
        }
        List<BiRow> rows = biRows();
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> frontier = new ArrayList<>();
        for (String n : names) {
            seen.add(targetType + ":" + n);
            frontier.add(new String[] {targetType, n});
        }
        for (int depth = 0; depth < 6 && !frontier.isEmpty(); depth++) {
            List<String[]> next = new ArrayList<>();
            for (String[] node : frontier) {
                Set<String> keys = BiBodies.keysFor(node[0]);
                for (BiRow r : rows) {
                    String id = r.subtype() + ":" + r.name();
                    if (seen.contains(id)) {
                        continue;
                    }
                    for (String[] p : r.pairs()) {
                        if (keys.contains(p[0]) && bare(p[1]).equals(node[1])) {
                            seen.add(id);
                            add(found, category(r.subtype()), r.name(), r.owner(), "uses " + node[0] + " " + node[1] + r.state());
                            next.add(new String[] {r.subtype(), r.name()});
                            break;
                        }
                    }
                }
            }
            frontier = next;
        }
    }

    // ---- stores -----------------------------------------------------------------------------------------------------------------

    private void scanPrefs(Map<String, List<Item>> found, Predicate<String> matches, Set<String> keys) {
        for (String user : prefs.users()) {
            for (Map.Entry<String, String> ns : NAMESPACES.entrySet()) {
                for (String key : prefs.keys(user, ns.getKey())) {
                    prefs.get(user, ns.getKey(), key).ifPresent(doc -> {
                        if (mentions(doc, matches, keys)) {
                            add(found, ns.getValue(), key, user, "");
                        }
                    });
                }
            }
            prefs.get(user, "settings", "ui").or(() -> prefs.get(user, "settings", "settings")).ifPresent(doc -> {
                JsonNode searches = doc.path("savedSearches");
                if (searches.isArray()) {
                    for (JsonNode s : searches) {
                        if (mentions(s, matches, keys)) {
                            add(found, "saved-searches", s.path("name").asText(s.path("path").asText("(unnamed)")), user, s.path("path").asText(""));
                        }
                    }
                }
            });
        }
    }

    private void scanShares(Map<String, List<Item>> found, Set<String> kinds) {
        ShareStore store = shares.getIfAvailable();
        if (store == null) {
            return;
        }
        String after = null;
        int seen = 0;
        while (seen < SHARE_SCAN) {
            List<Share> page = store.page(after, 500);
            if (page.isEmpty()) {
                return;
            }
            for (Share s : page) {
                if (s.kind() != null && kinds.contains(s.kind().toLowerCase(Locale.ROOT))) {
                    add(found, "shares", s.kind() + " " + s.entityId(), s.sender(), "shared " + s.createdAt());
                }
                after = s.id();
            }
            seen += page.size();
        }
    }

    private void scanEmbeds(Map<String, List<Item>> found, Set<String> kinds) {
        EmbedAppStore store = embeds.getIfAvailable();
        if (store == null) {
            return;
        }
        for (EmbedAppStore.App a : store.all()) {
            List<String> hit = a.kinds().stream().filter(k -> kinds.contains(k.toLowerCase(Locale.ROOT))).toList();
            if (!hit.isEmpty()) {
                add(found, "embed-hosts", a.id(), a.contact() == null ? "" : a.contact(), "kinds " + hit + (a.enabled() ? "" : " (disabled)"));
            }
        }
    }

    // ---- reading text -----------------------------------------------------------------------------------------------------------

    private static Predicate<String> kindMatcher(Set<String> kinds) {
        return s -> {
            for (String t : s.toLowerCase(Locale.ROOT).split("[/?&=#:\\s,;]+")) {
                if (kinds.contains(t)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** True when a string under one of {@code keys} (at any depth; array elements inherit the key) satisfies {@code matches}. */
    static boolean mentions(JsonNode n, Predicate<String> matches, Set<String> keys) {
        boolean[] hit = {false};
        walk(n, "", keys, matches, hit);
        return hit[0];
    }

    private static void walk(JsonNode n, String key, Set<String> keys, Predicate<String> matches, boolean[] hit) {
        if (hit[0] || n == null) {
            return;
        }
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> walk(e.getValue(), e.getKey(), keys, matches, hit));
        } else if (n.isArray()) {
            n.forEach(x -> walk(x, key, keys, matches, hit));
        } else if (n.isTextual() && keys.contains(key) && matches.test(n.asText())) {
            hit[0] = true;
        }
    }

    private static String bare(String ref) {
        return ref.startsWith("file:") ? ref.substring(ref.lastIndexOf(':') + 1) : ref;
    }

    private static String category(String subtype) {
        return switch (subtype) {
            case "dataset" -> "datasets";
            case "report" -> "reports";
            case "board" -> "boards";
            case "notebook" -> "notebooks";
            default -> "python";
        };
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        in.forEach(s -> out.add(s.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void add(Map<String, List<Item>> found, String category, String name, String owner, String detail) {
        found.computeIfAbsent(category, k -> new ArrayList<>()).add(new Item(name, owner, detail));
    }

    private Impact impact(String kind, String subtype, String name, Map<String, List<Item>> found, boolean templated, String templatePack, List<String> notes) {
        List<Group> groups = new ArrayList<>();
        int total = 0;
        for (String cat : LABELS.keySet()) {
            List<Item> items = found.get(cat);
            if (items != null && !items.isEmpty()) {
                groups.add(new Group(cat, LABELS.get(cat), items.size(), List.copyOf(items)));
                total += items.size();
            }
        }
        return new Impact(kind, subtype, name, groups, total, templated, templatePack, notes);
    }

    // ---- BI items as rows ---------------------------------------------------------------------------------------------------------

    private record BiRow(String id, String subtype, String name, String owner, boolean shipped, boolean published, List<String[]> pairs) {

        String state() {
            return published ? "" : " (a draft)";
        }
    }

    private List<BiRow> biRows() {
        List<BiRow> out = new ArrayList<>();
        for (BiStore.Item i : folder.allItems()) {
            out.add(row(i, true));
        }
        for (BiStore.Item i : bi.items()) {
            if (!BiFolder.isShipped(i.id())) {
                out.add(row(i, false));
            }
        }
        return out;
    }

    private static BiRow row(BiStore.Item i, boolean shipped) {
        String name = shipped ? i.id().substring(i.id().lastIndexOf(':') + 1) : i.title();
        return new BiRow(i.id(), i.kind(), name, shipped ? "" : i.owner(), shipped, i.isPublished(), BiBodies.pairs(YAML, i.body(), i.kind()));
    }
}
