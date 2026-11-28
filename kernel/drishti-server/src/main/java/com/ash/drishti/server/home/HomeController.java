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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.HomeDefinition;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.modules.ModuleNode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.switches.CommandCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Homes API (docs/architecture/ROLE_HOME_SUTRAS.md): which Homes the caller is offered and which opens, a Home's Sutra (compiled with
 * the administrator's lock), a home table's rows for the caller, what stands behind the "Since" panel, and the caller's own choice of start
 * Home. Everything is read as the caller: a Home never reveals a subject, row or Home the caller's role does not reach.
 */
@RestController
@RequestMapping("/api/v1/homes")
public class HomeController {

    static final String NS = "home";                           // the person's setting: key "start"
    private final HomeProperties props;
    private final HomeCatalogue catalogue;
    private final HomeTables tables;
    private final HomeStore store;
    private final HomeClock clock;
    private final ChangeEngineHost changes;
    private final MeasureSnapshotJob snapshots;
    private final Entitlements entitlements;
    private final PreferenceStore prefs;
    private final ModuleNode modules;
    private final CommandCatalog commands;
    private final ObjectMapper json = new ObjectMapper();

    @SuppressWarnings("java:S107")
    public HomeController(HomeProperties props, HomeCatalogue catalogue, HomeTables tables, HomeStore store, HomeClock clock, ChangeEngineHost changes,
            MeasureSnapshotJob snapshots, Entitlements entitlements, PreferenceStore prefs, ModuleNode modules, CommandCatalog commands) {
        this.props = props;
        this.catalogue = catalogue;
        this.tables = tables;
        this.store = store;
        this.clock = clock;
        this.changes = changes;
        this.snapshots = snapshots;
        this.entitlements = entitlements;
        this.prefs = prefs;
        this.modules = modules;
        this.commands = commands;
    }

    private List<HomeDefinition> offered(Principal p) {
        if (!props.enabled()) {
            return List.of();
        }
        return catalogue.offered(p, entitlements.isAdmin(p), m -> modules.entry(m).map(e -> e.state() == ModuleNode.State.ACTIVE).orElse(false),
                code -> commands.command(code).isPresent());
    }

    private HomeDefinition mine(String name, Principal p) {
        return offered(p).stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no Home " + name + " is offered to you"));
    }

    /** The Homes offered to the caller, the one to open first marked. One role, one Home: the console shows no picker. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<HomeDefinition> o = offered(p);
        String chosen = prefs.get(p.user(), NS, "start").map(n -> n.path("home").asText("")).orElse("");
        String start = catalogue.start(o, chosen).map(HomeDefinition::name).orElse(null);
        List<Map<String, Object>> homes = new ArrayList<>();
        for (HomeDefinition d : o) {
            homes.add(Map.of("home", d.name(), "title", d.title(), "description", d.description(), "arrange", d.arrange(), "start", d.name().equals(start)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.enabled());
        out.put("picker", o.size() > 1);
        out.put("start", start);
        out.put("homes", homes);
        out.put("faults", entitlements.isAdmin(p) ? catalogue.faults() : Map.of());
        return out;
    }

    /** The Home's Sutra as served (a locked Home has {@code user-can-arrange: false}), with the name its arrangement is kept under. */
    @GetMapping("/{home}")
    public Map<String, Object> get(@PathVariable String home, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        HomeDefinition d = mine(home, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("home", d.name());
        out.put("title", d.title());
        out.put("sutra", catalogue.sutraFor(d));
        out.put("sutraName", d.sutraName());
        out.put("arrange", d.arrange());
        out.put("tables", tables.names());
        out.put("mnemonics", catalogue.mnemonicsOf(d));
        return out;
    }

    /** One home table's rows for the caller. */
    @GetMapping("/{home}/tables/{table}")
    public Map<String, Object> table(@PathVariable String home, @PathVariable String table, @RequestParam(required = false) String since,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        HomeDefinition d = mine(home, p);
        java.time.Instant from = null;
        if (since != null && !since.isBlank()) {
            try {
                from = java.time.Instant.parse(since);
            } catch (java.time.format.DateTimeParseException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "since is a UTC time such as 2026-11-27T09:00:00Z");
            }
        }
        List<Map<String, Object>> rows = tables.rows(table, p, d, from)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no home table " + table));
        return Map.of("table", table, "rows", rows);
    }

    /** What stands behind the panels: the snapshot there is, and why "Since" may be empty, in words. */
    @GetMapping("/{home}/status")
    public Map<String, Object> status(@PathVariable String home, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        HomeDefinition d = mine(home, p);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean snap = store.hasSnapshot(clock.previous());
        out.put("snapshot", snap);
        out.put("since", clock.words(d.measures().cutoff() == null ? props.cutoffTime() : LocalTime.parse(d.measures().cutoff())));
        out.put("changes", changes.available());
        out.put("note", !changes.available() ? changes.reason() : !snap ? "no cut-off snapshot yet: the first one is taken at " + props.cutoff() : "");
        return out;
    }

    /** The engine's explanation of one change: the move and each driver's share (what {@code WHY <key>} opens). */
    @GetMapping("/{home}/why")
    public Map<String, Object> why(@PathVariable String home, @RequestParam String subject, @RequestParam String measure,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        HomeDefinition d = mine(home, p);
        List<Map<String, Object>> rows = tables.rows("home_changes", p, d).orElse(List.of());
        Map<String, Object> hit = rows.stream().filter(r -> subject.equals(r.get("SUBJECT")) && measure.equals(r.get("MEASURE"))).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no change of " + subject + " " + measure + " is shown to you"));
        HomeData.Change c = new HomeData.Change("", subject, measure, String.valueOf(hit.get("KIND")), num(hit.get("THEN")), num(hit.get("NOW")),
                ((Number) hit.get("CHANGE")).doubleValue(), String.valueOf(hit.get("WHY")), String.valueOf(hit.get("COMMAND")));
        return Map.of("subject", subject, "measure", measure, "steps", changes.explain(p, c, store.drivers(clock.today()).stream()
                .filter(x -> x.subject().equals(subject) && x.measure().equals(measure)).toList()));
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    /** The caller's own start Home (their setting {@code home}): one they are offered. */
    @PutMapping("/me")
    public Map<String, Object> choose(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String name = body.path("home").asText("");
        mine(name, p);
        prefs.put(p.user(), NS, "start", json.createObjectNode().put("home", name));
        return Map.of("start", name);
    }

    /** Administrator: take the snapshot now (after a late load), or read the Home files again. */
    @PostMapping("/admin/snapshot")
    public Map<String, Object> snapshotNow(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        admin(p);
        return Map.of("values", snapshots.run(clock.today(), props.cutoffTime()));
    }

    @PostMapping("/admin/reload")
    public Map<String, Object> reload(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        admin(p);
        catalogue.reload();
        return Map.of("homes", catalogue.all().size(), "faults", catalogue.faults());
    }

    private void admin(Principal p) {
        if (!entitlements.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "only an administrator may do this");
        }
    }
}
