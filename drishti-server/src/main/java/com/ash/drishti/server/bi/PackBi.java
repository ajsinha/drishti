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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The BI content a pack archive carries (docs/guides/BI_GOVERNANCE.md, "BI content in packs"): {@code config/bi/datasets|reports|boards|notebooks|python/} inside
 * the pack folder, in the same file shapes as the BI folder. A deploy does not copy the files anywhere readers could use them unchecked: they become a bundle that
 * goes through the bundle import, so the same rules apply as to any import (the draft and the published text must parse, a dataset change that would break published
 * reports is refused unless a bi-admin forces it, the audience is the pack, certification does not travel), and the import is kept so it can be rolled back.
 * The items live in the shared workspace {@code drishti.bi.governance.pack-workspace}, one item per file with a stable id, so a later version of the pack updates
 * them as a new published version.
 */
public final class PackBi {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, String> FOLDERS = Map.of("datasets", "dataset", "reports", "report", "boards", "board", "notebooks", "notebook", "python", "python");

    private final BiBundles bundles;
    private final BiStore store;
    private final BiGovProperties gov;
    private final AuditLog audit;

    public PackBi(BiBundles bundles, BiStore store, BiGovProperties gov, AuditLog audit) {
        this.bundles = bundles;
        this.store = store;
        this.gov = gov;
        this.audit = audit;
    }

    /** The BI items a pack folder carries; empty when it has no {@code config/bi}. */
    public static List<BiBundles.PackItem> read(Path packRoot, String pack) {
        List<BiBundles.PackItem> out = new ArrayList<>();
        Path bi = packRoot.resolve("config").resolve("bi");
        for (Map.Entry<String, String> f : FOLDERS.entrySet()) {
            Path dir = bi.resolve(f.getKey());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            String suffix = "." + f.getValue() + ".yaml";
            try (Stream<Path> s = Files.list(dir)) {
                for (Path file : s.filter(x -> x.getFileName().toString().endsWith(suffix)).sorted().toList()) {
                    String name = file.getFileName().toString().substring(0, file.getFileName().toString().length() - suffix.length());
                    String body = Files.readString(file, StandardCharsets.UTF_8);
                    JsonNode top = tryRead(body);
                    String title = top != null && top.path("title").isTextual() ? top.path("title").asText() : name;
                    String description = top != null && top.path("description").isTextual() ? top.path("description").asText().strip() : "";
                    out.add(new BiBundles.PackItem(id(pack, f.getValue(), name), f.getValue(), title, description, body));
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

    private static JsonNode tryRead(String body) {
        try {
            return YAML.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    /** A stable id for a pack's item, safe in a URL. */
    static String id(String pack, String kind, String name) {
        return ("pk-" + pack + "-" + kind + "-" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
    }

    /** How many BI items the pack folder carries, by kind, for the upload check. */
    public static Map<String, Object> summary(Path packRoot, String pack) {
        List<BiBundles.PackItem> items = read(packRoot, pack);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items.size());
        Map<String, Integer> kinds = new LinkedHashMap<>();
        items.forEach(i -> kinds.merge(i.kind(), 1, Integer::sum));
        m.put("kinds", kinds);
        return m;
    }

    private BiStore.Workspace workspace(Principal p, boolean create) {
        String id = gov.packWorkspace();
        BiStore.Workspace w = store.workspace(id).orElse(null);
        if (w == null && create) {
            w = store.saveWorkspace(new BiStore.Workspace(id, "Pack content", "BI content that deployed packs carry. Each item is updated by the next version of its pack.", false, p.user(), false, Instant.now(), p.user()));
            store.setMember(new BiStore.Member(id, p.user(), "admin", Instant.now(), p.user()));
            audit.record(p.user(), "bi-workspace-created", id, "for the BI content of deployed packs");
        }
        return w;
    }

    /** What loading the pack's BI content would do (a dry run of the import): created, changed, left alone, refused. */
    public Map<String, Object> preview(Principal p, Path packRoot, String pack, String version) {
        List<BiBundles.PackItem> items = read(packRoot, pack);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.size());
        if (items.isEmpty()) {
            return out;
        }
        try {
            BiStore.Workspace w = workspace(p, false);
            ObjectNode bundle = bundles.packBundle(p, pack, version, w == null ? gov.packWorkspace() : w.id(), items);
            if (w == null) {
                out.put("note", "workspace '" + gov.packWorkspace() + "' does not exist yet; the deploy creates it");
                return out;
            }
            Map<String, Object> dry = bundles.imports(p, bundle, new BiBundles.Options(true, true, false, w.id(), Map.of(), Map.of()), "pack " + pack + " " + version);
            out.put("counts", dry.get("counts"));
            out.put("ok", dry.get("ok"));
            out.put("plan", dry.get("items"));                       // each item: what the import would do with it, and why
        } catch (DrishtiException e) {
            out.put("problem", e.getMessage());
        }
        return out;
    }

    /**
     * Loads the pack's BI content as an import (overwriting what an earlier version of the pack put there). Returns what the import answered; a refusal is
     * answered as {@code {loaded: false, problem}} so the pack deploy itself goes on, and the content can be loaded again by a bi-admin with {@code force}.
     */
    public Map<String, Object> load(Principal p, Path packRoot, String pack, String version, boolean force) {
        List<BiBundles.PackItem> items = read(packRoot, pack);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.size());
        if (items.isEmpty()) {
            out.put("loaded", false);
            return out;
        }
        try {
            BiStore.Workspace w = workspace(p, true);
            ObjectNode bundle = bundles.packBundle(p, pack, version, w.id(), items);
            Map<String, Object> done = bundles.imports(p, bundle, new BiBundles.Options(false, true, force, w.id(), Map.of(), Map.of()), "pack " + pack + " " + version);
            out.put("loaded", true);
            out.put("importId", done.get("importId"));
            out.put("counts", done.get("counts"));
            audit.record(p.user(), "pack-bi-loaded", pack, items.size() + " BI item(s) from version " + version + "; import " + done.get("importId"));
        } catch (DrishtiException e) {
            out.put("loaded", false);
            out.put("problem", e.getMessage());
            audit.record(p.user(), "pack-bi-held", pack, "BI content of version " + version + " was not loaded: " + e.getMessage());
        }
        return out;
    }
}
