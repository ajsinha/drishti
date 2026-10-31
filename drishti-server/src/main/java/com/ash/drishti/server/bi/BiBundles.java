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
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.deploy.PackArchive;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves BI content between environments (docs/guides/BI_GUIDE.md, "Environments"): {@link #export} writes items with their published versions, their
 * sample flag, their audience and their owner as one checksummed JSON bundle; {@link #imports} reads one into another server, as a dry run that lists what
 * it would create, change, skip or refuse, or for real. Nothing travels that a person did not already have the right to edit; certification does not
 * travel (a steward of the receiving environment certifies after looking); an owner who does not exist on the receiving server is mapped or becomes the
 * importer; a workspace is mapped or must exist. Shipped files are not in a bundle: they are files and travel with the BI folder.
 */
public final class BiBundles {

    public static final String FORMAT = "drishti-bi-bundle";
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final BiService bi;
    private final BiStore store;
    private final BiAccess access;
    private final ImpactAnalyzer impact;
    private final BiGovProperties props;
    private final BiProperties biProps;
    private final AuditLog audit;
    private final UserService users;
    private final RoleCatalog roles;
    private final PackAccess packs;
    private final ImportLog log;

    public BiBundles(BiService bi, BiStore store, BiAccess access, ImpactAnalyzer impact, BiGovProperties props, BiProperties biProps, AuditLog audit, UserService users,
            RoleCatalog roles, PackAccess packs, ImportLog log) {
        this.log = log;
        this.bi = bi;
        this.store = store;
        this.access = access;
        this.impact = impact;
        this.props = props;
        this.biProps = biProps;
        this.audit = audit;
        this.users = users;
        this.roles = roles;
        this.packs = packs;
    }

    // ---- export ------------------------------------------------------------------------------------------------------------

    /**
     * A bundle of the given items (and, with {@code withDependencies}, the stored datasets the reports among them read). The caller needs {@code bi-author}
     * and the contributor role in each item's workspace; items they may only read, and shipped items, are listed under {@code skipped}.
     */
    public ObjectNode export(Principal p, List<String> ids, String workspace, boolean withDependencies) {
        bi.requirePower(p, BiAccess.AUTHOR);
        List<String> wanted = new ArrayList<>(ids == null ? List.of() : ids);
        if (workspace != null && !workspace.isBlank()) {
            BiStore.Workspace w = "me".equals(workspace) ? bi.personal(p) : bi.workspaceOf(workspace);
            bi.requireRole(w, p, "contributor", "exporting its items");
            store.itemsIn(w.id()).forEach(i -> wanted.add(i.id()));
        }
        if (wanted.isEmpty()) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "name the items to export (items=a,b) or a workspace (workspace=...)");
        }
        Map<String, BiStore.Item> chosen = new LinkedHashMap<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String id : new LinkedHashSet<>(wanted)) {
            take(p, id, chosen, skipped, "asked for");
        }
        if (withDependencies) {
            for (BiStore.Item i : new ArrayList<>(chosen.values())) {
                if (com.ash.drishti.server.bi.board.ReportBoards.isBoard(i.kind(), i.body())) {
                    for (BiStore.Item rep : impact.reportsOf(i.body())) {
                        if (!chosen.containsKey(rep.id())) {
                            take(p, rep.id(), chosen, skipped, "a report on the board '" + i.title() + "'");
                        }
                    }
                }
            }
            for (BiStore.Item i : new ArrayList<>(chosen.values())) {
                if ("report".equals(i.kind())) {
                    BiStore.Item ds = impact.datasetOf(i.body());
                    if (ds != null && !chosen.containsKey(ds.id())) {
                        take(p, ds.id(), chosen, skipped, "the dataset of '" + i.title() + "'");
                    }
                }
            }
        }
        ArrayNode items = JSON.createArrayNode();
        List<String> lines = new ArrayList<>();
        for (BiStore.Item i : chosen.values()) {
            items.add(node(i, lines));
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("formatVersion", FORMAT_VERSION);
        root.put("createdAt", Instant.now().toString());
        root.put("createdBy", p.user());
        root.set("items", items);
        root.put("checksum", PackArchive.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        root.set("skipped", JSON.valueToTree(skipped));
        audit.record(p.user(), "bi-bundle-exported", "bundle", chosen.size() + " item(s): " + String.join(", ", chosen.keySet()));
        return root;
    }

    private void take(Principal p, String id, Map<String, BiStore.Item> chosen, List<Map<String, Object>> skipped, String why) {
        try {
            if (BiFolder.isShipped(id)) {
                skipped.add(Map.of("id", id, "reason", "shipped content is a file of the BI folder; it travels with the folder"));
                return;
            }
            BiStore.Item i = bi.readableForGovernance(p, id);
            if (BiAccess.rank(bi.roleIn(bi.workspaceOf(i.workspace()), p)) < BiAccess.rank("contributor")) {
                skipped.add(Map.of("id", id, "reason", "you may read '" + i.title() + "' but not edit it, so you may not export its draft"));
                return;
            }
            chosen.put(i.id(), i);
        } catch (DrishtiException e) {
            skipped.add(Map.of("id", id, "reason", e.getMessage()));
        }
    }

    private ObjectNode node(BiStore.Item i, List<String> lines) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", i.id());
        n.put("kind", i.kind());
        n.put("title", i.title());
        n.put("description", i.description());
        n.put("owner", i.owner());
        n.put("workspace", i.workspace());
        n.put("workspaceName", bi.workspaceOf(i.workspace()).name());
        n.set("tags", JSON.valueToTree(i.tags()));
        n.put("sample", i.tags().contains(BiFolder.SAMPLE_TAG));
        n.put("endorsement", i.endorsement());
        ObjectNode a = n.putObject("audience");
        a.set("roles", JSON.valueToTree(i.audienceRoles()));
        a.set("users", JSON.valueToTree(i.audienceUsers()));
        a.set("packs", JSON.valueToTree(i.audiencePacks()));
        n.put("status", i.status().equals("proposed") ? "draft" : i.status());
        n.put("publishedVersion", i.publishedVersion());
        n.put("body", i.body());
        n.put("sha256", PackArchive.sha256(i.body().getBytes(StandardCharsets.UTF_8)));
        ArrayNode versions = n.putArray("versions");
        StringBuilder line = new StringBuilder(i.id()).append('|').append(n.get("sha256").asText()).append('|').append(i.publishedVersion());
        for (BiStore.Version v : store.versions(i.id()).stream().sorted(java.util.Comparator.comparingInt(BiStore.Version::version)).toList()) {
            ObjectNode vn = versions.addObject();
            vn.put("version", v.version());
            vn.put("note", v.note());
            vn.put("createdBy", v.createdBy());
            vn.put("createdAt", v.createdAt() == null ? null : v.createdAt().toString());
            vn.put("body", v.body());
            vn.put("sha256", PackArchive.sha256(v.body().getBytes(StandardCharsets.UTF_8)));
            line.append('|').append(v.version()).append(':').append(vn.get("sha256").asText());
        }
        lines.add(line.toString());
        return n;
    }

    // ---- a pack's BI content --------------------------------------------------------------------------------------------------

    /** One BI file of a pack: a stable id, its kind, the title and description it declares, and its text. */
    public record PackItem(String id, String kind, String title, String description, String body) {}

    /**
     * A bundle of a pack's BI items for the import: each is published (version 1, or the next one when the text differs from what an earlier version of the pack
     * left; the same version when it does not, so an unchanged item stays unchanged), has the pack as its audience, is owned by the importer and is never certified.
     */
    public ObjectNode packBundle(Principal p, String pack, String version, String workspace, List<PackItem> items) {
        ArrayNode nodes = JSON.createArrayNode();
        List<String> lines = new ArrayList<>();
        Instant now = Instant.now();
        for (PackItem it : items) {
            BiStore.Item here = store.item(it.id()).orElse(null);
            String have = here == null ? null : impact.publishedBody(here);
            int pv = here == null ? 1 : it.body().equals(have) ? here.publishedVersion() : here.publishedVersion() + 1;
            ObjectNode n = JSON.createObjectNode();
            n.put("id", it.id());
            n.put("kind", it.kind());
            n.put("title", it.title());
            n.put("description", it.description());
            n.put("owner", p.user());
            n.put("workspace", workspace);
            n.put("workspaceName", workspace);
            n.set("tags", JSON.valueToTree(List.of("pack:" + pack)));
            n.put("sample", false);
            n.put("endorsement", "none");
            ObjectNode a = n.putObject("audience");
            a.set("roles", JSON.createArrayNode());
            a.set("users", JSON.createArrayNode());
            a.set("packs", JSON.valueToTree(List.of(pack)));
            n.put("status", "published");
            n.put("publishedVersion", pv);
            n.put("body", it.body());
            n.put("sha256", PackArchive.sha256(it.body().getBytes(StandardCharsets.UTF_8)));
            ArrayNode versions = n.putArray("versions");
            ObjectNode vn = versions.addObject();
            vn.put("version", pv);
            vn.put("note", "pack " + pack + " " + version);
            vn.put("createdBy", p.user());
            vn.put("createdAt", now.toString());
            vn.put("body", it.body());
            vn.put("sha256", n.get("sha256").asText());
            lines.add(it.id() + "|" + n.get("sha256").asText() + "|" + pv + "|" + pv + ":" + n.get("sha256").asText());
            nodes.add(n);
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("formatVersion", FORMAT_VERSION);
        root.put("createdAt", now.toString());
        root.put("createdBy", "pack " + pack + " " + version);
        root.set("items", nodes);
        root.put("checksum", PackArchive.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        return root;
    }

    // ---- import ------------------------------------------------------------------------------------------------------------

    /** The choices of an import. */
    public record Options(boolean dryRun, boolean overwrite, boolean force, String workspace, Map<String, String> workspaces, Map<String, String> owners) {}

    private record Plan(JsonNode src, String action, String id, String workspace, String owner, List<String> reasons, List<String> warnings, List<String> blocks) {}

    /** Reads a bundle: a dry run reports; a real run applies when nothing blocks it. Needs {@code bi-steward} (an environment's governance act). */
    public Map<String, Object> imports(Principal p, JsonNode bundle, Options o) {
        return imports(p, bundle, o, "bundle");
    }

    /** As {@link #imports(Principal, JsonNode, Options)}, saying where the content came from ({@code bundle}, {@code pack <name> <version>}) for the import log. */
    public Map<String, Object> imports(Principal p, JsonNode bundle, Options o, String source) {
        bi.requirePower(p, BiAccess.STEWARD);
        verify(bundle);
        List<Plan> plans = new ArrayList<>();
        Set<String> inBundle = new LinkedHashSet<>();
        bundle.get("items").forEach(n -> inBundle.add(n.path("id").asText()));
        for (JsonNode n : bundle.get("items")) {
            plans.add(plan(p, n, o, inBundle, bundle.get("items")));
        }
        int blocked = (int) plans.stream().filter(x -> !x.blocks().isEmpty()).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", o.dryRun());
        out.put("createdBy", bundle.path("createdBy").asText(""));
        out.put("createdAt", bundle.path("createdAt").asText(""));
        out.put("items", plans.stream().map(BiBundles::planMap).toList());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String a : List.of("create", "update", "unchanged", "conflict", "invalid")) {
            counts.put(a, (int) plans.stream().filter(x -> x.action().equals(a)).count());
        }
        out.put("counts", counts);
        out.put("ok", blocked == 0);
        out.put("applied", false);
        if (o.dryRun()) {
            return out;
        }
        if (blocked > 0) {
            Plan first = plans.stream().filter(x -> !x.blocks().isEmpty()).findFirst().orElseThrow();
            boolean invalid = first.action().equals("invalid");
            throw new DrishtiException(invalid ? ErrorCode.BI_BUNDLE : ErrorCode.BI_IMPORT, blocked + " item(s) of the bundle cannot be imported; nothing was changed. First: '" + first.id()
                    + "': " + first.blocks().get(0) + " (run it as a dry run to see them all)");
        }
        int done = 0;
        ObjectNode record = log.begin(p.user(), source, bundle.path("createdBy").asText(""));
        for (Plan x : plans) {
            if (x.action().equals("create") || x.action().equals("update")) {
                ImportLog.entries(record).add(apply(p, x, bundle.path("createdBy").asText("")));
                done++;
            }
        }
        log.save(record);
        audit.record(p.user(), "bi-bundle-imported", "bundle", done + " item(s) written from a bundle made by " + bundle.path("createdBy").asText("?") + " at " + bundle.path("createdAt").asText("?")
                + "; import " + record.path("id").asText());
        out.put("applied", true);
        out.put("importId", record.path("id").asText());
        return out;
    }

    private static Map<String, Object> planMap(Plan x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("kind", x.src().path("kind").asText());
        m.put("title", x.src().path("title").asText());
        m.put("action", x.action());
        m.put("workspace", x.workspace());
        m.put("owner", x.owner());
        m.put("sample", x.src().path("sample").asBoolean(false));
        m.put("publishedVersion", x.src().path("publishedVersion").asInt(0));
        m.put("versions", x.src().path("versions").size());
        m.put("reasons", x.reasons());
        m.put("warnings", x.warnings());
        m.put("blocks", x.blocks());
        return m;
    }

    private void verify(JsonNode b) {
        if (b == null || !b.isObject() || !FORMAT.equals(b.path("format").asText()) || b.path("formatVersion").asInt(0) != FORMAT_VERSION || !b.path("items").isArray()) {
            throw new DrishtiException(ErrorCode.BI_BUNDLE, "not a " + FORMAT + " bundle of format " + FORMAT_VERSION);
        }
        if (b.toString().length() > props.bundleMaxBytes()) {
            throw new DrishtiException(ErrorCode.BI_BUNDLE, "the bundle is larger than " + props.bundleMaxBytes() + " bytes (drishti.bi.governance.bundle-max-bytes)");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode n : b.get("items")) {
            String id = n.path("id").asText();
            if (id.isBlank() || !n.path("body").isTextual() || !n.path("sha256").isTextual()) {
                throw new DrishtiException(ErrorCode.BI_BUNDLE, "an item of the bundle has no id, body or checksum");
            }
            if (!n.get("sha256").asText().equals(PackArchive.sha256(n.get("body").asText().getBytes(StandardCharsets.UTF_8)))) {
                throw new DrishtiException(ErrorCode.BI_BUNDLE, "the body of '" + id + "' does not match its checksum (the file was changed after it was exported)");
            }
            StringBuilder line = new StringBuilder(id).append('|').append(n.get("sha256").asText()).append('|').append(n.path("publishedVersion").asInt(0));
            for (JsonNode v : n.path("versions")) {
                if (!v.get("sha256").asText().equals(PackArchive.sha256(v.path("body").asText().getBytes(StandardCharsets.UTF_8)))) {
                    throw new DrishtiException(ErrorCode.BI_BUNDLE, "version " + v.path("version").asInt() + " of '" + id + "' does not match its checksum");
                }
                line.append('|').append(v.path("version").asInt()).append(':').append(v.get("sha256").asText());
            }
            lines.add(line.toString());
        }
        if (!b.path("checksum").asText().equals(PackArchive.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)))) {
            throw new DrishtiException(ErrorCode.BI_BUNDLE, "the bundle's checksum does not match its items");
        }
    }

    private Plan plan(Principal p, JsonNode n, Options o, Set<String> inBundle, JsonNode all) {
        String id = n.path("id").asText();
        String kind = n.path("kind").asText();
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        String body = n.path("body").asText();
        // the item itself
        if (!BiService.KINDS.contains(kind)) {
            blocks.add("kind '" + kind + "' is not one of " + BiService.KINDS);
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > biProps.maxBodyBytes()) {
            blocks.add("the body is larger than drishti.bi.max-body-bytes");
        }
        problemOf(kind, body, "the draft", blocks);
        for (JsonNode v : n.path("versions")) {
            if (v.path("version").asInt() == n.path("publishedVersion").asInt()) {
                problemOf(kind, v.path("body").asText(), "published version " + v.path("version").asInt(), blocks);
            }
        }
        // the workspace
        String src = n.path("workspace").asText();
        String target = o.workspaces().getOrDefault(src, src.startsWith("u-") && !o.workspaces().containsKey(src) ? "me" : o.workspace() != null && !o.workspace().isBlank() ? o.workspace() : src);
        String wsId = target;
        try {
            BiStore.Workspace w = "me".equals(target) ? bi.personal(p) : bi.workspaceOf(target);
            wsId = w.id();
            bi.requireRole(w, p, n.path("publishedVersion").asInt(0) > 0 ? "member" : "contributor", "importing into it");
            if (w.personal() && n.path("publishedVersion").asInt(0) > 0) {
                blocks.add("a personal workspace cannot hold published items: map '" + src + "' to a shared workspace");
            }
        } catch (DrishtiException e) {
            blocks.add("workspace '" + target + "' (from '" + src + "'): " + e.getMessage() + "; create it here, or map it with workspaces=" + src + ":<workspace>");
        }
        // the owner
        String srcOwner = n.path("owner").asText();
        String owner = o.owners().getOrDefault(srcOwner, srcOwner);
        if (users.find(owner).isEmpty()) {
            warnings.add("owner '" + srcOwner + "' does not exist here" + (o.owners().containsKey(srcOwner) ? " (nor does the mapped '" + owner + "')" : "") + ": the importer " + p.user() + " becomes the owner (map owners with owners=" + srcOwner + ":<user>)");
            owner = p.user();
        } else if (!owner.equals(srcOwner)) {
            warnings.add("owner '" + srcOwner + "' is mapped to '" + owner + "'");
        }
        // what is there now
        BiStore.Item here = store.item(id).orElse(null);
        String action = "create";
        if (here == null && BiFolder.isShipped(id)) {
            blocks.add("shipped ids cannot be imported");
        }
        if (here != null) {
            if (!here.workspace().equals(wsId)) {
                blocks.add("an item with this id exists here in workspace '" + here.workspace() + "', not '" + wsId + "'");
            }
            String pub = impact.publishedBody(here);
            String bundlePub = publishedBody(n);
            boolean sameDraft = here.body().equals(body);
            boolean samePub = (pub == null ? "" : pub).equals(bundlePub == null ? "" : bundlePub) && here.publishedVersion() == n.path("publishedVersion").asInt(0);
            if (sameDraft && samePub) {
                action = "unchanged";
                reasons.add("identical to what is here");
            } else {
                action = "update";
                if (!sameDraft) {
                    reasons.add("the draft differs");
                }
                if (!samePub) {
                    reasons.add("published version " + here.publishedVersion() + " here, " + n.path("publishedVersion").asInt(0) + " in the bundle");
                }
                if (!o.overwrite()) {
                    blocks.add("it exists here and differs (" + String.join("; ", reasons) + "): import with overwrite to replace it");
                }
            }
            if ("dataset".equals(kind) && !samePub && pub != null && bundlePub != null) {
                ImpactAnalyzer.Result res = impact.impact(here, pub, bundlePub);
                List<ImpactAnalyzer.Hit> hit = res.published().stream().filter(h -> !inBundle.contains(h.report().id())).toList();
                if (res.change().breaking() && !hit.isEmpty() && props.refuseBreaking()) {
                    String names = hit.stream().map(h -> "'" + h.report().title() + "'").limit(5).collect(java.util.stream.Collectors.joining(", "));
                    if (o.force() && access.has(p, BiAccess.ADMIN)) {
                        warnings.add("FORCED: breaks published report(s) " + names + " by removing " + String.join(", ", res.change().removed()));
                    } else {
                        blocks.add("would break published report(s) " + names + " by removing " + String.join(", ", res.change().removed())
                                + (o.force() ? " (forcing needs bi-admin)" : "; a bi-admin may import with force"));
                    }
                }
            }
        }
        if (action.equals("create")) {
            reasons.add("new here");
        }
        // sample and certification
        if (n.path("endorsement").asText().equals("certified")) {
            warnings.add("certification does not travel: it is promoted here; a bi-steward certifies it after the import");
        }
        if (n.path("sample").asBoolean(false)) {
            warnings.add("a sample item: hidden from those who may not see samples");
        }
        // a report's dataset
        if ("report".equals(kind)) {
            Report r = ReportParser.parse(body).report();
            if (r != null && r.model() == null && !r.dataset().isBlank() && !datasetKnown(r.dataset(), all)) {
                warnings.add("its dataset '" + r.dataset() + "' is neither in the bundle nor here: the report cannot be drawn until it is");
            }
        }
        // a board's reports
        if (com.ash.drishti.server.bi.board.ReportBoards.isBoard(kind, body)) {
            for (com.ash.drishti.server.bi.board.Board.Page pg : boardPages(body)) {
                for (com.ash.drishti.server.bi.board.Board.Tile t : pg.tiles()) {
                    if (!t.report().isBlank() && !reportKnown(t.report(), all)) {
                        warnings.add("its report '" + t.report() + "' is neither in the bundle nor here: that tile cannot be drawn until it is");
                    }
                }
            }
        }
        // the audience
        for (String role : strings(n.path("audience").path("roles"))) {
            if (!roles.exists(role) && !role.equals("*")) {
                warnings.add("audience role '" + role + "' does not exist here and is left out");
            }
        }
        if (!blocks.isEmpty() && !action.equals("unchanged")) {
            boolean structural = blocks.stream().anyMatch(b -> b.startsWith("the draft") || b.startsWith("published version") || b.startsWith("kind ") || b.startsWith("the body"));
            action = structural ? "invalid" : "conflict";
        }
        return new Plan(n, action, id, wsId, owner, reasons, warnings, blocks);
    }

    private static List<com.ash.drishti.server.bi.board.Board.Page> boardPages(String body) {
        com.ash.drishti.server.bi.board.Board b = com.ash.drishti.server.bi.board.ReportBoards.boardOfText(body);
        return b == null ? List.of() : b.pages();
    }

    private boolean reportKnown(String ref, JsonNode all) {
        for (JsonNode n : all) {
            if ("report".equals(n.path("kind").asText()) && (n.path("id").asText().equals(ref) || n.path("id").asText().equals("file:report:" + ref)
                    || n.path("title").asText().equalsIgnoreCase(ref))) {
                return true;
            }
        }
        return impact.reports().stream().anyMatch(r -> ImpactAnalyzer.reportNamed(ref, r));
    }

    private boolean datasetKnown(String ref, JsonNode all) {
        for (JsonNode n : all) {
            if ("dataset".equals(n.path("kind").asText()) && (n.path("id").asText().equals(ref) || n.path("title").asText().equalsIgnoreCase(ref)
                    || ref.equals(ImpactAnalyzer.declaredName(n.path("body").asText())))) {
                return true;
            }
        }
        return impact.datasets().stream().anyMatch(d -> impact.refersTo(ref, d));
    }

    private static String publishedBody(JsonNode n) {
        int pv = n.path("publishedVersion").asInt(0);
        if (pv <= 0) {
            return null;
        }
        for (JsonNode v : n.path("versions")) {
            if (v.path("version").asInt() == pv) {
                return v.path("body").asText();
            }
        }
        return null;
    }

    private static void problemOf(String kind, String body, String which, List<String> blocks) {
        if (body.isBlank()) {
            return;
        }
        if ("dataset".equals(kind)) {
            DatasetParser.Result r = DatasetParser.parse(body);
            if (!r.ok()) {
                blocks.add(which + " is not a valid dataset: " + r.firstError());
            }
        } else if ("report".equals(kind)) {
            ReportParser.Result r = ReportParser.parse(body);
            if (!r.ok()) {
                blocks.add(which + " is not a valid report: " + r.firstError());
            }
        } else if ("board".equals(kind)) {
            var r = com.ash.drishti.server.bi.board.BoardParser.parse(body);
            if (!r.ok()) {
                blocks.add(which + " is not a valid board: " + r.firstError());
            }
        }
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out;
    }

    private List<String> keep(JsonNode a, java.util.function.Predicate<String> exists) {
        return strings(a).stream().filter(exists).toList();
    }

    private ObjectNode apply(Principal p, Plan x, String madeBy) {
        JsonNode n = x.src();
        Instant now = Instant.now();
        BiStore.Item here = store.item(x.id()).orElse(null);
        ObjectNode entry = JSON.createObjectNode();
        entry.put("itemId", x.id());
        entry.put("title", n.path("title").asText());
        entry.put("kind", n.path("kind").asText());
        entry.put("created", here == null);
        if (here != null) {
            entry.set("before", snapshot(here));
        }
        int pv = n.path("publishedVersion").asInt(0);
        String body = n.path("body").asText();
        String pub = publishedBody(n);
        List<String> tags = strings(n.path("tags"));
        if (n.path("sample").asBoolean(false) && !tags.contains(BiFolder.SAMPLE_TAG)) {
            tags = new ArrayList<>(tags);
            tags.add(BiFolder.SAMPLE_TAG);
        }
        List<String> installed = packs.installed();
        String endorsement = n.path("endorsement").asText("none").equals("none") ? "none" : "promoted";
        String status = pv > 0 && body.equals(pub) ? "published" : "draft";
        if (here != null) {
            revise(here, here.body(), "before import by " + p.user(), p.user(), "save");
        }
        BiStore.Item item = new BiStore.Item(x.id(), x.workspace(), n.path("kind").asText(), n.path("title").asText(), n.path("description").asText(), x.owner(), tags, body, status, pv,
                endorsement, endorsement.equals("none") ? "" : p.user(), keep(n.path("audience").path("roles"), r -> roles.exists(r) || r.equals("*")),
                keep(n.path("audience").path("users"), u -> users.find(u).isPresent()), keep(n.path("audience").path("packs"), installed::contains), "", null,
                here == null ? now : here.createdAt(), now, p.user(), pv > 0 ? now : null, pv > 0 ? p.user() : "");
        store.saveItem(item);
        for (JsonNode v : n.path("versions")) {
            store.addVersion(new BiStore.Version(x.id(), v.path("version").asInt(), v.path("body").asText(), v.path("note").asText(), v.path("createdBy").asText(),
                    v.path("createdAt").isTextual() ? Instant.parse(v.path("createdAt").asText()) : now), biProps.versionsKeep());
        }
        BiStore.Revision r = revise(item, body, "imported from a bundle made by " + madeBy, p.user(), "import");
        store.addEvent(x.id(), "import", p.user(), "from a bundle made by " + madeBy + (x.warnings().isEmpty() ? "" : "; " + String.join("; ", x.warnings())), r.seq(), now);
        audit.record(p.user(), "bi-item-imported", x.id(), n.path("kind").asText() + " '" + n.path("title").asText() + "' in " + x.workspace() + (here == null ? " (created)" : " (overwritten)"));
        entry.put("afterRevision", r.seq());
        entry.put("afterVersion", pv);
        return entry;
    }

    // ---- rolling an import back ---------------------------------------------------------------------------------------------

    /** Everything about an item that an import can change: its row, its published versions. */
    private ObjectNode snapshot(BiStore.Item i) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", i.id());
        n.put("workspace", i.workspace());
        n.put("kind", i.kind());
        n.put("title", i.title());
        n.put("description", i.description());
        n.put("owner", i.owner());
        n.set("tags", JSON.valueToTree(i.tags()));
        n.put("body", i.body());
        n.put("status", i.status());
        n.put("publishedVersion", i.publishedVersion());
        n.put("endorsement", i.endorsement());
        n.put("endorsedBy", i.endorsedBy());
        n.set("audienceRoles", JSON.valueToTree(i.audienceRoles()));
        n.set("audienceUsers", JSON.valueToTree(i.audienceUsers()));
        n.set("audiencePacks", JSON.valueToTree(i.audiencePacks()));
        n.put("proposedBy", i.proposedBy());
        n.put("proposedAt", i.proposedAt() == null ? null : i.proposedAt().toString());
        n.put("createdAt", i.createdAt() == null ? null : i.createdAt().toString());
        n.put("publishedAt", i.publishedAt() == null ? null : i.publishedAt().toString());
        n.put("publishedBy", i.publishedBy());
        ArrayNode versions = n.putArray("versions");
        for (BiStore.Version v : store.versions(i.id())) {
            ObjectNode vn = versions.addObject();
            vn.put("version", v.version());
            vn.put("body", v.body());
            vn.put("note", v.note());
            vn.put("createdBy", v.createdBy());
            vn.put("createdAt", v.createdAt() == null ? null : v.createdAt().toString());
        }
        return n;
    }

    private static Instant instant(JsonNode n) {
        return n.isTextual() ? Instant.parse(n.asText()) : null;
    }

    /** The imports whose replaced states are kept, newest first (a steward). */
    public Map<String, Object> imported(Principal p) {
        bi.requirePower(p, BiAccess.STEWARD);
        return Map.of("imports", log.all().stream().map(ImportLog::summary).toList());
    }

    /** One import with the items it wrote and whether each is still as the import left it. */
    public Map<String, Object> importDetail(Principal p, String id) {
        bi.requirePower(p, BiAccess.STEWARD);
        ObjectNode r = record(id);
        Map<String, Object> out = new LinkedHashMap<>(JSON.convertValue(ImportLog.summary(r), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode e : r.path("entries")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.path("itemId").asText());
            m.put("title", e.path("title").asText());
            m.put("kind", e.path("kind").asText());
            m.put("created", e.path("created").asBoolean());
            m.put("restoresVersion", e.path("before").path("publishedVersion").asInt(0));
            m.put("changedSince", changedSince(e));
            items.add(m);
        }
        out.put("entries", items);
        return out;
    }

    private ObjectNode record(String id) {
        ObjectNode r = log.get(id);
        if (r == null) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no import '" + id + "' is kept (drishti.bi.governance.imports-keep says how many)");
        }
        return r;
    }

    /** True when the item is no longer as the import left it (edited or published since), or has gone. */
    private boolean changedSince(JsonNode e) {
        BiStore.Item now = store.item(e.path("itemId").asText()).orElse(null);
        if (now == null) {
            return true;
        }
        int latest = store.revisions(now.id()).stream().findFirst().map(BiStore.Revision::seq).orElse(0);
        return latest != e.path("afterRevision").asInt() || now.publishedVersion() != e.path("afterVersion").asInt();
    }

    /**
     * Puts back what an import replaced: an item it created is removed, an item it overwrote gets its earlier row and published versions again (as a new saved
     * state, so nothing in the history is lost). An item edited since the import is not touched, and the roll back stops with nothing changed, unless
     * {@code force} (a bi-admin) says to restore it anyway. Audited.
     */
    public Map<String, Object> rollback(Principal p, String id, boolean force) {
        bi.requirePower(p, BiAccess.STEWARD);
        ObjectNode r = record(id);
        if (r.path("rolledBackAt").isTextual()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "import " + id + " was already rolled back by " + r.path("rolledBackBy").asText() + " at " + r.path("rolledBackAt").asText());
        }
        List<String> changed = new ArrayList<>();
        for (JsonNode e : r.path("entries")) {
            if (changedSince(e) && store.item(e.path("itemId").asText()).isPresent()) {
                changed.add("'" + e.path("title").asText() + "'");
            }
        }
        if (!changed.isEmpty() && !force) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, changed.size() + " item(s) were edited or published since the import: " + String.join(", ", changed)
                    + ". Rolling back would lose that work; nothing was changed." + (access.has(p, BiAccess.ADMIN) ? " A bi-admin may roll back with force." : " Ask a bi-admin to roll back with force."));
        }
        if (!changed.isEmpty()) {
            access.require(p, BiAccess.ADMIN);
        }
        Instant now = Instant.now();
        int restored = 0;
        int removed = 0;
        for (JsonNode e : r.path("entries")) {
            String itemId = e.path("itemId").asText();
            if (e.path("created").asBoolean()) {
                if (store.deleteItem(itemId)) {
                    removed++;
                }
                continue;
            }
            JsonNode b = e.path("before");
            store.dropVersionsAbove(itemId, b.path("publishedVersion").asInt(0));
            for (JsonNode v : b.path("versions")) {
                store.addVersion(new BiStore.Version(itemId, v.path("version").asInt(), v.path("body").asText(), v.path("note").asText(), v.path("createdBy").asText(), instant(v.path("createdAt"))),
                        biProps.versionsKeep());
            }
            store.saveItem(new BiStore.Item(itemId, b.path("workspace").asText(), b.path("kind").asText(), b.path("title").asText(), b.path("description").asText(), b.path("owner").asText(),
                    strings(b.path("tags")), b.path("body").asText(), b.path("status").asText(), b.path("publishedVersion").asInt(0), b.path("endorsement").asText("none"),
                    b.path("endorsedBy").asText(), strings(b.path("audienceRoles")), strings(b.path("audienceUsers")), strings(b.path("audiencePacks")), b.path("proposedBy").asText(),
                    instant(b.path("proposedAt")), instant(b.path("createdAt")) == null ? now : instant(b.path("createdAt")), now, p.user(), instant(b.path("publishedAt")), b.path("publishedBy").asText()));
            BiStore.Revision rev = revise(store.item(itemId).orElseThrow(), b.path("body").asText(), "rolled back import " + id, p.user(), "rollback");
            store.addEvent(itemId, "rollback", p.user(), "import " + id + " rolled back", rev.seq(), now);
            restored++;
        }
        r.put("rolledBackAt", now.toString());
        r.put("rolledBackBy", p.user());
        log.save(r);
        audit.record(p.user(), "bi-import-rolled-back", id, restored + " item(s) restored, " + removed + " created item(s) removed" + (changed.isEmpty() ? "" : "; FORCED over " + changed.size() + " edited since"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("restored", restored);
        out.put("removed", removed);
        out.put("forced", !changed.isEmpty());
        return out;
    }


    private BiStore.Revision revise(BiStore.Item i, String body, String message, String actor, String origin) {
        return store.addRevision(i.id(), body, message, actor, Instant.now(), origin, 0, props.revisionsKeep());
    }
}
