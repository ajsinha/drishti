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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.control.ControlValues;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.wire.ScreenWire;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Events and child panels of Sutra Next screens (SUTRA_NEXT.md 7, 8, 15). A click is evaluated here, as the person, never in the
 * browser: {@code POST /api/v1/views/{kind}/{id}/events} with {@code {panel, event, row, selections}} returns the selection's token
 * and what it shows (the payload as the person saw it, masked fields {@code •••}), the panels that read the event, the clicked
 * panel's children, and the panels whose selections it cleared. {@code GET .../panels/{panel}/children?sel=...} fills a panel's
 * children lazily. Earlier selections travel as tokens ({@code selections}, {@code sel}) that only the same person resolves on the
 * same screen ({@link IdTokens}): the payload the server follows (a masked id among it) never leaves the server.
 */
@RestController
@RequestMapping("/api/v1/views")
public class ViewEventController {

    /** The most selections one request may carry (a screen nests three deep; a few panels emit). */
    static final int MAX_SELECTIONS = 32;

    /** A click: the panel, the event it fires, the row's place in the panel's data ({@code ev} of the row), earlier selections. */
    public record Click(String panel, String event, Integer row, List<String> selections) {}

    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final IdTokens tokens;
    private final SutraRegistry registry;

    public ViewEventController(ViewPipeline pipeline, Entitlements entitlements, SutraRegistry registry,
            @Value("${drishti.views.selection-ttl:PT30M}") Duration ttl) {
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.registry = registry;
        this.tokens = new IdTokens(Clock.systemUTC(), ttl);
    }

    /**
     * The Sutra a reader names for a view ({@code ?sutra=book-desk}): the newest version of that name, which must be of the entity's kind;
     * empty when none is named (the matched Sutra draws the view).
     *
     * @throws DrishtiException {@code DRS-5001} for a name no Sutra of the kind has
     */
    public static Optional<Sutra> chosen(SutraRegistry registry, String kind, String name) {
        if (name == null || name.isBlank() || registry == null) {
            return Optional.empty();
        }
        Sutra s = registry.latest(name.trim()).filter(x -> kind.equals(x.match().kind()))
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no Sutra '" + name + "' draws " + kind + " entities"));
        return Optional.of(s);
    }

    /** The tokens' store (tests: another person's resolve). */
    IdTokens tokens() {
        return tokens;
    }

    @PostMapping("/{kind}/{id}/events")
    public Map<String, Object> event(@PathVariable String kind, @PathVariable String id, @RequestBody Click click, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal, HttpServletRequest request) {
        entitlements.requireOpen(principal, kind);
        if (click == null || click.panel() == null || click.event() == null || click.row() == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a click names the panel, the event and the row: {panel, event, row, selections}");
        }
        String scope = kind + "\u0000" + id;
        List<ScreenWire.Selection> fired = resolve(principal, scope, click.selections());
        ScreenWire.Result r = pipeline.event(EntityRef.of(kind, id), asOf, entitlements.redactor(principal), k -> entitlements.mayOpen(principal, k),
                ControlValues.of(request.getParameterMap()), chosen(registry, kind, request.getParameter("sutra")), fired, click.panel(), click.event(),
                click.row());
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("panel", r.selection().panel());
        sel.put("event", r.selection().event());
        sel.put("token", tokens.issue(principal.user(), scope, r.selection()));
        sel.put("payload", plain(r.selection().seen()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selection", sel);
        out.put("panels", restrict(principal, r.panels()));
        out.put("children", r.children().stream().map(c -> children(principal, c)).toList());
        out.put("cleared", r.cleared());
        return out;
    }

    @GetMapping("/{kind}/{id}/panels/{panel}/children")
    public List<ScreenWire.ChildrenView> children(@PathVariable String kind, @PathVariable String id, @PathVariable String panel,
            @RequestParam(name = "sel", required = false) List<String> sel, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal, HttpServletRequest request) {
        entitlements.requireOpen(principal, kind);
        List<ScreenWire.Selection> fired = resolve(principal, kind + "\u0000" + id, sel);
        return pipeline.children(EntityRef.of(kind, id), asOf, entitlements.redactor(principal), k -> entitlements.mayOpen(principal, k),
                ControlValues.of(request.getParameterMap()), chosen(registry, kind, request.getParameter("sutra")), fired, panel).stream().map(c -> children(principal, c)).toList();
    }

    private List<ScreenWire.Selection> resolve(Principal principal, String scope, List<String> tokenList) {
        List<String> list = tokenList == null ? List.of() : tokenList;
        if (list.size() > MAX_SELECTIONS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX_SELECTIONS + " selections in one request");
        }
        List<ScreenWire.Selection> out = new ArrayList<>();
        for (String t : list) {
            if (tokens.resolve(principal.user(), scope, t) instanceof ScreenWire.Selection s) {
                out.add(s);
            }
        }
        return out;
    }

    private ScreenWire.ChildrenView children(Principal principal, ScreenWire.ChildrenView c) {
        return new ScreenWire.ChildrenView(c.parent(), c.group(), c.place(), c.show(), c.hidden(), restrict(principal, c.panels()));
    }

    /** The panels with links the person may not follow disabled, as a view's are. */
    private List<ViewModel.PanelView> restrict(Principal principal, List<ViewModel.PanelView> panels) {
        if (panels.isEmpty()) {
            return panels;
        }
        ViewModel v = new ViewModel(new ViewModel.Ref("-", "-"), null, new ViewModel.TitleView(null, "-", null), List.of(), panels, List.of(),
                null, Map.of(), List.of(), List.of());
        ViewModel r = entitlements.restrict(principal, v);
        List<ViewModel.PanelView> out = new ArrayList<>();
        for (int i = 0; i < panels.size(); i++) {
            out.add(r.panels().get(i).withWire(panels.get(i).wire()));
        }
        return out;
    }

    /** A payload as plain values for JSON: what the person saw. */
    static Object plain(DataNode n) {
        if (n instanceof DataNode.Obj o) {
            Map<String, Object> m = new LinkedHashMap<>();
            o.fields().forEach((k, v) -> m.put(k, plain(v)));
            return m;
        }
        if (n instanceof DataNode.Arr a) {
            return a.elements().stream().map(ViewEventController::plain).toList();
        }
        return n instanceof DataNode.Val v ? v.value() : null;
    }
}
