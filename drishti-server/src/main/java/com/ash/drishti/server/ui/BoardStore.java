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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiDocs;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.rachana.ui.BoardCheck;
import com.ash.drishti.rachana.ui.BoardCheckContext;
import com.ash.drishti.rachana.ui.UiProblem;
import com.ash.drishti.server.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saved UI Boards (UI_BOARD.md sections 3, 8 and 14.1): personal boards ({@code ui/boards/users/<user>/<board>.yaml}), private by default
 * and shared by link with named people; workspace boards ({@code ui/boards/workspaces/<ws>/<board>.yaml}) under the BI workspace
 * membership. Every save is a compare-and-set and keeps the earlier save as a revision; the where-used index of every chip version
 * ({@code ui/where-used/<chip>/<n>.json}) follows saves and deletes; boards follow a compatible chip upgrade automatically.
 */
public class BoardStore implements ChipStore.PublishListener {

    private static final Logger LOG = LoggerFactory.getLogger(BoardStore.class);
    public static final String USER = "user", WORKSPACE = "workspace";

    /** Where a board is: a person's own space or a workspace, and its name. */
    public record Ref(String scope, String space, String board) {

        public Ref {
            if (!USER.equals(scope) && !WORKSPACE.equals(scope)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "a board is a user's or a workspace's");
            }
            if (space == null || space.isBlank()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "a board needs its " + scope);
            }
            UiDocs.name(board, "board");
        }

        String dir() {
            return (USER.equals(scope) ? "ui/boards/users/" : "ui/boards/workspaces/") + BiDocs.seg(space) + "/";
        }

        String key() {
            return dir() + board + ".yaml";
        }

        String revisions() {
            return dir() + board + ".revisions/";
        }

        String shareKey() {
            return dir() + board + ".share.json";
        }

        public String id() {
            return scope + ":" + space + ":" + board;
        }
    }

    /** A saved board: its document text, the store version (the ETag) and who it is shared with. */
    public record Saved(Ref ref, String text, String version, List<String> sharedWith) {}

    /** The where-used index of one chip version. */
    public record Uses(List<Ref> boards) {}

    /** The people a personal board is shared with. */
    public record Share(String owner, String board, List<String> people) {}

    private final ContentStore store;
    private final ContentDocs docs;
    private final ChipStore chips;
    private final UiAccess access;
    private final UiSettings settings;

    public BoardStore(ContentStore store, ChipStore chips, UiAccess access, UiSettings settings) {
        this.store = store;
        this.docs = new ContentDocs(store);
        this.chips = chips;
        this.access = access;
        this.settings = settings;
        chips.addListener(this);
    }

    static String usesKey(String chip, int n) {
        return "ui/where-used/" + BiDocs.seg(chip) + "/" + String.format("%010d", n) + ".json";
    }

    // ---- reading -------------------------------------------------------------------------------------------------------------

    /** Whether {@code p} may open the board: its owner or a person it is shared with; a workspace's viewers and above. */
    public boolean mayRead(Ref r, Principal p) {
        if (!access.mayView(p)) {
            return false;
        }
        if (WORKSPACE.equals(r.scope())) {
            return UiAccess.rank(access.workspaceRole(r.space(), p)) >= 0;
        }
        return r.space().equals(p.user()) || share(r).people().contains(p.user());
    }

    private Share share(Ref r) {
        return docs.value(r.shareKey(), Share.class).orElse(new Share(r.space(), r.board(), List.of()));
    }

    public Saved get(Ref r, Principal p) {
        if (!mayRead(r, p)) {
            throw notFound(r);
        }
        ContentStore.Item it = store.get(r.key()).orElseThrow(() -> notFound(r));
        return new Saved(r, it.text(), it.version(), USER.equals(r.scope()) ? share(r).people() : List.of());
    }

    /** The boards the caller may open: their own, those shared with them, and a workspace's when one is named. */
    public List<Map<String, Object>> list(String workspace, Principal p) {
        if (!access.mayView(p)) {
            throw ChipStore.forbidden(p, "open boards");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : boardKeys(new Ref(USER, p.user(), "x").dir())) {
            out.add(summary(new Ref(USER, p.user(), boardName(k)), "mine"));
        }
        for (String k : store.list("ui/shared/" + BiDocs.seg(p.user()) + "/")) {
            docs.value(k, Share.class).filter(s -> s.people().contains(p.user())).ifPresent(s -> {
                Ref r = new Ref(USER, s.owner(), s.board());
                if (store.get(r.key()).isPresent()) {
                    out.add(summary(r, "shared"));
                }
            });
        }
        if (workspace != null && !workspace.isBlank()) {
            if (UiAccess.rank(access.workspaceRole(workspace, p)) < 0) {
                throw new DrishtiException(ErrorCode.UI_BOARD_NOT_FOUND, "no workspace '" + workspace + "' you may open");
            }
            for (String k : boardKeys(new Ref(WORKSPACE, workspace, "x").dir())) {
                out.add(summary(new Ref(WORKSPACE, workspace, boardName(k)), "workspace"));
            }
        }
        return out;
    }

    private List<String> boardKeys(String dir) {
        return store.list(dir).stream().filter(k -> k.endsWith(".yaml") && k.indexOf('/', dir.length()) < 0).toList();
    }

    private static String boardName(String key) {
        return key.substring(key.lastIndexOf('/') + 1, key.length() - ".yaml".length());
    }

    private Map<String, Object> summary(Ref r, String how) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("scope", r.scope());
        m.put(USER.equals(r.scope()) ? "owner" : "workspace", r.space());
        m.put("board", r.board());
        m.put("access", how);
        store.get(r.key()).ifPresent(it -> {
            Map<String, Object> d = UiDocs.read(it.text());
            m.put("title", d.get("title"));
            m.put("version", d.get("version"));
            m.put("chips", UiDocs.map(d.get("chips")).size());
            m.put("etag", it.version());
        });
        return m;
    }

    // ---- saving --------------------------------------------------------------------------------------------------------------

    /**
     * Saves a board: create with {@code expected} null (the board must not exist), update with the version read ({@code DRS-5057} when
     * it changed elsewhere). The document's sizes are checked ({@code DRS-2075}), every chip use must resolve ({@code DRS-2071}); the
     * store sets {@code owner}, {@code workspace} and {@code version} (the revision), keeps the earlier save and updates where-used.
     */
    public Saved save(Ref r, String text, String expected, Principal p) {
        if (!access.maySave(p)) {
            throw ChipStore.forbidden(p, "save boards");
        }
        if (USER.equals(r.scope()) && !r.space().equals(p.user())) {
            throw ChipStore.forbidden(p, "save into " + r.space() + "'s boards (copy the board to your own instead)");
        }
        String owner = r.space();
        if (WORKSPACE.equals(r.scope())) {
            if (UiAccess.rank(access.workspaceRole(r.space(), p)) < UiAccess.rank("contributor")) {
                throw ChipStore.forbidden(p, "save boards in workspace " + r.space() + " (a contributor or above may)");
            }
            owner = store.get(r.key()).map(it -> UiDocs.str(UiDocs.read(it.text()).get("owner"))).orElse(p.user());
        }
        if (expected == null && USER.equals(r.scope()) && boardKeys(r.dir()).size() >= settings.boardsPerPerson()) {
            throw new DrishtiException(ErrorCode.UI_TOO_LARGE, p.user() + " has " + settings.boardsPerPerson() + " saved boards, the most allowed");
        }
        Map<String, Object> doc = checked(r, text, p);
        return write(r, doc, owner, expected, p, "ui.board.save");
    }

    /** Parses and checks a board for saving at {@code r}; answers the document. */
    Map<String, Object> checked(Ref r, String text, Principal p) {
        if (text != null && text.getBytes(StandardCharsets.UTF_8).length > settings.boardBytes()) {
            throw new DrishtiException(ErrorCode.UI_TOO_LARGE, "the board is over " + settings.boardBytes() + " bytes");
        }
        Map<String, Object> doc = UiDocs.read(text);
        Object name = doc.get("board");
        if (name != null && !r.board().equals(String.valueOf(name))) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the document is board '" + name + "', not '" + r.board() + "'");
        }
        doc.putIfAbsent("rachana", 1);
        doc.put("board", r.board());
        Map<String, Object> instances = UiDocs.map(doc.get("chips"));
        if (instances.size() > settings.chips()) {
            throw new DrishtiException(ErrorCode.UI_TOO_LARGE, "the board has " + instances.size() + " chips; at most " + settings.chips());
        }
        int traces = UiDocs.list(doc.get("traces")).size();
        if (traces > settings.traces()) {
            throw new DrishtiException(ErrorCode.UI_TOO_LARGE, "the board has " + traces + " traces; at most " + settings.traces());
        }
        List<String> errors = problems(r, doc, p).stream().filter(UiProblem::error).map(Object::toString).toList();
        if (!errors.isEmpty()) {
            throw new DrishtiException(ErrorCode.UI_CHECK_FAILED, "the board check failed: " + String.join("; ", errors));
        }
        return doc;
    }

    /**
     * The board check (the grammar's {@code BoardCheck}, BC-01 to BC-11 and the warnings) against the Chip Store and the caller's own
     * drafts, as a personal or a shared board, with the limits of {@code ui.yaml}; then the store's own rule that a chip version before
     * publish is for its author's personal boards only. Problems, errors and warnings.
     */
    List<UiProblem> problems(Ref r, Map<String, Object> doc, Principal p) {
        boolean shared = WORKSPACE.equals(r.scope());
        BoardCheckContext ctx = new BoardCheckContext(chips.then(chips.draftsOf(p)), null,
                new BoardCheckContext.Limits(settings.boardBytes(), settings.chips(), settings.traces(), 3), shared,
                shared && settings.certifiedOnly().contains(r.space()), null, false);
        List<UiProblem> out = new ArrayList<>(BoardCheck.check(UiDocs.write(doc), ctx));
        if (!BoardCheck.failed(out)) {
            for (String m : useProblems(r, doc, p)) {
                out.add(UiProblem.error(m.substring(0, 5), "chips", m.substring(6), null));
            }
        }
        return out;
    }

    /** The check of an unsaved document as if saved at {@code r} (the check endpoint): every problem; an unreadable document is BC-01. */
    public List<UiProblem> check(Ref r, String text, Principal p) {
        if (!access.mayView(p)) {
            throw ChipStore.forbidden(p, "check boards");
        }
        Map<String, Object> doc;
        try {
            doc = UiDocs.read(text);
        } catch (DrishtiException e) {
            return List.of(UiProblem.error("BC-01", "", e.getMessage(), null));
        }
        doc.putIfAbsent("rachana", 1);
        doc.put("board", r.board());
        return problems(r, doc, p);
    }

    /** BC-02 and BC-11 against the store: every use resolves, none is withdrawn, drafts only on the author's own boards. */
    List<String> useProblems(Ref r, Map<String, Object> doc, Principal p) {
        List<String> out = new ArrayList<>();
        Set<String> local = UiDocs.localChips(doc);
        boolean certifiedOnly = WORKSPACE.equals(r.scope()) && settings.certifiedOnly().contains(r.space());
        for (Map.Entry<String, String> u : UiDocs.uses(doc).entrySet()) {
            String[] nv = u.getValue().split("@");
            if (local.contains(nv[0])) {
                continue;
            }
            int n = Integer.parseInt(nv[1]);
            Optional<ChipStore.Version> v = chips.find(nv[0], n);
            String where = "chip '" + u.getKey() + "' (" + u.getValue() + ")";
            if (v.isEmpty()) {
                boolean ownDraft = USER.equals(r.scope()) && draftOf(nv[0], n, p);
                if (!ownDraft) {
                    out.add("BC-02 " + where + ": no such chip version in the Chip Store");
                }
                continue;
            }
            ChipStore.State s = v.get().state();
            if (ChipStore.WITHDRAWN.equals(s.state())) {
                out.add("BC-02 " + where + ": withdrawn (" + s.comment() + ")");
            } else if (!s.released()) {
                if (!(USER.equals(r.scope()) && p.user().equals(s.author()))) {
                    out.add("BC-11 " + where + ": " + s.state() + "; only its author's personal boards may use it before it is published");
                }
            } else if (certifiedOnly && !ChipStore.CERTIFIED.equals(s.state())) {
                out.add("BC-11 " + where + ": workspace " + r.space() + " takes certified chips only");
            }
        }
        return out;
    }

    private boolean draftOf(String chip, int n, Principal p) {
        try {
            Map<String, Object> d = UiDocs.read(chips.draft(chip, p).text());
            return UiDocs.version(d) == n;
        } catch (DrishtiException e) {
            return false;
        }
    }

    private Saved write(Ref r, Map<String, Object> doc, String owner, String expected, Principal p, String what) {
        Optional<ContentStore.Item> old = store.get(r.key());
        int revision = old.map(it -> {
            try {
                return UiDocs.version(UiDocs.read(it.text()));
            } catch (DrishtiException e) {
                return 0;
            }
        }).orElse(0) + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rachana", doc.getOrDefault("rachana", 1));
        out.put("board", r.board());
        out.put("version", revision);
        doc.forEach((k, v) -> {
            if (!List.of("rachana", "board", "version", "owner", "workspace").contains(k)) {
                out.put(k, v);
            }
        });
        out.put("owner", owner);
        if (WORKSPACE.equals(r.scope())) {
            out.put("workspace", r.space());
        }
        String text = UiDocs.write(out);
        String ver = store.put(r.key(), text.getBytes(StandardCharsets.UTF_8), expected);
        if (old.isPresent()) {
            store.putLatest(r.revisions() + String.format("%010d", revision - 1) + ".yaml", old.get().bytes());
            List<String> revs = store.list(r.revisions());
            for (int i = 0; i < revs.size() - settings.revisions(); i++) {
                store.deleteLatest(revs.get(i));
            }
        }
        reindex(r, old.map(it -> usesOf(it.text())).orElse(Set.of()), usesOf(text));
        access.audit(p, what, r.id(), "revision " + revision);
        return new Saved(r, text, ver, USER.equals(r.scope()) ? share(r).people() : List.of());
    }

    private static Set<String> usesOf(String text) {
        try {
            Map<String, Object> d = UiDocs.read(text);
            Set<String> local = UiDocs.localChips(d);
            Set<String> out = new LinkedHashSet<>();
            UiDocs.uses(d).values().stream().filter(u -> !local.contains(u.split("@")[0])).forEach(out::add);
            return out;
        } catch (DrishtiException e) {
            return Set.of();
        }
    }

    private void reindex(Ref r, Set<String> before, Set<String> after) {
        for (String u : before) {
            if (!after.contains(u)) {
                String[] nv = u.split("@");
                docs.update(usesKey(nv[0], Integer.parseInt(nv[1])), Uses.class, x -> {
                    if (x == null) {
                        return null;
                    }
                    List<Ref> left = x.boards().stream().filter(b -> !b.equals(r)).toList();
                    return left.isEmpty() ? null : new Uses(left);
                });
            }
        }
        for (String u : after) {
            if (!before.contains(u)) {
                String[] nv = u.split("@");
                docs.update(usesKey(nv[0], Integer.parseInt(nv[1])), Uses.class, x -> {
                    List<Ref> all = new ArrayList<>(x == null ? List.of() : x.boards());
                    if (!all.contains(r)) {
                        all.add(r);
                    }
                    return new Uses(all);
                });
            }
        }
    }

    /** Deletes a board (compare-and-set), its revisions, its shares and its where-used entries. Its owner, or a workspace admin. */
    public void delete(Ref r, String expected, Principal p) {
        ContentStore.Item it = store.get(r.key()).filter(x -> mayRead(r, p)).orElseThrow(() -> notFound(r));
        String owner = UiDocs.str(UiDocs.read(it.text()).get("owner"));
        boolean allowed = USER.equals(r.scope()) ? r.space().equals(p.user())
                : p.user().equals(owner) || UiAccess.rank(access.workspaceRole(r.space(), p)) >= UiAccess.rank("admin");
        if (!allowed) {
            throw ChipStore.forbidden(p, "delete " + r.id() + " (its owner or a workspace admin may)");
        }
        if (expected == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "deleting a board needs If-Match with the version you read");
        }
        store.delete(r.key(), expected);
        store.list(r.revisions()).forEach(store::deleteLatest);
        if (USER.equals(r.scope())) {
            unshare(r, share(r).people());
            store.deleteLatest(r.shareKey());
        }
        reindex(r, usesOf(it.text()), Set.of());
        access.audit(p, "ui.board.delete", r.id(), "");
    }

    // ---- sharing, copying, revisions ---------------------------------------------------------------------------------------

    /** Shares a personal board by link with named people (replacing the list); its owner only. */
    public List<String> share(Ref r, List<String> people, Principal p) {
        if (!USER.equals(r.scope()) || !r.space().equals(p.user()) || store.get(r.key()).isEmpty()) {
            throw notFound(r);
        }
        List<String> next = people == null ? List.of() : people.stream().filter(x -> x != null && !x.isBlank() && !x.equals(p.user())).distinct().toList();
        List<String> before = share(r).people();
        unshare(r, before.stream().filter(x -> !next.contains(x)).toList());
        Share s = new Share(r.space(), r.board(), next);
        docs.putLatest(r.shareKey(), s);
        for (String person : next) {
            docs.putLatest(sharedKey(person, r), s);
        }
        access.audit(p, "ui.board.share", r.id(), String.join(",", next));
        return next;
    }

    private static String sharedKey(String person, Ref r) {
        return "ui/shared/" + BiDocs.seg(person) + "/" + BiDocs.seg(r.space()) + "/" + r.board() + ".json";
    }

    private void unshare(Ref r, List<String> people) {
        for (String person : people) {
            store.deleteLatest(sharedKey(person, r));
        }
    }

    /** Copies a board the caller may open into a workspace (a new board there, owned by the caller). */
    public Saved copyToWorkspace(Ref from, String workspace, String name, Principal p) {
        Saved src = get(from, p);
        Ref to = new Ref(WORKSPACE, workspace, name == null || name.isBlank() ? from.board() : name);
        Map<String, Object> doc = UiDocs.read(src.text());
        doc.put("board", to.board());
        if (UiAccess.rank(access.workspaceRole(workspace, p)) < UiAccess.rank("contributor")) {
            throw ChipStore.forbidden(p, "save boards in workspace " + workspace + " (a contributor or above may)");
        }
        if (!access.maySave(p)) {
            throw ChipStore.forbidden(p, "save boards");
        }
        Map<String, Object> checked = checked(to, UiDocs.write(doc), p);
        checked.remove("version");
        return write(to, checked, p.user(), null, p, "ui.board.copy");
    }

    /** The earlier saves of a board, newest first (revision numbers). */
    public List<Integer> revisions(Ref r, Principal p) {
        get(r, p);
        List<Integer> out = new ArrayList<>();
        for (String k : store.list(r.revisions())) {
            out.add(0, Integer.parseInt(k.substring(k.lastIndexOf('/') + 1, k.length() - ".yaml".length())));
        }
        return out;
    }

    public String revision(Ref r, int n, Principal p) {
        get(r, p);
        return store.get(r.revisions() + String.format("%010d", n) + ".yaml").map(ContentStore.Item::text)
                .orElseThrow(() -> new DrishtiException(ErrorCode.UI_BOARD_NOT_FOUND, "no revision " + n + " of " + r.id()));
    }

    // ---- where used and upgrades -------------------------------------------------------------------------------------------

    /** The boards using each version of a chip: those the caller may open named, the rest counted (section 6.1). */
    public List<Map<String, Object>> whereUsed(String chip, Principal p) {
        chips.versions(chip, p);
        List<Map<String, Object>> out = new ArrayList<>();
        String prefix = "ui/where-used/" + BiDocs.seg(chip) + "/";
        for (String k : store.list(prefix)) {
            Uses u = docs.value(k, Uses.class).orElse(new Uses(List.of()));
            int n = Integer.parseInt(k.substring(prefix.length(), k.length() - ".json".length()));
            List<String> named = u.boards().stream().filter(b -> mayRead(b, p)).map(Ref::id).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", n);
            m.put("boards", named);
            m.put("others", u.boards().size() - named.size());
            out.add(m);
        }
        return out;
    }

    /**
     * A chip version was published, verified compatible with earlier ones (9.3, decision 14.1 item 3): every board using one of them
     * moves to it as a new revision when its check still passes (a certified-only workspace waits for certification).
     */
    @Override
    public void published(String chip, int version, List<Integer> compatibleWith) {
        Principal system = new Principal("drishti", List.of());
        for (int c : compatibleWith) {
            for (Ref r : docs.value(usesKey(chip, c), Uses.class).map(Uses::boards).orElse(List.of())) {
                if (WORKSPACE.equals(r.scope()) && settings.certifiedOnly().contains(r.space())) {
                    continue;
                }
                try {
                    ContentStore.Item it = store.get(r.key()).orElse(null);
                    if (it == null) {
                        continue;
                    }
                    Map<String, Object> doc = UiDocs.read(it.text());
                    boolean changed = false;
                    for (Object inst : UiDocs.map(doc.get("chips")).values()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) inst;
                        if ((chip + "@" + c).equals(m.get("use")) && !"pinned".equals(m.get("follow"))) {
                            m.put("use", chip + "@" + version);
                            changed = true;
                        }
                    }
                    if (changed) {
                        write(r, doc, UiDocs.str(doc.get("owner")), it.version(), system, "ui.board.follow");
                        LOG.info("board {} follows {}@{} (compatible with {})", r.id(), chip, version, c);
                    }
                } catch (DrishtiException e) {
                    LOG.warn("board {} did not follow {}@{}: {}", r.id(), chip, version, e.getMessage());
                }
            }
        }
    }

    private static DrishtiException notFound(Ref r) {
        return new DrishtiException(ErrorCode.UI_BOARD_NOT_FOUND, "no board " + r.id() + " you may open");
    }
}
