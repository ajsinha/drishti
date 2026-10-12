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
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
import com.ash.drishti.server.governance.GovernanceProperties;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import com.ash.drishti.server.security.SecurityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Business intelligence, Rupaka phase 1 (docs/architecture/RUPAKA.md sections 3, 10, 11): workspaces (a personal one per user and shared
 * ones with the roles viewer, contributor, member and admin), items (typed placeholders: dataset, report, board or notebook, with a YAML
 * body that is stored and versioned but not run yet), the lifecycle draft, proposed, published (review before publish reuses the
 * Sutra approver power and four-eyes), endorsement, and the catalogue that shows each person only what its audience lets them see.
 * Every decision is made here, on the server, from the caller's identity; every change is audited.
 */
public final class BiService {

    public static final List<String> KINDS = List.of("dataset", "model", "report", "board", "notebook", "python");
    public static final List<String> ENDORSEMENTS = List.of("none", "promoted", "certified");
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,47}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._:/-]{0,39}");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String CREATION = "workspace-creation";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BiProperties props;
    private final BiStore store;
    private final BiFolder folder;
    private final BiAccess access;
    private final AuditLog audit;
    private final UserService users;
    private final RoleCatalog roles;
    private final PackAccess packs;
    private final Entitlements entitlements;
    private final GovernanceProperties governance;
    private final SecurityProperties security;
    private final BiGovProperties gov;
    private final ImpactAnalyzer impact;
    private final BiNotifier notifier;
    private final BiRenames renames;

    public BiService(BiProperties props, BiStore store, BiFolder folder, BiAccess access, AuditLog audit, UserService users, RoleCatalog roles,
            PackAccess packs, Entitlements entitlements, GovernanceProperties governance, SecurityProperties security, BiGovProperties gov, ImpactAnalyzer impact,
            BiNotifier notifier) {
        this.gov = gov;
        this.impact = impact;
        this.renames = new BiRenames(store, audit, props, gov);
        this.notifier = notifier;
        this.props = props;
        this.store = store;
        this.folder = folder;
        this.access = access;
        this.audit = audit;
        this.users = users;
        this.roles = roles;
        this.packs = packs;
        this.entitlements = entitlements;
        this.governance = governance;
        this.security = security;
    }

    // ===================================================================================================================
    // Who am I
    // ===================================================================================================================
    /** The caller's BI powers, personal workspace and whether they may create shared workspaces. */
    public Map<String, Object> me(Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean on = props.enabled();
        m.put("enabled", on);
        m.put("powers", on ? access.powers(p) : Set.of());
        m.put("user", p.user());
        m.put("roles", p.roles());
        m.put("personalWorkspace", personalId(p.user()));
        m.put("workspaceCreation", creationPolicy());
        m.put("mayCreateWorkspace", on && mayCreateWorkspace(p));
        m.put("kinds", KINDS);
        m.put("roles", BiAccess.ROLES);
        return m;
    }

    private void open(Principal p) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.BI_OFF, "business intelligence is switched off on this server (drishti.bi.enabled)");
        }
        access.require(p, BiAccess.BI);
    }

    // ===================================================================================================================
    // Settings
    // ===================================================================================================================
    public String creationPolicy() {
        return store.setting(CREATION).filter(v -> List.of("authors", "stewards", "admins").contains(v)).orElse(props.workspaceCreation());
    }

    public Map<String, Object> settings(Principal p) {
        open(p);
        access.require(p, BiAccess.ADMIN);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspaceCreation", creationPolicy());
        m.put("workspaceCreationOptions", List.of("authors", "stewards", "admins"));
        m.put("fourEyes", governance.fourEyes() && security.enabled());
        m.put("dir", props.dir());
        m.put("shippedItems", folder.items().size());
        m.put("watch", folder.watchState());
        m.put("folderProblems", folder.problems().stream().map(x -> Map.of("file", x.file(), "message", x.message(), "since", x.since(), "keptLastGood", x.keptLastGood())).toList());
        m.put("powers", com.ash.drishti.identity.RoleDefinition.BI_POWERS);
        m.put("roles", roles.all().stream().map(r -> Map.of("name", r.name(), "builtIn", r.builtIn(), "bi", new java.util.TreeSet<>(r.bi()))).toList());
        return m;
    }

    public Map<String, Object> setSettings(Principal p, Map<String, Object> body) {
        open(p);
        access.require(p, BiAccess.ADMIN);
        Object policy = body.get("workspaceCreation");
        if (policy != null) {
            if (!List.of("authors", "stewards", "admins").contains(String.valueOf(policy))) {
                throw new DrishtiException(ErrorCode.BI_INVALID, "workspaceCreation is authors, stewards or admins");
            }
            store.setSetting(CREATION, String.valueOf(policy));
            audit.record(p.user(), "bi-settings-changed", "workspace-creation", String.valueOf(policy));
        }
        return settings(p);
    }

    private boolean mayCreateWorkspace(Principal p) {
        return switch (creationPolicy()) {
            case "admins" -> access.has(p, BiAccess.ADMIN);
            case "stewards" -> access.has(p, BiAccess.STEWARD);
            default -> access.has(p, BiAccess.AUTHOR);
        };
    }

    // ===================================================================================================================
    // Workspaces
    // ===================================================================================================================
    /** The id of a user's personal workspace: stable and unique per exact user name. */
    public static String personalId(String user) {
        String clean = user.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String hash = String.format("%04x", user.hashCode() & 0xffff);
        return "u-" + (clean.length() > 30 ? clean.substring(0, 30) : clean) + "-" + hash;
    }

    BiStore.Workspace personal(Principal p) {
        String id = personalId(p.user());
        return store.workspace(id).orElseGet(() -> {
            BiStore.Workspace w = store.saveWorkspace(new BiStore.Workspace(id, "My workspace", "Drafts only you can see. Move an item to a shared workspace to publish it.",
                    true, p.user(), false, Instant.now(), p.user()));
            audit.record(p.user(), "bi-workspace-created", id, "personal");
            return w;
        });
    }

    BiStore.Workspace workspaceOf(String id) {
        if (BiFolder.WORKSPACE.equals(id)) {
            return new BiStore.Workspace(BiFolder.WORKSPACE, "Shipped content", "Read-only content shipped in the BI folder (its place on the server is under Admin, BI settings).", false, "", false, Instant.EPOCH, "");
        }
        return store.workspace(id).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "no workspace '" + id + "'"));
    }

    /** The caller's role in the workspace, or null: the owner of a personal one and a bi-admin are admins; the shipped one is viewable by all. */
    String roleIn(BiStore.Workspace w, Principal p) {
        if (BiFolder.WORKSPACE.equals(w.id())) {
            return "viewer";
        }
        if (w.personal()) {
            return w.owner().equals(p.user()) ? "admin" : null;
        }
        if (access.has(p, BiAccess.ADMIN)) {
            return "admin";
        }
        return store.members(w.id()).stream().filter(m -> m.username().equals(p.user())).map(BiStore.Member::role).findFirst().orElse(null);
    }

    String requireRole(BiStore.Workspace w, Principal p, String atLeast, String what) {
        String role = roleIn(w, p);
        if (BiAccess.rank(role) < 0) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no workspace '" + w.id() + "'");     // existence is not revealed to non-members
        }
        if (BiAccess.rank(role) < BiAccess.rank(atLeast)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "you are a " + role + " of " + w.name() + "; " + what + " needs " + atLeast);
        }
        return role;
    }

    private Map<String, Object> view(BiStore.Workspace w, String role, int items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("name", w.name());
        m.put("description", w.description());
        m.put("personal", w.personal());
        m.put("shipped", BiFolder.WORKSPACE.equals(w.id()));
        m.put("owner", w.owner());
        m.put("reviewRequired", w.reviewRequired());
        m.put("role", role);
        m.put("items", items);
        m.put("createdAt", w.createdAt());
        return m;
    }

    /** The workspaces the caller can see: their personal one, the ones they belong to (a bi-admin: all) and the shipped one. */
    public List<Map<String, Object>> workspaces(Principal p) {
        open(p);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Long> counts = store.items().stream().collect(Collectors.groupingBy(BiStore.Item::workspace, Collectors.counting()));
        BiStore.Workspace mine = personal(p);
        out.add(view(mine, "admin", counts.getOrDefault(mine.id(), 0L).intValue()));
        for (BiStore.Workspace w : store.workspaces()) {
            if (w.personal()) {
                continue;
            }
            String role = roleIn(w, p);
            if (role != null) {
                out.add(view(w, role, counts.getOrDefault(w.id(), 0L).intValue()));
            }
        }
        if (!folder.items().isEmpty()) {
            out.add(view(workspaceOf(BiFolder.WORKSPACE), "viewer", folder.items().size()));
        }
        return out;
    }

    public Map<String, Object> workspace(Principal p, String id) {
        open(p);
        BiStore.Workspace w = "me".equals(id) ? personal(p) : workspaceOf(id);
        String role = requireRole(w, p, "viewer", "opening it");
        Map<String, Object> m = view(w, role, (BiFolder.WORKSPACE.equals(w.id()) ? folder.items() : store.itemsIn(w.id())).size());
        if (!w.personal() && !BiFolder.WORKSPACE.equals(w.id())) {
            m.put("members", store.members(w.id()).stream().map(x -> Map.of("username", x.username(), "role", x.role(), "addedBy", x.addedBy(), "addedAt", x.addedAt())).toList());
        }
        return m;
    }

    public Map<String, Object> createWorkspace(Principal p, Map<String, Object> body) {
        open(p);
        if (!mayCreateWorkspace(p)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "creating shared workspaces needs the " + switch (creationPolicy()) {
                case "admins" -> BiAccess.ADMIN;
                case "stewards" -> BiAccess.STEWARD;
                default -> BiAccess.AUTHOR;
            } + " power (Admin, BI settings)");
        }
        String name = text(body, "name", 80, true);
        String id = body.get("id") == null ? slug(name) : String.valueOf(body.get("id"));
        if (!SLUG.matcher(id).matches() || BiFolder.WORKSPACE.equals(id) || id.startsWith("u-")) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "a workspace id is 2-48 of a-z, 0-9 and -, starting with a letter (and not 'shipped' or u-...)");
        }
        if (store.workspace(id).isPresent()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "a workspace '" + id + "' exists already");
        }
        BiStore.Workspace w = store.saveWorkspace(new BiStore.Workspace(id, name, text(body, "description", 2000, false), false, p.user(),
                Boolean.TRUE.equals(body.get("reviewRequired")), Instant.now(), p.user()));
        store.setMember(new BiStore.Member(id, p.user(), "admin", Instant.now(), p.user()));
        audit.record(p.user(), "bi-workspace-created", id, "name=" + name);
        return workspace(p, w.id());
    }

    public Map<String, Object> updateWorkspace(Principal p, String id, Map<String, Object> body) {
        open(p);
        BiStore.Workspace w = shared(id);
        requireRole(w, p, "admin", "changing its settings");
        BiStore.Workspace n = new BiStore.Workspace(w.id(), body.containsKey("name") ? text(body, "name", 80, true) : w.name(),
                body.containsKey("description") ? text(body, "description", 2000, false) : w.description(), false, w.owner(),
                body.containsKey("reviewRequired") ? Boolean.TRUE.equals(body.get("reviewRequired")) : w.reviewRequired(), w.createdAt(), w.createdBy());
        store.saveWorkspace(n);
        audit.record(p.user(), "bi-workspace-updated", id, "name=" + n.name() + " review=" + n.reviewRequired());
        return workspace(p, id);
    }

    public void deleteWorkspace(Principal p, String id) {
        open(p);
        BiStore.Workspace w = shared(id);
        requireRole(w, p, "admin", "deleting it");
        if (!store.itemsIn(id).isEmpty()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + w.name() + "' still holds items; move or delete them first");
        }
        store.deleteWorkspace(id);
        audit.record(p.user(), "bi-workspace-deleted", id, w.name());
    }

    private BiStore.Workspace shared(String id) {
        BiStore.Workspace w = workspaceOf(id);
        if (w.personal()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "a personal workspace is private: it has no members and cannot be changed this way");
        }
        if (BiFolder.WORKSPACE.equals(id)) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "the shipped workspace is read-only (it is the BI folder)");
        }
        return w;
    }

    public Map<String, Object> setMember(Principal p, String id, String username, String role) {
        open(p);
        BiStore.Workspace w = shared(id);
        requireRole(w, p, "admin", "sharing it");
        if (!BiAccess.ROLES.contains(role)) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "a workspace role is one of " + BiAccess.ROLES);
        }
        if (users.find(username).isEmpty()) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "no user '" + username + "'");
        }
        List<BiStore.Member> now = store.members(id);
        boolean demotesLast = !"admin".equals(role) && now.stream().filter(m -> m.role().equals("admin")).allMatch(m -> m.username().equals(username))
                && now.stream().anyMatch(m -> m.username().equals(username) && m.role().equals("admin"));
        if (demotesLast) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + username + "' is the last admin of " + w.name() + "; make someone else an admin first");
        }
        store.setMember(new BiStore.Member(id, username, role, Instant.now(), p.user()));
        audit.record(p.user(), "bi-member-set", id, username + "=" + role);
        return workspace(p, id);
    }

    public Map<String, Object> removeMember(Principal p, String id, String username) {
        open(p);
        BiStore.Workspace w = shared(id);
        requireRole(w, p, "admin", "sharing it");
        List<BiStore.Member> now = store.members(id);
        if (now.stream().filter(m -> m.role().equals("admin")).allMatch(m -> m.username().equals(username)) && now.stream().anyMatch(m -> m.username().equals(username))) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + username + "' is the last admin of " + w.name());
        }
        if (!store.removeMember(id, username)) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + username + "' is not a member of " + w.name());
        }
        audit.record(p.user(), "bi-member-removed", id, username);
        return workspace(p, id);
    }

    // ===================================================================================================================
    // Items
    // ===================================================================================================================
    private BiStore.Item itemOf(String id) {
        if (BiFolder.isShipped(id)) {
            return folder.item(id).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "no item '" + id + "' (a shipped file deleted from the BI folder is gone)"));
        }
        return store.item(id).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "no item '" + id + "'"));
    }

    /** Whether the caller may see the item in the catalogue (published, not personal) because of its audience, ownership or membership. */
    private boolean visible(BiStore.Item i, Principal p, Set<String> myWorkspaces, Set<String> myPacks, boolean admin) {
        if (!i.isPublished() || i.workspace().startsWith("u-") || hiddenSample(i, p)) {
            return false;
        }
        if (admin || i.owner().equals(p.user()) || myWorkspaces.contains(i.workspace())) {
            return true;
        }
        if (BiFolder.WORKSPACE.equals(i.workspace()) && i.audienceRoles().isEmpty() && i.audienceUsers().isEmpty() && i.audiencePacks().isEmpty()) {
            return true;
        }
        if (i.audienceUsers().contains(p.user())) {
            return true;
        }
        if (i.audienceRoles().stream().anyMatch(r -> p.roles().contains(r) || p.roles().contains("*"))) {
            return true;
        }
        return i.audiencePacks().stream().anyMatch(myPacks::contains);
    }

    private Set<String> myWorkspaces(Principal p) {
        return store.membershipsOf(p.user()).stream().map(BiStore.Member::workspace).collect(Collectors.toSet());
    }

    private Set<String> myPacks(Principal p) {
        try {
            return Set.copyOf(packs.assigned(p.user()));
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private Map<String, Object> view(BiStore.Item i, Principal p, Map<String, BiStore.Mark> marks, Map<String, Integer> views, boolean detail) {
        BiStore.Workspace w = workspaceOf(i.workspace());
        String role = roleIn(w, p);
        boolean shipped = BiFolder.isShipped(i.id());
        int rank = BiAccess.rank(role);
        BiStore.Mark mark = marks.get(i.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("workspace", i.workspace());
        m.put("workspaceName", w.name());
        m.put("personal", w.personal());
        m.put("workspaceReviewRequired", w.reviewRequired());
        m.put("kind", i.kind());
        m.put("title", i.title());
        m.put("description", i.description());
        m.put("owner", i.owner());
        m.put("tags", i.tags());
        boolean reviewing = !shipped && i.status().equals("proposed") && access.has(p, BiAccess.STEWARD);
        m.put("status", rank >= BiAccess.rank("contributor") || shipped || reviewing ? i.status() : "published");     // a viewer sees what is published, not drafts in progress
        m.put("publishedVersion", i.publishedVersion());
        m.put("endorsement", i.endorsement());
        m.put("endorsedBy", i.endorsedBy());
        m.put("audience", Map.of("roles", i.audienceRoles(), "users", i.audienceUsers(), "packs", i.audiencePacks()));
        m.put("publishedAt", i.publishedAt());
        m.put("freshness", i.publishedAt() == null ? null : i.publishedAt());
        m.put("createdAt", i.createdAt());
        m.put("updatedAt", i.updatedAt());
        m.put("etag", i.updatedAt() == null ? "" : i.updatedAt().toString());                 // the saved state a designer saved from; an update names it back (baseEtag)
        m.put("proposedBy", i.proposedBy());
        m.put("proposedAt", i.proposedAt());
        m.put("views", views.getOrDefault(i.id(), 0));
        m.put("viewsDays", props.usageDays());
        m.put("favourite", mark != null && mark.favourite());
        m.put("lastOpened", mark == null ? null : mark.lastOpened());
        m.put("shipped", shipped);
        m.put("role", role);
        boolean edit = !shipped && rank >= BiAccess.rank("contributor") && access.has(p, BiAccess.AUTHOR);
        boolean publish = edit && rank >= BiAccess.rank("member") && !w.personal() && !w.reviewRequired();
        boolean approve = !shipped && i.status().equals("proposed") && mayReview(p, w) && !(fourEyes() && i.proposedBy().equals(p.user()));
        Map<String, Object> can = new LinkedHashMap<>();
        can.put("edit", edit);
        can.put("propose", edit && !w.personal() && !i.status().equals("proposed") && !i.status().equals("published"));
        can.put("publish", publish && !i.status().equals("published") && !i.status().equals("proposed"));
        can.put("approve", approve);
        can.put("withdraw", !shipped && i.status().equals("proposed") && (i.proposedBy().equals(p.user()) || rank >= BiAccess.rank("admin")));
        can.put("promote", !shipped && i.isPublished() && (i.owner().equals(p.user()) || rank >= BiAccess.rank("member")));
        can.put("certify", !shipped && i.isPublished() && access.has(p, BiAccess.STEWARD));
        can.put("review", approve);
        can.put("force", access.has(p, BiAccess.ADMIN));
        can.put("delete", edit && (rank >= BiAccess.rank("member") || i.owner().equals(p.user())));
        can.put("move", edit && !i.status().equals("proposed"));
        m.put("can", can);
        if (detail) {
            boolean draft = !shipped && (rank >= BiAccess.rank("contributor") || reviewing);
            String body = draft || shipped ? i.body() : store.version(i.id(), i.publishedVersion()).map(BiStore.Version::body).orElse("");
            m.put("body", body);
            m.put("bodyIs", draft ? "draft" : "published");
            m.put("draftChanges", !shipped && i.isPublished() && !i.status().equals("published"));
            if (!shipped && i.status().equals("proposed")) {
                m.put("approvers", notifier.reviewers(i, i.proposedBy()).stream().sorted().toList());          // who can decide it (told when it was proposed)
                m.put("approveRule", "a bi-steward, or a member of " + w.name() + " whose role has the approve power" + (fourEyes() ? "; not " + i.proposedBy() + ", who proposed it (four-eyes)" : ""));
            }
        }
        return m;
    }

    /** A board is shown to everyone who may open it, so it may name only reports that are published: a draft report is its author's alone (BOARD_INVALID). */
    private void requirePublishedReports(BiStore.Item board, String verb) {
        if (!"board".equals(board.kind())) {
            return;
        }
        List<String> drafts = impact.draftReportsOf(board.body()).stream().map(r -> "'" + r.title() + "'").distinct().toList();
        if (!drafts.isEmpty()) {
            boolean one = drafts.size() == 1;
            throw new DrishtiException(ErrorCode.BOARD_INVALID, "'" + board.title() + "' cannot be " + verb + ": it shows " + (one ? "the report " : "the reports ")
                    + String.join(", ", drafts) + ", which " + (one ? "is" : "are") + " still " + (one ? "a draft" : "drafts")
                    + " that only its author can open. Publish " + (one ? "it" : "them") + " first, or choose a published report.");
        }
    }

    private boolean fourEyes() {
        return governance.fourEyes() && security.enabled();
    }

    public List<Map<String, Object>> items(Principal p, String workspaceId) {
        open(p);
        BiStore.Workspace w = "me".equals(workspaceId) ? personal(p) : workspaceOf(workspaceId);
        String role = requireRole(w, p, "viewer", "listing it");
        List<BiStore.Item> all = BiFolder.WORKSPACE.equals(w.id()) ? folder.items() : store.itemsIn(w.id());
        Map<String, BiStore.Mark> marks = store.marks(p.user());
        Map<String, Integer> views = store.views(props.usageDays(), Instant.now());
        boolean members = BiAccess.rank(role) >= BiAccess.rank("viewer") && !w.id().equals(BiFolder.WORKSPACE);
        return all.stream().filter(i -> !members || BiAccess.rank(role) >= BiAccess.rank("contributor") || i.isPublished())
                .sorted(Comparator.comparing(BiStore.Item::updatedAt).reversed()).limit(props.listLimit())
                .map(i -> view(i, p, marks, views, false)).toList();
    }

    public Map<String, Object> createItem(Principal p, String workspaceId, Map<String, Object> body) {
        open(p);
        access.require(p, BiAccess.AUTHOR);
        BiStore.Workspace w = "me".equals(workspaceId) ? personal(p) : shared0(workspaceId);
        requireRole(w, p, "contributor", "creating items");
        String kind = String.valueOf(body.getOrDefault("kind", ""));
        if (!KINDS.contains(kind)) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "kind is one of " + KINDS);
        }
        Instant now = Instant.now();
        String title = text(body, "title", 200, true);
        String content = com.ash.drishti.server.bi.layer.LayerBodies.onSave(kind, checkedBody(body.get("body")));
        if (BiTitles.KINDS.contains(kind) && BiTitles.of(content) != null && !title.equals(BiTitles.of(content))) {
            content = BiTitles.with(content, title);                                           // one title: the body carries the item's
        }
        BiStore.Item i = new BiStore.Item("bi-" + randomId(), w.id(), kind, title, text(body, "description", 2000, false), p.user(),
                tags(body.get("tags")), content, "draft", 0, "none", "", audienceList(body, "audienceRoles", true),
                audienceList(body, "audienceUsers", false), audienceList(body, "audiencePacks", false), "", null, now, now, p.user(), null, "");
        store.saveItem(i);
        revise(i, i.body(), text(body, "message", 500, false).isEmpty() ? "created" : text(body, "message", 500, false), p.user(), "create", 0);
        audit.record(p.user(), "bi-item-created", i.id(), kind + " '" + i.title() + "' in " + w.id());
        return item(p, i.id(), false);
    }

    private BiStore.Workspace shared0(String id) {
        return workspaceOf(id);
    }

    /** One item: members of its workspace see the draft too; everyone else the published version, if their audience allows it. */
    public Map<String, Object> item(Principal p, String id, boolean record) {
        open(p);
        BiStore.Item i = readable(p, id);
        if (record && i.isPublished()) {
            store.opened(p.user(), i.id(), Instant.now());
        }
        return view(i, p, store.marks(p.user()), store.views(props.usageDays(), Instant.now()), true);
    }

    /** Sample content (a shipped file with {@code sample: true}) is hidden from those who may not see sample packs. */
    private boolean hiddenSample(BiStore.Item i, Principal p) {
        return i.tags().contains(BiFolder.SAMPLE_TAG) && BiFolder.isShipped(i.id()) && !packs.samplesVisible(p.user());
    }

    /** The item for a caller who may read it (the dataset service's gate): the same rules as {@link #item}, without a view being counted. */
    public BiStore.Item readableItem(Principal p, String id) {
        open(p);
        return readable(p, id);
    }

    /** The caller must hold the BI power (BI itself is on, they hold {@code bi}, and the higher one when asked); DRS-9002 otherwise. */
    public void requirePower(Principal p, String power) {
        open(p);
        if (!BiAccess.BI.equals(power)) {
            access.require(p, power);
        }
    }

    /** True when the caller holds the power (a bi-admin holds them all); no failure when not. */
    public boolean hasPower(Principal p, String power) {
        return access.has(p, power);
    }

    /** True when the caller holds the {@code bi-author} power (no failure when not). */
    public boolean mayAuthor(Principal p) {
        return access.has(p, BiAccess.AUTHOR);
    }

    /** The body of an item as this caller reads it: the working draft for those who may edit, else the published version. */
    public String bodyFor(Principal p, BiStore.Item i) {
        boolean shipped = BiFolder.isShipped(i.id());
        boolean draft = !shipped && BiAccess.rank(roleIn(workspaceOf(i.workspace()), p)) >= BiAccess.rank("contributor") && !BiReaderView.published();
        return draft || shipped ? i.body() : publishedBody(i);
    }

    /**
     * The published version of an item as its readers get it, for an author who is looking at a draft ("View as readers see it"): the body, the version and when
     * it was published. The data an author then draws from it is masked for the author, who stands in for the reader. DRS-9001 when it was never published.
     */
    public Map<String, Object> publishedView(Principal p, String id) {
        open(p);
        BiStore.Item i = readable(p, id);
        if (!i.isPublished() && !BiFolder.isShipped(i.id())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + i.title() + "' has not been published, so readers see nothing of it yet");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("kind", i.kind());
        m.put("title", i.title());
        m.put("version", BiFolder.isShipped(i.id()) ? 1 : i.publishedVersion());
        m.put("publishedAt", i.publishedAt());
        m.put("body", publishedBody(i));
        m.put("draftDiffers", !publishedBody(i).equals(i.body()));
        return m;
    }

    /** The body everyone with access reads: the shipped file, or the published version. */
    public String publishedBody(BiStore.Item i) {
        return BiFolder.isShipped(i.id()) ? i.body() : store.version(i.id(), i.publishedVersion()).map(BiStore.Version::body).orElse("");
    }

    /** The published dataset items the caller may see. */
    public List<BiStore.Item> datasetItems(Principal p) {
        open(p);
        return catalogueItems(p).stream().filter(i -> "dataset".equals(i.kind())).toList();
    }

    /** The caller's own unpublished datasets (in their private workspace, or in a workspace they belong to): listed, greyed with the reason, never usable by a report until published. */
    public List<BiStore.Item> draftDatasets(Principal p) {
        open(p);
        Set<String> mine = myWorkspaces(p);
        return store.items().stream().filter(i -> "dataset".equals(i.kind()) && !i.isPublished() && (i.owner().equals(p.user()) || mine.contains(i.workspace()))).toList();
    }

    /** The published report items the caller may see (what a board's tile can name). */
    public List<BiStore.Item> reportItems(Principal p) {
        open(p);
        return catalogueItems(p).stream().filter(i -> "report".equals(i.kind())).toList();
    }

    /**
     * The reports a board can name for this caller: the published ones they may see, then their own unpublished ones (they own them, or belong to the workspace),
     * which only they and their workspace's members can open until they are published.
     */
    public List<BiStore.Item> reportItemsWithDrafts(Principal p) {
        List<BiStore.Item> out = new ArrayList<>(reportItems(p));
        Set<String> mine = myWorkspaces(p);
        store.items().stream().filter(i -> "report".equals(i.kind()) && !i.isPublished() && (i.owner().equals(p.user()) || mine.contains(i.workspace()))).forEach(out::add);
        return out;
    }

    /** Every published dataset, whoever may read it (no caller): what a data-load signal concerns. */
    public List<BiStore.Item> allDatasets() {
        List<BiStore.Item> all = new ArrayList<>(store.published());
        all.addAll(folder.items());
        return all.stream().filter(i -> "dataset".equals(i.kind())).toList();
    }

    /** The dataset item a source of a composed dataset names (catalogue id, file name, the dataset's name or title): published before draft; empty when there is none. */
    public java.util.Optional<BiStore.Item> datasetNamed(String ref) {
        return impact.datasetNamed(ref);
    }

    /** The text a composed or derived dataset's source is read from: what is published, else (never published) the draft. */
    public String sourceBody(BiStore.Item i) {
        String published = impact.publishedBody(i);
        return published != null ? published : i.body();
    }

    /** Every published Python item, whoever may read it (no caller): what a report's python visual resolves its item among. */
    public List<BiStore.Item> allPythons() {
        List<BiStore.Item> all = new ArrayList<>(store.published());
        all.addAll(folder.items());
        return all.stream().filter(i -> "python".equals(i.kind())).toList();
    }

    private volatile java.util.function.Function<String, String> pythonCheck;
    private volatile java.util.function.BiFunction<Principal, BiStore.Item, List<String>> testGate;
    private volatile java.util.function.BiFunction<Principal, BiStore.Item, List<String>> drawCheck;

    /** Registers the check that lists why a report cannot be drawn (its dataset, a field it names); propose, approve and publish refuse it unless a bi-admin forces. */
    public void drawCheck(java.util.function.BiFunction<Principal, BiStore.Item, List<String>> check) {
        this.drawCheck = check;
    }

    private List<String> undrawable(Principal p, BiStore.Item i, boolean force, String what) {
        if (drawCheck == null || !"report".equals(i.kind()) || i.body().isBlank()) {
            return List.of();
        }
        List<String> errors = drawCheck.apply(p, i);
        if (!errors.isEmpty()) {
            if (!force) {
                throw new DrishtiException(ErrorCode.REPORT_INVALID, "'" + i.title() + "' cannot be " + what + ": it cannot be drawn (" + errors.size() + " error" + (errors.size() == 1 ? "" : "s") + "): "
                        + String.join("; ", errors.stream().limit(5).toList()) + (errors.size() > 5 ? "; ..." : "") + ". Fix them in the designer (Check)"
                        + (access.has(p, BiAccess.ADMIN) ? ", or go on anyway with force." : "; a bi-admin may force it."));
            }
            access.require(p, BiAccess.ADMIN);
        }
        return errors;
    }

    /** Set once by the expected-values runner: what publishing a report or dataset runs its tests with (a line per test that fails or cannot run; empty when all pass). */
    public void testGate(java.util.function.BiFunction<Principal, BiStore.Item, List<String>> gate) {
        this.testGate = gate;
    }

    private volatile java.util.function.BiFunction<Principal, BiStore.Item, String> layerCheck;

    /**
     * Set once by the model catalogue: what publishing a data model or a {@code rachana: 2} dataset runs (Check, the contract's versioning, the security
     * safeguard). It throws the refusal; it answers the {@code may-see-all} notes the publish relies on, which are audited (empty when none).
     */
    public void layerCheck(java.util.function.BiFunction<Principal, BiStore.Item, String> check) {
        this.layerCheck = check;
    }

    /** Every published item of a kind, stored and shipped, whoever may read it (the model catalogue resolves names among them). */
    public List<BiStore.Item> publishedOfKind(String kind) {
        List<BiStore.Item> all = new ArrayList<>(store.published());
        all.addAll(folder.items());
        return all.stream().filter(i -> kind.equals(i.kind())).toList();
    }

    /** The caller's own unpublished items of a kind (they own them or belong to the workspace). */
    public List<BiStore.Item> draftsOfKind(Principal p, String kind) {
        Set<String> mine = myWorkspaces(p);
        return store.items().stream().filter(i -> kind.equals(i.kind()) && !i.isPublished() && (i.owner().equals(p.user()) || mine.contains(i.workspace()))).toList();
    }

    /** Set once by the Python items service: what publishing a Python item checks its manifest with (the first error, or an empty text). */
    public void pythonCheck(java.util.function.Function<String, String> check) {
        this.pythonCheck = check;
    }

    /** Every item, stored (drafts and published) and shipped, whoever may read it: what the model catalogue finds a model's consumers among. */
    public List<BiStore.Item> allItems() {
        List<BiStore.Item> all = new ArrayList<>(store.items());
        all.addAll(folder.items());
        return all;
    }

    /** Set once by the model catalogue: why an item may not be deleted (a model that consumers still read), or an empty text. */
    private volatile java.util.function.Function<BiStore.Item, String> deleteGuard;

    public void deleteGuard(java.util.function.Function<BiStore.Item, String> guard) {
        this.deleteGuard = guard;
    }

    /** Every published board, whoever may read it (no caller): what board notifications are derived from. */
    public List<BiStore.Item> allBoards() {
        List<BiStore.Item> all = new ArrayList<>(store.published());
        all.addAll(folder.items());
        return all.stream().filter(i -> "board".equals(i.kind())).toList();
    }

    /** Whether the person has the BI power and may read the item (a board's audience, for notifications). */
    public boolean mayRead(Principal p, BiStore.Item i) {
        return access.has(p, BiAccess.BI) && canRead(p, i);
    }

    BiStore.Item readable(Principal p, String id) {
        BiStore.Item i = itemOf(id);
        if (hiddenSample(i, p)) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no item '" + id + "'");
        }
        BiStore.Workspace w = workspaceOf(i.workspace());
        int rank = BiAccess.rank(roleIn(w, p));
        if (rank >= 0 && !BiFolder.WORKSPACE.equals(w.id()) && (i.isPublished() || rank >= BiAccess.rank("contributor"))) {
            return i;                                                    // a viewer reads what is published, not drafts in progress
        }
        if (visible(i, p, myWorkspaces(p), myPacks(p), access.has(p, BiAccess.ADMIN))) {
            return i;
        }
        if (!BiFolder.isShipped(i.id()) && !w.personal() && i.status().equals("proposed") && access.has(p, BiAccess.STEWARD)) {
            return i;                                                    // a steward reviews what is proposed, wherever it is (what is published stays behind its audience)
        }
        throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no item '" + id + "'");
    }

    /** The item for a caller who may read it, after the BI gate (enabled, the bi power): the governance service's door. */
    BiStore.Item readableForGovernance(Principal p, String id) {
        open(p);
        return readable(p, id);
    }

    /** Whether the caller may see the item (used to keep names out of a list shown to someone who could not open them). */
    boolean canRead(Principal p, BiStore.Item i) {
        try {
            readable(p, i.id());
            return true;
        } catch (DrishtiException e) {
            return false;
        }
    }

    BiStore.Item editable(Principal p, String id, String what) {
        open(p);
        access.require(p, BiAccess.AUTHOR);
        BiStore.Item i = readable(p, id);
        if (BiFolder.isShipped(id)) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "shipped content is read-only: change the file in the BI folder");
        }
        requireRole(workspaceOf(i.workspace()), p, "contributor", what);
        return i;
    }

    public Map<String, Object> updateItem(Principal p, String id, Map<String, Object> body) {
        BiStore.Item i = editable(p, id, "editing items");
        if (i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' is proposed for review; withdraw it to edit");
        }
        Object base = body.get("baseEtag");
        if (base != null && !String.valueOf(base).isBlank() && !String.valueOf(base).equals(i.updatedAt() == null ? "" : i.updatedAt().toString())) {
            throw new DrishtiException(ErrorCode.BI_STALE, "'" + i.title() + "' was saved by " + i.updatedBy() + " since you opened it: reload it, save yours as a copy, or overwrite");
        }
        String newBody = body.containsKey("body") ? com.ash.drishti.server.bi.layer.LayerBodies.onSave(i.kind(), checkedBody(body.get("body"))) : i.body();
        String newTitle = body.containsKey("title") ? text(body, "title", 200, true) : i.title();
        if (BiTitles.KINDS.contains(i.kind())) {                                       // one title: the item's and the body's agree after every save
            String inBody = BiTitles.of(newBody);
            if (!newTitle.equals(i.title())) {
                newBody = BiTitles.with(newBody, newTitle);                              // renamed on the item: the body follows
            } else if (inBody != null && !inBody.isBlank() && !inBody.equals(BiTitles.of(i.body())) && inBody.length() <= 200) {
                newTitle = inBody;                                                       // renamed in the body: the item follows
            }
        }
        boolean bodyChanged = !newBody.equals(i.body());
        boolean catalogueOnly = !bodyChanged;
        BiStore.Item n = new BiStore.Item(i.id(), i.workspace(), i.kind(), newTitle,
                body.containsKey("description") ? text(body, "description", 2000, false) : i.description(), i.owner(),
                body.containsKey("tags") ? tags(body.get("tags")) : i.tags(), newBody, bodyChanged && i.isPublished() ? "draft" : i.status(), i.publishedVersion(),
                i.endorsement(), i.endorsedBy(), body.containsKey("audienceRoles") ? audienceList(body, "audienceRoles", true) : i.audienceRoles(),
                body.containsKey("audienceUsers") ? audienceList(body, "audienceUsers", false) : i.audienceUsers(),
                body.containsKey("audiencePacks") ? audienceList(body, "audiencePacks", false) : i.audiencePacks(), i.proposedBy(), i.proposedAt(),
                i.createdAt(), Instant.now(), p.user(), i.publishedAt(), i.publishedBy());
        store.saveItem(n);
        if (bodyChanged) {
            revise(n, newBody, text(body, "message", 500, false), p.user(), "save", 0);
        }
        audit.record(p.user(), "bi-item-updated", id, (bodyChanged ? "body " : "") + (catalogueOnly ? "catalogue fields" : ""));
        return item(p, id, false);
    }

    public void deleteItem(Principal p, String id) {
        BiStore.Item i = editable(p, id, "deleting items");
        BiStore.Workspace w = workspaceOf(i.workspace());
        if (!i.owner().equals(p.user())) {
            requireRole(w, p, "member", "deleting someone else's item");
        }
        java.util.function.Function<BiStore.Item, String> guard = deleteGuard;
        String held = guard == null ? "" : guard.apply(i);                // a model is referenced, never owned: it stays while consumers read it
        if (!held.isEmpty()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, held);
        }
        store.deleteItem(id);
        audit.record(p.user(), "bi-item-deleted", id, i.kind() + " '" + i.title() + "'");
    }

    public Map<String, Object> moveItem(Principal p, String id, String target) {
        BiStore.Item i = editable(p, id, "moving items");
        if (i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "withdraw the proposal before moving '" + i.title() + "'");
        }
        BiStore.Workspace to = "me".equals(target) ? personal(p) : shared0(target);
        if (BiFolder.WORKSPACE.equals(to.id())) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "the shipped workspace is read-only");
        }
        requireRole(to, p, "contributor", "adding items");
        BiStore.Item n = new BiStore.Item(i.id(), to.id(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), i.body(), i.status(), i.publishedVersion(),
                i.endorsement(), i.endorsedBy(), i.audienceRoles(), i.audienceUsers(), i.audiencePacks(), i.proposedBy(), i.proposedAt(), i.createdAt(),
                Instant.now(), p.user(), i.publishedAt(), i.publishedBy());
        store.saveItem(n);
        audit.record(p.user(), "bi-item-moved", id, i.workspace() + " -> " + to.id());
        return item(p, id, false);
    }

    // -- lifecycle: draft, proposed, published -------------------------------------------------------------------------
    public Map<String, Object> propose(Principal p, String id, String note) {
        return propose(p, id, note, false);
    }

    /** One proposal at a time per item: a double click (two requests at once) proposes once and tells the reviewers once. */
    private final Map<String, java.util.concurrent.locks.ReentrantLock> proposing = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, Object> propose(Principal p, String id, String note, boolean force) {
        java.util.concurrent.locks.ReentrantLock lock = proposing.computeIfAbsent(id, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            return proposeLocked(p, id, note, force);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> proposeLocked(Principal p, String id, String note, boolean force) {
        BiStore.Item i = editable(p, id, "proposing");
        BiStore.Workspace w = workspaceOf(i.workspace());
        if (w.personal()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "move the item to a shared workspace before proposing it");
        }
        if (i.status().equals("proposed")) {
            if (i.proposedBy().equals(p.user())) {
                return item(p, id, false);                                    // idempotent: the same person asking again changes nothing and tells nobody again
            }
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' is proposed already");
        }
        if (i.status().equals("published")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' has no changes since version " + i.publishedVersion());
        }
        requirePublishedReports(i, "proposed");
        List<String> forced = undrawable(p, i, force, "proposed");
        store.saveItem(withStatus(i, "proposed", p.user(), Instant.now(), p.user()));
        store.addEvent(id, "request", p.user(), note(note), latestRevision(id), Instant.now());
        notifier.tell(notifier.reviewers(i, p.user()), BiNotifier.REVIEW, id, p.user() + " asks for review of " + i.kind() + " '" + i.title() + "'", p.user());
        audit.record(p.user(), "bi-item-proposed", id, note(note) + (forced.isEmpty() ? "" : "; FORCED over " + forced.size() + " drawing error(s)"));
        return item(p, id, false);
    }

    public Map<String, Object> withdraw(Principal p, String id) {
        BiStore.Item i = editable(p, id, "withdrawing");
        if (!i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' is not proposed");
        }
        if (!i.proposedBy().equals(p.user())) {
            requireRole(workspaceOf(i.workspace()), p, "admin", "withdrawing someone else's proposal");
        }
        store.saveItem(withStatus(i, "draft", "", null, p.user()));
        store.addEvent(id, "withdraw", p.user(), "", latestRevision(id), Instant.now());
        audit.record(p.user(), "bi-item-withdrawn", id, "");
        return item(p, id, false);
    }

    /** Whether the caller may decide a review of an item in this workspace: a bi-steward anywhere, or a workspace member holding the approve power. */
    private boolean mayReview(Principal p, BiStore.Workspace w) {
        return access.has(p, BiAccess.STEWARD) || BiAccess.rank(roleIn(w, p)) >= BiAccess.rank("member") && entitlements.mayApprove(p);
    }

    public Map<String, Object> approve(Principal p, String id, String note) {
        return approve(p, id, note, false);
    }

    public Map<String, Object> approve(Principal p, String id, String note, boolean force) {
        return approve(p, id, note, force, false);
    }

    public Map<String, Object> approve(Principal p, String id, String note, boolean force, boolean applyRenames) {
        open(p);
        BiStore.Item i = readable(p, id);
        BiStore.Workspace w = workspaceOf(i.workspace());
        if (BiFolder.isShipped(id) || !i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' is not waiting for review");
        }
        if (!mayReview(p, w)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "approving needs the bi-steward power, or a member of the workspace with the approve power on a role");
        }
        if (fourEyes() && i.proposedBy().equals(p.user())) {
            throw new DrishtiException(ErrorCode.BI_FOUR_EYES, "four-eyes: " + p.user() + " proposed this and may not approve it");
        }
        Map<String, Object> out = publishNow(p, i, "approved: " + note(note), force, applyRenames);
        store.addEvent(id, "approve", p.user(), note(note), latestRevision(id), Instant.now());
        notifier.tell(List.of(i.proposedBy()), BiNotifier.DECISION, id, p.user() + " approved " + i.kind() + " '" + i.title() + "'" + (note(note).isBlank() ? "" : ": " + note(note)), p.user());
        audit.record(p.user(), "bi-item-approved", id, "proposed by " + i.proposedBy() + "; " + note(note));
        return out;
    }

    public Map<String, Object> reject(Principal p, String id, String reason) {
        open(p);
        BiStore.Item i = readable(p, id);
        if (BiFolder.isShipped(id) || !i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' is not waiting for review");
        }
        if (!mayReview(p, workspaceOf(i.workspace()))) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "rejecting needs the bi-steward power, or a member of the workspace with the approve power on a role");
        }
        if (fourEyes() && i.proposedBy().equals(p.user())) {
            throw new DrishtiException(ErrorCode.BI_FOUR_EYES, "four-eyes: " + p.user() + " proposed this; withdraw it instead of rejecting it");
        }
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "say why it is rejected");
        }
        store.saveItem(withStatus(i, "draft", "", null, p.user()));
        store.addEvent(id, "reject", p.user(), note(reason), latestRevision(id), Instant.now());
        notifier.tell(List.of(i.proposedBy()), BiNotifier.DECISION, id, p.user() + " rejected " + i.kind() + " '" + i.title() + "': " + note(reason), p.user());
        audit.record(p.user(), "bi-item-rejected", id, "proposed by " + i.proposedBy() + "; " + note(reason));
        return viewOrStub(p, id);
    }

    public Map<String, Object> publish(Principal p, String id, String note) {
        return publish(p, id, note, false);
    }

    public Map<String, Object> publish(Principal p, String id, String note, boolean force) {
        return publish(p, id, note, force, false);
    }

    /** @param applyRenames when the dataset renames a field or measure the same way (same path or expression), rewrite the reports that use the old name in the same act */
    public Map<String, Object> publish(Principal p, String id, String note, boolean force, boolean applyRenames) {
        BiStore.Item i = editable(p, id, "publishing");
        BiStore.Workspace w = workspaceOf(i.workspace());
        if (w.personal()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "personal drafts are private: move the item to a shared workspace to publish it");
        }
        requireRole(w, p, "member", "publishing");
        if (w.reviewRequired()) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, w.name() + " requires review before publishing: propose the item and let a second person approve it");
        }
        if (i.status().equals("published")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' has no changes since version " + i.publishedVersion());
        }
        if (i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "'" + i.title() + "' is proposed by " + i.proposedBy() + ": it must be approved, rejected or withdrawn before it can be published");
        }
        return publishNow(p, i, note(note), force, applyRenames);
    }

    private Map<String, Object> publishNow(Principal p, BiStore.Item i, String note, boolean force, boolean applyRenames) {
        if (layerCheck != null && ("model".equals(i.kind()) || "dataset".equals(i.kind()) && com.ash.drishti.server.bi.layer.DatasetDefParser.isVersion2(i.body()))) {
            String noted = layerCheck.apply(p, i);                                  // the model layer's checks, contract and security safeguard: refuses with its code
            if (!noted.isEmpty()) {
                audit.record(p.user(), "bi-model-may-see-all", i.id(), noted);
            }
        } else if ("dataset".equals(i.kind()) && !i.body().isBlank()) {
            com.ash.drishti.server.bi.dataset.DatasetParser.Result r = com.ash.drishti.server.bi.dataset.DatasetParser.parse(i.body());
            if (!r.ok()) {
                throw new DrishtiException(ErrorCode.DATASET_INVALID, "'" + i.title() + "' cannot be published: " + r.firstError() + " (check it on its dataset page)");
            }
        }
        if ("report".equals(i.kind()) && !i.body().isBlank()) {
            com.ash.drishti.server.bi.report.ReportParser.Result r = com.ash.drishti.server.bi.report.ReportParser.parse(i.body());
            if (!r.ok()) {
                throw new DrishtiException(ErrorCode.REPORT_INVALID, "'" + i.title() + "' cannot be published: " + r.firstError() + " (check it in the designer)");
            }
        }
        if ("python".equals(i.kind()) && !i.body().isBlank() && pythonCheck != null) {
            String why = pythonCheck.apply(i.body());
            if (!why.isEmpty()) {
                throw new DrishtiException(ErrorCode.PYTHON_INVALID, "'" + i.title() + "' cannot be published: " + why + " (check it on its item page)");
            }
        }
        if ("board".equals(i.kind()) && !i.body().isBlank()) {
            com.ash.drishti.server.bi.board.BoardParser.Result r = com.ash.drishti.server.bi.board.BoardParser.parse(i.body());
            if (!r.ok()) {
                throw new DrishtiException(ErrorCode.BOARD_INVALID, "'" + i.title() + "' cannot be published: " + r.firstError());
            }
            requirePublishedReports(i, "published");
        }
        List<String> undrawn = undrawable(p, i, force, "published");
        // the expected values the item declares (docs/guides/REPORTS.md section 14): a failing one stops a publish unless a bi-admin forces it
        List<String> failing = List.of();
        if (testGate != null && gov.refuseFailingTests() && ("report".equals(i.kind()) || "dataset".equals(i.kind())) && i.body().contains("tests:")) {
            failing = testGate.apply(p, i);
            if (!failing.isEmpty()) {
                if (!force) {
                    throw new DrishtiException(ErrorCode.BI_TESTS, "'" + i.title() + "' cannot be published: " + failing.size() + " of its tests " + (failing.size() == 1 ? "fails" : "fail") + " ("
                            + String.join("; ", failing.stream().limit(3).toList()) + (failing.size() > 3 ? "; ..." : "") + "). Fix the item or the expected value"
                            + (access.has(p, BiAccess.ADMIN) ? ", or publish anyway with force." : "; a bi-admin may publish it with force."));
                }
                access.require(p, BiAccess.ADMIN);
            }
        }
        // what this publish does to the reports that read a dataset (docs/guides/BI_GOVERNANCE.md, Impact)
        ImpactAnalyzer.Result damage = null;
        ImpactAnalyzer.Result renaming = null;
        if ("dataset".equals(i.kind()) && i.isPublished()) {
            damage = impact.impact(i, impact.publishedBody(i), i.body());
            if (applyRenames && !damage.change().renames().isEmpty()) {                       // the renames are applied to the reports after the checks below pass
                renaming = damage;
                damage = withoutRenamed(damage);
            }
            if (damage.change().breaking() && damage.breaksPublished() && gov.refuseBreaking()) {
                if (!force) {
                    throw new DrishtiException(ErrorCode.BI_IMPACT, impactMessage(p, i, damage) + (access.has(p, BiAccess.ADMIN)
                            ? " A bi-admin may publish anyway with force." : " Ask a bi-admin to publish it with force, or keep the names the reports use."));
                }
                access.require(p, BiAccess.ADMIN);
            }
        }
        int version = i.publishedVersion() + 1;
        Instant now = Instant.now();
        boolean revoke = gov.revokeCertification() && damage != null && damage.change().breaking();
        boolean ownRevoked = revoke && i.endorsement().equals("certified");
        List<String> renamed = renaming == null ? List.of() : renames.apply(p, impact, renaming, version, i.title());
        store.addVersion(new BiStore.Version(i.id(), version, i.body(), note, p.user(), now), props.versionsKeep());
        store.saveItem(new BiStore.Item(i.id(), i.workspace(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), i.body(), "published", version,
                ownRevoked ? "none" : i.endorsement(), ownRevoked ? "" : i.endorsedBy(), i.audienceRoles(), i.audienceUsers(), i.audiencePacks(), "", null, i.createdAt(), now,
                p.user(), now, p.user()));
        BiStore.Revision last = store.revisions(i.id()).stream().findFirst().orElse(null);
        if (last != null && last.body().equals(i.body())) {
            store.markRevisionPublished(i.id(), last.seq(), version);
        } else {
            revise(i, i.body(), note, p.user(), "publish", version);
        }
        audit.record(p.user(), "bi-item-published", i.id(), "version " + version + "; " + note
                + (failing.isEmpty() ? "" : "; FORCED over " + failing.size() + " failing test(s)")
                + (undrawn.isEmpty() ? "" : "; FORCED over " + undrawn.size() + " drawing error(s)")
                + (damage != null && damage.breaksPublished() ? "; FORCED over " + damage.published().size() + " published report(s)" : ""));
        List<String> warnings = new ArrayList<>(renamed);
        if (damage != null && damage.change().breaking()) {
            warnings.add("This version removes or renames " + String.join(", ", damage.change().removed()) + ".");
            damage.published().forEach(h -> warnings.add("Published report '" + h.report().title() + "' uses " + String.join("; ", h.uses()) + " and is broken."));
        }
        if (ownRevoked) {
            revoked(p, i, "the dataset changed incompatibly", warnings);
        }
        if (revoke) {
            for (ImpactAnalyzer.Hit h : damage.published()) {
                BiStore.Item r = h.report();
                if (r.endorsement().equals("certified")) {
                    if (BiFolder.isShipped(r.id())) {
                        warnings.add("Shipped report '" + r.title() + "' is certified in its file and stays so; change the file.");
                    } else {
                        revoked(p, r, "its dataset '" + i.title() + "' changed incompatibly", warnings);
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(viewOrStub(p, i.id()));
        out.put("warnings", warnings);
        return out;
    }

    /** The damage left once the reports that only lose renamed names are counted as repaired. */
    private ImpactAnalyzer.Result withoutRenamed(ImpactAnalyzer.Result res) {
        List<BiStore.Item> fixed = renames.renamable(res);
        List<ImpactAnalyzer.Hit> published = res.published().stream().filter(h -> fixed.stream().noneMatch(f -> f.id().equals(h.report().id()))).toList();
        List<ImpactAnalyzer.Hit> drafts = res.drafts().stream().filter(h -> fixed.stream().noneMatch(f -> f.id().equals(h.report().id()))).toList();
        return new ImpactAnalyzer.Result(res.change(), published, drafts, res.altered());
    }

    /** The item as the caller sees it now; a reviewer who may not open it afterwards (a draft sent back, a publication outside their audience) gets its id and status only. */
    private Map<String, Object> viewOrStub(Principal p, String id) {
        try {
            return item(p, id, false);
        } catch (DrishtiException e) {
            BiStore.Item x = store.item(id).orElseThrow(() -> e);
            return new LinkedHashMap<>(Map.of("id", id, "status", x.status(), "title", x.title(), "publishedVersion", x.publishedVersion()));
        }
    }

    /** The refusal text of a publish that would break published reports: the reports the caller may see by name, the rest counted. */
    private String impactMessage(Principal p, BiStore.Item dataset, ImpactAnalyzer.Result res) {
        List<String> seen = new ArrayList<>();
        int hidden = 0;
        for (ImpactAnalyzer.Hit h : res.published()) {
            if (canRead(p, h.report())) {
                seen.add("'" + h.report().title() + "' (" + String.join("; ", h.uses().subList(0, Math.min(3, h.uses().size()))) + (h.uses().size() > 3 ? "; and " + (h.uses().size() - 3) + " more" : "") + ")");
            } else {
                hidden++;
            }
        }
        return "publishing '" + dataset.title() + "' would break " + res.published().size() + " published report" + (res.published().size() == 1 ? "" : "s") + " by removing or renaming "
                + String.join(", ", res.change().removed()) + ": " + String.join(", ", seen) + (hidden > 0 ? (seen.isEmpty() ? "" : "; and ") + hidden + " you cannot see" : "") + ".";
    }

    /** Takes certification away from an item, records it, and tells its owner and whoever certified it. */
    private void revoked(Principal p, BiStore.Item x, String why, List<String> warnings) {
        BiStore.Item now = store.item(x.id()).orElse(x);
        store.saveItem(new BiStore.Item(now.id(), now.workspace(), now.kind(), now.title(), now.description(), now.owner(), now.tags(), now.body(), now.status(),
                now.publishedVersion(), "none", "", now.audienceRoles(), now.audienceUsers(), now.audiencePacks(), now.proposedBy(), now.proposedAt(), now.createdAt(),
                now.updatedAt(), now.updatedBy(), now.publishedAt(), now.publishedBy()));
        store.addEvent(x.id(), "revoke", p.user(), why, latestRevision(x.id()), Instant.now());
        audit.record(p.user(), "bi-certification-revoked", x.id(), why);
        java.util.Set<String> to = new java.util.LinkedHashSet<>();
        to.add(x.owner());
        if (!x.endorsedBy().isBlank()) {
            to.add(x.endorsedBy());
        }
        to.remove(p.user());
        notifier.tell(to, BiNotifier.CERTIFICATION, x.id(), "Certification of " + x.kind() + " '" + x.title() + "' was revoked: " + why, p.user());
        warnings.add("Certification of '" + x.title() + "' was revoked: " + why + ". A steward certifies it again after checking it.");
    }

    private BiStore.Revision revise(BiStore.Item i, String body, String message, String actor, String origin, int version) {
        return store.addRevision(i.id(), body, message, actor, Instant.now(), origin, version, gov.revisionsKeep());
    }

    int latestRevision(String id) {
        return store.revisions(id).stream().findFirst().map(BiStore.Revision::seq).orElse(0);
    }

    private BiStore.Item withStatus(BiStore.Item i, String status, String proposedBy, Instant proposedAt, String actor) {
        return new BiStore.Item(i.id(), i.workspace(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), i.body(), status, i.publishedVersion(),
                i.endorsement(), i.endorsedBy(), i.audienceRoles(), i.audienceUsers(), i.audiencePacks(), proposedBy, proposedAt, i.createdAt(), Instant.now(), actor,
                i.publishedAt(), i.publishedBy());
    }

    // -- versions -----------------------------------------------------------------------------------------------------
    public List<Map<String, Object>> versions(Principal p, String id) {
        open(p);
        BiStore.Item i = readable(p, id);
        if (BiFolder.isShipped(id)) {
            return List.of(Map.of("version", i.publishedVersion(), "note", "the file in the BI folder (version " + i.publishedVersion() + " since the server started)", "createdBy", "", "createdAt", i.updatedAt(), "current", true));
        }
        return store.versions(id).stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v.version());
            m.put("note", v.note());
            m.put("createdBy", v.createdBy());
            m.put("createdAt", v.createdAt());
            m.put("current", v.version() == i.publishedVersion());
            return m;
        }).toList();
    }

    public Map<String, Object> version(Principal p, String id, int n) {
        open(p);
        BiStore.Item i = readable(p, id);
        if (BiFolder.isShipped(id)) {
            if (n != i.publishedVersion()) {
                throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "shipped content keeps only its current version (" + i.publishedVersion() + ")");
            }
            return Map.of("version", n, "body", i.body());
        }
        BiStore.Version v = store.version(id, n).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + i.title() + "' has no version " + n));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.version());
        m.put("note", v.note());
        m.put("createdBy", v.createdBy());
        m.put("createdAt", v.createdAt());
        m.put("body", v.body());
        return m;
    }

    /** Makes a past version the draft again (the item then has changes to publish). */
    public Map<String, Object> restore(Principal p, String id, int n) {
        BiStore.Item i = editable(p, id, "restoring versions");
        if (i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "withdraw the proposal before restoring");
        }
        BiStore.Version v = store.version(id, n).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + i.title() + "' has no version " + n));
        store.saveItem(new BiStore.Item(i.id(), i.workspace(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), v.body(),
                n == i.publishedVersion() ? "published" : "draft", i.publishedVersion(), i.endorsement(), i.endorsedBy(), i.audienceRoles(), i.audienceUsers(),
                i.audiencePacks(), "", null, i.createdAt(), Instant.now(), p.user(), i.publishedAt(), i.publishedBy()));
        revise(i, v.body(), "restored version " + n, p.user(), "restore", 0);
        audit.record(p.user(), "bi-item-restored", id, "version " + n);
        return item(p, id, false);
    }

    /** Makes a saved state (a revision) the draft again, as a new revision: nothing is lost, and the published version is not touched. */
    public Map<String, Object> restoreRevision(Principal p, String id, int seq) {
        BiStore.Item i = editable(p, id, "restoring versions");
        if (i.status().equals("proposed")) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "withdraw the proposal before restoring");
        }
        BiStore.Revision r = store.revision(id, seq).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + i.title() + "' has no saved state " + seq));
        String published = i.isPublished() ? impact.publishedBody(i) : null;
        store.saveItem(new BiStore.Item(i.id(), i.workspace(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), r.body(),
                r.body().equals(published) ? "published" : "draft", i.publishedVersion(), i.endorsement(), i.endorsedBy(), i.audienceRoles(), i.audienceUsers(),
                i.audiencePacks(), "", null, i.createdAt(), Instant.now(), p.user(), i.publishedAt(), i.publishedBy()));
        revise(i, r.body(), "restored saved state " + seq, p.user(), "restore", 0);
        audit.record(p.user(), "bi-item-restored", id, "saved state " + seq);
        return item(p, id, false);
    }

    // -- endorsement ----------------------------------------------------------------------------------------------------
    public Map<String, Object> endorse(Principal p, String id, String level) {
        open(p);
        if (!ENDORSEMENTS.contains(level)) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "endorsement is one of " + ENDORSEMENTS);
        }
        BiStore.Item i = readable(p, id);
        if (BiFolder.isShipped(id)) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "shipped content carries its endorsement in its file");
        }
        if (!i.isPublished()) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "only published items are endorsed");
        }
        BiStore.Workspace w = workspaceOf(i.workspace());
        boolean steward = access.has(p, BiAccess.STEWARD);
        boolean owner = i.owner().equals(p.user()) || BiAccess.rank(roleIn(w, p)) >= BiAccess.rank("member");
        boolean allowed = switch (level) {
            case "certified" -> steward;
            case "promoted" -> owner || steward;
            default -> i.endorsement().equals("certified") ? steward : owner || steward;
        };
        if (!allowed) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, level.equals("certified") || i.endorsement().equals("certified")
                    ? "certifying needs the bi-steward power" : "promoting needs the owner or a member of the workspace");
        }
        store.saveItem(new BiStore.Item(i.id(), i.workspace(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), i.body(), i.status(), i.publishedVersion(),
                level, level.equals("none") ? "" : p.user(), i.audienceRoles(), i.audienceUsers(), i.audiencePacks(), i.proposedBy(), i.proposedAt(), i.createdAt(),
                i.updatedAt(), i.updatedBy(), i.publishedAt(), i.publishedBy()));
        store.addEvent(id, level.equals("certified") ? "certify" : level.equals("none") && i.endorsement().equals("certified") ? "uncertify" : level.equals("promoted") ? "promote" : "unpromote",
                p.user(), "", latestRevision(id), Instant.now());
        audit.record(p.user(), "bi-item-endorsed", id, level);
        return item(p, id, false);
    }

    public Map<String, Object> favourite(Principal p, String id, boolean on) {
        open(p);
        BiStore.Item i = readable(p, id);
        store.favourite(p.user(), i.id(), on);
        return item(p, id, false);
    }

    // ===================================================================================================================
    // Catalogue and home
    // ===================================================================================================================
    private List<BiStore.Item> catalogueItems(Principal p) {
        Set<String> mine = myWorkspaces(p);
        Set<String> myPacks = myPacks(p);
        boolean admin = access.has(p, BiAccess.ADMIN);
        List<BiStore.Item> all = new ArrayList<>(store.published());
        all.addAll(folder.items());
        return all.stream().filter(i -> visible(i, p, mine, myPacks, admin)).toList();
    }

    /** The published items the caller may see, searched, filtered and sorted; certified ones first (unless sorted by title). */
    public Map<String, Object> catalogue(Principal p, Map<String, String> q) {
        open(p);
        List<BiStore.Item> seen = catalogueItems(p);
        Map<String, BiStore.Mark> marks = store.marks(p.user());
        Map<String, Integer> views = store.views(props.usageDays(), Instant.now());
        Map<String, String> workspaceNames = new java.util.HashMap<>();
        seen.forEach(i -> workspaceNames.computeIfAbsent(i.workspace(), k -> workspaceOf(k).name()));
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        List<String> terms = text.isEmpty() ? List.of() : List.of(text.split("\\s+"));
        long freshSince = q.containsKey("fresh") && !q.get("fresh").isBlank() ? Instant.now().minusSeconds(86_400L * Math.max(1, parse(q.get("fresh")))).toEpochMilli() : Long.MIN_VALUE;
        List<BiStore.Item> hits = seen.stream()
                .filter(i -> terms.stream().allMatch(t -> haystack(i, workspaceNames.get(i.workspace())).contains(t)))
                .filter(i -> blank(q, "kind") || i.kind().equals(q.get("kind")))
                .filter(i -> blank(q, "workspace") || i.workspace().equals(q.get("workspace")))
                .filter(i -> blank(q, "owner") || i.owner().equals(q.get("owner")))
                .filter(i -> blank(q, "endorsement") || i.endorsement().equals(q.get("endorsement")))
                .filter(i -> blank(q, "tag") || i.tags().contains(q.get("tag")))
                .filter(i -> blank(q, "pack") || i.audiencePacks().contains(q.get("pack")))
                .filter(i -> i.publishedAt() != null && i.publishedAt().toEpochMilli() >= freshSince)
                .toList();
        String sort = q.getOrDefault("sort", "use");
        Comparator<BiStore.Item> byTitle = Comparator.comparing(BiStore.Item::title, String.CASE_INSENSITIVE_ORDER);
        Comparator<BiStore.Item> order = switch (sort) {
            case "date" -> Comparator.comparing((BiStore.Item i) -> i.publishedAt() == null ? Instant.EPOCH : i.publishedAt()).reversed().thenComparing(byTitle);
            case "title" -> byTitle;
            default -> Comparator.comparingInt((BiStore.Item i) -> views.getOrDefault(i.id(), 0)).reversed().thenComparing(byTitle);
        };
        if (!"title".equals(sort)) {
            order = Comparator.comparing((BiStore.Item i) -> i.endorsement().equals("certified") ? 0 : 1).thenComparing(order);
        }
        int limit = Math.min(props.listLimit(), Math.max(1, q.containsKey("limit") ? parse(q.get("limit")) : props.listLimit()));
        List<Map<String, Object>> rows = hits.stream().sorted(order).limit(limit).map(i -> view(i, p, marks, views, false)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", hits.size());
        out.put("items", rows);
        out.put("facets", Map.of("kind", count(seen, i -> List.of(i.kind())), "workspace", count(seen, i -> List.of(i.workspace())), "owner", count(seen, i -> List.of(i.owner())),
                "endorsement", count(seen, i -> List.of(i.endorsement())), "tag", count(seen, BiStore.Item::tags), "pack", count(seen, BiStore.Item::audiencePacks)));
        out.put("workspaceNames", workspaceNames);
        return out;
    }

    /** Home: recent, favourites, certified for you, shared with me. */
    public Map<String, Object> home(Principal p) {
        open(p);
        List<BiStore.Item> seen = catalogueItems(p);
        Map<String, BiStore.Mark> marks = store.marks(p.user());
        Map<String, Integer> views = store.views(props.usageDays(), Instant.now());
        Set<String> mine = myWorkspaces(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recent", seen.stream().filter(i -> marks.containsKey(i.id()) && marks.get(i.id()).lastOpened() != null)
                .sorted(Comparator.comparing((BiStore.Item i) -> marks.get(i.id()).lastOpened()).reversed()).limit(props.recent()).map(i -> view(i, p, marks, views, false)).toList());
        out.put("favourites", seen.stream().filter(i -> marks.containsKey(i.id()) && marks.get(i.id()).favourite())
                .sorted(Comparator.comparing(BiStore.Item::title, String.CASE_INSENSITIVE_ORDER)).limit(props.listLimit()).map(i -> view(i, p, marks, views, false)).toList());
        out.put("certified", seen.stream().filter(i -> i.endorsement().equals("certified"))
                .sorted(Comparator.comparingInt((BiStore.Item i) -> views.getOrDefault(i.id(), 0)).reversed().thenComparing(BiStore.Item::title)).limit(props.recent())
                .map(i -> view(i, p, marks, views, false)).toList());
        out.put("shared", seen.stream().filter(i -> !i.owner().equals(p.user()) && (mine.contains(i.workspace()) || !i.audienceUsers().isEmpty() && i.audienceUsers().contains(p.user())))
                .sorted(Comparator.comparing((BiStore.Item i) -> i.publishedAt() == null ? Instant.EPOCH : i.publishedAt()).reversed()).limit(props.recent())
                .map(i -> view(i, p, marks, views, false)).toList());
        out.put("counts", Map.of("catalogue", seen.size(), "workspaces", mine.size() + 1));
        return out;
    }

    // ===================================================================================================================
    // Helpers
    // ===================================================================================================================
    private static String haystack(BiStore.Item i, String workspaceName) {
        return (i.title() + " " + i.description() + " " + String.join(" ", i.tags()) + " " + i.owner() + " " + i.kind() + " " + workspaceName).toLowerCase(Locale.ROOT);
    }

    private static boolean blank(Map<String, String> q, String k) {
        return q.get(k) == null || q.get(k).isBlank();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "'" + s + "' is not a whole number");
        }
    }

    private static Map<String, Integer> count(Collection<BiStore.Item> items, java.util.function.Function<BiStore.Item, Collection<String>> key) {
        Map<String, Integer> out = new TreeMap<>();
        items.forEach(i -> key.apply(i).forEach(k -> out.merge(k, 1, Integer::sum)));
        return out;
    }

    private static String note(String s) {
        return s == null ? "" : s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static String randomId() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        s = s.isEmpty() || !Character.isLetter(s.charAt(0)) ? "w-" + s : s;
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private static String text(Map<String, Object> body, String key, int max, boolean required) {
        Object v = body.get(key);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (required && s.isEmpty()) {
            throw new DrishtiException(ErrorCode.BI_INVALID, key + " is required");
        }
        if (s.length() > max) {
            throw new DrishtiException(ErrorCode.BI_INVALID, key + " is at most " + max + " characters");
        }
        return s;
    }

    private static List<String> strings(Object v) {
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            c.forEach(x -> out.add(String.valueOf(x).trim()));
        } else {
            for (String part : String.valueOf(v).split("[,\\n]")) {
                out.add(part.trim());
            }
        }
        return out.stream().filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static List<String> tags(Object v) {
        List<String> t = strings(v);
        if (t.size() > 20 || t.stream().anyMatch(x -> !TAG.matcher(x).matches())) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "at most 20 tags, each 1-40 letters, digits, space or . _ : / -");
        }
        return t;
    }

    private List<String> audienceList(Map<String, Object> body, String key, boolean checkRoles) {
        List<String> l = strings(body.get(key));
        if (key.equals("audienceRoles")) {
            l.stream().filter(r -> !roles.exists(r) && !r.equals("*")).findFirst().ifPresent(r -> {
                throw new DrishtiException(ErrorCode.BI_INVALID, "audience role '" + r + "' does not exist");
            });
        } else if (key.equals("audiencePacks")) {
            List<String> installed = packs.installed();
            l.stream().filter(x -> !installed.contains(x)).findFirst().ifPresent(x -> {
                throw new DrishtiException(ErrorCode.BI_INVALID, "audience pack '" + x + "' is not installed");
            });
        } else if (key.equals("audienceUsers")) {
            l.stream().filter(u -> users.find(u).isEmpty()).findFirst().ifPresent(u -> {
                throw new DrishtiException(ErrorCode.BI_INVALID, "audience user '" + u + "' does not exist");
            });
        }
        return l;
    }

    /** A YAML body: size-capped and parsed, never run. An empty body is allowed (a new placeholder). */
    private String checkedBody(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.getBytes(StandardCharsets.UTF_8).length > props.maxBodyBytes()) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "the body is larger than " + props.maxBodyBytes() + " bytes (drishti.bi.max-body-bytes)");
        }
        if (!s.isBlank()) {
            try {
                JsonNode n = YAML.readTree(s);
                if (n != null && !n.isObject() && !n.isMissingNode() && !n.isNull()) {
                    throw new DrishtiException(ErrorCode.BI_INVALID, "the body is YAML with a mapping at the top (key: value)");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new DrishtiException(ErrorCode.BI_INVALID, "the body is not valid YAML: " + YamlErrors.describe(e));
            }
        }
        return s;
    }
}
