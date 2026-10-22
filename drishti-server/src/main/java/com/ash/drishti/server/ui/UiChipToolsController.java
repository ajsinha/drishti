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
import com.ash.drishti.rachana.ui.BoardCheck;
import com.ash.drishti.rachana.ui.ChipPublishCheck;
import com.ash.drishti.rachana.ui.UiDocumentException;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.ash.drishti.rachana.ui.UiProblem;
import com.ash.drishti.rachana.ui.migrate.Conversion;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The chip designer's server calls (docs/architecture/UI_BOARD.md 12.6, phase P4b), beside P2's Chip Store routes:
 * <ul>
 *   <li>{@code POST /ui/chips/check}: the publish check (CP-01 to CP-05) of an unsaved chip document, as problems, never a refusal; with the
 *       latest released earlier version, what would break against it (removed or retyped pins, 9.3) and whether the document claims
 *       {@code compatible-with} it, and the boards that use each version (where used, before a release);</li>
 *   <li>{@code POST /ui/convert/sutra}: one Sutra converted by P5's converter (the designer's "Open as board");</li>
 *   <li>{@code POST /ui/convert/board}: a board painted back into a Sutra by P5's {@code BoardSutra} ("Export Sutra");</li>
 *   <li>{@code GET /ui/candidates} and {@code GET /ui/candidates/{pack}/{board}/{chip}}: the converter's board-local chips, the Chip Store
 *       candidates of 14.1 decision 5, and one of them as a chip document to make generic.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ui")
public class UiChipToolsController {

    /** {@code {sutra, pack?, kind?}}. */
    public record SutraIn(String sutra, String pack, String kind) {}

    /** {@code {document}} (a board as JSON). */
    public record BoardIn(Map<String, Object> document) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChipStore chips;
    private final BoardStore boards;
    private final PackScreens screens;
    private final UiAccess access;

    public UiChipToolsController(ChipStore chips, BoardStore boards, PackScreens screens, UiAccess access) {
        this.chips = chips;
        this.boards = boards;
        this.screens = screens;
        this.access = access;
    }

    private void author(Principal p) {
        if (!access.mayAuthor(p) && !access.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, p.user() + " may not author chips (a role with 'author' may)");
        }
    }

    /** The publish check of a chip document, with its compatibility against the latest released earlier version and where it is used. */
    @PostMapping("/chips/check")
    public Map<String, Object> check(@RequestBody String body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        author(p);
        List<UiProblem> problems = new ArrayList<>(ChipPublishCheck.check(body, chips.draftsOf(p).then(chips)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !BoardCheck.failed(problems));
        out.put("problems", problems);
        Map<String, Object> doc;
        try {
            doc = UiDocs.read(body);
        } catch (DrishtiException e) {
            return out;
        }
        String name = UiDocs.str(doc.get("chip"));
        int n;
        try {
            n = UiDocs.version(doc);
        } catch (DrishtiException e) {
            return out;
        }
        if (name == null || !UiDocs.NAME.matcher(name).matches()) {
            return out;
        }
        Optional<ChipStore.Version> prev = Optional.empty();
        try {
            prev = chips.versions(name, p).stream().filter(s -> s.released() && s.version() < n).reduce((a, b) -> b)
                    .flatMap(s -> chips.find(name, s.version()));
            out.put("whereUsed", boards.whereUsed(name, p));
        } catch (DrishtiException e) {
            out.put("whereUsed", List.of());                               // a new chip: nothing released, no board uses it
        }
        if (prev.isPresent()) {
            int pv = prev.get().state().version();
            List<String> breaking = UiDocs.incompatibilities(prev.get().doc(), doc);
            out.put("previous", pv);
            out.put("breaking", breaking);
            boolean claims;
            try {
                claims = UiDocs.compatibleWith(doc).contains(pv);
            } catch (DrishtiException e) {
                claims = false;
            }
            out.put("claimsCompatible", claims);
            if (claims && !breaking.isEmpty()) {
                problems.add(UiProblem.error("CP-06", "compatible-with", "version " + n + " claims to be compatible with " + pv + " but breaks it: "
                        + String.join("; ", breaking), "take it out of compatible-with: a breaking change is a new major version that boards move to by a click"));
                out.put("ok", false);
            }
        }
        return out;
    }

    /** One Sutra (a screen design's text) converted into a board by P5's converter. */
    @PostMapping("/convert/sutra")
    public Map<String, Object> fromSutra(@RequestBody SutraIn in, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!access.maySave(p) && !access.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, p.user() + " may not design boards");
        }
        if (in == null || in.sutra() == null || in.sutra().isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the Sutra's text as 'sutra'");
        }
        String pack = in.pack();
        if (pack == null || pack.isBlank()) {
            String kind = in.kind();
            if (kind == null) {
                try {
                    kind = UiDocuments.tree(in.sutra()).path("match").path("kind").asText(null);
                } catch (UiDocumentException e) {
                    kind = null;
                }
            }
            pack = kind == null ? null : screens.packOf(kind);
        }
        pack = pack == null || !pack.matches("[a-z][a-z0-9-]{0,63}") ? "design" : pack;
        Conversion c = screens.convert(pack, in.sutra());
        if (c == null) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the text is not a Sutra (no 'sutra:'): a block opens in the screen designer");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", c.document());
        out.put("status", c.status().text());
        out.put("pack", pack);
        out.put("screen", c.screen());
        out.put("seeds", c.seeds());
        out.put("locals", c.locals());
        out.put("traces", c.traces());
        out.put("links", c.links());
        out.put("reason", c.reason());
        out.put("problems", c.problems());
        return out;
    }

    /** A board painted back into a Sutra (Export Sutra, until 3.0): only a board with an entity {@code id} parameter has one. */
    @PostMapping("/convert/board")
    public Map<String, Object> toSutra(@RequestBody BoardIn in, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws Exception {
        if (!access.maySave(p) && !access.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, p.user() + " may not design boards");
        }
        if (in == null || in.document() == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the board as 'document'");
        }
        var read = UiDocuments.parseBoard(JSON.writeValueAsString(in.document()));
        if (read.doc() == null) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the board cannot be read: " + read.problems());
        }
        var sutra = screens.sutraOf(read.doc());
        Map<String, Object> out = new LinkedHashMap<>();
        if (sutra.isEmpty()) {
            out.put("yaml", null);
            out.put("notes", List.of("a Sutra paints one record: give the board a parameter id: { type: id, of: <pack>/<kind> } first"));
            return out;
        }
        out.put("yaml", UiDocs.YAML.writeValueAsString(sutra.get()));
        out.put("notes", read.doc().traces().isEmpty() ? List.of() : List.of("traces between chips have no Sutra form except a header control"));
        return out;
    }

    /** The board-local chips of every pack's converted screens: candidates for the Chip Store (14.1 decision 5). */
    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestParam(required = false) String pack, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        author(p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : pack == null || pack.isBlank() ? screens.packs() : List.of(pack)) {
            for (PackScreens.Served s : screens.boards(k).values()) {
                Conversion c = s.conversion();
                for (Conversion.Local l : c.locals()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pack", k);
                    m.put("board", c.board());
                    m.put("screen", c.screen());
                    m.put("chip", l.chip());
                    m.put("from", l.from());
                    m.put("reason", l.reason());
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** One candidate as a chip document (its YAML), to open in Chip mode and make generic. */
    @GetMapping("/candidates/{pack}/{board}/{chip}")
    public Map<String, Object> candidate(@PathVariable String pack, @PathVariable String board, @PathVariable String chip,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws Exception {
        author(p);
        for (PackScreens.Served s : screens.boards(pack).values()) {
            if (!s.conversion().board().equals(board) || s.conversion().document() == null) {
                continue;
            }
            for (JsonNode c : s.conversion().document().path("local-chips")) {
                if (chip.equals(c.path("chip").asText())) {
                    return Map.of("pack", pack, "board", board, "chip", chip, "document", c, "yaml", UiDocs.YAML.writeValueAsString(c));
                }
            }
        }
        throw new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no candidate " + chip + " on " + pack + "/" + board);
    }
}
