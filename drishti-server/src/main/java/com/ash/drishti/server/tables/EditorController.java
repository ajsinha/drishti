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
package com.ash.drishti.server.tables;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editors' API (docs/architecture/SHARED_SCREENS.md sections 1 to 4; TABLES_GUIDE.md section 11): {@code /api/v1/tables/editor}. Every answer is worked out on
 * the server for the person who asks; the console only draws it.
 */
@RestController
@RequestMapping("/api/v1/tables/editor")
public class EditorController {

    private final EditorService editors;

    public EditorController(EditorService editors) {
        this.editors = editors;
    }

    @GetMapping("/objects")
    public Map<String, Object> objects(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return editors.objects(p);
    }

    @GetMapping("/object")
    public Map<String, Object> object(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam String kind,
            @RequestParam(required = false) String name, @RequestParam(required = false) String item) {
        return editors.open(p, kind, name, item);
    }

    @GetMapping("/sources")
    public Map<String, Object> sources(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return editors.sources(p);
    }

    /** {@code {kind, yaml}} or {@code {kind, doc, yaml}} (a visual edit; the YAML's header is kept), with {@code original}. */
    @PostMapping("/check")
    @SuppressWarnings("unchecked")
    public Map<String, Object> check(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Map<String, Object> doc = body.get("doc") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return editors.check(p, text(body, "kind"), text(body, "yaml"), doc, opt(body, "original"));
    }

    /** {@code {statement, yaml, original}}: the Text toggle's statement read onto the table. */
    @PostMapping("/statement")
    public Map<String, Object> statement(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return editors.statement(p, text(body, "statement"), text(body, "yaml"), opt(body, "original"));
    }

    /** {@code {kind, yaml, date, original}}: the live preview with checks. */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        String kind = text(body, "kind");
        return EditorYaml.MODEL.equals(kind) ? editors.previewModel(p, text(body, "yaml"), opt(body, "date"), opt(body, "original"))
                : editors.previewTable(p, text(body, "yaml"), opt(body, "date"), opt(body, "original"));
    }

    /** {@code {yaml, original}}: a model's Access matrix. */
    @PostMapping("/access")
    public Map<String, Object> access(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return editors.access(p, text(body, "yaml"), opt(body, "original"));
    }

    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam String kind, @RequestParam String name) {
        return editors.impact(p, kind, name);
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam String item) {
        return editors.history(p, item);
    }

    /** {@code {name, kind, grain}}: the + New steps' suggested table over a kind. */
    /** Converts DAX to Rachana for the Tables tab and {@code drishti.py bi dax}: names exactly what could not be converted. */
    @PostMapping("/dax")
    public java.util.Map<String, Object> dax(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
        com.ash.drishti.server.bi.dax.DaxConverter.Result r = com.ash.drishti.server.bi.dax.DaxConverter.convert(String.valueOf(body.getOrDefault("dax", "")));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rachana", r.rachana());
        out.put("notConverted", r.notConverted());
        out.put("notes", r.notes());
        return out;
    }

    @PostMapping("/suggest")
    public Map<String, Object> suggest(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return editors.suggest(p, opt(body, "name"), text(body, "kind"), opt(body, "grain"));
    }

    /** {@code {kind, yaml, item?, workspace?, original?, message?, python?}}: a draft saved. */
    @PostMapping("/save")
    public Map<String, Object> save(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return editors.save(p, body);
    }

    /** {@code {item, note}}: proposed for review (four eyes). */
    @PostMapping("/propose")
    public Map<String, Object> propose(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return editors.propose(p, text(body, "item"), opt(body, "note"));
    }

    @GetMapping("/prefs")
    public Map<String, Object> prefs(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return editors.prefs(p);
    }

    @PutMapping("/prefs")
    public Map<String, Object> setPrefs(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return editors.setPrefs(p, body);
    }

    @GetMapping("/python/templates")
    public Map<String, Object> templates(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        editors.requireRead(p);
        return Map.of("templates", PythonDrafts.templates());
    }

    /** {@code {cells: [text, ...]}}: Notebook → Turn into table: the build function and its inputs. */
    @PostMapping("/python/notebook")
    public Map<String, Object> notebook(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        editors.requireRead(p);
        List<String> cells = new ArrayList<>();
        if (body.get("cells") instanceof List<?> l) {
            l.forEach(c -> cells.add(c == null ? "" : String.valueOf(c)));
        }
        if (cells.size() > 200) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "a notebook turned into a table has at most 200 cells");
        }
        return PythonDrafts.fromNotebook(cells);
    }

    /** {@code {yaml, source, date?, timeout?, memory?}}: a Python-driven table's sample run on the compute pool, checked against its contract. */
    @PostMapping("/python/run")
    public Map<String, Object> runPython(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        int timeout = body.get("timeout") instanceof Number n ? n.intValue() : 60;
        int memory = body.get("memory") instanceof Number n ? n.intValue() : 512;
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (body.get("inputs") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> inputs.put(String.valueOf(k), v));
        }
        return editors.samplePython(p, text(body, "yaml"), text(body, "source"), opt(body, "date"), timeout, memory, inputs);
    }

    /** Whether the sample run and the Notebook's cells run here, and why not. */
    @GetMapping("/python/runtime")
    public Map<String, Object> pythonRuntime(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        editors.requireRead(p);
        java.util.Optional<String> why = editors.pythonUnavailable();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", why.isEmpty());
        out.put("why", why.orElse(null));
        return out;
    }

    /** {@code {source, columns: {NAME: type}}}: the output contract proposed from the code. */
    @PostMapping("/python/contract")
    public Map<String, Object> contract(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        editors.requireRead(p);
        Map<String, String> cols = new LinkedHashMap<>();
        if (body.get("columns") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> cols.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return Map.of("columns", PythonDrafts.contract(text(body, "source"), cols));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String opt(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
