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

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.PackOverlay;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.security.PackAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every installed artifact in one list: packs (the packs folder and the installed folder), connector files, and the shipped BI files
 * (datasets, reports, boards, notebooks, Python items). Each row says what it is, its state, where it came from, its version or
 * modification time, what is wrong with it, and where its own page is. Read only; the actions are {@link ArtifactService}'s.
 *
 * <p>States: {@code on}, {@code off} (disabled: kept, switched off), {@code not loaded} (a pack on disk the server did not load) and
 * {@code problem}. Origins: {@code shipped} (the packs and BI folders the product ships), {@code archive} (a pack deployed from an
 * archive), {@code file} (a connector file, or one placed by hand), {@code ui} (a connector saved from the console), {@code template}
 * (a connector a pack's template wrote), {@code application} (a connector only the server's own configuration defines).
 */
public final class ArtifactCatalog {

    private static final int AUDIT_WINDOW = 5_000;

    private final ArtifactPaths paths;
    private final PackRegistry packs;
    private final PackAccess access;
    private final PackOverlay overlay;
    private final ConnectorManager connectors;
    private final BiFolder folder;
    private final AuditLog audit;

    public ArtifactCatalog(ArtifactPaths paths, PackRegistry packs, PackAccess access, PackOverlay overlay, ConnectorManager connectors, BiFolder folder, AuditLog audit) {
        this.paths = paths;
        this.packs = packs;
        this.access = access;
        this.overlay = overlay;
        this.connectors = connectors;
        this.folder = folder;
        this.audit = audit;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(packRows());
        out.addAll(connectorRows());
        out.addAll(biRows());
        return out;
    }

    private List<Map<String, Object>> packRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ArtifactPaths.PackFolder f : paths.packFolders()) {
            if (!seen.add(f.name())) {
                continue;
            }
            Pack loaded = packs.packs().stream().filter(p -> p.name().equals(f.name())).findFirst().orElse(null);
            List<String> problems = new ArrayList<>();
            if (!f.problem().isEmpty()) {
                problems.add(f.problem());
            }
            String problem = packs.problems().get(f.name());
            if (problem != null) {
                problems.add(problem);
            }
            String state = loaded == null ? (problems.isEmpty() ? "not loaded" : "problem") : !access.isEnabled(f.name()) ? "off" : problems.isEmpty() ? "on" : "problem";
            Map<String, Object> r = row("pack", "", f.name(), f.title(), state, f.area().equals("installed") ? "archive" : "shipped", f.version(), modified(f.dir().resolve("pack.yaml")), problems,
                    "/admin/packs/" + f.name());
            r.put("added", overlay.added().contains(f.name()));
            r.put("location", f.dir().toString());
            out.add(r);
        }
        for (Pack p : packs.packs()) {
            if (seen.add(p.name())) {                                       // loaded from somewhere the folders do not show (a custom location)
                Map<String, Object> r = row("pack", "", p.name(), p.title(), access.isEnabled(p.name()) ? "on" : "off", "shipped", p.version(), null, List.of(), "/admin/packs/" + p.name());
                r.put("location", p.dir() == null ? "" : p.dir().toString());
                out.add(r);
            }
        }
        return out;
    }

    private List<Map<String, Object>> connectorRows() {
        Set<String> ui = new HashSet<>();
        for (AuditLog.Event e : audit.recent(AUDIT_WINDOW, null)) {
            if ("connector-saved".equals(e.action())) {
                ui.add(e.subject());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : connectors.list()) {
            String name = String.valueOf(c.get("name"));
            boolean on = !Boolean.FALSE.equals(c.get("enabled"));
            @SuppressWarnings("unchecked")
            List<String> problems = c.get("problems") instanceof List<?> l ? new ArrayList<>((List<String>) l) : new ArrayList<>();
            String origin = switch (String.valueOf(c.get("origin"))) {
                case "file" -> ui.contains(name) ? "ui" : "file";
                case "pack" -> "template";
                default -> "application";
            };
            boolean fileProblem = connectors.fileProblems().containsKey(name);
            String state = !on ? "off" : fileProblem || !problems.isEmpty() && !"RUNNING".equals(String.valueOf(c.get("state"))) && !"IDLE".equals(String.valueOf(c.get("state"))) ? "problem" : "on";
            Object updated = c.get("updated");
            Map<String, Object> r = row("connector", "", name, title(c), state, origin, "", updated == null ? null : updated.toString(),
                    problems, "/admin/connectors/" + name);
            r.put("plugin", c.get("plugin"));
            r.put("connectorState", c.get("state"));
            r.put("level", c.get("level"));                      // the same level and label as the Connectors page: "with a warning" is not "on"
            r.put("label", c.get("label"));
            r.put("kinds", c.get("kinds"));
            r.put("hasFile", connectors.files().exists(name));
            out.add(r);
        }
        return out;
    }

    private List<Map<String, Object>> biRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> problemsByFile = new LinkedHashMap<>();
        folder.problems().forEach(p -> problemsByFile.put(p.file(), p.message() + (p.keptLastGood() ? " (the last good version is in use)" : "")));
        Set<String> seen = new HashSet<>();
        for (BiStore.Item i : folder.allItems()) {
            String name = i.id().substring(i.id().lastIndexOf(':') + 1);
            String file = ArtifactPaths.BI_FOLDERS.get(i.kind()) + "/" + name + "." + i.kind() + ".yaml";
            seen.add(file);
            List<String> problems = new ArrayList<>();
            if (problemsByFile.containsKey(file)) {
                problems.add(problemsByFile.get(file));
            }
            boolean hidden = folder.hidden().contains(i.id());
            Map<String, Object> r = row("bi", i.kind(), name, i.title(), problems.isEmpty() ? (hidden ? "off" : "on") : "problem", "shipped", "v" + i.publishedVersion(),
                    i.updatedAt() == null ? null : i.updatedAt().toString(), problems, "/bi/i/" + i.id());
            r.put("hidden", hidden);
            r.put("path", file);
            out.add(r);
        }
        problemsByFile.forEach((file, message) -> {                        // a file that never parsed: no item, only a problem
            if (!seen.contains(file)) {
                int slash = file.indexOf('/');
                String base = file.substring(slash + 1);
                String[] parts = base.split("\\.");
                String sub = parts.length >= 3 ? parts[parts.length - 2] : "";
                out.add(row("bi", sub, parts[0], parts[0], "problem", "shipped", "", null, List.of(message), "/admin/bi"));
            }
        });
        return out;
    }

    private static String title(Map<String, Object> c) {
        Object d = c.get("description");
        if (d != null && !d.toString().isBlank()) {
            return d.toString();
        }
        return c.get("plugin") == null ? "" : c.get("plugin") + " connector";
    }

    private static Map<String, Object> row(String kind, String subtype, String name, String title, String state, String origin, String version, String modified,
            List<String> problems, String link) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind);
        r.put("subtype", subtype);
        r.put("name", name);
        r.put("title", title);
        r.put("state", state);
        r.put("origin", origin);
        r.put("version", version);
        r.put("modified", modified);
        r.put("problems", problems);
        r.put("link", link);
        return r;
    }

    private static String modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
