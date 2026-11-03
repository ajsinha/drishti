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
package com.ash.drishti.server.pivot;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraLayoutEditor;
import com.ash.drishti.rachana.SutraPivotEditor;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PivotSpec;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.governance.Proposal;
import com.ash.drishti.server.governance.SutraGovernance;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An author's promotion of a panel's pivot to its Sutra (PIVOT_GUIDE.md section 11, kept from the retired Pivot tab): the view the author
 * arranged on a panel source ({@code panel/<kind>/<panel>/<id>}) becomes the panel's {@code pivot:} arrangement in the Sutra's next
 * version, written by {@link SutraPivotEditor}; with review on it is proposed, with review off live at once. {@code preview: true}
 * answers the next version's text and the changes in words without proposing anything. Needs the author power and Studio saving.
 */
@RestController
@RequestMapping("/api/v1/pivot/promote")
public class PivotPromoteController {

    private final Entitlements entitlements;
    private final SutraRegistry sutras;
    private final SutraGovernance governance;
    private final RachanaProperties rachana;
    private final SutraPivotEditor editor = new SutraPivotEditor();

    public PivotPromoteController(Entitlements entitlements, SutraRegistry sutras, SutraGovernance governance, RachanaProperties rachana) {
        this.entitlements = entitlements;
        this.sutras = sutras;
        this.governance = governance;
        this.rachana = rachana;
    }

    /** {@code {sutra, view, note, preview}}: the promotion, or what it would propose. */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> promote(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p)
            throws java.io.IOException {
        if (!rachana.studioSave()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "saving Sutras is disabled (drishti.rachana.studio-save)");
        }
        if (!entitlements.mayAuthor(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is not a Sutra author: promoting a pivot needs a role with author");
        }
        Map<String, Object> view = body.get("view") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String source = String.valueOf(view.getOrDefault("source", ""));
        if (!source.startsWith(PanelSources.PREFIX)) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "only a panel's pivot (source panel/<kind>/<panel>/<id>) is promoted to its Sutra; "
                    + "save any other view instead");
        }
        String[] parts = source.substring(PanelSources.PREFIX.length()).split("/", 3);
        String panel = parts.length > 1 ? parts[1] : "";
        String name = String.valueOf(body.getOrDefault("sutra", ""));
        Sutra s = sutras.latest(name).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no Sutra '" + name + "'"));
        Optional<Panel> pn = s.panels().stream().filter(x -> x.id().equals(panel)).findFirst();
        PivotSpec offered = pn.flatMap(Panel::pivot).orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "panel '" + panel + "' of "
                + s.id() + " does not offer a pivot (its Sutra has no pivot: on it)"));
        Map<String, Object> arrangement;
        try {
            arrangement = SpecViews.arrangement(view);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, e.getMessage());
        }
        PivotSpec.Parsed parsed = PivotSpec.arrangement(arrangement, offered);
        if (!parsed.problems().isEmpty()) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, String.join("; ", parsed.problems()));
        }
        String base = sutras.source(s.name(), s.version()).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, s.id()));
        SutraLayoutEditor.Edit edit;
        try {
            edit = editor.apply(base, panel, parsed.spec());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot write this pivot into " + s.id() + ": " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sutra", s.name());
        out.put("panel", panel);
        out.put("fromVersion", edit.fromVersion());
        out.put("version", edit.version());
        out.put("changes", edit.changes());
        out.put("review", governance.enabled());
        if (Boolean.TRUE.equals(body.get("preview"))) {
            out.put("base", base);
            out.put("text", edit.text());
            return ResponseEntity.ok(out);
        }
        String note = String.valueOf(body.getOrDefault("note", "")).strip();
        String why = note.isEmpty() ? "Pivot of " + panel + " promoted from " + p.user() + "'s pivot view" : note;
        if (governance.enabled()) {
            Proposal pr = governance.propose(edit.text(), why.length() > 500 ? why.substring(0, 500) : why, p);
            out.put("proposal", Map.of("id", pr.id(), "name", pr.name(), "version", pr.version(), "status", pr.status()));
            return ResponseEntity.accepted().body(out);
        }
        Sutra saved = sutras.save(edit.text());
        out.put("saved", Map.of("name", saved.name(), "version", saved.version()));
        return ResponseEntity.ok(out);
    }
}
