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
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The UI Board API of phase P2 (UI_BOARD.md section 6.1, all but render): saved boards (personal, shared by link, workspace), their
 * revisions and copies; the Chip Store listing, versions, manuals, where-used, drafts and the lifecycle steps. A board is named by its
 * name, with {@code owner} (a personal board shared with you; your own by default) or {@code workspace}.
 */
@RestController
@RequestMapping("/api/v1/ui")
public class UiController {

    /** A lifecycle step's note (required to reject and to withdraw). */
    public record Note(String comment) {}

    /** The people a personal board is shared with. */
    public record People(List<String> people) {}

    /** Where a copy goes: the workspace and, optionally, a new name. */
    public record Copy(String workspace, String name) {}

    private final ChipStore chips;
    private final BoardStore boards;

    public UiController(ChipStore chips, BoardStore boards) {
        this.chips = chips;
        this.boards = boards;
    }

    private static BoardStore.Ref ref(String name, String owner, String workspace, Principal p) {
        if (workspace != null && !workspace.isBlank()) {
            return new BoardStore.Ref(BoardStore.WORKSPACE, workspace, name);
        }
        return new BoardStore.Ref(BoardStore.USER, owner == null || owner.isBlank() ? p.user() : owner, name);
    }

    private static String unquote(String etag) {
        if (etag == null || etag.isBlank() || etag.equals("*")) {
            return null;
        }
        String e = etag.trim();
        if (e.startsWith("W/")) {
            e = e.substring(2);
        }
        return e.startsWith("\"") && e.endsWith("\"") && e.length() > 1 ? e.substring(1, e.length() - 1) : e;
    }

    private static ResponseEntity<Map<String, Object>> board(BoardStore.Saved s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.ref().id());
        m.put("board", s.ref().board());
        m.put("scope", s.ref().scope());
        m.put(BoardStore.USER.equals(s.ref().scope()) ? "owner" : "workspace", s.ref().space());
        m.put("etag", s.version());
        m.put("sharedWith", s.sharedWith());
        m.put("document", UiDocs.read(s.text()));
        m.put("yaml", s.text());
        return ResponseEntity.ok().eTag("\"" + s.version() + "\"").body(m);
    }

    // ---- boards ------------------------------------------------------------------------------------------------------------

    @GetMapping("/boards")
    public List<Map<String, Object>> boards(@RequestParam(required = false) String workspace, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return boards.list(workspace, p);
    }

    /** The store's part of the board check (parse, sizes, chip versions; BC-01, BC-02, BC-09, BC-11): the problems, empty when it passes. */
    @PostMapping("/boards/check")
    public Map<String, Object> check(@RequestBody String body, @RequestParam(required = false) String workspace,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<String> problems = new ArrayList<>();
        try {
            Map<String, Object> doc = UiDocs.read(body);
            String name = doc.get("board") == null ? "scratch" : String.valueOf(doc.get("board"));
            boards.checked(ref(name, null, workspace, p), body, p);
        } catch (DrishtiException e) {
            problems.add(e.getMessage());
        }
        return Map.of("ok", problems.isEmpty(), "problems", problems);
    }

    @GetMapping("/boards/{name}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String name, @RequestParam(required = false) String owner,
            @RequestParam(required = false) String workspace, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return board(boards.get(ref(name, owner, workspace, p), p));
    }

    /** Saves: create with {@code If-None-Match: *} (or no header), update with {@code If-Match: <etag>}. */
    @PutMapping("/boards/{name}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable String name, @RequestParam(required = false) String workspace,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody String body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return board(boards.save(ref(name, null, workspace, p), body, unquote(ifMatch), p));
    }

    @DeleteMapping("/boards/{name}")
    public Map<String, Object> delete(@PathVariable String name, @RequestParam(required = false) String workspace,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        BoardStore.Ref r = ref(name, null, workspace, p);
        boards.delete(r, unquote(ifMatch), p);
        return Map.of("deleted", r.id());
    }

    /** Shares one of your personal boards by link with named people (the list replaces the old one; empty makes it private again). */
    @PutMapping("/boards/{name}/share")
    public Map<String, Object> share(@PathVariable String name, @RequestBody People people, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        BoardStore.Ref r = ref(name, null, null, p);
        return Map.of("id", r.id(), "sharedWith", boards.share(r, people == null ? List.of() : people.people(), p));
    }

    /** Copies a board you may open (yours, shared with you, or a workspace's) into a workspace. */
    @PostMapping("/boards/{name}/copy")
    public ResponseEntity<Map<String, Object>> copy(@PathVariable String name, @RequestParam(required = false) String owner,
            @RequestParam(required = false) String workspace, @RequestBody Copy to, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (to == null || to.workspace() == null || to.workspace().isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "copy needs {\"workspace\": ...}");
        }
        return board(boards.copyToWorkspace(ref(name, owner, workspace, p), to.workspace(), to.name(), p));
    }

    @GetMapping("/boards/{name}/revisions")
    public Map<String, Object> revisions(@PathVariable String name, @RequestParam(required = false) String owner,
            @RequestParam(required = false) String workspace, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        BoardStore.Ref r = ref(name, owner, workspace, p);
        return Map.of("id", r.id(), "revisions", boards.revisions(r, p));
    }

    @GetMapping("/boards/{name}/revisions/{n}")
    public Map<String, Object> revision(@PathVariable String name, @PathVariable int n, @RequestParam(required = false) String owner,
            @RequestParam(required = false) String workspace, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        BoardStore.Ref r = ref(name, owner, workspace, p);
        return Map.of("id", r.id(), "revision", n, "yaml", boards.revision(r, n, p));
    }

    // ---- chips -------------------------------------------------------------------------------------------------------------

    @GetMapping("/chips")
    public List<Map<String, Object>> chips(@RequestParam(required = false) String category, @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q, @RequestParam(required = false) Boolean certified,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return chips.list(category, tag, q, certified, p);
    }

    @GetMapping("/chips/{name}")
    public Map<String, Object> versions(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("chip", name, "versions", chips.versions(name, p));
    }

    @GetMapping("/chips/{name}/{version:[0-9]+}")
    public Map<String, Object> chip(@PathVariable String name, @PathVariable int version, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ChipStore.Version v = chips.get(name, version, p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chip", name);
        m.put("version", version);
        m.put("state", v.state());
        m.put("document", v.doc());
        m.put("yaml", v.text());
        return m;
    }

    /** The technical manual alone, for people and agents. */
    @GetMapping("/chips/{name}/{version:[0-9]+}/manual")
    public Map<String, Object> manual(@PathVariable String name, @PathVariable int version, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ChipStore.Version v = chips.get(name, version, p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chip", name);
        m.put("version", version);
        m.put("title", v.doc().get("title"));
        m.put("state", v.state().state());
        m.put("pins", v.doc().get("pins"));
        m.put("manual", v.doc().get("manual"));
        return m;
    }

    @GetMapping("/chips/{name}/where-used")
    public Map<String, Object> whereUsed(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("chip", name, "versions", boards.whereUsed(name, p));
    }

    @GetMapping("/chips/{name}/events")
    public Map<String, Object> events(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("chip", name, "events", chips.events(name, p));
    }

    @GetMapping("/chips/{name}/draft")
    public ResponseEntity<Map<String, Object>> draft(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ContentStore.Item it = chips.draft(name, p);
        return ResponseEntity.ok().eTag("\"" + it.version() + "\"").body(Map.of("chip", name, "etag", it.version(), "yaml", it.text()));
    }

    /** Saves a draft: the first with no {@code If-Match}, later ones with the draft's ETag. */
    @PutMapping("/chips/{name}/draft")
    public ResponseEntity<Map<String, Object>> saveDraft(@PathVariable String name, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String v = chips.saveDraft(name, body, unquote(ifMatch), p);
        return ResponseEntity.ok().eTag("\"" + v + "\"").body(Map.of("chip", name, "etag", v));
    }

    /** {@code submit}, {@code approve}, {@code reject}, {@code publish}, {@code certify}, {@code deprecate}, {@code withdraw}. */
    @PostMapping("/chips/{name}/{version:[0-9]+}/{action}")
    public ChipStore.State act(@PathVariable String name, @PathVariable int version, @PathVariable String action,
            @RequestBody(required = false) Note note, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return chips.act(name, version, action, note == null ? null : note.comment(), p);
    }
}
